package com.mydog.ui.sync;

import com.mydog.connection.ConnectionProfile;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.metadata.MySqlMetadataReader;
import com.mydog.sync.Cancellation;
import com.mydog.sync.ConnectionFactory;
import com.mydog.sync.DataCounts;
import com.mydog.sync.DdlScriptGenerator;
import com.mydog.sync.SchemaComparator;
import com.mydog.sync.SyncAction;
import com.mydog.sync.SyncExecutor;
import com.mydog.sync.SyncMode;
import com.mydog.sync.SyncOptions;
import com.mydog.sync.SyncPlan;
import com.mydog.sync.SyncReport;
import com.mydog.sync.SyncScope;

import javafx.concurrent.Task;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/**
 * The sync wizard's back half: everything that talks to a server, with no widgets involved.
 *
 * <p>Every method here returns a {@link Task} rather than running work itself, because the rule the
 * whole app runs on is that the FX thread never touches JDBC. The wizard binds a progress bar to the
 * task and stays responsive; the cancel button reaches {@link Cancellation}, which the tasks and the
 * row producers share, so one press stops a comparison, a running export and a table being paged
 * through.
 *
 * <p>State (chosen connections, options, the last plan and report) lives here instead of in controls
 * so the wizard can rebuild a step without losing what the user picked, and so the flow can be
 * driven from a test as well as from the widgets.
 *
 * <p>The tasks are plain {@code Task} instances, i.e. {@code Task.Mode.PLATFORM}: their progress,
 * message and value changes are delivered on the FX thread, which is what lets the wizard bind a
 * progress bar and append to a log directly. The other half of that contract is that nothing here
 * may be read or written from the worker thread — the wizard's own callbacks are the only readers.
 */
public final class SyncWizardModel {

    private final AppContext ctx;
    private final Cancellation cancellation = new Cancellation();

    private ConnectionProfile sourceProfile;
    private ConnectionProfile targetProfile;
    private String sourceSchema;
    private String targetSchema;

    private SyncMode mode = SyncMode.STRUCTURE_AND_DATA;
    private boolean dropExtraneous;
    private int chunkSize = 5000;
    private boolean fastDataMode;

    private SyncPlan plan;
    private SyncReport report;

    public SyncWizardModel(AppContext ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ chosen endpoints

    public ConnectionProfile sourceProfile() { return sourceProfile; }
    public ConnectionProfile targetProfile() { return targetProfile; }
    public String sourceSchema() { return sourceSchema; }
    public String targetSchema() { return targetSchema; }

    public void setSource(ConnectionProfile profile, String schema) {
        this.sourceProfile = profile;
        this.sourceSchema = schema;
    }

    public void setTarget(ConnectionProfile profile, String schema) {
        this.targetProfile = profile;
        this.targetSchema = schema;
    }

    /** True once both ends are named, which is what every later step needs. */
    public boolean endpointsChosen() {
        return sourceProfile != null && targetProfile != null
                && sourceSchema != null && targetSchema != null;
    }

    /**
     * Same server <em>and</em> same schema means the plan would compare a schema with itself: the
     * engine would find nothing to do, or worse, rewrite objects onto themselves.
     */
    public boolean endpointsIdentical() {
        return endpointsChosen()
                && sourceProfile.host().equals(targetProfile.host())
                && sourceProfile.port() == targetProfile.port()
                && sourceSchema.equalsIgnoreCase(targetSchema);
    }

    // ------------------------------------------------------------------ options

    public SyncMode mode() { return mode; }
    public void setMode(SyncMode mode) { this.mode = mode; }

    public boolean dropExtraneous() { return dropExtraneous; }
    public void setDropExtraneous(boolean value) { this.dropExtraneous = value; }

    public int chunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public boolean fastDataMode() { return fastDataMode; }
    public void setFastDataMode(boolean value) { this.fastDataMode = value; }

    public SyncOptions options() {
        return new SyncOptions(mode, dropExtraneous, chunkSize, fastDataMode);
    }

    public SyncPlan plan() { return plan; }
    public SyncReport report() { return report; }

    // ------------------------------------------------------------------ cancel

    public void cancel() {
        cancellation.cancel();
    }

    /** A new run starts from an uncancelled state; the wizard calls this before each task. */
    public void beginRun() {
        cancellation.reset();
    }

    // ------------------------------------------------------------------ tasks

    /** Databases visible to a profile — the source and target pickers load independently. */
    public Task<List<String>> schemasTask(ConnectionProfile profile) {
        return new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                try (Connection c = ctx.openConnection(profile)) {
                    return new MySqlMetadataReader(c).listSchemas().stream()
                            .map(s -> s.name()).toList();
                }
            }
        };
    }

    /** Object names on either side, for the scope step's list. */
    public Task<List<SchemaComparator.Candidate>> candidatesTask() {
        return new Task<>() {
            @Override
            protected List<SchemaComparator.Candidate> call() throws Exception {
                return comparator().candidates();
            }
        };
    }

    /**
     * Builds the plan for the objects the scope step selected.
     *
     * <p>The result is stored on the model as well as returned: export and execute both run after
     * this, and they must apply the very plan the preview showed rather than a fresh comparison.
     */
    public Task<SyncPlan> compareTask(SyncScope scope) {
        return new Task<>() {
            @Override
            protected SyncPlan call() throws Exception {
                updateMessage(I18n.tr("sync.compare.reading"));
                SyncPlan result = comparator().compare(scope);
                plan = result;
                report = null;
                updateProgress(1, 1);
                return result;
            }
        };
    }

    /**
     * Streams the script for the current plan to a file.
     *
     * <p>Export re-runs the data diff rather than replaying stored rows, which is what keeps a
     * million-row plan out of the heap — and it means the file reflects the servers as they are now,
     * not as they were when Compare was pressed.
     */
    public Task<Path> exportTask(Path file) {
        return new Task<>() {
            @Override
            protected Path call() throws Exception {
                SyncPlan current = plan;
                if (current == null) throw new IllegalStateException("nothing to export");
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    DdlScriptGenerator.writeTo(current, options(), writer);
                }
                return file;
            }
        };
    }

    /** Writes the report of the last run to a text file. */
    public Task<Path> exportReportTask(Path file) {
        return new Task<>() {
            @Override
            protected Path call() throws Exception {
                Files.writeString(file, reportText(), StandardCharsets.UTF_8);
                return file;
            }
        };
    }

    public Task<SyncReport> executeTask() {
        return new Task<>() {
            @Override
            protected SyncReport call() throws Exception {
                SyncPlan current = plan;
                if (current == null) throw new IllegalStateException("nothing to execute");
                int total = current.selectedActions().size();
                updateProgress(0, total);
                try (Connection target = ctx.openConnection(targetProfile)) {
                    SyncExecutor executor = new SyncExecutor(target, options(), cancellation);
                    report = executor.execute(current, new SyncExecutor.ProgressListener() {
                        @Override
                        public void started(SyncAction action, int index, int totalActions) {
                            updateProgress(index - 1, totalActions);
                            updateMessage(action.kind() + " " + action.object());
                        }

                        @Override
                        public void finished(SyncAction action, DataCounts counts, long millis) {
                            // the bar advances in started(), before each unit of work, so a long
                            // table transfer does not sit at the previous action's percentage
                        }

                        @Override
                        public void message(String text) {
                            updateMessage(text);
                        }
                    });
                    updateProgress(total, total);
                    return report;
                }
            }
        };
    }

    /** Human-readable run summary, shared by the report step and the file export. */
    public String reportText() {
        return SyncReportText.of(this);
    }

    // ------------------------------------------------------------------ internals

    private ConnectionFactory factory(ConnectionProfile profile) {
        return () -> ctx.openConnection(profile);
    }

    private SchemaComparator comparator() {
        return new SchemaComparator(factory(sourceProfile), factory(targetProfile),
                sourceSchema, targetSchema, options(), cancellation);
    }

    /** Rows applied by the last run, for the summary line. */
    public DataCounts totals() {
        return report == null ? DataCounts.ZERO : report.totals();
    }
}

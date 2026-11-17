package com.mydog.ui.sync;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.SslMode;
import com.mydog.core.AppContext;
import com.mydog.sync.ObjectKind;
import com.mydog.sync.SchemaComparator;
import com.mydog.sync.SyncAction;
import com.mydog.sync.SyncMode;
import com.mydog.sync.SyncPlan;
import com.mydog.sync.SyncReport;
import com.mydog.sync.SyncScope;

import javafx.concurrent.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static com.mydog.ui.sync.FxTestSupport.failureOf;
import static com.mydog.ui.sync.FxTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wizard's back half, driven without a display.
 *
 * <p>The point is the seam, not the engine — the engine has its own end-to-end proof in
 * {@code DockerSyncIT}. What is specific here is that every step of the wizard reaches the servers
 * through a {@link Task}, so the test drives them the way the application does — JDBC on a worker
 * thread, results observed on the FX thread — which proves two things a screenshot cannot: that the
 * state the later steps depend on ({@code model.plan()}, {@code model.report()}) really is captured
 * by the earlier ones, and that a scope chosen in step 2 changes what step 3 plans.
 *
 * <p>The toolkit is started for real, and skipped-with-an-assumption where no display exists; see
 * {@link FxTestSupport#ensureToolkit()} for why {@code Task} cannot be read from the test thread directly.
 */
@Tag("docker")
class SyncWizardModelIT {

    private static final String PASSWORD = "root123";
    private static final String SRC = "mydog_wiz_src";
    private static final String DST = "mydog_wiz_dst";
    private static final String SRC_ID = "it-wiz-src";
    private static final String DST_ID = "it-wiz-dst";

    @TempDir
    static Path configDir;

    static AppContext ctx;

    @BeforeAll
    static void requireSandboxAndStoreProfiles() {
        FxTestSupport.ensureToolkit();
        try (Connection c = MySqlConnectionFactory.open(
                profile(SRC_ID, 3307, "shop"), PASSWORD)) {
            // reachable
        } catch (SQLException e) {
            Assumptions.abort("docker MySQL sandbox not usable (3307/3308): " + e.getMessage()
                    + " — run: cd docker && docker compose up -d --wait");
        }
        ctx = AppContext.createIn(configDir);
        ctx.connections().save(profile(SRC_ID, 3307, SRC)
                .withEncryptedPassword(ctx.cipher().encrypt(PASSWORD)));
        ctx.connections().save(profile(DST_ID, 3308, DST)
                .withEncryptedPassword(ctx.cipher().encrypt(PASSWORD)));
    }

    @BeforeEach
    void resetSchemas() throws SQLException {
        try (Connection c = MySqlConnectionFactory.open(
                profile(SRC_ID, 3307, null), PASSWORD); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS `" + SRC + "`");
            st.execute("CREATE SCHEMA `" + SRC + "`");
            st.execute("USE `" + SRC + "`");
            st.execute("CREATE TABLE t_users (id INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL)");
            st.execute("INSERT INTO t_users VALUES (1,'a'),(2,'b'),(3,'c')");
            st.execute("CREATE VIEW v_users AS SELECT id FROM t_users WHERE id > 1");
        }
        try (Connection c = MySqlConnectionFactory.open(
                profile(DST_ID, 3308, null), PASSWORD); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS `" + DST + "`");
            st.execute("CREATE SCHEMA `" + DST + "`");
        }
    }

    @AfterAll
    static void dropSchemas() {
        executeQuietly(SRC_ID, 3307, "DROP SCHEMA IF EXISTS `" + SRC + "`");
        executeQuietly(DST_ID, 3308, "DROP SCHEMA IF EXISTS `" + DST + "`");
    }

    // ------------------------------------------------------------------ the wizard's own path

    @Test
    void everyStepRunsThroughATaskAndThePlanConverges() throws Exception {
        SyncWizardModel model = model();

        // the picker lists user databases of *that* server: no system schemas, and nothing from the
        // target server, which is a separate combo fed by a separate task
        assertThat(run(model.schemasTask(sourceProfile())))
                .contains(SRC, "shop")
                .doesNotContain("information_schema", "mysql", DST);

        List<SchemaComparator.Candidate> candidates = run(model.candidatesTask());
        assertThat(candidates).extracting(c -> c.ref().name())
                .contains("t_users", "v_users");

        SyncPlan plan = run(model.compareTask(SyncScope.everything()));
        // export and execute both happen after this step and must reuse exactly this plan
        assertThat(model.plan()).isSameAs(plan);
        assertThat(plan.selectedActions()).isNotEmpty();

        SyncReport report = run(model.executeTask());
        assertThat(model.report()).isSameAs(report);
        assertThat(report.hasErrors()).isFalse();
        assertThat(report.totals().inserts()).isEqualTo(3);
        assertThat(model.reportText()).contains(SRC).contains(DST).contains("3 inserted");

        SyncPlan after = run(model.compareTask(SyncScope.everything()));
        assertThat(after.selectedActions()).isEmpty();
        // a fresh comparison invalidates the previous run's report, so step 5 cannot show a report
        // that no longer describes the plan on screen
        assertThat(model.report()).isNull();
    }

    @Test
    void untickedObjectsStayOutOfThePlan() throws Exception {
        SyncWizardModel model = model();
        // only a table that does not exist is selected: nothing on either side may be touched
        SyncPlan plan = run(model.compareTask(
                new SyncScope(Set.of("does_not_exist"), Set.of(), Set.of(), Set.of(), Set.of())));

        assertThat(plan.actions()).filteredOn(a -> a.object().kind() == ObjectKind.TABLE).isEmpty();
        // an empty name set means "all of that kind", so the view is still in the plan — but its base
        // table was left out, and MySQL refuses a view over a table that is not there, so the plan
        // refuses too instead of planning a statement that would fail halfway through the run
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a.object().kind()).isEqualTo(ObjectKind.VIEW);
            assertThat(a.kind()).isEqualTo(SyncAction.Kind.SKIP);
            assertThat(a.detail()).contains("t_users");
        });
    }

    @Test
    void dataOnlyModeRefusesTablesTheTargetDoesNotHaveYet() throws Exception {
        SyncWizardModel model = model();
        model.setMode(SyncMode.DATA_ONLY);

        SyncPlan plan = run(model.compareTask(SyncScope.everything()));

        assertThat(plan.actions()).allSatisfy(a -> assertThat(a.kind())
                .isIn(SyncAction.Kind.SKIP, SyncAction.Kind.DATA));
        assertThat(plan.actions()).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo(SyncAction.Kind.SKIP);
            assertThat(a.detail()).contains("structure");
        });
        // nothing was created, so the target is still empty
        assertThat(run(model.executeTask()).totals().inserts()).isZero();
    }

    @Test
    void exportWritesTheScriptFromTheStoredPlan() throws Exception {
        SyncWizardModel model = model();
        run(model.compareTask(SyncScope.everything()));

        Path script = configDir.resolve("plan.sql");
        assertThat(run(model.exportTask(script))).isEqualTo(script);
        String text = Files.readString(script);
        assertThat(text).contains("USE `" + DST + "`")
                .contains("CREATE TABLE")
                .contains("INSERT INTO");
    }

    @Test
    void theSameDatabaseOnBothSidesIsRefusedBeforeAnyQuery() {
        SyncWizardModel model = model();
        model.setTarget(sourceProfile(), SRC);

        assertThat(model.endpointsChosen()).isTrue();
        assertThat(model.endpointsIdentical()).isTrue();
    }

    @Test
    void executingWithoutAPlanFailsTheTaskInsteadOfThrowingOnTheFxThread() throws Exception {
        SyncWizardModel model = model();

        // the wizard binds the task, so a broken sequence must surface as a failed task
        assertThat(failureOf(model.executeTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing to execute");
    }

    // ------------------------------------------------------------------ helpers

    private static SyncWizardModel model() {
        SyncWizardModel model = new SyncWizardModel(ctx);
        model.setSource(sourceProfile(), SRC);
        model.setTarget(targetProfile(), DST);
        return model;
    }

    private static ConnectionProfile sourceProfile() {
        return ctx.connections().find(SRC_ID).orElseThrow();
    }

    private static ConnectionProfile targetProfile() {
        return ctx.connections().find(DST_ID).orElseThrow();
    }

    private static ConnectionProfile profile(String id, int port, String schema) {
        return new ConnectionProfile(id, id, "127.0.0.1", port, "root", "", schema,
                SslMode.DISABLED, null);
    }

    private static void executeQuietly(String id, int port, String sql) {
        try (Connection c = MySqlConnectionFactory.open(profile(id, port, null), PASSWORD);
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException ignored) {
            // best-effort cleanup; the next run recreates the schemas anyway
        }
    }
}

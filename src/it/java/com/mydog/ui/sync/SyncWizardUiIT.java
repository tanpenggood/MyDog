package com.mydog.ui.sync;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.SslMode;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.sync.ObjectKind;
import com.mydog.sync.SyncAction;
import com.mydog.sync.SyncPlan;
import com.mydog.sync.SyncReport;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.mydog.ui.sync.FxTestSupport.ensureToolkit;
import static com.mydog.ui.sync.FxTestSupport.fx;
import static com.mydog.ui.sync.FxTestSupport.runOnFx;
import static com.mydog.ui.sync.FxTestSupport.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clicks through the real wizard: five steps, the buttons, the tables, the tasks behind them, and
 * the servers they talk to.
 *
 * <p>This is the only test that can catch a mistake that compiles: a combo whose value is never read
 * by the next step, a checkbox bound to the wrong property, a step that stays empty because a task
 * was created but never started. Everything it asserts is what the user would see.
 *
 * <p>Needs both a display and the docker MySQL pair; without either it aborts instead of failing,
 * like the rest of the {@code docker}-tagged tests.
 */
@Tag("docker")
class SyncWizardUiIT {

    private static final String PASSWORD = "root123";
    private static final String SRC = "mydog_ui_src";
    private static final String DST = "mydog_ui_dst";
    private static final String SRC_ID = "it-ui-src";
    private static final String DST_ID = "it-ui-dst";

    @TempDir
    static Path configDir;

    static AppContext ctx;

    /** Why the window went away, if it does: the stack of whoever closed it. */
    static final AtomicReference<String> hiddenWhy = new AtomicReference<>();

    @BeforeAll
    static void setUp() throws SQLException {
        ensureToolkit();
        try (Connection c = MySqlConnectionFactory.open(profile(SRC_ID, 3307, null), PASSWORD)) {
            // reachable
        } catch (SQLException e) {
            Assumptions.abort("docker MySQL sandbox not usable (3307/3308): " + e.getMessage()
                    + " — run: cd docker && docker compose up -d --wait");
        }
        ctx = AppContext.createIn(configDir);
        // after the context, which re-applies whatever locale its settings say
        I18n.setLocale("en");            // button labels are how the clicks find their target
        ctx.connections().save(profile(SRC_ID, 3307, SRC)
                .withEncryptedPassword(ctx.cipher().encrypt(PASSWORD)));
        ctx.connections().save(profile(DST_ID, 3308, DST)
                .withEncryptedPassword(ctx.cipher().encrypt(PASSWORD)));
        resetSchemas();
    }

    @AfterAll
    static void tearDown() {
        executeQuietly(3307, "DROP SCHEMA IF EXISTS `" + SRC + "`");
        executeQuietly(3308, "DROP SCHEMA IF EXISTS `" + DST + "`");
    }

    @Test
    void wizardWalksFromEndpointsToAppliedChanges() {
        SyncWizard wizard = fx(() -> {
            SyncWizard w = SyncWizard.open(null, ctx, SRC);
            w.dialog().setOnHidden(e -> hiddenWhy.set("at step " + w.currentStep()
                    + ", status \"" + w.statusText() + "\", closed by " + whoClosed()));
            return w;
        });
        Platform.runLater(wizard::showAndWait);   // nested modal loop; the test drives from here
        waitUntil("the dialog to be showing", () -> wizard.dialog().isShowing());

        // ---- step 1: pick both endpoints
        waitUntil("the connection pickers to be populated", () -> {
            List<ComboBox<?>> pickers = combos(pane(wizard, 0));
            return pickers.size() == 4 && !pickers.get(0).getItems().isEmpty();
        });
        List<ComboBox<?>> combos = combos(pane(wizard, 0));
        @SuppressWarnings("unchecked")
        ComboBox<ConnectionProfile> sourceConnection = (ComboBox<ConnectionProfile>) combos.get(0);
        ComboBox<String> sourceSchema = (ComboBox<String>) combos.get(1);
        @SuppressWarnings("unchecked")
        ComboBox<ConnectionProfile> targetConnection = (ComboBox<ConnectionProfile>) combos.get(2);
        ComboBox<String> targetSchema = (ComboBox<String>) combos.get(3);

        runOnFx(() -> {
            sourceConnection.getSelectionModel().select(profileWithId(SRC_ID));
            targetConnection.getSelectionModel().select(profileWithId(DST_ID));
        });
        // the list arriving and the field showing it are two different things — and the field is what
        // step 1 reads, so that is what the test waits on rather than the list behind it
        waitUntil("both schema pickers to be filled",
                () -> SRC.equals(sourceSchema.getValue()) && DST.equals(targetSchema.getValue()));
        runOnFx(() -> {
            sourceSchema.setValue(SRC);
            targetSchema.setValue(DST);
        });

        // ---- step 2: the object scope
        click(wizard, I18n.tr("sync.next"));
        assertThat(wizard.currentStep()).isEqualTo(1);
        TableView<?> scopeTable = tableView(pane(wizard, 1));
        waitUntil("the candidate list to load", () -> scopeTable.getItems().size() >= 4);
        assertThat(items(scopeTable)).contains("TABLE t_users", "TABLE t_orders", "VIEW v_paid");
        // target-only objects are offered (so they can be dropped) but start unticked
        assertThat(uncheckedNames(scopeTable)).containsExactly("TABLE t_scratch");

        // the filter narrows what is visible without changing what is selected
        TextField filter = filterField(pane(wizard, 1));
        runOnFx(() -> filter.setText("orders"));
        waitUntil("the filter to apply", () -> visibleItems(scopeTable) == 1);
        assertThat(items(scopeTable)).containsExactly("TABLE t_orders");
        runOnFx(() -> filter.setText(""));
        waitUntil("the filter to clear", () -> visibleItems(scopeTable) >= 4);
        // filtering changes what is on screen, never what is ticked: were clearing the filter
        // treated as "select all", this target-only table would silently become a scheduled DELETE
        assertThat(uncheckedNames(scopeTable)).containsExactly("TABLE t_scratch");
        assertThat(countTicked(scopeTable)).isEqualTo(visibleItems(scopeTable) - 1);

        // ---- step 3: compare, which the pane starts on its own
        click(wizard, I18n.tr("sync.next"));
        assertThat(wizard.currentStep()).isEqualTo(2);
        waitUntil("the comparison to finish", () -> wizard.model().plan() != null);
        SyncPlan plan = wizard.model().plan();
        assertThat(plan.byKind().get(ObjectKind.TABLE)).isNotEmpty();
        assertThat(plan.warnings()).isEmpty();
        // dropExtraneous was left off, so the target-only table is not in the plan in any form: the
        // destructive half of the engine stays unreachable unless the user asks for it
        assertThat(plan.actions()).noneMatch(a -> a.kind() == SyncAction.Kind.DROP);

        // ---- step 4: review, and drop one object by unticking it
        click(wizard, I18n.tr("sync.next"));
        assertThat(wizard.currentStep()).isEqualTo(3);
        TableView<?> preview = tableView(pane(wizard, 3));
        waitUntil("the plan to appear in the preview", () -> preview.getItems().size() == plan
                .actions().size());
        assertThat(items(preview)).anySatisfy(row -> assertThat(row).contains("TABLE t_orders"));
        // one prefix, both of that table's actions: unticking the object means neither DDL nor rows
        tick(preview, "TABLE t_orders", false);

        click(wizard, I18n.tr("sync.apply"));
        assertThat(wizard.currentStep()).isEqualTo(4);
        assertThat(wizard.model().plan().selectedActions())
                .extracting(a -> a.object().kind() + " " + a.object().name())
                .doesNotContain("TABLE t_orders")
                .contains("TABLE t_users");

        // ---- step 5: run it
        click(wizard, I18n.tr("sync.execute.run"));
        waitUntil("the run to finish", () -> wizard.model().report() != null);
        SyncReport report = wizard.model().report();
        assertThat(report.hasErrors())
                .describedAs("errors: %s", report.errors())
                .isFalse();
        assertThat(report.totals().inserts()).isEqualTo(2);
        assertThat(rowsInTarget()).containsEntry("t_users", 3L);
        assertThat(tableExistsInTarget("t_orders")).isFalse();   // the row unticked in step 4
        // v_paid reads from t_orders, and MySQL refuses a view over a table that is not there, so the
        // run skips it and says why instead of failing — which used to stop the whole run
        assertThat(report.count(SyncAction.Status.SKIPPED)).isEqualTo(1);
        assertThat(tableExistsInTarget("v_paid")).isFalse();

        SyncPlan applied = fx(wizard.model()::plan);

        // ---- Back to the first step: the panes are cached, so nothing may be forgotten on the way
        for (int i = 0; i < 4; i++) {
            click(wizard, I18n.tr("sync.back"));
        }
        assertThat(wizard.currentStep()).isZero();
        assertThat(sourceSchema.getValue()).isEqualTo(SRC);
        assertThat(sourceConnection.getValue()).isEqualTo(profileWithId(SRC_ID));

        // ---- second round, with two options the first round left alone: structure only, and the
        //      cleanup of objects that exist only on the target. The source also gains a new table,
        //      which is how the test tells a re-read object list from a stale one.
        driftAgain();
        runOnFx(() -> {
            radios(wizard).get(0).setSelected(true);      // 仅结构 / Structure only
            checkBoxes(wizard).get(0).setSelected(true);  // drop extraneous objects
        });

        click(wizard, I18n.tr("sync.next"));
        waitUntil("the object list to be re-read for the new round",
                () -> items(scopeTable).contains("TABLE t_extra"));
        assertThat(uncheckedNames(scopeTable)).containsExactly("TABLE t_scratch");
        // the row is what opts a target-only object in: the option on step 1 alone deletes nothing
        tick(scopeTable, "TABLE t_scratch", true);

        click(wizard, I18n.tr("sync.next"));
        waitUntil("the second comparison", () -> wizard.model().plan() != applied);
        SyncPlan second = fx(wizard.model()::plan);
        assertThat(second.actions()).isNotEmpty();
        // the mode radio reached the engine: no row transfer is planned at all
        assertThat(second.actions()).noneMatch(a -> a.kind() == SyncAction.Kind.DATA);
        assertThat(second.actions()).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo(SyncAction.Kind.CREATE);
            assertThat(a.object().name()).isEqualTo("t_extra");
        });
        // and the option plus the ticked row together put the target-only table in the plan
        assertThat(second.actions()).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo(SyncAction.Kind.DROP);
            assertThat(a.object().name()).isEqualTo("t_scratch");
        });

        // and the dialog closes from its own button
        click(wizard, I18n.tr("sync.close"));
        waitUntil("the dialog to close", () -> !wizard.dialog().isShowing());
    }

    // ------------------------------------------------------------------ scene graph helpers

    /** The FX thread's own frames: hiding the dialog is always somebody else's doing. */
    private static String whoClosed() {
        return java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(3)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining(" <- "));
    }

    private static Node pane(SyncWizard wizard, int step) {
        return fx(() -> wizard.paneOf(step));
    }

    /** Presses the wizard button whose label matches, which is how the user advances. */
    private static void click(SyncWizard wizard, String label) {
        try {
            waitUntil("the \"" + label + "\" button", () -> button(wizard, label) != null);
        } catch (AssertionError missing) {
            throw new AssertionError(missing.getMessage() + " — " + describe(wizard));
        }
        Button button = button(wizard, label);
        assertThat(button).as("button %s", label).isNotNull();
        // a click on a window the user can no longer see "works" for one more pulse and then the
        // whole rest of the test fails for a reason that has nothing to do with the real cause
        assertThat(fx(() -> wizard.dialog().isShowing()))
                .as("the dialog is still on screen when %s is pressed", label)
                .isTrue();
        runOnFx(button::fire);
    }

    /**
     * The button labelled {@code label}: the dialog's own bar first, then the current step's pane,
     * because the step that runs the sync owns its Run button instead of borrowing the bar's.
     */
    private static Button button(SyncWizard wizard, String label) {
        return fx(() -> {
            Node pane = wizard.paneOf(wizard.currentStep());
            if (pane == null || pane.getScene() == null) return null;
            Button found = matchIn(pane.getScene().getRoot(), ".button-bar .button", label);
            return found != null ? found : matchIn(pane, ".button", label);
        });
    }

    private static Button matchIn(Node root, String selector, String label) {
        for (Node node : root.lookupAll(selector)) {
            if (node instanceof Button b && label.equals(b.getText()) && b.isVisible()
                    && !b.isDisabled()) {
                return b;
            }
        }
        return null;
    }

    /**
     * What the test could see when a click failed: a GUI test that hangs must say why, in the same
     * terms a person looking at the window would use.
     */
    private static String describe(SyncWizard wizard) {
        return fx(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("windows=").append(javafx.stage.Window.getWindows().stream()
                    .map(w -> w.getClass().getSimpleName() + (w.isShowing() ? ":showing" : ":hidden"))
                    .toList());
            for (int i = 0; i < 5; i++) {
                Node pane = wizard.paneOf(i);
                sb.append(" | pane").append(i).append('=').append(pane == null ? "not built"
                        : pane.getClass().getSimpleName() + (pane.getScene() == null
                        ? " (no scene)" : " (in scene)"));
            }
            sb.append(" | hidden=").append(hiddenWhy.get() == null ? "still open" : hiddenWhy.get());
            Node current = wizard.paneOf(wizard.currentStep());
            if (current != null && current.getScene() != null) {
                sb.append(" buttons=");
                for (Node n : current.getScene().getRoot().lookupAll(".button")) {
                    Button b = (Button) n;
                    sb.append('"').append(b.getText()).append('"')
                            .append(b.isDisabled() ? "[disabled]" : "")
                            .append(b.isVisible() ? "" : "[invisible]")
                            .append(' ');
                }
            }
            return sb.toString();
        });
    }

    @SuppressWarnings("unchecked")
    private static List<RadioButton> radios(SyncWizard wizard) {
        return fx(() -> {
            List<RadioButton> found = new ArrayList<>();
            for (Node node : pane(wizard, 0).lookupAll(".radio-button")) {
                found.add((RadioButton) node);
            }
            return found;
        });
    }

    private static List<CheckBox> checkBoxes(SyncWizard wizard) {
        return fx(() -> {
            List<CheckBox> found = new ArrayList<>();
            for (Node node : pane(wizard, 0).lookupAll(".check-box")) {
                found.add((CheckBox) node);
            }
            return found;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<ComboBox<?>> combos(Node pane) {
        return fx(() -> {
            List<ComboBox<?>> found = new ArrayList<>();
            for (Node node : pane.lookupAll(".combo-box")) found.add((ComboBox<?>) node);
            return found;
        });
    }

    private static TableView<?> tableView(Node pane) {
        return fx(() -> (TableView<?>) pane.lookup(".table-view"));
    }

    private static TextField filterField(Node pane) {
        return fx(() -> {
            for (Node node : pane.lookupAll(".text-field")) {
                if (node instanceof TextField tf && I18n.tr("sync.filter").equals(tf.getPromptText())) {
                    return tf;
                }
            }
            throw new AssertionError("the name filter field was not found");
        });
    }

    private static List<String> items(TableView<?> table) {
        return fx(() -> table.getItems().stream().map(Object::toString).toList());
    }

    /** Items the table is actually showing, which is the filtered list, not the row visuals. */
    private static int visibleItems(TableView<?> table) {
        return fx(table.getItems()::size);
    }

    /**
     * Names of the rows that are not ticked.
     *
     * <p>The tick lives in the wizard's private row wrapper — that indirection is the point (the
     * engine's objects stay free of JavaFX), so the test reaches for the property reflectively
     * rather than making production code public for testing.
     */
    private static int countTicked(TableView<?> table) {
        return fx(() -> {
            int n = 0;
            for (Object row : table.getItems()) {
                if (selected(row)) n++;
            }
            return n;
        });
    }

    private static List<String> uncheckedNames(TableView<?> table) {
        return fx(() -> {
            List<String> names = new ArrayList<>();
            for (Object row : table.getItems()) {
                if (!selected(row)) names.add(row.toString());
            }
            return names;
        });
    }

    /** Ticks or unticks every row named by that prefix, which is what clicking the box would do. */
    private static void tick(TableView<?> table, String rowName, boolean value) {
        waitUntil("row " + rowName, () -> items(table).stream().anyMatch(r -> r.startsWith(rowName)));
        fx(() -> {
            for (Object row : table.getItems()) {
                if (row.toString().startsWith(rowName)) {
                    setSelected(row, value);
                }
            }
            return null;
        });
    }

    private static boolean selected(Object row) {
        try {
            return ((BooleanProperty) field(row)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setSelected(Object row, boolean value) {
        try {
            ((BooleanProperty) field(row)).set(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object field(Object row) throws ReflectiveOperationException {
        Field field = row.getClass().getDeclaredField("selected");
        field.setAccessible(true);
        return field.get(row);
    }

    // ------------------------------------------------------------------ sandbox

    private static void resetSchemas() throws SQLException {
        try (Connection c = MySqlConnectionFactory.open(profile(SRC_ID, 3307, null), PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS `" + SRC + "`");
            st.execute("CREATE SCHEMA `" + SRC + "`");
            st.execute("USE `" + SRC + "`");
            st.execute("CREATE TABLE t_users (id INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL)");
            st.execute("INSERT INTO t_users VALUES (1,'a'),(2,'b'),(3,'c')");
            st.execute("CREATE TABLE t_orders (id INT NOT NULL PRIMARY KEY, total DECIMAL(10,2) NOT NULL)");
            st.execute("INSERT INTO t_orders VALUES (10,9.5),(11,20.0)");
            st.execute("CREATE VIEW v_paid AS SELECT id FROM t_orders WHERE total > 5");
        }
        try (Connection c = MySqlConnectionFactory.open(profile(DST_ID, 3308, null), PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS `" + DST + "`");
            st.execute("CREATE SCHEMA `" + DST + "`");
            st.execute("USE `" + DST + "`");
            st.execute("CREATE TABLE t_users (id INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL)");
            st.execute("INSERT INTO t_users VALUES (1,'a')");
            st.execute("CREATE TABLE t_scratch (id INT NOT NULL PRIMARY KEY)");
        }
    }

    /**
     * Moves both sides again while the wizard is still open: a new table on the source, and the
     * target reset so that its only object is one the source does not have.
     */
    private static void driftAgain() {
        executeQuietly(3307, "CREATE TABLE `" + SRC + "`.`t_extra` (id INT NOT NULL PRIMARY KEY)");
        try (Connection c = MySqlConnectionFactory.open(profile(DST_ID, 3308, null), PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS `" + DST + "`");
            st.execute("CREATE SCHEMA `" + DST + "`");
            st.execute("CREATE TABLE `" + DST + "`.`t_scratch` (id INT NOT NULL PRIMARY KEY)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.Map<String, Long> rowsInTarget() {
        try (Connection c = MySqlConnectionFactory.open(profile(DST_ID, 3308, DST), PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `t_users`")) {
            rs.next();
            return java.util.Map.of("t_users", rs.getLong(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean tableExistsInTarget(String table) {
        try (Connection c = MySqlConnectionFactory.open(profile(DST_ID, 3308, DST), PASSWORD);
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema = '" + DST
                             + "' AND table_name = '" + table + "'")) {
            return rs.next();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConnectionProfile profileWithId(String id) {
        return ctx.connections().find(id).orElseThrow();
    }

    private static ConnectionProfile profile(String id, int port, String schema) {
        return new ConnectionProfile(id, id, "127.0.0.1", port, "root", "", schema,
                SslMode.DISABLED, null);
    }

    private static void executeQuietly(int port, String sql) {
        try (Connection c = MySqlConnectionFactory.open(profile("cleanup", port, null), PASSWORD);
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }
}

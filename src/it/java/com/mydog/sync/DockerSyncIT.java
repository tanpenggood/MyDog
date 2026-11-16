package com.mydog.sync;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.SslMode;
import com.mydog.metadata.DdlNormalizer;
import com.mydog.metadata.MySqlMetadataReader;
import com.mydog.metadata.model.RoutineDef;
import com.mydog.metadata.model.TableDef;
import com.mydog.metadata.model.TriggerDef;
import com.mydog.metadata.model.ViewDef;
import com.mydog.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the sync engine against the docker MySQL pair: a drifted schema is compared,
 * planned, applied, and then has nothing left to apply.
 *
 * <p>The test builds its own {@code mydog_it_src} / {@code mydog_it_dst} schemas rather than using
 * the demo {@code shop} fixtures, for two reasons: the assertions can name exact row counts and
 * object lists without breaking when the demo data changes, and syncing under <em>different</em>
 * schema names exercises the qualifier rewriting that the demo pair (same name on both sides)
 * would pass without.
 *
 * <p>Each test resets both schemas, so they are independent and the class is re-runnable — which is
 * the same property the engine itself is being asked to have.
 */
@Tag("docker")
class DockerSyncIT {

    private static final String PASSWORD = "root123";
    private static final String SRC = "mydog_it_src";
    private static final String DST = "mydog_it_dst";
    private static final String REPLAY = "mydog_it_replay";
    private static final int SOURCE_PORT = 3307;
    private static final int TARGET_PORT = 3308;

    private static final SyncOptions STRUCTURE_ONLY =
            new SyncOptions(SyncMode.STRUCTURE_ONLY, false, 5000, false);
    private static final SyncOptions DATA_ONLY =
            new SyncOptions(SyncMode.DATA_ONLY, false, 5000, false);
    private static final SyncOptions BOTH =
            new SyncOptions(SyncMode.STRUCTURE_AND_DATA, false, 1000, false);

    private final ConnectionFactory sourceFactory =
            () -> MySqlConnectionFactory.openToSchema(profile(SOURCE_PORT, SRC), PASSWORD, SRC);
    private final ConnectionFactory targetFactory =
            () -> MySqlConnectionFactory.openToSchema(profile(TARGET_PORT, DST), PASSWORD, DST);
    private final Cancellation cancel = new Cancellation();

    // ------------------------------------------------------------------ sandbox lifecycle

    @BeforeEach
    void resetSandbox() {
        cancel.reset();
        try (Connection c = MySqlConnectionFactory.open(profile(SOURCE_PORT, null), PASSWORD)) {
            dropAndCreate(c, SRC);
            loadSourceFixture(c);
        } catch (SQLException e) {
            Assumptions.abort("docker MySQL sandbox not usable on " + SOURCE_PORT + " ("
                    + e.getMessage() + ") — run: cd docker && docker compose up -d --wait");
        }
        try (Connection c = MySqlConnectionFactory.open(profile(TARGET_PORT, null), PASSWORD)) {
            dropAndCreate(c, DST);
            dropAndCreate(c, REPLAY);
            loadDriftedTargetFixture(c);
        } catch (SQLException e) {
            Assumptions.abort("docker MySQL sandbox not usable on " + TARGET_PORT + " ("
                    + e.getMessage() + ") — run: cd docker && docker compose up -d --wait");
        }
    }

    // ------------------------------------------------------------------ comparison

    @Test
    void compareClassifiesEveryDriftExactlyOnce() throws Exception {
        SyncPlan plan = comparator(BOTH).compare(SyncScope.everything());

        assertThat(plan.byKind().get(ObjectKind.TABLE)).isNotEmpty();

        SyncAction alter = action(plan, ObjectKind.TABLE, "t_users", SyncAction.Kind.ALTER);
        assertThat(alter).isNotNull();
        assertThat(alter.statements()).hasSize(1);
        assertThat(alter.statements().get(0))
                .startsWith("ALTER TABLE `" + DST + "`.`t_users`")
                .contains("MODIFY COLUMN `email` varchar(128) DEFAULT NULL")
                .contains("ADD KEY `idx_created` (`created_at`)");

        assertThat(action(plan, ObjectKind.TABLE, "t_orders", SyncAction.Kind.CREATE)).isNotNull();
        assertThat(action(plan, ObjectKind.TABLE, "t_users", SyncAction.Kind.DATA)).isNotNull();
        assertThat(action(plan, ObjectKind.TABLE, "t_orders", SyncAction.Kind.DATA)).isNotNull();

        assertThat(action(plan, ObjectKind.VIEW, "v_active", SyncAction.Kind.CREATE)).isNotNull();
        SyncAction createProc = action(plan, ObjectKind.PROCEDURE, "p_touch", SyncAction.Kind.CREATE);
        assertThat(createProc).isNotNull();
        assertThat(createProc.statements().get(0))
                .contains("CREATE PROCEDURE")
                .doesNotContain("ONLY_FULL_GROUP_BY");
        SyncAction createTrigger = action(plan, ObjectKind.TRIGGER, "trg_touch", SyncAction.Kind.CREATE);
        assertThat(createTrigger).isNotNull();
        // MySQL prints a same-schema trigger unqualified (ON `t_users`), so the assertion is that
        // the source schema is gone and the timing/event clause survived
        assertThat(createTrigger.statements().get(0))
                .contains("CREATE TRIGGER")
                .contains("BEFORE UPDATE ON")
                .doesNotContain("`" + SRC + "`")
                .doesNotContain("DEFINER");

        // a target-only table is untouched unless the destructive option is on
        assertThat(action(plan, ObjectKind.TABLE, "t_scratch", SyncAction.Kind.DROP)).isNull();

        // t_logs has no key: it is planned as a reload, and the plan says so loudly
        SyncAction logs = action(plan, ObjectKind.TABLE, "t_logs", SyncAction.Kind.DATA);
        assertThat(logs).isNotNull();
        assertThat(logs.statements()).containsExactly("DELETE FROM `" + DST + "`.`t_logs`");
        assertThat(logs.detail()).contains("full reload");
        assertThat(plan.warnings()).anySatisfy(w -> assertThat(w).contains("no primary key"));
    }

    @Test
    void scopeRestrictsThePlanToWhatWasSelected() throws Exception {
        SyncScope onlyUsers = new SyncScope(Set.of("t_users"), Set.of(), Set.of(), Set.of(), Set.of());

        SyncPlan plan = comparator(BOTH).compare(onlyUsers);

        assertThat(plan.actions()).filteredOn(a -> a.object().kind() == ObjectKind.TABLE)
                .allSatisfy(a -> assertThat(a.object().name()).isEqualTo("t_users"));
        // an empty set means "every object of that kind", so views and routines stay in scope
        assertThat(action(plan, ObjectKind.VIEW, "v_active", SyncAction.Kind.CREATE)).isNotNull();
    }

    // ------------------------------------------------------------------ execution

    @Test
    void applyingThePlanLeavesNothingToApply() throws Exception {
        SyncReport report = execute(comparator(BOTH).compare(SyncScope.everything()), BOTH);

        assertThat(report.errors()).isEmpty();
        assertThat(report.count(SyncAction.Status.FAILED)).isZero();
        assertThat(report.totals().inserts()).isPositive();

        // convergence: comparing again must find nothing selected, or the engine is not finished
        SyncPlan second = comparator(BOTH).compare(SyncScope.everything());
        assertThat(keyedActions(second))
                .extracting(a -> a.kind() + " " + a.object())
                .isEmpty();

        try (Connection src = sourceFactory.open(); Connection dst = targetFactory.open()) {
            assertThat(count(dst, DST, "t_users")).isEqualTo(5);
            assertThat(count(dst, DST, "t_orders")).isEqualTo(4);
            assertThat(count(dst, DST, "SELECT COUNT(*) FROM `" + DST + "`.`t_users` WHERE id = 9"))
                    .as("the target-only row is deleted").isZero();
            assertThat(count(dst, DST, "SELECT COUNT(*) FROM `" + DST + "`.`t_users` WHERE id = 5"))
                    .as("the missing row is inserted").isEqualTo(1);
            assertThat(value(dst, "SELECT balance FROM `" + DST + "`.`t_users` WHERE id = 3"))
                    .isEqualTo(value(src, "SELECT balance FROM `" + SRC + "`.`t_users` WHERE id = 3"));
            assertThat(contentOf(dst, DST, "t_logs"))
                    .as("keyless table compared by content, not by checksum")
                    .isEqualTo(contentOf(src, SRC, "t_logs"));
        }
    }

    @Test
    void objectDefinitionsAreEquivalentAfterSyncIncludingRewrittenQualifiers() throws Exception {
        execute(comparator(BOTH).compare(SyncScope.everything()), BOTH);

        try (Connection src = sourceFactory.open(); Connection dst = targetFactory.open()) {
            MySqlMetadataReader sr = new MySqlMetadataReader(src);
            MySqlMetadataReader tr = new MySqlMetadataReader(dst);
            DdlNormalizer.configureDefaultCollations(sr.defaultCollations());

            for (String table : List.of("t_users", "t_orders", "t_logs")) {
                TableDef a = sr.readTable(SRC, table);
                TableDef b = tr.readTable(DST, table);
                assertThat(DdlNormalizer.equivalent(a.createStatement(), b.createStatement()))
                        .as("table " + table).isTrue();
            }

            ViewDef srcView = view(sr.listViews(SRC), "v_active");
            ViewDef dstView = view(tr.listViews(DST), "v_active");
            // MySQL prints a same-schema view body unqualified, so equality is the assertion here
            // rather than "the target name appears in the text" — plus the count below, which is the
            // functional proof that the copy resolves against its own schema.
            assertThat(dstView.createStatement()).doesNotContain("`" + SRC + "`");
            assertThat(DdlNormalizer.normalizeObjectDdl(srcView.createStatement()).replace(SRC, DST))
                    .isEqualTo(DdlNormalizer.normalizeObjectDdl(dstView.createStatement()));
            assertThat(count(dst, DST, "SELECT COUNT(*) FROM v_active")).isEqualTo(4);

            RoutineDef srcRoutine = routine(sr.listRoutines(SRC), "p_touch");
            RoutineDef dstRoutine = routine(tr.listRoutines(DST), "p_touch");
            // SHOW CREATE PROCEDURE keeps its body in a later column; reading column 2 there yields
            // the session sql_mode string instead of DDL
            assertThat(dstRoutine.createStatement()).startsWith("CREATE").contains("PROCEDURE `p_touch`");
            assertThat(DdlNormalizer.normalizeObjectDdl(srcRoutine.createStatement()).replace(SRC, DST))
                    .isEqualTo(DdlNormalizer.normalizeObjectDdl(dstRoutine.createStatement()));

            TriggerDef srcTrigger = trigger(sr.listTriggers(SRC), "trg_touch");
            TriggerDef dstTrigger = trigger(tr.listTriggers(DST), "trg_touch");
            assertThat(dstTrigger.createStatement()).startsWith("CREATE").contains("TRIGGER");
            assertThat(dstTrigger.table()).isEqualTo("t_users");
            assertThat(DdlNormalizer.normalizeObjectDdl(srcTrigger.createStatement()).replace(SRC, DST))
                    .isEqualTo(DdlNormalizer.normalizeObjectDdl(dstTrigger.createStatement()));
        }
    }

    @Test
    void structureOnlyModeDoesNotTouchRows() throws Exception {
        long usersBefore = count(openTarget(), DST, "t_users");

        SyncPlan plan = comparator(STRUCTURE_ONLY).compare(SyncScope.everything());
        assertThat(plan.dataActionCount()).isZero();
        execute(plan, STRUCTURE_ONLY);

        try (Connection dst = openTarget()) {
            assertThat(count(dst, DST, "t_users")).as("existing rows untouched").isEqualTo(usersBefore);
            assertThat(count(dst, DST, "t_orders")).as("created empty").isZero();
        }
    }

    @Test
    void dataOnlyModeMovesRowsAndRefusesMissingTables() throws Exception {
        execute(comparator(STRUCTURE_ONLY).compare(SyncScope.everything()), STRUCTURE_ONLY);
        // t_logs is keyless and missing rows; make its content differ so the reload has work to do
        try (Connection dst = openTarget()) {
            exec(dst, "DELETE FROM `" + DST + "`.`t_logs`");
        }

        SyncPlan plan = comparator(DATA_ONLY).compare(SyncScope.everything());
        assertThat(plan.actions()).allSatisfy(a -> assertThat(a.kind()).isIn(
                SyncAction.Kind.DATA, SyncAction.Kind.SKIP));
        assertThat(ddlStatementCount(plan)).as("only the reload's DELETE").isEqualTo(1);

        SyncReport report = execute(plan, DATA_ONLY);
        assertThat(report.errors()).isEmpty();
        try (Connection src = sourceFactory.open(); Connection dst = openTarget()) {
            for (String table : List.of("t_users", "t_orders", "t_logs")) {
                assertThat(count(dst, DST, table)).as(table).isEqualTo(count(src, SRC, table));
            }
        }
    }

    @Test
    void dropExtraneousRemovesTargetOnlyObjects() throws Exception {
        SyncOptions options = new SyncOptions(SyncMode.STRUCTURE_ONLY, true, 5000, false);
        SyncPlan plan = comparator(options).compare(SyncScope.everything());

        SyncAction drop = action(plan, ObjectKind.TABLE, "t_scratch", SyncAction.Kind.DROP);
        assertThat(drop).isNotNull();
        assertThat(drop.statements().get(0)).isEqualTo("DROP TABLE IF EXISTS `" + DST + "`.`t_scratch`");

        execute(plan, options);
        try (Connection dst = openTarget()) {
            assertThat(count(dst, DST, "SELECT COUNT(*) FROM information_schema.tables WHERE "
                    + "table_schema='" + DST + "' AND table_name='t_scratch'")).isZero();
        }
    }

    @Test
    void cancellingStopsAtTheNextActionAndReportsWhatWasSkipped() throws Exception {
        SyncPlan plan = comparator(BOTH).compare(SyncScope.everything());
        assertThat(plan.selectedActions().size()).isGreaterThan(3);

        SyncReport report;
        try (Connection target = openTarget()) {
            SyncExecutor executor = new SyncExecutor(target, BOTH, cancel);
            report = executor.execute(plan, new SyncExecutor.ProgressListener() {
                @Override
                public void started(SyncAction action, int index, int total) {
                    if (index == 2) {
                        executor.cancel();
                    }
                }

                @Override
                public void finished(SyncAction action, DataCounts counts, long millis) {
                }

                @Override
                public void message(String text) {
                }
            });
        }

        assertThat(report.cancelled()).isTrue();
        assertThat(report.count(SyncAction.Status.SKIPPED)).isPositive();
        assertThat(report.actions()).filteredOn(a -> a.status() == SyncAction.Status.SKIPPED)
                .allSatisfy(a -> assertThat(a.message()).isEqualTo("cancelled"));
        // the first action ran before the cancel, so the run is not a no-op either
        assertThat(report.count(SyncAction.Status.DONE)).isPositive();
    }

    // ------------------------------------------------------------------ script export

    @Test
    void exportedScriptRebuildsAnEmptySchemaOnItsOwn() throws Exception {
        // a target that does not exist yet: every action is a CREATE plus a full row copy
        SchemaComparator intoEmpty = new SchemaComparator(sourceFactory,
                () -> MySqlConnectionFactory.openToSchema(profile(TARGET_PORT, REPLAY), PASSWORD, REPLAY),
                SRC, REPLAY, BOTH, cancel);
        SyncPlan plan = intoEmpty.compare(SyncScope.everything());
        String script = DdlScriptGenerator.generate(plan, BOTH);

        List<String> statements = SqlStatementSplitter.splitTexts(script);
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("CREATE PROCEDURE"));
        assertThat(statements).noneSatisfy(s -> assertThat(s.strip()).isEqualTo("DELIMITER $$"));

        try (Connection admin = MySqlConnectionFactory.open(profile(TARGET_PORT, null), PASSWORD)) {
            dropAndCreate(admin, REPLAY);
        }
        try (Connection replay = MySqlConnectionFactory.openToSchema(
                profile(TARGET_PORT, REPLAY), PASSWORD, REPLAY);
             Statement st = replay.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }

        // the file alone, run by hand in the mysql client, is enough to reproduce the source
        SyncPlan leftovers = intoEmpty.compare(SyncScope.everything());
        assertThat(keyedActions(leftovers))
                .extracting(a -> a.kind() + " " + a.object())
                .isEmpty();
        try (Connection src = sourceFactory.open();
             Connection replay = MySqlConnectionFactory.openToSchema(
                     profile(TARGET_PORT, REPLAY), PASSWORD, REPLAY)) {
            assertThat(contentOf(replay, REPLAY, "t_logs")).isEqualTo(contentOf(src, SRC, "t_logs"));
            assertThat(count(replay, REPLAY, "t_users")).isEqualTo(5);
        }
    }

    // ------------------------------------------------------------------ helpers

    private SchemaComparator comparator(SyncOptions options) {
        return new SchemaComparator(sourceFactory, targetFactory, SRC, DST, options, cancel);
    }

    private Connection openTarget() throws SQLException {
        return targetFactory.open();
    }

    private SyncReport execute(SyncPlan plan, SyncOptions options) throws SQLException {
        try (Connection target = targetFactory.open()) {
            return new SyncExecutor(target, options, cancel).execute(plan, SyncExecutor.NOOP);
        }
    }

    /**
     * Selected actions except the keyless table's data action.
     *
     * <p>CHECKSUM TABLE folds rows in storage order, which for a table without a key is not
     * guaranteed to match after a copy, so that one action can legitimately reappear. Its content is
     * asserted separately and exactly, by {@link #contentOf}.
     */
    private static List<SyncAction> keyedActions(SyncPlan plan) {
        List<SyncAction> out = new ArrayList<>();
        for (SyncAction a : plan.selectedActions()) {
            if (a.object().kind() == ObjectKind.TABLE && a.object().name().equals("t_logs")) continue;
            out.add(a);
        }
        return out;
    }

    private static SyncAction action(SyncPlan plan, ObjectKind kind, String name, SyncAction.Kind what) {
        for (SyncAction a : plan.actions()) {
            if (a.object().kind() == kind && a.object().name().equalsIgnoreCase(name)
                    && a.kind() == what) {
                return a;
            }
        }
        return null;
    }

    private static long ddlStatementCount(SyncPlan plan) {
        long total = 0;
        for (SyncAction a : plan.selectedActions()) total += a.statements().size();
        return total;
    }

    /** Order-independent digest of a table's contents, for asserting a keyless table really matches. */
    private static String contentOf(Connection c, String schema, String table) throws SQLException {
        String sql = "SELECT GROUP_CONCAT(CONCAT_WS('|', `stamp`, `message`) ORDER BY `stamp`, `message`) "
                + "FROM `" + schema + "`.`" + table + "`";
        if (!table.equals("t_logs")) {
            sql = "SELECT GROUP_CONCAT(CONCAT_WS('|', `id`, `username`, IFNULL(`email`,'-'), `is_active`, `balance`) "
                    + "ORDER BY `id`) FROM `" + schema + "`.`" + table + "`";
        }
        try (Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? String.valueOf(rs.getString(1)) : null;
        }
    }

    private static ConnectionProfile profile(int port, String schema) {
        return new ConnectionProfile(null, "it-" + port, "127.0.0.1", port, "root",
                PASSWORD, schema, SslMode.DISABLED, null);
    }

    private static void dropAndCreate(Connection c, String schema) throws SQLException {
        exec(c, "DROP SCHEMA IF EXISTS `" + schema + "`",
                "CREATE SCHEMA `" + schema + "` DEFAULT CHARACTER SET utf8mb4");
    }

    private static void exec(Connection c, String... statements) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private static long count(Connection c, String schema, String tableOrQuery) throws SQLException {
        String sql = tableOrQuery.trim().toUpperCase().startsWith("SELECT")
                ? tableOrQuery
                : "SELECT COUNT(*) FROM `" + schema + "`.`" + tableOrQuery + "`";
        try (Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String value(Connection c, String query) throws SQLException {
        try (Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(query)) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : null;
        }
    }

    private static ViewDef view(List<ViewDef> views, String name) {
        for (ViewDef v : views) {
            if (v.name().equalsIgnoreCase(name)) return v;
        }
        throw new AssertionError("view " + name + " missing: " + views.stream()
                .map(ViewDef::name).toList());
    }

    private static RoutineDef routine(List<RoutineDef> items, String name) {
        for (RoutineDef r : items) {
            if (r.name().equalsIgnoreCase(name)) return r;
        }
        throw new AssertionError("routine " + name + " missing");
    }

    private static TriggerDef trigger(List<TriggerDef> items, String name) {
        for (TriggerDef t : items) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        throw new AssertionError("trigger " + name + " missing");
    }

    // ------------------------------------------------------------------ fixtures

    /** The source side: everything present, including a keyless table and one of each object kind. */
    private static void loadSourceFixture(Connection c) throws SQLException {
        exec(c,
                "CREATE TABLE `" + SRC + "`.`t_users` (\n"
                        + "  `id` bigint unsigned NOT NULL AUTO_INCREMENT,\n"
                        + "  `username` varchar(64) NOT NULL,\n"
                        + "  `email` varchar(128) DEFAULT NULL,\n"
                        + "  `is_active` tinyint(1) NOT NULL DEFAULT '1',\n"
                        + "  `balance` decimal(12,2) NOT NULL DEFAULT '0.00',\n"
                        + "  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
                        + "  PRIMARY KEY (`id`),\n"
                        + "  UNIQUE KEY `uk_username` (`username`),\n"
                        + "  KEY `idx_created` (`created_at`)\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='users'",
                "CREATE TABLE `" + SRC + "`.`t_orders` (\n"
                        + "  `order_id` bigint unsigned NOT NULL AUTO_INCREMENT,\n"
                        + "  `user_id` bigint unsigned NOT NULL,\n"
                        + "  `amount` decimal(12,2) NOT NULL,\n"
                        + "  `remark` varchar(255) DEFAULT NULL,\n"
                        + "  PRIMARY KEY (`order_id`),\n"
                        + "  KEY `idx_user` (`user_id`),\n"
                        + "  CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) "
                        + "REFERENCES `t_users` (`id`) ON DELETE CASCADE\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE `" + SRC + "`.`t_logs` (\n"
                        + "  `stamp` datetime NOT NULL,\n"
                        + "  `message` varchar(200) NOT NULL\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "INSERT INTO `" + SRC + "`.`t_users` (id, username, email, is_active, balance) VALUES\n"
                        + "  (1,'amy','amy@example.com',1,'10.00'),\n"
                        + "  (2,'bob',NULL,1,'20.00'),\n"
                        + "  (3,'cid','cid@example.com',0,'33.30'),\n"
                        + "  (4,'dee','dee@example.com',1,'40.00'),\n"
                        + "  (5,'ely','ely@example.com',1,'50.00')",
                "INSERT INTO `" + SRC + "`.`t_orders` (order_id, user_id, amount, remark) VALUES\n"
                        + "  (1,1,'5.00','first'), (2,1,'7.50',NULL), (3,3,'9.00','x'), (4,5,'1.00',NULL)",
                "INSERT INTO `" + SRC + "`.`t_logs` (stamp, message) VALUES\n"
                        + "  ('2026-01-01 00:00:00','created'),\n"
                        + "  ('2026-01-02 03:04:05','touched 订单'),\n"
                        + "  ('2026-01-03 00:00:00','closed')",
                "CREATE VIEW `" + SRC + "`.`v_active` AS\n"
                        + "  SELECT `id`, `username`, `balance` FROM `" + SRC + "`.`t_users` "
                        + "WHERE `is_active` = 1",
                "CREATE PROCEDURE `" + SRC + "`.`p_touch`(IN p_id BIGINT)\n"
                        + "BEGIN\n  UPDATE `" + SRC + "`.`t_users` SET `balance` = `balance` + 1 "
                        + "WHERE `id` = p_id;\n  SELECT ROW_COUNT() AS touched;\nEND",
                "CREATE TRIGGER `" + SRC + "`.`trg_touch` BEFORE UPDATE ON `" + SRC + "`.`t_users` "
                        + "FOR EACH ROW BEGIN\n  SET NEW.`username` = NEW.`username`;\nEND");
    }

    /**
     * The drifted side: t_users narrower and missing an index with different rows, t_orders and
     * every view / routine / trigger absent, t_logs present with other content, plus a table the
     * source does not have.
     */
    private static void loadDriftedTargetFixture(Connection c) throws SQLException {
        exec(c,
                "CREATE TABLE `" + DST + "`.`t_users` (\n"
                        + "  `id` bigint unsigned NOT NULL AUTO_INCREMENT,\n"
                        + "  `username` varchar(64) NOT NULL,\n"
                        + "  `email` varchar(64) DEFAULT NULL,\n"
                        + "  `is_active` tinyint(1) NOT NULL DEFAULT '1',\n"
                        + "  `balance` decimal(12,2) NOT NULL DEFAULT '0.00',\n"
                        + "  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
                        + "  PRIMARY KEY (`id`),\n"
                        + "  UNIQUE KEY `uk_username` (`username`)\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='users'",
                "CREATE TABLE `" + DST + "`.`t_logs` (\n"
                        + "  `stamp` datetime NOT NULL,\n"
                        + "  `message` varchar(200) NOT NULL\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE `" + DST + "`.`t_scratch` (\n"
                        + "  `id` int NOT NULL, PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB",
                // 1 and 2 match, 3 differs, 4 matches, 5 missing, 9 exists only here
                "INSERT INTO `" + DST + "`.`t_users` (id, username, email, is_active, balance) VALUES\n"
                        + "  (1,'amy','amy@example.com',1,'10.00'),\n"
                        + "  (2,'bob',NULL,1,'20.00'),\n"
                        + "  (3,'cid','cid@example.com',0,'30.00'),\n"
                        + "  (4,'dee','dee@example.com',1,'40.00'),\n"
                        + "  (9,'ghost','ghost@example.com',1,'0.00')",
                "INSERT INTO `" + DST + "`.`t_logs` (stamp, message) VALUES\n"
                        + "  ('2026-01-01 00:00:00','created'),\n"
                        + "  ('2025-12-31 23:59:59','stale')",
                "INSERT INTO `" + DST + "`.`t_scratch` (id) VALUES (1)");
    }
}

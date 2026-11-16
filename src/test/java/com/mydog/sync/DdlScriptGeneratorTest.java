package com.mydog.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script a user can read, keep, and run in the mysql client.
 *
 * <p>Assertion style here is substring-based on purpose for the row statements (the point is that a
 * row is addressable and the values are quoted) and exact for the scaffolding — the delimiter and
 * foreign-key lines are what make the file runnable rather than merely readable.
 */
class DdlScriptGeneratorTest {

    private static final SyncOptions BOTH = new SyncOptions(SyncMode.STRUCTURE_AND_DATA, false, 5000, false);

    @Test
    void writesHeaderStructureDataAndFooterInExecutionOrder() throws Exception {
        ObjectRef table = new ObjectRef(ObjectKind.TABLE, "shop_copy", "t_users");
        ObjectRef trigger = new ObjectRef(ObjectKind.TRIGGER, "shop_copy", "trg_users");
        SyncPlan plan = new SyncPlan("shop@3307", "shop_copy@3308", List.of(
                SyncAction.ddl(trigger, SyncAction.Kind.CREATE,
                        List.of("CREATE TRIGGER `trg_users` AFTER INSERT ON `users` FOR EACH ROW\n"
                                + "BEGIN\n  UPDATE `t_users` SET `balance` = 0;\nEND"), "trigger missing"),
                SyncAction.ddl(table, SyncAction.Kind.CREATE,
                        List.of("CREATE TABLE `t_users` (\n  `id` int NOT NULL\n)"), "table missing"),
                SyncAction.data(table, sink -> {
                    sink.insert(user(1, "a", null));
                    return new DataCounts(1, 0, 0, 1);
                }, "rows 3 -> 0", false)),
                List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        assertThat(script).startsWith("-- MyDog synchronization script")
                .contains("-- source: shop@3307    target: shop_copy@3308")
                .contains("USE `shop_copy`;")
                .contains("SET NAMES utf8mb4;")
                .contains("SET FOREIGN_KEY_CHECKS = 0;")
                .endsWith("-- end of script\n");

        // tables before triggers even though the trigger was added to the plan first
        assertThat(script.indexOf("-- ============================================================ table"))
                .isLessThan(script.indexOf("-- ============================================================ trigger"));
        assertThat(script.indexOf("CREATE TABLE `t_users`"))
                .isLessThan(script.indexOf("CREATE TRIGGER `trg_users`"));
        assertThat(script.indexOf("SET FOREIGN_KEY_CHECKS = 1;"))
                .isGreaterThan(script.indexOf("INSERT INTO"));
    }

    @Test
    void dataRowsBecomeAddressableStatementsWithQuotedLiterals() throws Exception {
        ObjectRef table = new ObjectRef(ObjectKind.TABLE, "shop", "t_users");
        SyncPlan plan = new SyncPlan("a", "b", List.of(
                SyncAction.data(table, sink -> {
                    sink.insert(user(1, "O'Brien", null));
                    sink.insert(user(2, "普通用户", new BigDecimal("10.50")));
                    sink.update(user(3, "new", null), user(3, "old", null));
                    sink.delete(user(4, "gone", null));
                    return new DataCounts(2, 1, 1, 4);
                }, "rows 3 -> 1", false)), List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        // two inserts share one statement; values are escaped, not interpolated naively
        assertThat(script).contains("INSERT INTO `shop`.`t_users` (`id`, `name`, `balance`)\nVALUES\n"
                + "  (1, 'O''Brien', NULL),\n"
                + "  (2, '普通用户', 10.50);");
        // an update writes every non-key column, so re-running the script is idempotent
        assertThat(script).contains("UPDATE `shop`.`t_users` SET `name` = 'new', `balance` = NULL"
                + " WHERE `id` = 3;");
        assertThat(script).contains("DELETE FROM `shop`.`t_users` WHERE `id` = 4;");
    }

    @Test
    void nullKeyValueBecomesIsNullNotEqualsNull() throws Exception {
        // "= NULL" matches nothing, so a row whose key is NULL must be addressed with IS NULL
        ObjectRef table = new ObjectRef(ObjectKind.TABLE, "shop", "t_logs");
        SyncPlan plan = new SyncPlan("a", "b", List.of(SyncAction.data(table, sink -> {
            sink.delete(user(null, "x", 1));
            return new DataCounts(0, 0, 1, 1);
        }, "", false)), List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        assertThat(script).contains("DELETE FROM `shop`.`t_logs` WHERE `id` IS NULL;");
    }

    @Test
    void routineBodiesGetTheirOwnDelimiter() throws Exception {
        ObjectRef proc = new ObjectRef(ObjectKind.PROCEDURE, "shop", "p_sync");
        SyncPlan plan = new SyncPlan("a", "b", List.of(
                SyncAction.ddl(proc, SyncAction.Kind.CREATE,
                        List.of("CREATE PROCEDURE `p_sync`() BEGIN\n  SELECT 1;\nEND"), "missing")),
                List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        assertThat(script).contains("DELIMITER $$\n"
                + "CREATE PROCEDURE `p_sync`() BEGIN\n  SELECT 1;\nEND $$\n"
                + "DELIMITER ;");
    }

    @Test
    void aSingleStatementBodyNeedsNoDelimiterChange() throws Exception {
        ObjectRef view = new ObjectRef(ObjectKind.VIEW, "shop", "v_users");
        SyncPlan plan = new SyncPlan("a", "b", List.of(
                SyncAction.ddl(view, SyncAction.Kind.CREATE,
                        List.of("CREATE ALGORITHM=UNDEFINED VIEW `v_users` AS select `id` from `t_users`"),
                        "missing")), List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        assertThat(script).doesNotContain("DELIMITER")
                .contains("CREATE ALGORITHM=UNDEFINED VIEW `v_users` AS select `id` from `t_users`;");
    }

    @Test
    void reloadStatementPrecedesTheRowsItFeeds() throws Exception {
        ObjectRef table = new ObjectRef(ObjectKind.TABLE, "shop", "t_nopk");
        SyncPlan plan = new SyncPlan("a", "b", List.of(
                SyncAction.data(table, List.of("DELETE FROM `shop`.`t_nopk`"),
                        countingProducer(), "no row key: full reload", false)), List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        assertThat(script.indexOf("DELETE FROM `shop`.`t_nopk`;"))
                .isLessThan(script.indexOf("INSERT INTO `shop`.`t_nopk`"));
    }

    @Test
    void unselectedActionsAreLeftOutOfTheScript() throws Exception {
        SyncAction dropped = SyncAction.ddl(new ObjectRef(ObjectKind.TABLE, "shop", "t_scratch"),
                SyncAction.Kind.DROP, List.of("DROP TABLE IF EXISTS `shop`.`t_scratch`"), "extra");
        dropped.setSelected(false);
        SyncPlan plan = new SyncPlan("a", "b", List.of(
                dropped,
                SyncAction.ddl(new ObjectRef(ObjectKind.TABLE, "shop", "t_users"), SyncAction.Kind.CREATE,
                        List.of("CREATE TABLE `t_users` (`id` int)"), "missing")), List.of());

        String script = DdlScriptGenerator.generate(plan, BOTH);

        assertThat(script).doesNotContain("DROP TABLE").contains("CREATE TABLE `t_users`");
    }

    @Test
    void anEmptyPlanStillWritesARunnableFile() throws Exception {
        String script = DdlScriptGenerator.generate(new SyncPlan("a", "b", List.of(), List.of()), BOTH);

        assertThat(script).contains("SET FOREIGN_KEY_CHECKS = 0;")
                .doesNotContain("USE ")
                .endsWith("-- end of script\n");
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> COLUMNS = List.of("id", "name", "balance");

    /** Arrays.asList, not List.of: the NULL-value cases pass a null element. */
    private static Row user(Object id, Object name, Object balance) {
        return new Row(COLUMNS, Arrays.asList(id, name, balance), List.of("id"));
    }

    /** Two inserts, so the batching path is covered by every test that uses it. */
    private static DataProducer countingProducer() {
        return sink -> {
            sink.insert(user(1, "a", null));
            sink.insert(user(2, "b", null));
            return new DataCounts(2, 0, 0, 2);
        };
    }
}

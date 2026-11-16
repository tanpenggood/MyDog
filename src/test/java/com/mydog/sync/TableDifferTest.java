package com.mydog.sync;

import com.mydog.metadata.model.TableDef;
import com.mydog.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ALTER text the differ derives from two definitions, asserted exactly.
 *
 * <p>These tests are the reason the assertion style is exact-match: an ALTER is applied to a live
 * table, so a clause that is merely "close" (a dropped column instead of a moved one, a missing
 * AFTER, a rebuild where an in-place change was possible) is a production incident rather than a
 * failing test.
 */
class TableDifferTest {

    // ------------------------------------------------------------------ the sandbox fixtures

    @Test
    void autoIncrementWatermarkAloneProducesNoAlter() {
        // src_t_users and dst_t_users differ only in AUTO_INCREMENT=6 vs =7 — a counter, not a schema
        List<String> alter = TableDiffer.alterStatements(
                table("shop", "t_users", Fixtures.read("src_t_users.sql")),
                table("shop", "t_users", Fixtures.read("dst_t_users.sql")));

        assertThat(alter).isEmpty();
    }

    @Test
    void addsMissingColumnIndexAndForeignKeyInOneStatement() {
        List<String> alter = TableDiffer.alterStatements(
                table("shop", "t_orders", Fixtures.read("src_t_orders.sql")),
                table("shop", "t_orders", Fixtures.read("dst_t_orders.sql")));

        assertThat(alter).hasSize(1);
        assertThat(alter.get(0)).isEqualTo("""
                ALTER TABLE `shop`.`t_orders` ADD COLUMN `remark` varchar(255) DEFAULT NULL AFTER `status`, \
                ADD UNIQUE KEY `uk_order_no` (`order_no`), \
                ADD CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `t_users` (`id`) ON DELETE CASCADE""");
    }

    @Test
    void alterNamesTheTargetSchemaNotTheSource() {
        // the common real case: shop on one server into shop_copy on another
        List<String> alter = TableDiffer.alterStatements(
                table("shop", "t_orders", Fixtures.read("src_t_orders.sql")),
                table("shop_copy", "t_orders", Fixtures.read("dst_t_orders.sql")));

        assertThat(alter.get(0)).startsWith("ALTER TABLE `shop_copy`.`t_orders`");
    }

    // ------------------------------------------------------------------ derived cases

    @Test
    void droppedColumnAndTypeChangeBecomeModifyAndDrop() {
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `id` int NOT NULL,\n"
                        + "  `name` varchar(100) NOT NULL,\n"
                        + "  PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `id` int NOT NULL,\n"
                        + "  `name` varchar(50) NOT NULL,\n"
                        + "  `legacy` int DEFAULT NULL,\n"
                        + "  PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter).containsExactly(
                "ALTER TABLE `s`.`t` DROP COLUMN `legacy`, MODIFY COLUMN `name` varchar(100) NOT NULL");
    }

    @Test
    void columnInsertedInTheMiddleBecomesOneAddAfter() {
        // matching by longest common subsequence is what stops a mid-list insert from rewriting
        // everything after it
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `a` int NOT NULL,\n"
                        + "  `b` int NOT NULL,\n"
                        + "  `c` int NOT NULL,\n"
                        + "  PRIMARY KEY (`a`)\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `a` int NOT NULL,\n"
                        + "  `c` int NOT NULL,\n"
                        + "  PRIMARY KEY (`a`)\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter).containsExactly("ALTER TABLE `s`.`t` ADD COLUMN `b` int NOT NULL AFTER `a`");
    }

    @Test
    void firstColumnUsesFirstNotAfter() {
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `tenant` int NOT NULL,\n"
                        + "  `id` int NOT NULL,\n"
                        + "  PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `id` int NOT NULL,\n"
                        + "  PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter).containsExactly("ALTER TABLE `s`.`t` ADD COLUMN `tenant` int NOT NULL FIRST");
    }

    @Test
    void changedIndexOnSameNameIsDroppedThenAdded() {
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `a` int NOT NULL,\n"
                        + "  `b` int NOT NULL,\n"
                        + "  KEY `idx_ab` (`a`, `b`)\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `a` int NOT NULL,\n"
                        + "  `b` int NOT NULL,\n"
                        + "  KEY `idx_ab` (`a`)\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter).containsExactly(
                "ALTER TABLE `s`.`t` DROP INDEX `idx_ab`, ADD KEY `idx_ab` (`a`, `b`)");
    }

    @Test
    void primaryKeyReplacementDropsBeforeAdding() {
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `a` int NOT NULL,\n"
                        + "  `b` int NOT NULL,\n"
                        + "  PRIMARY KEY (`a`, `b`)\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n"
                        + "  `a` int NOT NULL,\n"
                        + "  `b` int NOT NULL,\n"
                        + "  PRIMARY KEY (`a`)\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter.get(0))
                .startsWith("ALTER TABLE `s`.`t` DROP PRIMARY KEY, ADD PRIMARY KEY (`a`, `b`)");
    }

    @Test
    void charsetDriftUsesConvertToCharacterSet() {
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n  `a` int NOT NULL\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci"),
                table("s", "t", "CREATE TABLE `t` (\n  `a` int NOT NULL\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=latin1"));

        assertThat(alter).containsExactly(
                "ALTER TABLE `s`.`t` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }

    @Test
    void integerDisplayWidthDifferenceIsIgnored() {
        // a 5.7 dump against an 8.0 table: int(11) vs int is the same column
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n  `id` int(11) NOT NULL,\n  PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  PRIMARY KEY (`id`)\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter).isEmpty();
    }

    @Test
    void tinyintBooleanIsStillADifference() {
        List<String> alter = TableDiffer.alterStatements(
                table("s", "t", "CREATE TABLE `t` (\n  `flag` tinyint(1) NOT NULL DEFAULT '0'\n"
                        + ") ENGINE=InnoDB"),
                table("s", "t", "CREATE TABLE `t` (\n  `flag` tinyint NOT NULL DEFAULT '0'\n"
                        + ") ENGINE=InnoDB"));

        assertThat(alter).containsExactly(
                "ALTER TABLE `s`.`t` MODIFY COLUMN `flag` tinyint(1) NOT NULL DEFAULT '0'");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Only the DDL and the identity matter to the differ — the structured column and index lists
     * come from information_schema and are used by the data layer, not here.
     */
    private static TableDef table(String schema, String name, String ddl) {
        return new TableDef(schema, name, ddl, List.of(), List.of(), List.of(),
                "utf8mb4", "utf8mb4_0900_ai_ci", 0);
    }
}

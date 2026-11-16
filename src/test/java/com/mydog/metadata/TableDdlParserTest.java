package com.mydog.metadata;

import com.mydog.metadata.TableDdlParser.Column;
import com.mydog.metadata.TableDdlParser.Constraint;
import com.mydog.metadata.TableDdlParser.Kind;
import com.mydog.metadata.TableDdlParser.ParsedTable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing of real {@code SHOW CREATE TABLE} output, asserted on the MySQL 8.0.42 fixtures in this
 * package, plus the clause shapes that a naive comma split gets wrong.
 */
class TableDdlParserTest {

    // ------------------------------------------------------------------ the real fixtures

    @Test
    void parsesSourceOrdersFixture() {
        ParsedTable table = TableDdlParser.parse(fixture("src_t_orders.sql"));

        assertThat(table.tableName()).isEqualTo("t_orders");
        assertThat(table.columns()).extracting(Column::name).containsExactly(
                "order_id", "user_id", "order_no", "amount", "status", "remark", "created_at");
        assertThat(table.constraints()).extracting(Constraint::kind).containsExactly(
                Kind.PRIMARY_KEY, Kind.UNIQUE_KEY, Kind.KEY, Kind.FOREIGN_KEY);
        assertThat(table.constraints()).extracting(Constraint::name).containsExactly(
                "PRIMARY", "uk_order_no", "idx_user", "fk_orders_user");

        assertThat(table.tableOptions()).startsWith("ENGINE=InnoDB")
                .contains("AUTO_INCREMENT=1006")
                .contains("COLLATE=utf8mb4_0900_ai_ci")
                .contains("COMMENT='orders'");
    }

    @Test
    void parsesTargetOrdersFixtureWithItsDrift() {
        ParsedTable table = TableDdlParser.parse(fixture("dst_t_orders.sql"));

        assertThat(table.columns()).hasSize(6).extracting(Column::name).doesNotContain("remark");
        assertThat(table.constraints()).hasSize(2).extracting(Constraint::kind)
                .containsExactly(Kind.PRIMARY_KEY, Kind.KEY);
        // only the watermark moved, so the options still differ from the source text
        assertThat(table.tableOptions()).contains("AUTO_INCREMENT=1007");
    }

    @Test
    void constraintTextKeepsTheWholeClause() {
        ParsedTable table = TableDdlParser.parse(fixture("src_t_orders.sql"));

        assertThat(table.constraints().get(3).text()).isEqualTo(
                "CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `t_users` (`id`) ON DELETE CASCADE");
        assertThat(table.constraints().get(0).text()).isEqualTo("PRIMARY KEY (`order_id`)");
    }

    @Test
    void columnDefinitionKeepsTypeDefaultAndAttributes() {
        List<Column> columns = TableDdlParser.parse(fixture("src_t_orders.sql")).columns();

        assertThat(columns.get(0).definition()).isEqualTo("`order_id` bigint unsigned NOT NULL AUTO_INCREMENT");
        // decimal(12,2): the comma inside the parentheses is not an item separator
        assertThat(columns.get(3).definition()).isEqualTo("`amount` decimal(12,2) NOT NULL");
        // enum('pending','paid',...): quoted commas are not item separators either
        assertThat(columns.get(4).definition())
                .isEqualTo("`status` enum('pending','paid','shipped','closed') NOT NULL DEFAULT 'pending'");
        assertThat(columns.get(6).definition())
                .isEqualTo("`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP");
    }

    @Test
    void parsesUsersFixtureIncludingTinyintValueAndTwoTimestamps() {
        ParsedTable table = TableDdlParser.parse(fixture("src_t_users.sql"));

        assertThat(table.columns()).hasSize(7);
        assertThat(table.columns().get(3).definition()).isEqualTo("`is_active` tinyint(1) NOT NULL DEFAULT '1'");
        assertThat(table.columns().get(6).definition())
                .isEqualTo("`updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        assertThat(table.constraints()).extracting(Constraint::kind).containsExactly(
                Kind.PRIMARY_KEY, Kind.UNIQUE_KEY, Kind.KEY);
        assertThat(table.constraints()).extracting(Constraint::name).containsExactly(
                "PRIMARY", "uk_username", "idx_created");
    }

    @Test
    void parenthesesInsideACommentLiteralDoNotEndTheColumnList() {
        ParsedTable table = TableDdlParser.parse(fixture("src_t_logs.sql"));

        assertThat(table.tableName()).isEqualTo("t_logs");
        assertThat(table.columns()).extracting(Column::name)
                .containsExactly("id", "user_id", "action", "created_at");
        assertThat(table.constraints()).hasSize(2);
        assertThat(table.tableOptions()).isEqualTo(
                "ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci"
                        + " COMMENT='audit logs (source only)'");
    }

    // ------------------------------------------------------------------ awkward clause shapes

    @Test
    void defaultContainingCommaAndParenIsOneColumn() {
        ParsedTable table = TableDdlParser.parse("""
                CREATE TABLE `weird` (
                  `a` varchar(32) NOT NULL DEFAULT 'x, y(z)' COMMENT 'has , and ) inside',
                  `b` int NOT NULL,
                  PRIMARY KEY (`a`)
                ) ENGINE=InnoDB""");

        assertThat(table.columns()).hasSize(2);
        assertThat(table.columns().get(0).definition())
                .isEqualTo("`a` varchar(32) NOT NULL DEFAULT 'x, y(z)' COMMENT 'has , and ) inside'");
        assertThat(table.constraints()).singleElement()
                .extracting(Constraint::kind).isEqualTo(Kind.PRIMARY_KEY);
    }

    @Test
    void aCheckConstraintSpanningLinesBecomesOneStableItem() {
        ParsedTable table = TableDdlParser.parse("""
                CREATE TABLE `chk` (
                  `a` int NOT NULL,
                  `b` int NOT NULL,
                  CONSTRAINT `chk_a_le_b`
                    CHECK
                    ((`a` <= `b`)),
                  PRIMARY KEY (`a`)
                ) ENGINE=InnoDB""");

        assertThat(table.constraints()).hasSize(2);
        Constraint check = table.constraints().get(0);
        assertThat(check.kind()).isEqualTo(Kind.CHECK);
        assertThat(check.name()).isEqualTo("chk_a_le_b");
        assertThat(check.text()).isEqualTo("CONSTRAINT `chk_a_le_b` CHECK ((`a` <= `b`))");
    }

    @Test
    void unnamedConstraintsGetAStableFallbackName() {
        ParsedTable table = TableDdlParser.parse("""
                CREATE TABLE `noname` (
                  `a` int NOT NULL,
                  `b` int NOT NULL,
                  FOREIGN KEY (`b`) REFERENCES `other` (`id`),
                  CHECK ((`a` > 0)),
                  UNIQUE (`a`)
                ) ENGINE=InnoDB""");

        assertThat(table.constraints()).extracting(Constraint::kind).containsExactly(
                Kind.FOREIGN_KEY, Kind.CHECK, Kind.UNIQUE_KEY);
        assertThat(table.constraints()).extracting(Constraint::name).containsExactly(
                "foreign_key_3", "check_4", "unique_key_5");
        // the same text always produces the same names, which is all a diff needs to pair them up
        assertThat(TableDdlParser.parse("""
                CREATE TABLE `noname` (
                  `a` int NOT NULL,
                  `b` int NOT NULL,
                  FOREIGN KEY (`b`) REFERENCES `other` (`id`),
                  CHECK ((`a` > 0)),
                  UNIQUE (`a`)
                ) ENGINE=InnoDB""").constraints()).isEqualTo(table.constraints());
    }

    @Test
    void indexClauseVariantsAreClassified() {
        ParsedTable table = TableDdlParser.parse("""
                CREATE TABLE `idx` (
                  `a` int NOT NULL,
                  `note` varchar(200) NOT NULL,
                  `g` geometry NOT NULL,
                  FULLTEXT KEY `ft_note` (`note`),
                  SPATIAL KEY `sp_g` (`g`),
                  INDEX `by_a` (`a` (4) DESC),
                  KEY (`a`)
                ) ENGINE=InnoDB""");

        assertThat(table.constraints()).extracting(Constraint::kind).containsExactly(
                Kind.FULLTEXT, Kind.SPATIAL, Kind.KEY, Kind.KEY);
        assertThat(table.constraints()).extracting(Constraint::name)
                .containsExactly("ft_note", "sp_g", "by_a", "key_7");
        assertThat(table.columns()).hasSize(3);
    }

    @Test
    void reservedWordsUsedAsColumnNamesStayColumns() {
        ParsedTable table = TableDdlParser.parse("""
                CREATE TABLE `kw` (
                  `key` int NOT NULL,
                  `check` int NOT NULL,
                  `primary` int NOT NULL,
                  `order` varchar(8) NOT NULL DEFAULT 'asc, desc'
                ) ENGINE=InnoDB""");

        assertThat(table.columns()).extracting(Column::name).containsExactly("key", "check", "primary", "order");
        assertThat(table.constraints()).isEmpty();
    }

    @Test
    void emptyTableAndSchemaQualifiedNameAreAccepted() {
        assertThat(TableDdlParser.parse("CREATE TABLE `shop`.`t_orders` (\n  `a` int\n) ENGINE=InnoDB")
                .tableName()).isEqualTo("t_orders");
        ParsedTable bare = TableDdlParser.parse("CREATE TABLE t (a int) ENGINE=InnoDB");
        assertThat(bare.tableName()).isEqualTo("t");
        assertThat(bare.columns()).singleElement().extracting(Column::name).isEqualTo("a");
    }

    // ------------------------------------------------------------------ garbage

    @Test
    void garbageInputIsRejectedInsteadOfParsedAsAnEmptyTable() {
        assertThatThrownBy(() -> TableDdlParser.parse("not a table"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableDdlParser.parse("SELECT * FROM t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableDdlParser.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableDdlParser.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        // truncated output: the column list never closes
        assertThatThrownBy(() -> TableDdlParser.parse("CREATE TABLE `t` (`a` int, `b` varchar(4)"))
                .isInstanceOf(IllegalArgumentException.class);
        // nothing after the name to split
        assertThatThrownBy(() -> TableDdlParser.parse("CREATE TABLE `t`"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helper

    private static String fixture(String name) {
        try {
            var url = Objects.requireNonNull(TableDdlParserTest.class.getResource(name), "missing " + name);
            return Files.readString(Path.of(url.toURI()));
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }
}

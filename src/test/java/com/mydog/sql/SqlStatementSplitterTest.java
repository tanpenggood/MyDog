package com.mydog.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.mydog.sql.SqlStatementSplitter.split;
import static com.mydog.sql.SqlStatementSplitter.splitTexts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Covers the MySQL dialect rules the editor and the sync runner execute scripts with. */
class SqlStatementSplitterTest {

    /** Verbatim tail of docker/init/src/01_schema.sql: stored programs written between DELIMITER lines. */
    private static final String FIXTURE_STORED_PROGRAMS = """
            -- fn_get_user_balance: function (target has older version)
            DROP FUNCTION IF EXISTS fn_get_user_balance;
            DELIMITER $$
            CREATE FUNCTION fn_get_user_balance(p_user_id BIGINT UNSIGNED) RETURNS DECIMAL(12,2)
                DETERMINISTIC
                READS SQL DATA
            BEGIN
                DECLARE v_balance DECIMAL(12,2);
                SELECT balance INTO v_balance FROM t_users WHERE id = p_user_id;
                RETURN v_balance;
            END$$
            DELIMITER ;

            -- sp_archive_old_orders: procedure (missing in target)
            DROP PROCEDURE IF EXISTS sp_archive_old_orders;
            DELIMITER $$
            CREATE PROCEDURE sp_archive_old_orders(IN p_days INT)
            BEGIN
                DELETE FROM t_orders
                WHERE status = 'closed' AND created_at < NOW() - INTERVAL p_days DAY;
            END$$
            DELIMITER ;

            -- trg_users_audit: trigger (missing in target)
            DROP TRIGGER IF EXISTS trg_users_audit;
            DELIMITER $$
            CREATE TRIGGER trg_users_audit AFTER UPDATE ON t_users FOR EACH ROW
            BEGIN
                INSERT INTO t_logs (user_id, action) VALUES (NEW.id, CONCAT('update:', OLD.username));
            END$$
            DELIMITER ;
            """;

    @Test
    void nullEmptyAndBlankScriptsYieldNothing() {
        assertThat(split(null)).isEmpty();
        assertThat(split("")).isEmpty();
        assertThat(splitTexts(null)).isEmpty();
        assertThat(splitTexts("   \n\t \n")).isEmpty();
    }

    @Test
    void splitsOnDefaultDelimiterAndTrimsWhitespace() {
        assertThat(splitTexts("SELECT 1;SELECT 2;")).containsExactly("SELECT 1", "SELECT 2");
        assertThat(splitTexts("  SELECT 1 ;\n\n  SELECT 2 ;  ")).containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void offsetsSpanExactlyTheStatementText() {
        String script = "SELECT 1;   SELECT 2";
        List<SqlStatementSplitter.Statement> statements = split(script);
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst().start()).isZero();
        assertThat(statements.getFirst().end()).isEqualTo(8);
        assertThat(statements.get(1).start()).isEqualTo(12);
        assertThat(statements.get(1).end()).isEqualTo(20);
        assertOffsetsExact(script, statements);
    }

    @Test
    void lastStatementWithoutTrailingDelimiterIsStillEmitted() {
        assertThat(splitTexts("SELECT 1; SELECT 2")).containsExactly("SELECT 1", "SELECT 2");
        assertThat(splitTexts("SELECT 1; SELECT 2\n")).containsExactly("SELECT 1", "SELECT 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIMITER $$", "DELIMITER  $$", "DELIMITER$$", "delimiter $$", "DeLiMiTeR $$   "})
    void everyDelimiterDirectiveFormTakesEffect(String directive) {
        String script = directive + "\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$";
        List<SqlStatementSplitter.Statement> statements = split(script);
        assertThat(splitTexts(script)).containsExactly("CREATE PROCEDURE p() BEGIN SELECT 1; END");
        assertOffsetsExact(script, statements);
    }

    @Test
    void delimiterRestoredToDefaultAndSlashDelimiter() {
        String script = """
                DELIMITER $$
                CREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END$$
                DELIMITER ;
                SELECT 1; SELECT 2;
                """;
        assertThat(splitTexts(script)).containsExactly(
                "CREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END", "SELECT 1", "SELECT 2");

        script = """
                delimiter //
                SELECT 'a;b'//
                DELIMITER ;
                SELECT 1; SELECT 2;
                """;
        assertThat(splitTexts(script)).containsExactly("SELECT 'a;b'", "SELECT 1", "SELECT 2");
    }

    @Test
    void delimiterWithoutArgumentIsANoOp() {
        assertThat(splitTexts("DELIMITER\nSELECT 1;")).containsExactly("SELECT 1");
        assertThat(splitTexts("DELIMITER ;\nSELECT 1;")).containsExactly("SELECT 1");
        assertThat(splitTexts("DELIMITER\n\nSELECT 1$$;")).containsExactly("SELECT 1$$");
    }

    @Test
    void directiveLinesAreNeverEmittedAsStatements() {
        List<String> texts = splitTexts(FIXTURE_STORED_PROGRAMS);
        assertThat(texts).noneMatch(t -> t.contains("DELIMITER"));
        assertOffsetsExact(FIXTURE_STORED_PROGRAMS, split(FIXTURE_STORED_PROGRAMS));
    }

    @Test
    void storedProgramBodiesStayOneStatementWithInnerSemicolons() {
        List<SqlStatementSplitter.Statement> statements = split(FIXTURE_STORED_PROGRAMS);
        assertThat(statements).hasSize(6);
        assertThat(statements).allSatisfy(st -> assertThat(st.text())
                .matches(t -> t.startsWith("DROP ") || t.startsWith("CREATE ")));

        assertThat(statements.get(1).text())
                .startsWith("CREATE FUNCTION fn_get_user_balance")
                .endsWith("END")
                .contains("DECLARE v_balance DECIMAL(12,2);\n    SELECT balance INTO v_balance FROM t_users WHERE id = p_user_id;\n    RETURN v_balance;");
        assertThat(statements.get(3).text())
                .startsWith("CREATE PROCEDURE sp_archive_old_orders")
                .endsWith("END")
                .contains("WHERE status = 'closed' AND created_at < NOW() - INTERVAL p_days DAY;");
        assertThat(statements.get(5).text())
                .startsWith("CREATE TRIGGER trg_users_audit")
                .endsWith("END")
                .contains("VALUES (NEW.id, CONCAT('update:', OLD.username));");
        assertOffsetsExact(FIXTURE_STORED_PROGRAMS, statements);
    }

    @Test
    void semicolonInsideStringLiteralDoesNotSplit() {
        assertThat(splitTexts("SELECT 'a;b' AS x; SELECT 2;")).containsExactly("SELECT 'a;b' AS x", "SELECT 2");
    }

    @Test
    void doubledQuotesAndBackslashEscapesKeepTheStringOpen() {
        assertThat(splitTexts("SELECT 'a\\'b;c'; SELECT 'it''s;ok'; SELECT 2;"))
                .containsExactly("SELECT 'a\\'b;c'", "SELECT 'it''s;ok'", "SELECT 2");
        assertThat(splitTexts("SELECT \"a\\\";b\"; SELECT 2;")).containsExactly("SELECT \"a\\\";b\"", "SELECT 2");
    }

    @Test
    void doubleQuotedStringsAndBacktickIdentifiersAreNotSplit() {
        assertThat(splitTexts("SELECT \"a;b\" FROM `t;x`.`c;d`; SELECT 2;"))
                .containsExactly("SELECT \"a;b\" FROM `t;x`.`c;d`", "SELECT 2");
    }

    @Test
    void lineCommentsDoNotSplitAndLeadOnlyNoiseIsDropped() {
        String script = """
                -- a; b
                SELECT 1;
                # c; d
                SELECT 2;
                """;
        assertThat(splitTexts(script)).containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void commentBodiesAreOpaqueToQuoting() {
        // apostrophe and delimiter chars inside comments must not open a string or split
        assertThat(splitTexts("-- it's fine; really\nSELECT 1;")).containsExactly("SELECT 1");
        assertThat(splitTexts("/* it's ; DELIMITER $$ */ SELECT 1;")).containsExactly("SELECT 1");
    }

    @Test
    void bareDoubleDashAtEndOfLineStartsAComment() {
        List<String> texts = splitTexts("SELECT 1 --\nSELECT 2;");
        assertThat(texts).hasSize(1);
        assertThat(texts.getFirst()).startsWith("SELECT 1").contains("SELECT 2");
    }

    @Test
    void blockCommentContentIsNotScannedForDelimiters() {
        assertThat(splitTexts("SELECT 1 /* a;b */; SELECT 2;"))
                .containsExactly("SELECT 1 /* a;b */", "SELECT 2");
        assertThat(splitTexts("/* leading */ /* more */ SELECT 1;")).containsExactly("SELECT 1");
        assertThat(splitTexts("SELECT 1 /* unterminated")).containsExactly("SELECT 1 /* unterminated");
    }

    @Test
    void commentOnlyScriptProducesNothing() {
        assertThat(splitTexts("-- just a note\n/* and more */\n# done\n")).isEmpty();
    }

    /** MySQL executes an executable version comment body as SQL, so a delimiter inside it ends the statement. */
    @Test
    void versionCommentBodyIsExecutableSqlAndCanEndTheStatement() {
        String script = "SET @x=1; /*!40000 SELECT 1; SELECT 2 */; SET @y=2;";
        assertThat(splitTexts(script)).containsExactly("SET @x=1", "/*!40000 SELECT 1", "SELECT 2 */", "SET @y=2");
        assertOffsetsExact(script, split(script));
    }

    @Test
    void versionCommentWithoutInnerDelimiterStaysAttachedToItsStatement() {
        String script = "/*!40101 SET @saved_cs_client = @@character_set_client */;";
        assertThat(splitTexts(script)).containsExactly("/*!40101 SET @saved_cs_client = @@character_set_client */");

        script = """
                CREATE TABLE t_vercomment (
                  id INT NOT NULL
                ) ENGINE=InnoDB /*!50100 TABLESPACE `innodb_system;` */;
                """;
        List<SqlStatementSplitter.Statement> statements = split(script);
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().text())
                .startsWith("CREATE TABLE t_vercomment")
                .endsWith("*/")
                .contains("TABLESPACE `innodb_system;`");
        assertOffsetsExact(script, statements);
    }

    @Test
    void multiRowInsertStaysOneStatement() {
        String script = """
                INSERT INTO t_users (id, username, email, is_active, balance) VALUES
                  (1, 'alice', 'alice@example.com', 1, 100.50),
                  (2, 'bob', NULL, 1, 0.00),
                  (3, 'carol', 'carol@example.com', 0, 42.42);
                SELECT 1;
                """;
        List<String> texts = splitTexts(script);
        assertThat(texts).hasSize(2);
        assertThat(texts.getFirst())
                .startsWith("INSERT INTO t_users")
                .endsWith("(3, 'carol', 'carol@example.com', 0, 42.42)");
    }

    @Test
    void createTableWithSemicolonInQuotedDefaults() {
        String script = """
                CREATE TABLE t_semicolon (
                  a VARCHAR(20) NOT NULL DEFAULT 'x;y',
                  b VARCHAR(20) NOT NULL DEFAULT "x;y",
                  `c;d` VARCHAR(20) NOT NULL DEFAULT 'p;q',
                  e VARCHAR(20) DEFAULT NULL COMMENT 'a; b; c'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='x;y';
                SELECT 2;
                """;
        List<SqlStatementSplitter.Statement> statements = split(script);
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst().text()).contains("DEFAULT 'x;y'").endsWith("COMMENT='x;y'");
        assertOffsetsExact(script, statements);
    }

    @Test
    void dockerFixtureSchemaFilesSplitCleanly() throws IOException {
        List<String> src = fixtureTexts("src");
        assertThat(src).hasSize(15);
        List<String> dst = fixtureTexts("dst");
        assertThat(dst).hasSize(12);

        for (List<String> texts : List.of(src, dst)) {
            assertThat(texts).noneMatch(t -> t.startsWith("--") || t.startsWith("#") || t.startsWith("/*"));
            assertThat(texts).noneMatch(t -> t.contains("DELIMITER"));
            assertThat(texts).noneMatch(t -> t.isBlank());
        }
        assertThat(src).filteredOn(t -> t.startsWith("CREATE FUNCTION")).hasSize(1);
        assertThat(src).filteredOn(t -> t.startsWith("CREATE PROCEDURE")).hasSize(1);
        assertThat(src).filteredOn(t -> t.startsWith("CREATE TRIGGER")).hasSize(1);
        assertThat(src).filteredOn(t -> t.contains("RETURN v_balance;")).hasSize(1);
    }

    /** mysqldump style: the restore-to-default directive can share a line with the closing delimiter. */
    @Test
    void directiveMayFollowTheDelimiterOnTheSameLine() {
        String script = "DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$ DELIMITER ;\nSELECT 1;";
        assertThat(splitTexts(script)).containsExactly("CREATE PROCEDURE p() BEGIN SELECT 1; END", "SELECT 1");
    }

    @Test
    void crlfLineWorks() {
        String script = "SELECT 1;\r\nDELIMITER $$\r\nCREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END$$\r\n"
                + "DELIMITER ;\r\nSELECT 2;\r\n";
        assertThat(splitTexts(script)).containsExactly(
                "SELECT 1", "CREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END", "SELECT 2");
        assertOffsetsExact(script, split(script));
    }

    /** Offsets are Java character indices, so they stay consistent with the editor's own positions. */
    @Test
    void multiByteCharactersDoNotShiftOffsets() {
        String script = "-- 注释; 中文\nSELECT '中文;值' AS 列, '😀;x' AS e; SELECT 2;";
        List<SqlStatementSplitter.Statement> statements = split(script);
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst().text()).isEqualTo("SELECT '中文;值' AS 列, '😀;x' AS e");
        assertOffsetsExact(script, statements);
    }

    /** Whitespace/comment runs must not turn the scan quadratic: nothing is rescanned per character. */
    @Test
    void hugeBlankAndCommentRunDoesNotDegradate() {
        StringBuilder sb = new StringBuilder(2 * 1024 * 1024);
        for (int k = 0; k < 100_000; k++) {
            sb.append("    -- filler line; not a statement\n");
        }
        sb.append("SELECT 1;");
        String script = sb.toString();
        long begin = System.nanoTime();
        assertThat(splitTexts(script)).containsExactly("SELECT 1");
        assertThat((System.nanoTime() - begin) / 1_000_000).isLessThan(3_000);
    }

    @Test
    void fiveMegabyteScriptSplitsFast() {
        StringBuilder sb = new StringBuilder(6 * 1024 * 1024);
        for (int k = 0; k < 100_000; k++) {
            sb.append("INSERT INTO t (a, b) VALUES (").append(k).append(", 'x;y;-- /* $$ */');\n");
        }
        String big = sb.toString();
        long begin = System.nanoTime();
        List<SqlStatementSplitter.Statement> statements = split(big);
        long millis = (System.nanoTime() - begin) / 1_000_000;
        assertThat(big).hasSizeGreaterThan(5 * 1024 * 1024);
        assertThat(statements).hasSize(100_000);
        assertThat(millis).isLessThan(3_000);
        assertOffsetsExact(big, statements);
    }

    private static List<String> fixtureTexts(String side) throws IOException {
        Path file = Path.of("docker/init", side, "01_schema.sql");
        assumeTrue(Files.isReadable(file), "docker fixture " + file + " not on disk");
        String script = Files.readString(file);
        List<SqlStatementSplitter.Statement> statements = split(script);
        assertOffsetsExact(script, statements);
        return statements.stream().map(SqlStatementSplitter.Statement::text).toList();
    }

    /** The offsets are the contract the editor highlights with: they must reproduce the text exactly. */
    private static void assertOffsetsExact(String script, List<SqlStatementSplitter.Statement> statements) {
        for (SqlStatementSplitter.Statement st : statements) {
            assertThat(st.start()).isBetween(0, script.length());
            assertThat(st.end()).isBetween(st.start(), script.length());
            assertThat(script.substring(st.start(), st.end())).isEqualTo(st.text());
        }
    }
}

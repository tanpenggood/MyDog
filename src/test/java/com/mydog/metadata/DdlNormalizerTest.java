package com.mydog.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The noise rules of {@link DdlNormalizer}, one test each, plus the drift the real MySQL 8.0.42
 * fixtures in this package are meant to produce.
 */
class DdlNormalizerTest {

    /** SHOW CREATE shape: two-space indent per item, which normalization trims away. */
    private static final String INPUT = "CREATE TABLE `t` (\n  `a` int NOT NULL\n";
    private static final String NORMALIZED = "CREATE TABLE `t` (\n`a` int NOT NULL\n";

    private static final String IDENTITY = """
            CREATE TABLE `t` (
              `id` bigint unsigned NOT NULL AUTO_INCREMENT,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='users'
            """;

    /** The collation map is process-wide static state, so every test states what it needs. */
    @BeforeEach
    void unconfigured() {
        DdlNormalizer.configureDefaultCollations(Map.of());
    }

    // ------------------------------------------------------------------ rule 1: auto-increment watermark

    @Test
    void autoIncrementCounterIsNoiseButTheColumnAttributeIsNot() {
        String normalized = DdlNormalizer.normalizeTableDdl(IDENTITY);

        assertThat(normalized).doesNotContain("AUTO_INCREMENT=6");
        // the attribute that makes the column an identity column stays: that is structure
        assertThat(normalized).contains("`id` bigint unsigned NOT NULL AUTO_INCREMENT");

        String otherWatermark = IDENTITY.replace("AUTO_INCREMENT=6", "AUTO_INCREMENT=40092");
        assertThat(DdlNormalizer.equivalent(IDENTITY, otherWatermark)).isTrue();
    }

    // ------------------------------------------------------------------ rule 2: DEFINER

    @Test
    void definerAccountIsDroppedAndSqlSecurityDefinerSurvives() {
        String view = "CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`localhost` SQL SECURITY DEFINER VIEW `v` AS select 1 AS `1`;";

        assertThat(DdlNormalizer.normalizeObjectDdl(view)).isEqualTo(
                "CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `v` AS select 1 AS `1`");

        String trigger = "CREATE DEFINER=CURRENT_USER TRIGGER `trg` BEFORE INSERT ON `t` FOR EACH ROW BEGIN END;";
        assertThat(DdlNormalizer.normalizeObjectDdl(trigger))
                .isEqualTo("CREATE TRIGGER `trg` BEFORE INSERT ON `t` FOR EACH ROW BEGIN END");
    }

    @Test
    void definerIsDroppedFromTableDdlToo() {
        // SHOW CREATE TABLE never carries one, but event/partition style text can
        assertThat(DdlNormalizer.normalizeTableDdl("CREATE TABLE `t` ( `a` int) DEFINER=`u`@`h` ;"))
                .isEqualTo("CREATE TABLE `t` ( `a` int)");
    }

    // ------------------------------------------------------------------ rule 3: line canonical form

    @Test
    void indentationBlankLinesAndLineEndingsAreNoiseButOrderIsNot() {
        String indented = "CREATE TABLE `t` (\r\n\r\n   `a` int NOT NULL,\n\t\t  `b` int NOT NULL\n\n) ENGINE=InnoDB";
        String flat = "CREATE TABLE `t` (\n`a` int NOT NULL,\n`b` int NOT NULL\n) ENGINE=InnoDB";

        assertThat(DdlNormalizer.equivalent(indented, flat)).isTrue();
        assertThat(DdlNormalizer.normalizeTableDdl(indented)).doesNotContain("\r").doesNotContain("\n\n");

        String swapped = "CREATE TABLE `t` (\n`b` int NOT NULL,\n`a` int NOT NULL\n) ENGINE=InnoDB";
        assertThat(DdlNormalizer.equivalent(flat, swapped)).isFalse();
    }

    // ------------------------------------------------------------------ rule 4: integer display widths

    @Test
    void integerDisplayWidthsAreDroppedCaseInsensitively() {
        assertThat(column("`n` int(11) NOT NULL")).contains("`n` int NOT NULL");
        assertThat(column("`n` bigint(20) unsigned NOT NULL")).contains("`n` bigint unsigned NOT NULL");
        assertThat(column("`n` smallint(6) NOT NULL")).contains("`n` smallint NOT NULL");
        assertThat(column("`n` mediumint(9) NOT NULL")).contains("`n` mediumint NOT NULL");
        assertThat(column("`n` INT(11) NOT NULL")).contains("`n` INT NOT NULL");
        assertThat(column("`n` tinyint(4) NOT NULL")).contains("`n` tinyint NOT NULL");
    }

    @Test
    void tinyintOneIsABooleanNotAWidthAndOtherParenthesesSurvive() {
        String bool = column("`flag` tinyint(1) NOT NULL DEFAULT '0'");
        assertThat(bool).contains("tinyint(1)");
        // tinyint(1) and tinyint really are different to clients
        assertThat(DdlNormalizer.equivalent(bool, column("`flag` tinyint NOT NULL DEFAULT '0'"))).isFalse();
        // precision/scale and bit width belong to the type, they are never display widths
        assertThat(column("`p` decimal(12,2) NOT NULL")).contains("decimal(12,2)");
        assertThat(column("`b` bit(1) NOT NULL")).contains("bit(1)");
    }

    // ------------------------------------------------------------------ rule 5: CURRENT_TIMESTAMP

    @Test
    void currentTimestampIsSpelledOneWayAndKeepsItsPrecisionAndUpdateClause() {
        String lowerCaseNoArgs = column("`c` datetime DEFAULT current_timestamp()");
        assertThat(lowerCaseNoArgs).isEqualTo(
                "CREATE TABLE `t` (\n`c` datetime DEFAULT CURRENT_TIMESTAMP\n) ENGINE=InnoDB");
        assertThat(lowerCaseNoArgs).doesNotContain("CURRENT_TIMESTAMP(");

        assertThat(column("`c` datetime DEFAULT CURRENT_TIMESTAMP() ON UPDATE CURRENT_TIMESTAMP()"))
                .contains("DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

        String withPrecision = column("`c` datetime(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)");
        assertThat(withPrecision).contains("DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)");

        // a default that merely spells the keyword stays exactly as written
        assertThat(column("`c` varchar(32) DEFAULT 'CURRENT_TIMESTAMP()'")).contains("DEFAULT 'CURRENT_TIMESTAMP()'");
    }

    // ------------------------------------------------------------------ rule 6: redundant collation

    @Test
    void collationIsDroppedOnlyWhenItIsTheConfiguredCharsetDefault() {
        String withDefault = INPUT + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci";
        String without = INPUT + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        // unconfigured: fail-safe, the clause is kept so a real difference cannot be hidden
        assertThat(DdlNormalizer.normalizeTableDdl(withDefault))
                .isEqualTo(NORMALIZED + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        assertThat(DdlNormalizer.equivalent(withDefault, without)).isFalse();

        DdlNormalizer.configureDefaultCollations(Map.of("utf8mb4", "utf8mb4_0900_ai_ci"));
        assertThat(DdlNormalizer.normalizeTableDdl(withDefault))
                .isEqualTo(NORMALIZED + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        assertThat(DdlNormalizer.equivalent(withDefault, without)).isTrue();

        // a collation that is not the default of its charset is a real difference
        String binary = withDefault.replace("utf8mb4_0900_ai_ci", "utf8mb4_bin");
        assertThat(DdlNormalizer.normalizeTableDdl(binary))
                .isEqualTo(NORMALIZED + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        assertThat(DdlNormalizer.equivalent(binary, without)).isFalse();

        // an unknown charset is not assumed to have any particular default either
        String other = withDefault.replace("utf8mb4_0900_ai_ci", "latin2_swedish_ci").replace("utf8mb4", "latin2");
        DdlNormalizer.configureDefaultCollations(Map.of("utf8mb4", "utf8mb4_general_ci"));
        assertThat(DdlNormalizer.normalizeTableDdl(other)).contains("COLLATE=latin2_swedish_ci");
    }

    @Test
    void columnLevelCharacterSetCollationPairIsHandledToo() {
        String ddl = "CREATE TABLE `t` (\n  `a` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL"
                + "\n) ENGINE=InnoDB";
        assertThat(DdlNormalizer.normalizeTableDdl(ddl)).isEqualTo(ddl.replace("  `a`", "`a`"));

        DdlNormalizer.configureDefaultCollations(Map.of("utf8mb4", "utf8mb4_0900_ai_ci"));
        assertThat(DdlNormalizer.normalizeTableDdl(ddl)).isEqualTo(
                "CREATE TABLE `t` (\n`a` varchar(32) CHARACTER SET utf8mb4 NOT NULL\n) ENGINE=InnoDB");
    }

    // ------------------------------------------------------------------ rules 7 and 8: row format, ; and engine

    @Test
    void defaultRowFormatIsDroppedButAnExplicitOneIsNot() {
        String dynamic = INPUT + ") ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4";
        assertThat(DdlNormalizer.normalizeTableDdl(dynamic))
                .isEqualTo(NORMALIZED + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        assertThat(DdlNormalizer.equivalent(dynamic, dynamic.replace("ROW_FORMAT=DYNAMIC ", ""))).isTrue();

        String compressed = dynamic.replace("DYNAMIC", "COMPRESSED");
        assertThat(DdlNormalizer.normalizeTableDdl(compressed)).contains("ROW_FORMAT=COMPRESSED");
        assertThat(DdlNormalizer.equivalent(compressed, dynamic)).isFalse();
    }

    @Test
    void trailingSemicolonIsDroppedAndEngineIsKept() {
        assertThat(DdlNormalizer.normalizeTableDdl(INPUT + ") ENGINE=InnoDB;"))
                .isEqualTo(NORMALIZED + ") ENGINE=InnoDB");
        assertThat(DdlNormalizer.normalizeTableDdl("CREATE TABLE `t` (`a` int) ENGINE=MyISAM;"))
                .isEqualTo("CREATE TABLE `t` (`a` int) ENGINE=MyISAM");
        // the storage engine is a real difference
        assertThat(DdlNormalizer.equivalent("CREATE TABLE `t` (`a` int) ENGINE=InnoDB",
                "CREATE TABLE `t` (`a` int) ENGINE=MyISAM;")).isFalse();
    }

    // ------------------------------------------------------------------ rule 9: backticks

    @Test
    void backticksArePreservedVerbatim() {
        assertThat(DdlNormalizer.normalizeTableDdl(IDENTITY)).contains("CREATE TABLE `t` (")
                .contains("PRIMARY KEY (`id`)");
        // stripping them would turn an identifier into a keyword
        assertThat(DdlNormalizer.normalizeTableDdl("CREATE TABLE `order` (\n  `desc` int NOT NULL\n) ENGINE=InnoDB"))
                .isEqualTo("CREATE TABLE `order` (\n`desc` int NOT NULL\n) ENGINE=InnoDB");
    }

    // ------------------------------------------------------------------ rule 10: literals are never mangled

    @Test
    void textInsideQuotesIsNeverTreatedAsCode() {
        String ddl = """
                CREATE TABLE `t` (
                  `a` varchar(16) NOT NULL DEFAULT 'a,b(c)' COMMENT 'has AUTO_INCREMENT=5 inside',
                  `b` int(11) NOT NULL DEFAULT 'ROW_FORMAT=DYNAMIC' COMMENT 'CURRENT_TIMESTAMP() and int(11) stay put',
                  `c` text COLLATE 'utf8mb4_bin'
                ) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COMMENT='tail; AUTO_INCREMENT=9'
                """;
        String normalized = DdlNormalizer.normalizeTableDdl(ddl);

        assertThat(normalized).contains("DEFAULT 'a,b(c)'");
        assertThat(normalized).contains("COMMENT 'has AUTO_INCREMENT=5 inside'");
        assertThat(normalized).contains("DEFAULT 'ROW_FORMAT=DYNAMIC'");
        assertThat(normalized).contains("COMMENT 'CURRENT_TIMESTAMP() and int(11) stay put'");
        assertThat(normalized).contains("COMMENT='tail; AUTO_INCREMENT=9'");
        assertThat(normalized).contains("COLLATE 'utf8mb4_bin'");
        // ... while the very same tokens outside quotes are normalized
        assertThat(normalized).contains("`b` int NOT NULL");
        assertThat(normalized).doesNotContain("AUTO_INCREMENT=5 DEFAULT");
    }

    // ------------------------------------------------------------------ object DDL

    @Test
    void versionCommentWrappersAreRemovedFromObjectDdl() {
        String dump = """
                /*!50001 CREATE ALGORITHM=UNDEFINED */
                /*!50017 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
                /*!50001 VIEW `v_user_stats` AS select 1 AS `id` */;""";

        assertThat(DdlNormalizer.normalizeObjectDdl(dump)).isEqualTo(
                "CREATE ALGORITHM=UNDEFINED\nSQL SECURITY DEFINER\nVIEW `v_user_stats` AS select 1 AS `id`");
    }

    @Test
    void objectBodyIsNotTreatedAsTableSyntax() {
        String proc = """
                CREATE DEFINER=`root`@`localhost` PROCEDURE `sp`()
                    BEGIN
                        ALTER TABLE `t` AUTO_INCREMENT=1;
                        SELECT `n` FROM `t` WHERE `n` = int(1);
                    END;""";

        assertThat(DdlNormalizer.normalizeObjectDdl(proc)).isEqualTo("""
                CREATE PROCEDURE `sp`()
                BEGIN
                ALTER TABLE `t` AUTO_INCREMENT=1;
                SELECT `n` FROM `t` WHERE `n` = int(1);
                END""");
    }

    // ------------------------------------------------------------------ the real fixtures

    @Test
    void fixtureUsersDifferOnlyByTheAutoIncrementWatermark() {
        assertThat(DdlNormalizer.equivalent(fixture("src_t_users.sql"), fixture("dst_t_users.sql"))).isTrue();
        assertThat(DdlNormalizer.normalizeTableDdl(fixture("src_t_users.sql")))
                .isEqualTo(DdlNormalizer.normalizeTableDdl(fixture("dst_t_users.sql")));
    }

    @Test
    void fixtureOrdersReallyDroppedAColumnAKeyAndAForeignKey() {
        assertThat(DdlNormalizer.equivalent(fixture("src_t_orders.sql"), fixture("dst_t_orders.sql"))).isFalse();

        DdlNormalizer.configureDefaultCollations(Map.of("utf8mb4", "utf8mb4_0900_ai_ci"));
        String normalized = DdlNormalizer.normalizeTableDdl(fixture("src_t_orders.sql"));
        assertThat(normalized).doesNotContain("AUTO_INCREMENT=").doesNotContain("COLLATE=");
        // what is left is what the sync engine has to act on
        assertThat(normalized).contains("enum('pending','paid','shipped','closed')")
                .contains("`remark` varchar(255) DEFAULT NULL").contains("UNIQUE KEY `uk_order_no`");
        assertThat(DdlNormalizer.normalizeTableDdl(fixture("src_t_users.sql"))).contains("tinyint(1)");
    }

    @Test
    void normalizationIsIdempotent() {
        for (String fixture : List.of("src_t_users.sql", "src_t_orders.sql", "src_t_logs.sql",
                "dst_t_users.sql", "dst_t_orders.sql")) {
            String once = DdlNormalizer.normalizeTableDdl(fixture(fixture));
            assertThat(DdlNormalizer.normalizeTableDdl(once)).isEqualTo(once);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A single-column table, so a rule's effect on one clause is visible on its own. */
    private static String column(String definition) {
        return DdlNormalizer.normalizeTableDdl("CREATE TABLE `t` (\n  " + definition + "\n) ENGINE=InnoDB");
    }

    private static String fixture(String name) {
        try {
            var url = Objects.requireNonNull(DdlNormalizerTest.class.getResource(name), "missing fixture " + name);
            return Files.readString(Path.of(url.toURI()));
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }
}

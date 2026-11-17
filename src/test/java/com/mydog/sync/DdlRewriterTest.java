package com.mydog.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-qualifier rewriting, which is what makes syncing {@code shop} into {@code shop_copy}
 * produce a view that reads {@code shop_copy} instead of quietly reading the source.
 */
class DdlRewriterTest {

    @Test
    void rewritesBackquotedQualifiers() {
        String ddl = "CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `v` AS "
                + "select `shop`.`u`.`id` AS `id` from `shop`.`users` `u`";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "shop_copy"))
                .isEqualTo("CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `v` AS "
                        + "select `shop_copy`.`u`.`id` AS `id` from `shop_copy`.`users` `u`");
    }

    @Test
    void rewritesUnqualifiedAndBareForms() {
        assertThat(DdlRewriter.retargetSchema("FROM shop.orders o", "shop", "shop_copy"))
                .isEqualTo("FROM `shop_copy`.orders o");
    }

    @Test
    void leavesAColumnNamedLikeTheSchemaAlone() {
        // `t`.`shop` is a column reference: the qualifier is the part followed by a dot
        String ddl = "select `t`.`shop` AS `shop` from `shop`.`orders` `t`";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "shop_copy"))
                .isEqualTo("select `t`.`shop` AS `shop` from `shop_copy`.`orders` `t`");
    }

    @Test
    void leavesStringLiteralsAndCommentsAlone() {
        String ddl = "SELECT 'from shop.orders' AS note /* see shop.orders */,\n"
                + "  -- shop.orders in a line comment\n  1 AS x FROM `shop`.`orders`";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "shop_copy"))
                .isEqualTo("SELECT 'from shop.orders' AS note /* see shop.orders */,\n"
                        + "  -- shop.orders in a line comment\n  1 AS x FROM `shop_copy`.`orders`");
    }

    @Test
    void rewritesInsideExecutableVersionComments() {
        // /*!50100 ... */ is real syntax the server executes, so a qualifier inside it matters
        String ddl = "CREATE TABLE `t` (`id` int) /*!50100 PARTITION BY HASH(`id`) */";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "shop_copy")).isEqualTo(ddl);

        String fk = "CONSTRAINT `fk` FOREIGN KEY (`user_id`) REFERENCES `shop`.`t_users` (`id`)";
        assertThat(DdlRewriter.retargetSchema(fk, "shop", "shop_copy"))
                .isEqualTo("CONSTRAINT `fk` FOREIGN KEY (`user_id`) REFERENCES `shop_copy`.`t_users` (`id`)");
    }

    @Test
    void doesNotTouchOtherSchemasOrIdenticalNames() {
        String ddl = "FROM `other`.`t` JOIN `shop`.`s`";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "other")).isEqualTo("FROM `other`.`t` JOIN `other`.`s`");
        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "shop")).isEqualTo(ddl);
        assertThat(DdlRewriter.retargetSchema(ddl, "shop", null)).isEqualTo(ddl);
    }

    @Test
    void doesNotMatchAWordThatMerelyStartsWithTheSchema() {
        String ddl = "SELECT shopping.id, `shopkeeper`.`x` FROM shop.orders";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "shop_copy"))
                .isEqualTo("SELECT shopping.id, `shopkeeper`.`x` FROM `shop_copy`.orders");
    }

    @Test
    void handlesDoubledBackticksInsideAnIdentifier() {
        String ddl = "SELECT `weird``name`.a FROM `shop`.`t`";

        assertThat(DdlRewriter.retargetSchema(ddl, "shop", "s2"))
                .isEqualTo("SELECT `weird``name`.a FROM `s2`.`t`");
    }

    @Test
    void passesThroughNullOrEmpty() {
        assertThat(DdlRewriter.retargetSchema(null, "shop", "s2")).isNull();
        assertThat(DdlRewriter.retargetSchema("", "shop", "s2")).isEmpty();
    }

    // ---------------------------------------------------------------- readsFrom

    @Test
    void findsTheTablesAViewReads() {
        String sql = "select `shop`.`u`.`id`, o.total from users u "
                + "join `orders` o on o.uid = u.id left join payouts p on p.id = u.id";

        assertThat(DdlRewriter.readsFrom(sql, java.util.List.of("users", "orders", "payouts"), "shop"))
                .containsExactly("users", "orders", "payouts");
    }

    @Test
    void ignoresNamesThatAreNotTablesAndQualifiersOfAnotherSchema() {
        String sql = "SELECT a FROM audit.log l JOIN shop.users u ON u.id = l.id";

        // `audit`.`log` is another schema's table — not this sync's business; `audit` itself is not a
        // table here, and `users` is qualified by the local schema so it counts
        assertThat(DdlRewriter.readsFrom(sql, java.util.List.of("users", "audit", "log"), "shop"))
                .containsExactly("users");
    }

    @Test
    void doesNotReadFromStringsCommentsOrDerivedTables() {
        String sql = "SELECT 'from orders' AS txt /* join payouts */ , `weird``name`.x "
                + "FROM (SELECT id FROM log) d";

        assertThat(DdlRewriter.readsFrom(sql, java.util.List.of("orders", "payouts", "log"), "shop"))
                .containsExactly("log");
    }

    @Test
    void returnsNothingForNullOrEmptyOrNoCandidates() {
        assertThat(DdlRewriter.readsFrom(null, java.util.List.of("t"), "shop")).isEmpty();
        assertThat(DdlRewriter.readsFrom("SELECT 1", java.util.List.of("t"), "shop")).isEmpty();
        assertThat(DdlRewriter.readsFrom("SELECT 1 FROM t", java.util.List.<String>of(), "shop")).isEmpty();
    }
}
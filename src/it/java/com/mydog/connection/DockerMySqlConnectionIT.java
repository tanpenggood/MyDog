package com.mydog.connection;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link MySqlConnectionFactory} against the docker MySQL sandbox
 * (docker/compose.yaml: source 3307, target 3308). Proves the driver settings that the
 * sync engine will silently depend on: MySQL 8 auth without TLS, forced UTC session
 * timezone, utf8mb4 round-trip. Aborts (does not fail) when the sandbox is not running.
 */
@Tag("docker")
class DockerMySqlConnectionIT {

    private static final String PASSWORD = "root123";

    private static ConnectionProfile profile(int port) {
        return new ConnectionProfile(null, "it-" + port, "127.0.0.1", port, "root",
                PASSWORD, "shop", SslMode.DISABLED, null);
    }

    @BeforeAll
    static void requireSandbox() {
        for (int port : new int[]{3307, 3308}) {
            try (Connection ignored = MySqlConnectionFactory.open(profile(port), PASSWORD)) {
                // reachable
            } catch (SQLException e) {
                Assumptions.abort("docker MySQL sandbox not reachable on 127.0.0.1:" + port
                        + " (" + e.getMessage() + ") — run: cd docker && docker compose up -d --wait");
            }
        }
    }

    @Test
    void connectsToBothSandboxServersWithMysql8Auth() throws SQLException {
        for (int port : new int[]{3307, 3308}) {
            try (Connection c = MySqlConnectionFactory.open(profile(port), PASSWORD)) {
                assertThat(c.getMetaData().getDatabaseProductVersion()).startsWith("8.");
                assertThat(c.isValid(2)).isTrue();
            }
        }
    }

    @Test
    void forcesUtcSessionTimeZoneOnBothSides() throws SQLException {
        // Same effective timezone on both servers is what prevents fake TIMESTAMP diffs later.
        for (int port : new int[]{3307, 3308}) {
            try (Connection c = MySqlConnectionFactory.open(profile(port), PASSWORD)) {
                assertThat(sessionVar(c, "time_zone")).isEqualTo("+00:00");
            }
        }
    }

    @Test
    void usesUtf8mb4AndRoundTripsChineseText() throws SQLException {
        try (Connection c = MySqlConnectionFactory.open(profile(3307), PASSWORD);
             Statement st = c.createStatement()) {
            assertThat(sessionVar(c, "character_set_client")).isEqualTo("utf8mb4");
            try (ResultSet rs = st.executeQuery("SELECT '订单同步_测试 😀' AS payload")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("payload")).isEqualTo("订单同步_测试 😀");
            }
        }
    }

    @Test
    void defaultSchemaBindingSelectsShop() throws SQLException {
        try (Connection c = MySqlConnectionFactory.open(profile(3307), PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("shop");
        }
    }

    @Test
    void sandboxFixturesAreDifferentiatedAsDesigned() throws SQLException {
        // source: 5 users, has t_logs, orders carries the remark column
        try (Connection src = MySqlConnectionFactory.open(profile(3307), PASSWORD)) {
            assertThat(queryLong(src, "SELECT COUNT(*) FROM shop.t_users")).isEqualTo(5);
            assertThat(tableExists(src, "shop", "t_logs")).isTrue();
            assertThat(columnExists(src, "shop", "t_orders", "remark")).isTrue();
            assertThat(routineExists(src, "shop", "fn_get_user_balance")).isTrue();
            assertThat(triggerExists(src, "shop", "trg_users_audit")).isTrue();
        }
        // target: drifted data (4 rows), missing remark, extra tables incl. a no-PK one
        try (Connection dst = MySqlConnectionFactory.open(profile(3308), PASSWORD)) {
            assertThat(queryLong(dst, "SELECT COUNT(*) FROM shop.t_users")).isEqualTo(4);
            assertThat(columnExists(dst, "shop", "t_orders", "remark")).isFalse();
            assertThat(tableExists(dst, "shop", "t_scratch")).isTrue();
            assertThat(tableExists(dst, "shop", "t_nopk")).isTrue();
            assertThat(queryLong(dst, "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE table_schema='shop' AND table_name='t_nopk' "
                    + "AND constraint_type='PRIMARY KEY'")).isZero();
        }
    }

    private static String sessionVar(Connection c, String name) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE '" + name + "'")) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static boolean tableExists(Connection c, String schema, String table) throws SQLException {
        return queryLong(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                + schema + "' AND table_name='" + table + "'") == 1;
    }

    private static boolean columnExists(Connection c, String schema, String table, String column)
            throws SQLException {
        return queryLong(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                + schema + "' AND table_name='" + table + "' AND column_name='" + column + "'") == 1;
    }

    private static boolean routineExists(Connection c, String schema, String name) throws SQLException {
        return queryLong(c, "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='"
                + schema + "' AND routine_name='" + name + "'") == 1;
    }

    private static boolean triggerExists(Connection c, String schema, String name) throws SQLException {
        return queryLong(c, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='"
                + schema + "' AND trigger_name='" + name + "'") == 1;
    }
}

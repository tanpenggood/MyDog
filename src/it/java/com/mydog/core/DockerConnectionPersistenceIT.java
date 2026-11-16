package com.mydog.core;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.SslMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The full user path against the real sandbox: type a password -> encrypt -> persist ->
 * "restart" (fresh AppContext) -> open a connection with the decrypted password.
 * The M1 dialog bug (test connection sending an empty password) lives exactly on this seam.
 */
@Tag("docker")
class DockerConnectionPersistenceIT {

    @TempDir
    static Path configDir;

    @BeforeAll
    static void requireSandbox() {
        try (Connection ignored = MySqlConnectionFactory.open(profile(), "root123")) {
            // reachable
        } catch (SQLException e) {
            Assumptions.abort("docker MySQL sandbox not reachable on 127.0.0.1:3307 — "
                    + "run: cd docker && docker compose up -d --wait");
        }
    }

    @Test
    void savedProfileConnectsAfterRestart() throws SQLException {
        AppContext first = AppContext.createIn(configDir);
        first.connections().save(profile().withEncryptedPassword(first.cipher().encrypt("root123")));

        AppContext restarted = AppContext.createIn(configDir);
        ConnectionProfile loaded = restarted.connections().all().getFirst();

        try (Connection c = restarted.openConnection(loaded);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).startsWith("8.");
        }

        // a wrong stored password must fail loudly, not connect anonymously
        ConnectionProfile wrong = loaded.withEncryptedPassword(restarted.cipher().encrypt("nope"));
        assertThatThrownBy(() -> restarted.openConnection(wrong).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Access denied");
    }

    private static ConnectionProfile profile() {
        return new ConnectionProfile(null, "it-persist", "127.0.0.1", 3307, "root",
                "", "shop", SslMode.DISABLED, null);
    }
}

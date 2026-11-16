package com.mydog.core;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.ConnectionStore;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.PasswordCipher;
import com.mydog.i18n.I18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * Tiny service locator for M1: owns config dir, settings, connection store, cipher.
 * Grows per milestone (metadata reader, sync engine, task runner) — DI framework stays out.
 */
public final class AppContext {

    private final Path configDir;
    private final SettingsService settings;
    private final ConnectionStore connections;
    private final PasswordCipher cipher;

    private Consumer<ConnectionProfile> onSessionChanged; // UI hook, called on FX thread

    private AppContext(Path configDir) {
        this.configDir = configDir;
        this.settings = new SettingsService(configDir.resolve("settings.json"));
        this.connections = new ConnectionStore(configDir.resolve("connections.json"));
        try {
            this.cipher = new PasswordCipher(configDir.resolve(".secret.key"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot init secret key", e);
        }
        I18n.setLocale(settings.get("locale", I18n.localeTag()));
    }

    public static AppContext create() {
        return createIn(Path.of(System.getProperty("user.home"), ".mydog"));
    }

    /** Create over an explicit config dir — used by tests and portable installs. */
    public static AppContext createIn(Path configDir) {
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + configDir, e);
        }
        return new AppContext(configDir);
    }

    public Path configDir() { return configDir; }
    public SettingsService settings() { return settings; }
    public ConnectionStore connections() { return connections; }
    public PasswordCipher cipher() { return cipher; }

    public void setLocale(String tag) {
        settings.put("locale", tag);
        I18n.setLocale(tag);
    }

    // ---- active session (M1: single active connection; multi-session in M2) ----

    private ConnectionProfile activeProfile;

    public synchronized ConnectionProfile activeProfile() { return activeProfile; }

    public synchronized void setActiveProfile(ConnectionProfile p) {
        this.activeProfile = p;
        Consumer<ConnectionProfile> hook = onSessionChanged;
        if (hook != null) hook.accept(p);
    }

    public void setOnSessionChanged(Consumer<ConnectionProfile> hook) {
        this.onSessionChanged = hook;
    }

    /** Open a JDBC connection to a profile (for testers and engine use). */
    public Connection openConnection(ConnectionProfile p) throws SQLException {
        String pw = cipher().decrypt(p.encryptedPassword());
        return MySqlConnectionFactory.open(p, pw);
    }
}

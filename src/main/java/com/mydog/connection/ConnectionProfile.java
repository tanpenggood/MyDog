package com.mydog.connection;

import java.util.Map;
import java.util.UUID;

/**
 * Saved connection configuration. Password is stored encrypted (see PasswordCipher);
 * {@code encryptedPassword} is {@code null} when no password was saved.
 */
public record ConnectionProfile(
        String id,
        String name,
        String host,
        int port,
        String user,
        String encryptedPassword,
        String defaultSchema,
        SslMode sslMode,
        Map<String, String> advancedProps) {

    public ConnectionProfile {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null) name = "";
        if (host == null) host = "localhost";
        if (user == null) user = "root";
        if (port <= 0) port = 3306;
        if (defaultSchema == null) defaultSchema = "";
        if (sslMode == null) sslMode = SslMode.PREFERRED;
        if (advancedProps == null) advancedProps = Map.of();
    }

    /** New profile with generated id, for the "new connection" dialog. */
    public static ConnectionProfile empty() {
        return new ConnectionProfile(null, "", "localhost", 3306, "root", "", "", SslMode.PREFERRED, null);
    }

    public ConnectionProfile withEncryptedPassword(String enc) {
        return new ConnectionProfile(id, name, host, port, user, enc, defaultSchema, sslMode, advancedProps);
    }
}

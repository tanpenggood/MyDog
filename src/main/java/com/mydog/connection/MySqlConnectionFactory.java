package com.mydog.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Builds JDBC connections from profiles. Centralizes the driver properties that matter for
 * correctness (charset, timezone, MySQL 8 auth without TLS).
 */
public final class MySqlConnectionFactory {

    private MySqlConnectionFactory() {
    }

    /** Properties applied to every connection MyDog opens. */
    public static Properties baseProps(ConnectionProfile p, String plainPassword) {
        Properties props = new Properties();
        props.setProperty("user", p.user());
        if (plainPassword != null && !plainPassword.isEmpty()) {
            props.setProperty("password", plainPassword);
        }
        // charset correctness
        props.setProperty("characterEncoding", "UTF-8");
        // avoid fake TIMESTAMP diffs when diffing two servers
        props.setProperty("connectionTimeZone", "UTC");
        props.setProperty("forceConnectionTimeZoneToSession", "true");
        // MySQL 8 caching_sha2_password without TLS
        props.setProperty("allowPublicKeyRetrieval", "true");
        props.setProperty("sslMode", p.sslMode().jdbcValue());
        // sync engine batch writes
        props.setProperty("rewriteBatchedStatements", "true");
        props.setProperty("allowLoadLocalInfile", "true");
        if (p.defaultSchema() != null && !p.defaultSchema().isBlank()) {
            props.setProperty("databaseTerm", "SCHEMA");
            props.setProperty("schemaName", p.defaultSchema());
        }
        p.advancedProps().forEach(props::setProperty);
        return props;
    }

    /** Open a connection. Schema defaults to the profile's default schema when set. */
    public static Connection open(ConnectionProfile p, String plainPassword) throws SQLException {
        String schema = (p.defaultSchema() == null || p.defaultSchema().isBlank()) ? "" : "/" + p.defaultSchema();
        String url = "jdbc:mysql://" + p.host() + ":" + p.port() + schema + "?useInformationSchema=true";
        return DriverManager.getConnection(url, baseProps(p, plainPassword));
    }

    /** Open a connection explicitly bound to a schema (empty string = server default). */
    public static Connection openToSchema(ConnectionProfile p, String plainPassword, String schema)
            throws SQLException {
        String suffix = (schema == null || schema.isBlank()) ? "" : "/" + schema;
        String url = "jdbc:mysql://" + p.host() + ":" + p.port() + suffix + "?useInformationSchema=true";
        Properties props = baseProps(p, plainPassword);
        if (suffix.isEmpty()) {
            props.remove("databaseTerm");
            props.remove("schemaName");
        }
        return DriverManager.getConnection(url, props);
    }

    /** Map of props for display/testing overrides. */
    public static Map<String, String> describe(ConnectionProfile p) {
        Map<String, String> m = new LinkedHashMap<>();
        baseProps(p, "").forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
        return m;
    }
}

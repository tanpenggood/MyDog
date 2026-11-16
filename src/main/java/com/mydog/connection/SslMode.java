package com.mydog.connection;

import com.mysql.cj.conf.PropertyKey;

/** TLS modes mapped to Connector/J sslMode connection property. */
public enum SslMode {
    DISABLED("DISABLED"),
    PREFERRED("PREFERRED"),
    REQUIRED("REQUIRED"),
    VERIFY_CA("VERIFY_CA"),
    VERIFY_IDENTITY("VERIFY_IDENTITY");

    private final String jdbcValue;

    SslMode(String jdbcValue) {
        this.jdbcValue = jdbcValue;
    }

    public String jdbcValue() {
        return jdbcValue;
    }

    public String propertyKey() {
        return PropertyKey.sslMode.getKeyName();
    }
}

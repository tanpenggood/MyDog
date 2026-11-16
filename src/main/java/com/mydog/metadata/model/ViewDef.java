package com.mydog.metadata.model;

/** A view; {@code createStatement} is SHOW CREATE VIEW output. */
public record ViewDef(String schema, String name, String createStatement) {
}

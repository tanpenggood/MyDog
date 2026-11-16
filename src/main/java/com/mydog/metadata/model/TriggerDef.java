package com.mydog.metadata.model;

/** A trigger bound to a table. */
public record TriggerDef(
        String schema,
        String name,
        String table,
        String timing,   // BEFORE / AFTER
        String event,    // INSERT / UPDATE / DELETE
        String body,     // ACTION_STATEMENT
        String createStatement) {
}

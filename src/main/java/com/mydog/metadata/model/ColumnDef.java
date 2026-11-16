package com.mydog.metadata.model;

/** One column of a table, as reported by information_schema. */
public record ColumnDef(
        String name,
        String type,           // COLUMN_TYPE, e.g. "bigint unsigned", "varchar(64)"
        boolean nullable,
        String defaultValue,   // raw expression text, null when absent
        String extra,          // AUTO_INCREMENT, GENERATED, ON UPDATE ...
        String comment,
        int ordinal) {
}

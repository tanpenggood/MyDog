package com.mydog.metadata.model;

import java.util.List;

/** A foreign key constraint with its referential actions. */
public record ForeignKeyDef(
        String name,
        List<String> columns,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns,
        String onDelete,
        String onUpdate) {
}

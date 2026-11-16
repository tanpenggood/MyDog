package com.mydog.metadata.model;

import java.util.List;

/**
 * A table. {@code createStatement} is the authoritative DDL text from SHOW CREATE TABLE;
 * the structured lists come from information_schema and are used for diffing and ordering.
 */
public record TableDef(
        String schema,
        String name,
        String createStatement,
        List<ColumnDef> columns,
        List<IndexDef> indexes,
        List<ForeignKeyDef> foreignKeys,
        String charset,
        String collation,
        long approxRows) {

    /** The single-column primary key, or the first multi-column PK. Empty when there is none. */
    public List<String> primaryKeyColumns() {
        return indexes.stream().filter(IndexDef::primary).findFirst()
                .map(IndexDef::columns).orElse(List.of());
    }

    /**
     * A usable row key: PK when present, else the first NOT NULL unique index.
     * Empty means the table has no key we can page or diff by.
     */
    public List<String> rowKeyColumns() {
        List<String> pk = primaryKeyColumns();
        if (!pk.isEmpty()) return pk;
        return indexes.stream()
                .filter(IndexDef::unique)
                .filter(idx -> idx.columns().stream().allMatch(col ->
                        columns().stream().anyMatch(c -> c.name().equals(col) && !c.nullable())))
                .map(IndexDef::columns)
                .findFirst().orElse(List.of());
    }
}

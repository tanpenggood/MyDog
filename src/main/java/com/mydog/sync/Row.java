package com.mydog.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * One table row: parallel column names and JDBC values, plus the key columns that identify it.
 * The key travels with the row so a writer can build a row-exact WHERE clause without
 * consulting the reader again.
 */
public record Row(List<String> columns, List<Object> values, List<String> keyColumns) {

    public Row(List<String> columns, List<Object> values) {
        this(columns, values, List.of());
    }

    public Object value(String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(column)) return values.get(i);
        }
        return null;
    }

    public List<Object> keyValues() {
        return keyValues(keyColumns);
    }

    /** Values of the given key columns, in order — the cursor for keyset paging. */
    public List<Object> keyValues(List<String> keys) {
        List<Object> out = new ArrayList<>(keys.size());
        for (String key : keys) out.add(value(key));
        return out;
    }

    public int size() {
        return values.size();
    }
}

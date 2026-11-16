package com.mydog.sync;

import com.mydog.metadata.model.TableDef;
import com.mydog.util.SqlNames;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rows of one table, paged with a keyset cursor instead of OFFSET.
 *
 * <p>MySQL compares row constructors, so a composite key pages with
 * {@code WHERE (a,b) > (?,?,?)} — which stays index-driven no matter how deep the page is,
 * unlike {@code LIMIT n OFFSET m}.
 */
public final class JdbcRowReader implements RowReader {

    private final Connection conn;
    private final TableDef table;
    private final List<String> columns;
    private final List<String> keyColumns;
    private final int chunkSize;

    public JdbcRowReader(Connection conn, TableDef table, int chunkSize) {
        this.conn = conn;
        this.table = table;
        this.columns = table.columns().stream().map(c -> c.name()).toList();
        this.keyColumns = table.rowKeyColumns();
        this.chunkSize = chunkSize;
        if (keyColumns.isEmpty()) {
            throw new IllegalStateException("table " + table.name() + " has no usable row key");
        }
    }

    @Override
    public List<String> columns() {
        return columns;
    }

    @Override
    public List<String> keyColumns() {
        return keyColumns;
    }

    @Override
    public List<Row> nextChunk(List<Object> lastKey) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ").append(quotedCsv(columns));
        sql.append(" FROM ").append(SqlNames.qualified(table.schema(), table.name()));
        if (!lastKey.isEmpty()) {
            sql.append(" WHERE (").append(quotedCsv(keyColumns)).append(") > (")
                    .append(String.join(", ", java.util.Collections.nCopies(keyColumns.size(), "?")))
                    .append(")");
        }
        sql.append(" ORDER BY ").append(quotedCsv(keyColumns));
        sql.append(" LIMIT ").append(chunkSize);

        List<Row> rows = new ArrayList<>(chunkSize);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < lastKey.size(); i++) {
                ps.setObject(i + 1, lastKey.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<Object> values = new ArrayList<>(columns.size());
                    for (int i = 1; i <= columns.size(); i++) {
                        values.add(rs.getObject(i));
                    }
                    rows.add(new Row(columns, values, keyColumns));
                }
            }
        }
        return rows;
    }

    private static String quotedCsv(List<String> identifiers) {
        return SqlNames.columnList(identifiers);
    }

    /** Cheap equality probe used before paying for a full diff of a big table. */
    public static long count(Connection conn, TableDef table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + SqlNames.qualified(table.schema(), table.name()));
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /**
     * Server-side checksum via CHECKSUM TABLE. Equal checksums on both sides let the engine
     * skip a full diff; unequal means it must run. Null when the statement cannot run
     * (permissions), which callers treat as "must diff".
     */
    public static String checksum(Connection conn, TableDef table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "CHECKSUM TABLE " + SqlNames.qualified(table.schema(), table.name()));
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(2) : null;
        }
    }
}

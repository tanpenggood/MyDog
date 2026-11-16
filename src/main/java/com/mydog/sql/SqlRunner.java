package com.mydog.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Executes statements and materializes small result sets for the UI.
 *
 * <p>Limited on purpose: the editor shows at most {@link #MAX_ROWS} rows and never streams,
 * because everything here must stay usable from the FX thread via a background Task.
 * The sync engine uses its own streaming chunker instead.
 */
public final class SqlRunner {

    /** Hard cap on rows materialized for display. */
    public static final int MAX_ROWS = 5000;

    private SqlRunner() {
    }

    /** Run one statement. Never throws for SQL errors — they come back in the result. */
    public static QueryResult runOne(Connection conn, String sql, int maxRows) {
        long start = System.nanoTime();
        try (Statement st = conn.createStatement()) {
            st.setMaxRows(maxRows);
            boolean hasResultSet = st.execute(sql);
            long ms = millis(start);
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    return QueryResult.data(sql, columnLabels(rs.getMetaData()), drain(rs, maxRows), ms);
                }
            }
            return QueryResult.update(sql, Math.max(st.getUpdateCount(), 0), ms);
        } catch (SQLException e) {
            return QueryResult.failure(sql, rootMessage(e), millis(start));
        }
    }

    /**
     * Run a whole script statement by statement. Each result is handed to {@code onResult}
     * as soon as it is available so the UI can show progress. Execution stops at the first
     * error — a half-applied script must never silently continue.
     *
     * @return the results, in order
     */
    public static List<QueryResult> runScript(Connection conn, List<String> statements,
                                              int maxRows, Consumer<QueryResult> onResult) {
        List<QueryResult> results = new ArrayList<>();
        for (String sql : statements) {
            if (sql.isBlank()) continue;
            QueryResult r = runOne(conn, sql, maxRows);
            results.add(r);
            if (onResult != null) onResult.accept(r);
            if (r.error() != null) break;
        }
        return results;
    }

    private static List<String> columnLabels(ResultSetMetaData md) throws SQLException {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String label = md.getColumnLabel(i);
            out.add(label == null || label.isEmpty() ? md.getColumnName(i) : label);
        }
        return out;
    }

    private static List<List<Object>> drain(ResultSet rs, int maxRows) throws SQLException {
        int columns = rs.getMetaData().getColumnCount();
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < maxRows) {
            List<Object> row = new ArrayList<>(columns);
            for (int i = 1; i <= columns; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String rootMessage(SQLException e) {
        StringBuilder sb = new StringBuilder();
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            if (cur.getMessage() == null) continue;
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(cur.getMessage());
            if (cur.getSQLState() != null) sb.append(" (SQLState ").append(cur.getSQLState()).append(')');
            break; // chained nextException is usually a duplicate of the first
        }
        return sb.isEmpty() ? e.getClass().getSimpleName() : sb.toString();
    }
}

package com.mydog.sql;

import java.util.List;

/** Outcome of one executed statement: either a result set or an update count. */
public record QueryResult(
        String sql,
        List<String> columns,
        List<List<Object>> rows,
        long affectedRows,
        long millis,
        boolean isResultSet,
        String error) {

    public static QueryResult data(String sql, List<String> columns, List<List<Object>> rows, long millis) {
        return new QueryResult(sql, columns, rows, rows.size(), millis, true, null);
    }

    public static QueryResult update(String sql, long affected, long millis) {
        return new QueryResult(sql, List.of(), List.of(), affected, millis, false, null);
    }

    public static QueryResult failure(String sql, String message, long millis) {
        return new QueryResult(sql, List.of(), List.of(), 0, millis, false, message);
    }

    public int rowCount() {
        return rows.size();
    }

    /** One-line description for the message panel. */
    public String summary() {
        if (error != null) return error;
        return isResultSet ? rowCount() + " rows" : affectedRows + " affected";
    }
}

package com.mydog.util;

import com.mydog.metadata.model.ColumnDef;
import com.mydog.metadata.model.TableDef;

import java.util.List;
import java.util.stream.Collectors;

/** Generates the ad-hoc SQL the object tree offers on right click. */
public final class SqlNames {

    private SqlNames() {
    }

    /** Quote one identifier. Backticks inside are doubled, which is MySQL's escape. */
    public static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public static String qualified(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /** Backquoted, comma-joined identifier list — the shape every generated statement needs. */
    public static String columnList(List<String> identifiers) {
        return identifiers.stream().map(SqlNames::quote).collect(Collectors.joining(", "));
    }

    public static String selectAll(TableDef t, int limit) {
        return "SELECT * FROM " + qualified(t.schema(), t.name()) + " LIMIT " + limit;
    }

    public static String selectColumns(TableDef t, int limit) {
        String cols = t.columns().stream()
                .map(c -> "`" + c.name().replace("`", "``") + "`")
                .collect(Collectors.joining(", "));
        return "SELECT " + cols + " FROM " + qualified(t.schema(), t.name()) + " LIMIT " + limit;
    }

    /** INSERT skeleton with one row of column placeholders. */
    public static String insertTemplate(TableDef t) {
        List<String> cols = t.columns().stream().map(ColumnDef::name).toList();
        String colList = cols.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
        String values = cols.stream().map(c -> "-- " + c).collect(Collectors.joining(", "));
        return "INSERT INTO " + qualified(t.schema(), t.name()) + " (" + colList + ")\nVALUES (" + values + ");";
    }

    /** UPDATE ... WHERE on the row key, so the statement stays row-exact. */
    public static String updateTemplate(TableDef t) {
        List<String> key = t.rowKeyColumns();
        String sets = t.columns().stream()
                .map(ColumnDef::name)
                .filter(c -> key.stream().noneMatch(k -> k.equals(c)))
                .map(c -> "`" + c + "` = ?")
                .collect(Collectors.joining(", "));
        String where = key.isEmpty()
                ? "-- no key columns: add an explicit WHERE before running this"
                : key.stream().map(k -> "`" + k + "` = ?").collect(Collectors.joining(" AND "));
        return "UPDATE " + qualified(t.schema(), t.name()) + " SET " + sets + "\nWHERE " + where + ";";
    }

    /** Escape a string for LIKE matching (backslash escapes, % and _ wildcarded away). */
    public static String likeEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("'", "''");
    }
}

package com.mydog.metadata;

import com.mydog.metadata.model.ColumnDef;
import com.mydog.metadata.model.DbSchema;
import com.mydog.metadata.model.ForeignKeyDef;
import com.mydog.metadata.model.IndexDef;
import com.mydog.metadata.model.RoutineDef;
import com.mydog.metadata.model.TableDef;
import com.mydog.metadata.model.TriggerDef;
import com.mydog.metadata.model.ViewDef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads MySQL structure through information_schema + SHOW CREATE.
 *
 * <p>SHOW CREATE output is the authoritative DDL text (it round-trips exactly what the server
 * would execute); information_schema supplies the structured view used for diffing and for
 * ordering decisions. Every query is parameterized on schema so one connection can read
 * several databases.
 */
public final class MySqlMetadataReader {

    /** Schemas that are server internals, never user data. */
    private static final List<String> SYSTEM_SCHEMAS =
            List.of("information_schema", "performance_schema", "mysql", "sys");

    private final Connection conn;

    public MySqlMetadataReader(Connection conn) {
        this.conn = conn;
    }

    public String serverVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    /** Backtick-quote an identifier for use in generated SQL. */
    public static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public static String qualify(String schema, String name) {
        return quote(schema) + "." + quote(name);
    }

    // ------------------------------------------------------------------ schemas

    public List<DbSchema> listSchemas() throws SQLException {
        String sql = """
                SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
                FROM information_schema.SCHEMATA
                WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys')
                ORDER BY SCHEMA_NAME""";
        List<DbSchema> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new DbSchema(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return out;
    }

    public boolean isSystemSchema(String schema) {
        return SYSTEM_SCHEMAS.contains(schema.toLowerCase());
    }

    /**
     * charset -> its default collation, as this server defines it.
     *
     * <p>Feeds {@link DdlNormalizer#configureDefaultCollations} so a {@code COLLATE} clause that
     * merely repeats the charset's default is not reported as a difference. It is read from the
     * server rather than assumed, because a deployment can change the default with
     * {@code --collation-server} and then the "redundant" clause is a real difference.
     * {@code information_schema.CHARACTER_SETS} is used instead of
     * {@code COLLATION_CHARACTER_SET_APPLICABILITY} because it carries the default in both 5.7 and 8.0.
     */
    public java.util.Map<String, String> defaultCollations() throws SQLException {
        String sql = "SELECT CHARACTER_SET_NAME, DEFAULT_COLLATE_NAME FROM information_schema.CHARACTER_SETS";
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tables

    public List<String> listTableNames(String schema) throws SQLException {
        String sql = """
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME""";
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Full table definition: DDL + columns + indexes + FKs + row estimate. */
    public TableDef readTable(String schema, String table) throws SQLException {
        String ddl = showCreateTable(schema, table);
        long approxRows = -1;
        String charset = "";
        String collation = "";
        // COLLATION_CHARACTER_SET_APPLICABILITY also works, but CHARACTER_SETS carries the same
        // mapping in both 5.7 and 8.0. (COLLATION_COLLATE, which an earlier draft joined, is a
        // MariaDB table and fails on MySQL — silently, until this catch was removed.)
        String sql = """
                SELECT t.TABLE_ROWS, t.TABLE_COLLATION, CCS.CHARACTER_SET_NAME
                FROM information_schema.TABLES t
                LEFT JOIN information_schema.CHARACTER_SETS CCS ON CCS.DEFAULT_COLLATE_NAME = t.TABLE_COLLATION
                WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    approxRows = rs.getLong(1);
                    collation = rs.getString(2) == null ? "" : rs.getString(2);
                    charset = rs.getString(3) == null ? collation2Charset(collation) : rs.getString(3);
                }
            }
        }
        return new TableDef(schema, table, ddl, columns(schema, table), indexes(schema, table),
                foreignKeys(schema, table), charset, collation, approxRows);
    }

    /** utf8mb4_0900_ai_ci -> utf8mb4, the usual fallback when the join finds nothing. */
    private static String collation2Charset(String collation) {
        int idx = collation.indexOf('_');
        return idx > 0 ? collation.substring(0, idx) : collation;
    }

    public String showCreateTable(String schema, String table) throws SQLException {
        return showCreate("SHOW CREATE TABLE " + qualify(schema, table));
    }

    public List<ColumnDef> columns(String schema, String table) throws SQLException {
        String sql = """
                SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA,
                       COLUMN_COMMENT, ORDINAL_POSITION
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION""";
        List<ColumnDef> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ColumnDef(
                            rs.getString(1),
                            rs.getString(2),
                            "YES".equalsIgnoreCase(rs.getString(3)),
                            rs.getString(4),
                            rs.getString(5) == null ? "" : rs.getString(5),
                            rs.getString(6) == null ? "" : rs.getString(6),
                            rs.getInt(7)));
                }
            }
        }
        return out;
    }

    /** Indexes grouped by name, columns in key order. PRIMARY is flagged, not returned as a name. */
    public List<IndexDef> indexes(String schema, String table) throws SQLException {
        String sql = """
                SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY INDEX_NAME, SEQ_IN_INDEX""";
        Map<String, IndexRow> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    boolean nonUnique = rs.getBoolean(2);
                    String type = rs.getString(5);
                    IndexRow row = grouped.computeIfAbsent(name, k -> new IndexRow(name, nonUnique, type));
                    String col = rs.getString(4);
                    if (col != null) row.columns.add(col);
                }
            }
        }
        List<IndexDef> out = new ArrayList<>();
        for (IndexRow r : grouped.values()) {
            boolean primary = "PRIMARY".equals(r.name);
            String name = primary ? "PRIMARY" : r.name;
            out.add(new IndexDef(name, primary, !r.nonUnique, List.copyOf(r.columns), r.type));
        }
        return out;
    }

    public List<ForeignKeyDef> foreignKeys(String schema, String table) throws SQLException {
        String sql = """
                SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_SCHEMA,
                       k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME,
                       r.DELETE_RULE, r.UPDATE_RULE
                FROM information_schema.KEY_COLUMN_USAGE k
                JOIN information_schema.REFERENTIAL_CONSTRAINTS r
                  ON r.CONSTRAINT_SCHEMA = k.TABLE_SCHEMA
                 AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                WHERE k.TABLE_SCHEMA = ? AND k.TABLE_NAME = ?
                  AND k.REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY k.CONSTRAINT_NAME, k.ORDINAL_POSITION""";
        Map<String, Parts> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Parts p = grouped.computeIfAbsent(name, n -> new Parts(n));
                    p.columns.add(rs.getString(2));
                    p.refSchema = rs.getString(3);
                    p.refTable = rs.getString(4);
                    p.refColumns.add(rs.getString(5));
                    p.onDelete = rs.getString(6);
                    p.onUpdate = rs.getString(7);
                }
            }
        }
        List<ForeignKeyDef> out = new ArrayList<>();
        for (Parts p : grouped.values()) {
            out.add(new ForeignKeyDef(p.name, List.copyOf(p.columns), p.refSchema, p.refTable,
                    List.copyOf(p.refColumns), p.onDelete, p.onUpdate));
        }
        return out;
    }

    // ------------------------------------------------------------------ views / routines / triggers

    public List<ViewDef> listViews(String schema) throws SQLException {
        String sql = """
                SELECT TABLE_NAME FROM information_schema.VIEWS
                WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME""";
        List<ViewDef> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            List<String> names = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
            for (String name : names) {
                out.add(new ViewDef(schema, name, showCreate("SHOW CREATE VIEW " + qualify(schema, name))));
            }
        }
        return out;
    }

    public List<RoutineDef> listRoutines(String schema) throws SQLException {
        String sql = """
                SELECT ROUTINE_NAME, ROUTINE_TYPE, ROUTINE_DEFINITION
                FROM information_schema.ROUTINES
                WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_NAME""";
        List<RoutineDef> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
            for (Object[] row : rows) {
                String name = (String) row[0];
                RoutineDef.Kind kind = "PROCEDURE".equalsIgnoreCase((String) row[1])
                        ? RoutineDef.Kind.PROCEDURE : RoutineDef.Kind.FUNCTION;
                String verb = kind == RoutineDef.Kind.PROCEDURE ? "PROCEDURE" : "FUNCTION";
                out.add(new RoutineDef(schema, name, kind, (String) row[2],
                        showCreate("SHOW CREATE " + verb + " " + qualify(schema, name))));
            }
        }
        return out;
    }

    public List<TriggerDef> listTriggers(String schema) throws SQLException {
        String sql = """
                SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION,
                       ACTION_STATEMENT
                FROM information_schema.TRIGGERS
                WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME""";
        List<TriggerDef> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)});
                }
            }
            for (Object[] row : rows) {
                out.add(new TriggerDef(schema, (String) row[0], (String) row[1],
                        (String) row[2], (String) row[3], (String) row[4],
                        showCreate("SHOW CREATE TRIGGER " + qualify(schema, (String) row[0]))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The DDL column of a {@code SHOW CREATE ...} result.
     *
     * <p>Tables and views put the object name in column 1 and the definition in column 2, but
     * routines and triggers lead with bookkeeping columns — {@code SHOW CREATE PROCEDURE} returns
     * Procedure, sql_mode, ... and the body only in column 6, and {@code SHOW CREATE TRIGGER} in
     * column 7. Reading column 2 there hands back the session sql_mode string, which then gets
     * executed as if it were DDL. So the definition is found by its column label
     * ({@code Create Table / Create View / Create Procedure / Create Function}), with
     * {@code SQL Original Statement} for triggers — which is what SHOW CREATE TRIGGER calls it —
     * and the old positional read as a last resort.
     */
    private String showCreate(String statement) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(statement)) {
            if (!rs.next()) return "";
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String label = md.getColumnLabel(i);
                // SHOW CREATE TRIGGER is the odd one out: its statement column is named
                // "SQL Original Statement", not "Create Trigger"
                if (label.regionMatches(true, 0, "Create ", 0, 7)
                        || label.equalsIgnoreCase("SQL Original Statement")) {
                    String ddl = rs.getString(i);
                    return ddl == null ? "" : ddl;
                }
            }
            return md.getColumnCount() >= 2 ? nullToEmpty(rs.getString(2)) : "";
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class IndexRow {
        final String name;
        final boolean nonUnique;
        final String type;
        final List<String> columns = new ArrayList<>();

        IndexRow(String name, boolean nonUnique, String type) {
            this.name = name;
            this.nonUnique = nonUnique;
            this.type = type;
        }
    }

    private static final class Parts {
        final String name;
        final List<String> columns = new ArrayList<>();
        final List<String> refColumns = new ArrayList<>();
        String refSchema = "";
        String refTable = "";
        String onDelete = "NO ACTION";
        String onUpdate = "NO ACTION";

        Parts(String name) {
            this.name = name;
        }
    }
}

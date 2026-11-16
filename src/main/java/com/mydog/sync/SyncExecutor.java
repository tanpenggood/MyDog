package com.mydog.sync;

import com.mydog.metadata.MySqlMetadataReader;
import com.mydog.metadata.model.TableDef;
import com.mydog.util.SqlNames;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a {@link SyncPlan} to the target server.
 *
 * <p>Ordering is what makes this correct rather than merely fast: foreign key checks are off
 * while table DDL runs, data follows structure, views and routines come after data, and
 * triggers are created last so a target-side trigger cannot fire while rows are being copied.
 *
 * <p>Data writes are batched and committed one chunk at a time, so cancelling stops at a chunk
 * boundary with everything before it durable and nothing after it applied. DDL is not
 * transactional in MySQL; a failed statement stops the run instead of cascading.
 */
public final class SyncExecutor {

    public interface ProgressListener {
        void started(SyncAction action, int index, int total);

        void finished(SyncAction action, DataCounts counts, long millis);

        void message(String text);
    }

    public static final ProgressListener NOOP = new ProgressListener() {
        @Override public void started(SyncAction action, int index, int total) { }

        @Override public void finished(SyncAction action, DataCounts counts, long millis) { }

        @Override public void message(String text) { }
    };

    private final Connection target;
    private final SyncOptions options;
    private final Cancellation cancelled;
    private final Map<String, List<String>> targetColumnCache = new HashMap<>();

    public SyncExecutor(Connection target, SyncOptions options) {
        this(target, options, new Cancellation());
    }

    /**
     * @param cancelled the same instance the {@link SchemaComparator} was built with, so pressing
     *                  cancel also stops a producer that is paging through a large table
     */
    public SyncExecutor(Connection target, SyncOptions options, Cancellation cancelled) {
        this.target = target;
        this.options = options;
        this.cancelled = cancelled;
    }

    public void cancel() {
        cancelled.cancel();
    }

    public boolean isCancelled() {
        return cancelled.isCancelled();
    }

    public SyncReport execute(SyncPlan plan, ProgressListener listener) throws SQLException {
        List<SyncAction> actions = plan.selectedActions();
        DataCounts totals = DataCounts.ZERO;
        List<String> errors = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        boolean originalAutoCommit = target.getAutoCommit();

        try (Statement st = target.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
        }

        boolean stopped = false;
        int index = 0;
        for (SyncAction action : actions) {
            index++;
            if (stopped) {
                action.setStatus(SyncAction.Status.SKIPPED);
                action.setMessage("stopped after earlier error");
                continue;
            }
            if (cancelled.isCancelled()) {
                action.setStatus(SyncAction.Status.SKIPPED);
                action.setMessage("cancelled");
                continue;
            }
            listener.started(action, index, actions.size());
            long actionStart = System.currentTimeMillis();
            try {
                target.setAutoCommit(true);
                for (String sql : action.statements()) {
                    try (Statement st = target.createStatement()) {
                        st.execute(sql);
                    }
                }
                DataCounts counts = DataCounts.ZERO;
                if (action.hasData()) {
                    target.setAutoCommit(false);
                    RowWriter writer = new RowWriter(action);
                    counts = action.dataProducer().produce(writer);
                    writer.flush();
                    target.commit();
                    if (cancelled.isCancelled()) {
                        // the producer stopped early: say so, rather than reporting the partial
                        // counts as if the whole table had been examined
                        action.setMessage("cancelled during data transfer");
                    }
                }
                totals = totals.plus(counts);
                action.setDataCounts(counts);
                action.setStatus(SyncAction.Status.DONE);
                listener.finished(action, counts, System.currentTimeMillis() - actionStart);
            } catch (Exception e) {
                try {
                    target.rollback();
                } catch (SQLException ignored) {
                    // rollback of a failed chunk is best effort
                }
                action.setStatus(SyncAction.Status.FAILED);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                action.setMessage(message);
                errors.add(action.object() + ": " + message);
                listener.message("ERROR " + action.object() + ": " + message);
                stopped = true;
            }
        }

        try (Statement st = target.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        target.setAutoCommit(originalAutoCommit);
        return new SyncReport(actions, totals, System.currentTimeMillis() - startedAt,
                cancelled.isCancelled(), errors);
    }

    /** Batches INSERT / UPDATE / DELETE for one table and flushes at chunk boundaries. */
    private final class RowWriter implements DataProducer.ChangeSink {

        private final ObjectRef object;
        private final boolean upsert;
        private PreparedStatement insertStmt;
        private PreparedStatement updateStmt;
        private PreparedStatement deleteStmt;
        private int pending;

        RowWriter(SyncAction action) throws SQLException {
            this.object = action.object();
            this.upsert = action.upsert();
            // resolve once per table: the writable column set is the intersection with the target
            this.availableColumns = targetColumns(object);
        }

        private final List<String> availableColumns;

        @Override
        public void insert(Row row) throws SQLException {
            List<String> cols = writableColumnsOf(row, availableColumns, false);
            if (cols.isEmpty()) return;
            if (insertStmt == null) {
                insertStmt = target.prepareStatement(insertSql(row, cols));
            }
            for (int i = 0; i < cols.size(); i++) {
                insertStmt.setObject(i + 1, row.value(cols.get(i)));
            }
            insertStmt.addBatch();
            bump();
        }

        @Override
        public void update(Row sourceRow, Row targetRow) throws SQLException {
            List<String> keys = sourceRow.keyColumns();
            List<String> cols = writableColumnsOf(sourceRow, availableColumns, true);
            cols.removeAll(keys);
            if (cols.isEmpty()) return;
            if (updateStmt == null) {
                updateStmt = target.prepareStatement("UPDATE " + tableRef() + " SET "
                        + assignments(cols) + " WHERE " + keyPredicate(keys));
            }
            int p = 1;
            for (String col : cols) {
                updateStmt.setObject(p++, sourceRow.value(col));
            }
            for (String key : keys) {
                updateStmt.setObject(p++, sourceRow.value(key));
            }
            updateStmt.addBatch();
            bump();
        }

        @Override
        public void delete(Row targetRow) throws SQLException {
            List<String> keys = targetRow.keyColumns();
            if (keys.isEmpty()) return;
            if (deleteStmt == null) {
                deleteStmt = target.prepareStatement("DELETE FROM " + tableRef()
                        + " WHERE " + keyPredicate(keys));
            }
            int p = 1;
            for (String key : keys) {
                deleteStmt.setObject(p++, targetRow.value(key));
            }
            deleteStmt.addBatch();
            bump();
        }

        /**
         * Fast mode copies every source row, so the statement must survive a row that already
         * exists: {@code ON DUPLICATE KEY UPDATE} turns the insert into an update of the non-key
         * columns. {@code VALUES()} is used rather than the 8.0.19 row-alias form to stay
         * acceptable to 5.7 servers.
         */
        private String insertSql(Row row, List<String> cols) {
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableRef())
                    .append(" (").append(columnList(cols))
                    .append(") VALUES (").append(placeholders(cols.size())).append(")");
            if (!upsert) return sql.toString();
            List<String> changed = new ArrayList<>();
            for (String col : cols) {
                if (!row.keyColumns().contains(col)) changed.add(col);
            }
            if (changed.isEmpty()) return sql.toString();
            sql.append(" ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < changed.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(SqlNames.quote(changed.get(i))).append(" = VALUES(")
                        .append(SqlNames.quote(changed.get(i))).append(")");
            }
            return sql.toString();
        }

        private void bump() throws SQLException {
            if (++pending >= options.chunkSize()) flush();
        }

        /** Commit what is buffered; called at every chunk boundary and at the end. */
        void flush() throws SQLException {
            if (pending == 0) return;
            for (PreparedStatement ps : new PreparedStatement[]{insertStmt, updateStmt, deleteStmt}) {
                if (ps != null) {
                    ps.executeBatch();
                    ps.clearBatch();
                }
            }
            target.commit();
            pending = 0;
        }

        private String tableRef() {
            return SqlNames.qualified(object.schema(), object.name());
        }
    }

    // ------------------------------------------------------------------ column resolution

    /** Columns present on both sides; {@code excludeKeys} drops key columns from an UPDATE SET. */
    private static List<String> writableColumnsOf(Row row, List<String> available, boolean excludeKeys) {
        List<String> keys = row.keyColumns();
        List<String> out = new ArrayList<>();
        for (String column : row.columns()) {
            if (excludeKeys && keys.contains(column)) continue;
            if (available.stream().anyMatch(c -> c.equalsIgnoreCase(column))) out.add(column);
        }
        return out;
    }

    private List<String> targetColumns(ObjectRef ref) throws SQLException {
        String cacheKey = ref.qualified();
        List<String> cached = targetColumnCache.get(cacheKey);
        if (cached != null) return cached;
        TableDef def = new MySqlMetadataReader(target).readTable(ref.schema(), ref.name());
        List<String> cols = new ArrayList<>();
        def.columns().forEach(c -> cols.add(c.name()));
        targetColumnCache.put(cacheKey, cols);
        return cols;
    }

    private static String columnList(List<String> columns) {
        return String.join(", ", columns.stream().map(SqlNames::quote).toList());
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static String assignments(List<String> columns) {
        return String.join(", ", columns.stream().map(c -> SqlNames.quote(c) + " = ?").toList());
    }

    private static String keyPredicate(List<String> keys) {
        return String.join(" AND ", keys.stream().map(k -> SqlNames.quote(k) + " = ?").toList());
    }
}

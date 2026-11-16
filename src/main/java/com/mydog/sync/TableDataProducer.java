package com.mydog.sync;

import com.mydog.metadata.model.ColumnDef;
import com.mydog.metadata.model.TableDef;
import com.mydog.util.SqlNames;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The row changes of one live table pair, recomputed on every {@link #produce}.
 *
 * <p>A plan is built during the compare step and may be executed minutes later, so this holds
 * connections and result sets not at all: each produce opens its own reader connections and closes
 * them on the way out. That also makes the same action safe to run twice — once to count changes
 * for the preview, once to apply them — which is what script export and execution need.
 *
 * <p>{@link Mode#DIFF} merges both sides by key and emits only what changed. {@link Mode#COPY}
 * streams every source row as an insert; it is used for a table that does not exist on the target
 * yet, for fast mode (paired with an upsert write), and, after a DELETE, for tables with no usable
 * row key where diffing is impossible.
 */
public final class TableDataProducer implements DataProducer {

    public enum Mode { DIFF, COPY }

    private final ConnectionFactory sourceFactory;
    private final ConnectionFactory targetFactory;
    private final TableDef source;
    private final TableDef target;
    private final int chunkSize;
    private final Mode mode;
    private final BooleanSupplier cancelled;

    public TableDataProducer(ConnectionFactory sourceFactory, ConnectionFactory targetFactory,
                             TableDef source, TableDef target, int chunkSize, Mode mode,
                             BooleanSupplier cancelled) {
        this.sourceFactory = sourceFactory;
        this.targetFactory = targetFactory;
        this.source = source;
        this.target = target;
        this.chunkSize = chunkSize;
        this.mode = mode;
        this.cancelled = cancelled;
    }

    @Override
    public DataCounts produce(ChangeSink sink) throws Exception {
        if (mode == Mode.COPY) return copy(sink);
        try (Connection src = sourceFactory.open(); Connection dst = targetFactory.open()) {
            JdbcRowReader sourceReader = new JdbcRowReader(src, source, chunkSize);
            JdbcRowReader targetReader = new JdbcRowReader(dst, keyTarget(), chunkSize);
            try {
                return DataDiffEngine.diff(sourceReader, targetReader, chunkSize, sink, cancelled)
                        .counts();
            } finally {
                sourceReader.close();
                targetReader.close();
            }
        }
    }

    /**
     * The target table as the diff sees it.
     *
     * <p>Key columns must match on both sides by name for the merge to order them the same way. When
     * the target's own key is spelled differently (a recreated table gets MySQL's default index
     * naming), the source key is what the target will have after the structure step, so read the
     * target through the source's key definition.
     */
    private TableDef keyTarget() {
        List<String> sourceKey = source.rowKeyColumns();
        if (target.rowKeyColumns().equals(sourceKey)) return target;
        List<com.mydog.metadata.model.IndexDef> indexes = new ArrayList<>(target.indexes());
        indexes.removeIf(i -> i.primary() || i.unique());
        indexes.add(new com.mydog.metadata.model.IndexDef("PRIMARY", true, true, sourceKey, "BTREE"));
        return new TableDef(target.schema(), target.name(), target.createStatement(),
                target.columns(), List.copyOf(indexes), target.foreignKeys(),
                target.charset(), target.collation(), target.approxRows());
    }

    private DataCounts copy(ChangeSink sink) throws Exception {
        List<String> columns = source.columns().stream().map(ColumnDef::name).toList();
        String sql = "SELECT " + SqlNames.columnList(columns)
                + " FROM " + SqlNames.qualified(source.schema(), source.name());
        long rows = 0;
        try (Connection conn = sourceFactory.open();
             PreparedStatement ps = conn.prepareStatement(sql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // Integer.MIN_VALUE is MySQL's "stream one row at a time" flag; without it the whole
            // table is buffered in the client, which is exactly what the chunked design avoids.
            ps.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (cancelled.getAsBoolean()) break;
                    List<Object> values = new ArrayList<>(columns.size());
                    for (int i = 1; i <= columns.size(); i++) {
                        values.add(rs.getObject(i));
                    }
                    sink.insert(new Row(columns, values, source.rowKeyColumns()));
                    rows++;
                }
            }
        }
        return new DataCounts(rows, 0, 0, rows);
    }
}

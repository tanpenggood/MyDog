package com.mydog.sync;

import com.mydog.util.RowKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Streams the row differences between two ordered readers with a two-way merge over keyset
 * cursors, so a 100-million-row table costs the same memory as a 100-row one.
 *
 * <p>Both sides must be ordered by the same key columns. Rows are compared by key; equal keys
 * compare values over the columns the two tables share.
 */
public final class DataDiffEngine {

    public record Result(DataCounts counts, boolean cancelled) {
    }

    private DataDiffEngine() {
    }

    public static Result diff(RowReader source, RowReader target, int chunkSize,
                              DataProducer.ChangeSink sink) throws Exception {
        return diff(source, target, chunkSize, sink, () -> false);
    }

    public static Result diff(RowReader source, RowReader target, int chunkSize,
                              DataProducer.ChangeSink sink, BooleanSupplier cancelled) throws Exception {
        List<String> keyColumns = source.keyColumns();
        if (!keyColumns.equals(target.keyColumns())) {
            throw new IllegalArgumentException("key columns differ: " + keyColumns
                    + " vs " + target.keyColumns());
        }
        Set<String> common = commonColumns(source.columns(), target.columns());

        Cursor a = new Cursor(source, chunkSize, keyColumns);
        Cursor b = new Cursor(target, chunkSize, keyColumns);

        long inserts = 0, updates = 0, deletes = 0, scanned = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                return new Result(new DataCounts(inserts, updates, deletes, scanned), true);
            }
            Row ra = a.current();
            Row rb = b.current();
            if (ra == null && rb == null) break;

            if (rb == null) {
                sink.insert(ra);
                inserts++;
                a.advance();
            } else if (ra == null) {
                sink.delete(rb);
                deletes++;
                b.advance();
            } else {
                int cmp = RowKeys.compare(ra.keyValues(keyColumns), rb.keyValues(keyColumns));
                if (cmp < 0) {
                    sink.insert(ra);
                    inserts++;
                    a.advance();
                } else if (cmp > 0) {
                    sink.delete(rb);
                    deletes++;
                    b.advance();
                } else {
                    if (!rowsEqual(ra, rb, common)) {
                        sink.update(ra, rb);
                        updates++;
                    }
                    a.advance();
                    b.advance();
                }
            }
            scanned++;
        }
        return new Result(new DataCounts(inserts, updates, deletes, scanned), false);
    }

    /** Columns present on both sides, in source order — the only ones safe to compare or write. */
    public static Set<String> commonColumns(List<String> source, List<String> target) {
        Set<String> targetSet = new HashSet<>(target);
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String c : source) {
            if (targetSet.contains(c)) out.add(c);
        }
        return out;
    }

    private static boolean rowsEqual(Row a, Row b, Set<String> columns) {
        for (String column : columns) {
            if (!RowKeys.valueEquals(a.value(column), b.value(column))) return false;
        }
        return true;
    }

    /** One side of the merge: a keyset-paged stream with a one-row lookahead buffer. */
    private static final class Cursor {
        private final RowReader reader;
        private final int chunkSize;
        private final List<String> keyColumns;
        private List<Row> chunk = List.of();
        private int position;
        private List<Object> lastKey = List.of();
        private boolean exhausted;

        Cursor(RowReader reader, int chunkSize, List<String> keyColumns) {
            this.reader = reader;
            this.chunkSize = chunkSize;
            this.keyColumns = keyColumns;
        }

        Row current() throws Exception {
            if (exhausted) return null;
            if (position >= chunk.size()) {
                chunk = reader.nextChunk(lastKey);
                position = 0;
                if (chunk.isEmpty()) {
                    exhausted = true;
                    return null;
                }
                guardProgress(chunk);
            }
            Row row = chunk.get(position);
            guardNonNullKey(row);
            return row;
        }

        void advance() {
            lastKey = chunk.get(position).keyValues(keyColumns);
            position++;
        }

        /**
         * A reader that hands back rows at or before the cursor would spin here forever, so
         * fail loudly instead of hanging the UI thread's worker.
         */
        private void guardProgress(List<Row> next) {
            if (lastKey.isEmpty()) return;
            List<Object> firstKey = next.get(0).keyValues(keyColumns);
            if (RowKeys.compare(firstKey, lastKey) <= 0) {
                throw new IllegalStateException("row reader did not advance past cursor " + lastKey
                        + " (first row key " + firstKey + ")");
            }
        }

        private void guardNonNullKey(Row row) {
            List<Object> key = row.keyValues(keyColumns);
            for (int i = 0; i < key.size(); i++) {
                if (key.get(i) == null) {
                    throw new IllegalStateException("key column " + keyColumns.get(i)
                            + " is NULL in row " + key + "; keyset paging cannot continue");
                }
            }
        }
    }

    /** Snapshot every row of a reader (small tables and tests only). */
    public static List<Row> drain(RowReader reader) throws Exception {
        List<String> keys = reader.keyColumns();
        List<Row> all = new ArrayList<>();
        List<Object> cursor = List.of();
        while (true) {
            List<Row> chunk = reader.nextChunk(cursor);
            if (chunk.isEmpty()) break;
            for (Row row : chunk) {
                all.add(row.keyColumns().isEmpty()
                        ? new Row(row.columns(), row.values(), keys)
                        : row);
            }
            cursor = chunk.get(chunk.size() - 1).keyValues(keys);
        }
        return all;
    }
}

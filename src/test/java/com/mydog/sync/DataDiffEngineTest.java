package com.mydog.sync;

import com.mydog.util.RowKeys;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The merge that decides which rows change, driven by an in-memory reader so the paging and the
 * guards can be tested at sizes a database would make slow.
 */
class DataDiffEngineTest {

    private static final List<String> SINGLE = List.of("id", "name");
    private static final List<String> ID = List.of("id");
    private static final List<String> COMPOSITE = List.of("a", "b");

    // ------------------------------------------------------------------ classification

    @Test
    void classifiesInsertsUpdatesAndDeletes() throws Exception {
        ListReader source = new ListReader(SINGLE, ID, List.of(
                row(1, "a"), row(2, "b-changed"), row(3, "c")), 10);
        ListReader target = new ListReader(SINGLE, ID, List.of(
                row(2, "b"), row(4, "d")), 10);
        Collector sink = new Collector();

        DataDiffEngine.Result result = DataDiffEngine.diff(source, target, 10, sink);

        assertThat(result.counts().inserts()).isEqualTo(2);
        assertThat(result.counts().updates()).isEqualTo(1);
        assertThat(result.counts().deletes()).isEqualTo(1);
        assertThat(sink.inserted).containsExactly("[1]", "[3]");
        assertThat(sink.updated).containsExactly("[2]");
        assertThat(sink.deleted).containsExactly("[4]");
        assertThat(result.cancelled()).isFalse();
    }

    @Test
    void identicalSidesChangeNothing() throws Exception {
        List<Row> rows = List.of(row(1, "a"), row(2, "b"), row(3, "c"));

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(SINGLE, ID, rows, 4), new ListReader(SINGLE, ID, rows, 4), 4,
                new Collector());

        assertThat(result.counts().changes()).isZero();
        assertThat(result.counts().rowsScanned()).isEqualTo(3);
    }

    @Test
    void compositeKeysOrderByEveryColumn() throws Exception {
        List<Row> source = List.of(
                new Row(COMPOSITE, List.of(1, 1), COMPOSITE),
                new Row(COMPOSITE, List.of(1, 2), COMPOSITE),
                new Row(COMPOSITE, List.of(2, 1), COMPOSITE));
        List<Row> target = List.of(
                new Row(COMPOSITE, List.of(1, 2), COMPOSITE),
                new Row(COMPOSITE, List.of(2, 1), COMPOSITE));

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(COMPOSITE, COMPOSITE, source, 2),
                new ListReader(COMPOSITE, COMPOSITE, target, 2), 2, new Collector());

        assertThat(result.counts().inserts()).isEqualTo(1);
        assertThat(result.counts().updates()).isZero();
        assertThat(result.counts().deletes()).isZero();
    }

    // ------------------------------------------------------------------ paging

    @Test
    void oneRowPerPageStillFindsEveryChange() throws Exception {
        // the chunk boundary is where a keyset cursor goes wrong: a row consumed at the end of one
        // page must not be re-read as the first row of the next
        List<Row> source = new ArrayList<>();
        List<Row> target = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            source.add(row(i, "v" + i));
            if (i % 3 != 0) target.add(row(i, "v" + i));
        }
        Collector sink = new Collector();

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(SINGLE, ID, source, 1), new ListReader(SINGLE, ID, target, 1), 1, sink);

        assertThat(result.counts().inserts()).isEqualTo(16);   // every third id is missing on target
        assertThat(result.counts().deletes()).isZero();
        assertThat(result.counts().rowsScanned()).isEqualTo(50);
        assertThat(sink.inserted).hasSize(16);
    }

    @Test
    void unevenRowDensityBothWaysStillTerminates() throws Exception {
        // three sparse source rows against a dense target: the merge must not lose the tail on
        // either side, and a source-only key past the last target key must still be inserted
        List<Row> source = List.of(row(1, "a"), row(100, "b"), row(101, "c"), row(500, "far"));
        List<Row> target = new ArrayList<>(List.of(row(1, "a"), row(100, "b"), row(101, "c")));
        for (int i = 2; i <= 200; i++) target.add(row(i, "x" + i));
        Collector sink = new Collector();

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(SINGLE, ID, source, 2), new ListReader(SINGLE, ID, target, 7), 7, sink);

        assertThat(result.counts().inserts()).isEqualTo(1);       // id 500
        assertThat(result.counts().updates()).isZero();           // overlapping keys match exactly
        assertThat(result.counts().deletes()).isEqualTo(199);     // ids 2..99 and 102..200
        assertThat(result.counts().rowsScanned()).isEqualTo(203);
    }

    @Test
    void readerThatDoesNotAdvanceIsRejectedInsteadOfHanging() {
        ListReader stuck = new ListReader(SINGLE, ID, List.of(row(1, "a"), row(2, "b")), 10) {
            @Override
            public List<Row> nextChunk(List<Object> lastKey) {
                return rows;   // ignores the cursor entirely
            }
        };

        assertThatThrownBy(() -> DataDiffEngine.diff(stuck,
                new ListReader(SINGLE, ID, List.of(), 10), 10, new Collector()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not advance past cursor");
    }

    @Test
    void nullKeyColumnAbortsRatherThanLooping() {
        // a row-value comparison against NULL is never true, so keyset paging cannot move past it
        ListReader withNull = new ListReader(SINGLE, ID, List.of(row(1, "a")), 10);
        withNull.rows.set(0, new Row(SINGLE, Arrays.asList(null, "a"), ID));

        assertThatThrownBy(() -> DataDiffEngine.diff(withNull,
                new ListReader(SINGLE, ID, List.of(), 10), 10, new Collector()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is NULL in row");
    }

    @Test
    void mismatchedKeyColumnsAreRefused() {
        ListReader source = new ListReader(SINGLE, ID, List.of(row(1, "a")), 10);
        ListReader target = new ListReader(SINGLE, List.of("id"), List.of(row(1, "a")), 10) {
            @Override
            public List<String> keyColumns() {
                return List.of("user_id");
            }
        };

        assertThatThrownBy(() -> DataDiffEngine.diff(source, target, 10, new Collector()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key columns differ");
    }

    // ------------------------------------------------------------------ comparison rules

    @Test
    void onlyColumnsPresentOnBothSidesAreCompared() throws Exception {
        List<String> withExtra = List.of("id", "name", "source_only");
        List<Row> source = List.of(new Row(withExtra, List.of(1, "a", "different"), ID));
        List<Row> target = List.of(new Row(SINGLE, List.of(1, "a"), ID));
        Collector sink = new Collector();

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(withExtra, ID, source, 10), new ListReader(SINGLE, ID, target, 10),
                10, sink);

        assertThat(result.counts().updates()).isZero();
    }

    @Test
    void numbersCompareByValueNotByJavaType() throws Exception {
        List<Row> source = List.of(new Row(SINGLE, List.of(1, new BigDecimal("5.00")), ID));
        List<Row> target = List.of(new Row(SINGLE, List.of(1L, 5), ID));

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(SINGLE, ID, source, 10), new ListReader(SINGLE, ID, target, 10),
                10, new Collector());

        assertThat(result.counts().updates()).isZero();
    }

    @Test
    void cancellationStopsTheMergeAndReportsIt() throws Exception {
        List<Row> source = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) source.add(row(i, "v" + i));
        AtomicInteger seen = new AtomicInteger();
        DataProducer.ChangeSink sink = new DataProducer.ChangeSink() {
            @Override
            public void insert(Row row) {
                seen.incrementAndGet();
            }

            @Override
            public void update(Row s, Row t) {
                seen.incrementAndGet();
            }

            @Override
            public void delete(Row t) {
                seen.incrementAndGet();
            }
        };

        DataDiffEngine.Result result = DataDiffEngine.diff(
                new ListReader(SINGLE, ID, source, 10), new ListReader(SINGLE, ID, List.of(), 10),
                10, sink, () -> seen.get() >= 25);

        assertThat(result.cancelled()).isTrue();
        assertThat(seen.get()).isBetween(25, 35);
        assertThat(result.counts().inserts()).isEqualTo(seen.get());
    }

    // ------------------------------------------------------------------ helpers

    private static Row row(int id, String name) {
        return new Row(SINGLE, List.of(id, name), ID);
    }

    private static final class Collector implements DataProducer.ChangeSink {
        final List<String> inserted = new ArrayList<>();
        final List<String> updated = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        @Override
        public void insert(Row row) {
            inserted.add(row.keyValues(row.keyColumns()).toString());
        }

        @Override
        public void update(Row sourceRow, Row targetRow) {
            updated.add(sourceRow.keyValues(sourceRow.keyColumns()).toString());
        }

        @Override
        public void delete(Row targetRow) {
            deleted.add(targetRow.keyValues(targetRow.keyColumns()).toString());
        }
    }

    /** A reader over a fixed list, honouring the keyset contract with a caller-chosen page size. */
    private static class ListReader implements RowReader {
        final List<Row> rows;
        private final List<String> columns;
        private final List<String> keys;
        private final int pageSize;

        ListReader(List<String> columns, List<String> keys, List<Row> rows, int pageSize) {
            this.columns = columns;
            this.keys = keys;
            this.rows = new ArrayList<>(rows);
            this.pageSize = pageSize;
            this.rows.sort((x, y) -> RowKeys.compare(x.keyValues(keys), y.keyValues(keys)));
        }

        @Override
        public List<String> columns() {
            return columns;
        }

        @Override
        public List<String> keyColumns() {
            return keys;
        }

        @Override
        public List<Row> nextChunk(List<Object> lastKey) {
            List<Row> out = new ArrayList<>();
            for (Row row : rows) {
                if (!lastKey.isEmpty() && RowKeys.compare(row.keyValues(keys), lastKey) <= 0) continue;
                out.add(row);
                if (out.size() >= pageSize) break;
            }
            return out;
        }
    }
}

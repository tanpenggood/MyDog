package com.mydog.sync;

/** Row-level outcome of diffing or applying one table. */
public record DataCounts(long inserts, long updates, long deletes, long rowsScanned) {

    public static final DataCounts ZERO = new DataCounts(0, 0, 0, 0);

    public long changes() {
        return inserts + updates + deletes;
    }

    public DataCounts plus(DataCounts other) {
        return new DataCounts(inserts + other.inserts, updates + other.updates,
                deletes + other.deletes, rowsScanned + other.rowsScanned);
    }
}

package com.mydog.sync;

/**
 * Knobs for one sync run.
 *
 * @param mode           structure, data, or both
 * @param dropExtraneous delete target objects the source does not have (destructive, off by default)
 * @param chunkSize      rows fetched per keyset page while diffing data
 * @param fastDataMode   skip row-by-row diffing and upsert every source row instead. Faster for
 *                       mostly-identical tables, but it cannot produce DELETE statements.
 */
public record SyncOptions(
        SyncMode mode,
        boolean dropExtraneous,
        int chunkSize,
        boolean fastDataMode) {

    public static SyncOptions defaults() {
        return new SyncOptions(SyncMode.STRUCTURE_AND_DATA, false, 5000, false);
    }

    public SyncOptions {
        if (chunkSize < 100) chunkSize = 100;
        if (chunkSize > 200_000) chunkSize = 200_000;
    }

    public boolean wantsStructure() {
        return mode.structure();
    }

    public boolean wantsData() {
        return mode.data();
    }
}

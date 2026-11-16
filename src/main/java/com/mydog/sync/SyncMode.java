package com.mydog.sync;

/** What a sync run is allowed to change. */
public enum SyncMode {
    /** DDL only: tables, views, routines, triggers. No row data touched. */
    STRUCTURE_ONLY,
    /** Row data only: structure is assumed already equal. */
    DATA_ONLY,
    /** Both, structure first then data. */
    STRUCTURE_AND_DATA;

    public boolean structure() {
        return this != DATA_ONLY;
    }

    public boolean data() {
        return this != STRUCTURE_ONLY;
    }
}

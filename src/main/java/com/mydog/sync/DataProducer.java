package com.mydog.sync;

/**
 * Produces the row changes of one table lazily.
 *
 * <p>A plan never holds the diffed rows in memory: the producer re-runs the keyset merge and
 * pushes each change to the sink, which either writes it to the target (execution) or counts it
 * (preview), or turns it into INSERT/UPDATE/DELETE text (script export).
 */
public interface DataProducer {

    /** Stream every change of this table into {@code sink}. */
    DataCounts produce(ChangeSink sink) throws Exception;

    /** Receives row changes one at a time. */
    interface ChangeSink {
        void insert(Row row) throws Exception;

        void update(Row sourceRow, Row targetRow) throws Exception;

        void delete(Row targetRow) throws Exception;
    }
}

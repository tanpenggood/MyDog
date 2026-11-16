package com.mydog.sync;

import java.util.List;

/**
 * Ordered source of rows for one table, paged by keyset cursor.
 *
 * <p>Both the live JDBC implementation and the in-memory test fake satisfy this, so the diff
 * algorithm is tested without a database.
 */
public interface RowReader extends AutoCloseable {

    /** Column names this reader produces, in stable order. */
    List<String> columns();

    /** Key columns used for ordering and paging (primary key or a NOT NULL unique key). */
    List<String> keyColumns();

    /**
     * Next page of rows ordered by key, strictly after {@code lastKey}.
     * An empty {@code lastKey} means "from the beginning". An empty result means "exhausted".
     */
    List<Row> nextChunk(List<Object> lastKey) throws Exception;

    @Override
    default void close() throws Exception {
        // nothing to release for in-memory sources
    }
}

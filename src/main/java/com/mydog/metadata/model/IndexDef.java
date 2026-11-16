package com.mydog.metadata.model;

import java.util.List;

/** An index (including the primary key, flagged by {@code primary}). */
public record IndexDef(
        String name,
        boolean primary,
        boolean unique,
        List<String> columns,  // ordered by SEQ_IN_INDEX
        String type) {         // BTREE, FULLTEXT, HASH, SPATIAL
}

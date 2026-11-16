package com.mydog.sync;

import com.mydog.metadata.model.ForeignKeyDef;
import com.mydog.metadata.model.TableDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orders tables so a referenced table is created before the table that references it.
 *
 * <p>Cycles exist in real schemas (self-references, mutual FKs), so the walk degrades to
 * "emit what is ready, otherwise break the cycle at the alphabetically first table" rather
 * than failing — the executor turns foreign key checks off around DDL anyway.
 */
public final class TableOrdering {

    private TableOrdering() {
    }

    public static List<TableDef> orderByDependency(List<TableDef> tables) {
        Map<String, TableDef> byName = new HashMap<>();
        for (TableDef t : tables) {
            byName.put(t.name(), t);
        }
        Map<String, Set<String>> deps = new HashMap<>();
        for (TableDef t : tables) {
            Set<String> refs = new HashSet<>();
            for (ForeignKeyDef fk : t.foreignKeys()) {
                // only same-schema references matter for creation order here
                if (fk.referencedTable().equals(t.name())) continue;
                if (fk.referencedSchema().equalsIgnoreCase(t.schema()) || fk.referencedSchema().isEmpty()) {
                    refs.add(fk.referencedTable());
                }
            }
            deps.put(t.name(), refs);
        }

        List<TableDef> ordered = new ArrayList<>(tables.size());
        Set<String> done = new HashSet<>();
        Set<String> remaining = new HashSet<>(deps.keySet());
        while (!remaining.isEmpty()) {
            List<String> ready = remaining.stream()
                    .filter(n -> done.containsAll(deps.get(n)))
                    .sorted()
                    .toList();
            if (ready.isEmpty()) {
                ready = List.of(remaining.stream().min(Comparator.naturalOrder()).orElseThrow());
            }
            for (String n : ready) {
                TableDef t = byName.get(n);
                if (t != null) ordered.add(t);
                done.add(n);
                remaining.remove(n);
            }
        }
        return ordered;
    }
}

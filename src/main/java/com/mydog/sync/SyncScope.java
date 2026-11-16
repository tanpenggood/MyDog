package com.mydog.sync;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which objects the comparison should consider, by kind and name.
 *
 * <p>An empty set means "every object of that kind", which is what the scope step's select-all and
 * name-filter produce when the user unchecks nothing — so a scope never has to be rebuilt when a
 * new table appears on the source between two comparisons.
 */
public record SyncScope(Set<String> tables,
                        Set<String> views,
                        Set<String> procedures,
                        Set<String> functions,
                        Set<String> triggers) {

    public SyncScope {
        tables = lower(tables);
        views = lower(views);
        procedures = lower(procedures);
        functions = lower(functions);
        triggers = lower(triggers);
    }

    public static SyncScope everything() {
        return new SyncScope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public boolean includes(ObjectKind kind, String name) {
        return setFor(kind).isEmpty() || setFor(kind).contains(name.toLowerCase(Locale.ROOT));
    }

    /** True when nothing was picked for this kind, i.e. the whole category is in scope. */
    public boolean selectsAll(ObjectKind kind) {
        return setFor(kind).isEmpty();
    }

    public int selectedCount(ObjectKind kind) {
        return setFor(kind).size();
    }

    private Set<String> setFor(ObjectKind kind) {
        return switch (kind) {
            case TABLE -> tables;
            case VIEW -> views;
            case PROCEDURE -> procedures;
            case FUNCTION -> functions;
            case TRIGGER -> triggers;
        };
    }

    private static Set<String> lower(Set<String> in) {
        if (in == null || in.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isEmpty()) out.add(s.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }
}

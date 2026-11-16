package com.mydog.sync;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The compared result: what would change, per object, with warnings the UI must show. */
public final class SyncPlan {

    private final String sourceLabel;
    private final String targetLabel;
    private final List<SyncAction> actions;
    private final List<String> warnings;

    public SyncPlan(String sourceLabel, String targetLabel, List<SyncAction> actions,
                    List<String> warnings) {
        this.sourceLabel = sourceLabel;
        this.targetLabel = targetLabel;
        this.actions = List.copyOf(actions);
        this.warnings = List.copyOf(warnings);
    }

    public String sourceLabel() { return sourceLabel; }
    public String targetLabel() { return targetLabel; }
    public List<SyncAction> actions() { return actions; }
    public List<String> warnings() { return warnings; }

    public List<SyncAction> selectedActions() {
        return actions.stream().filter(SyncAction::isSelected).toList();
    }

    public Map<ObjectKind, List<SyncAction>> byKind() {
        Map<ObjectKind, List<SyncAction>> out = new EnumMap<>(ObjectKind.class);
        for (SyncAction a : actions) {
            out.computeIfAbsent(a.object().kind(), k -> new java.util.ArrayList<>()).add(a);
        }
        return out;
    }

    public long ddlCount() {
        return actions.stream().filter(a -> a.isSelected() && !a.hasData()).count();
    }

    public long dataActionCount() {
        return actions.stream().filter(a -> a.isSelected() && a.hasData()).count();
    }

    public boolean isEmpty() {
        return selectedActions().isEmpty();
    }
}

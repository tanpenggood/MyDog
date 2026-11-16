package com.mydog.sync;

import java.util.List;

/** What a run actually did, per action, with totals and errors. */
public record SyncReport(
        List<SyncAction> actions,
        DataCounts totals,
        long millis,
        boolean cancelled,
        List<String> errors) {

    public long count(SyncAction.Status status) {
        return actions.stream().filter(a -> a.status() == status).count();
    }

    public long ddlStatements() {
        return actions.stream()
                .filter(a -> a.status() == SyncAction.Status.DONE)
                .mapToLong(a -> a.statements().size())
                .sum();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}

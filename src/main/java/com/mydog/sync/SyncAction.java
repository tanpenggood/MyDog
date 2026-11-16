package com.mydog.sync;

import java.util.List;

/**
 * One unit of work in a plan: DDL statements for an object, a streaming data diff, or both.
 *
 * <p>Mutable on purpose — the wizard toggles {@code selected} and the executor writes
 * {@code status}/{@code message} as it walks the plan.
 */
public final class SyncAction {

    public enum Kind { CREATE, ALTER, DROP, DATA, SKIP, ERROR }

    public enum Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    private final ObjectRef object;
    private final Kind kind;
    private final List<String> statements;
    private final DataProducer dataProducer;
    private final String detail;
    private final boolean upsert;

    private boolean selected;
    private Status status = Status.PENDING;
    private DataCounts dataCounts;
    private String message;

    private SyncAction(ObjectRef object, Kind kind, List<String> statements,
                       DataProducer dataProducer, String detail, boolean selected, boolean upsert) {
        this.object = object;
        this.kind = kind;
        this.statements = List.copyOf(statements);
        this.dataProducer = dataProducer;
        this.detail = detail;
        this.selected = selected;
        this.upsert = upsert;
    }

    public static SyncAction ddl(ObjectRef object, Kind kind, List<String> statements, String detail) {
        return new SyncAction(object, kind, statements, null, detail, true, false);
    }

    public static SyncAction data(ObjectRef object, DataProducer producer, String detail) {
        return data(object, List.of(), producer, detail, false);
    }

    /**
     * A data action. {@code statements} run before any row is produced — that is how a keyless
     * table's {@code DELETE FROM} travels with the reload it belongs to. {@code upsert} writes each
     * row as {@code INSERT ... ON DUPLICATE KEY UPDATE}, which is what makes fast mode's
     * copy-everything-source cheap and idempotent.
     */
    public static SyncAction data(ObjectRef object, DataProducer producer, String detail, boolean upsert) {
        return data(object, List.of(), producer, detail, upsert);
    }

    public static SyncAction data(ObjectRef object, List<String> statements, DataProducer producer,
                                  String detail, boolean upsert) {
        return new SyncAction(object, Kind.DATA, statements, producer, detail, true, upsert);
    }

    public static SyncAction skipped(ObjectRef object, String detail) {
        return new SyncAction(object, Kind.SKIP, List.of(), null, detail, false, false);
    }

    public static SyncAction error(ObjectRef object, String message) {
        SyncAction a = new SyncAction(object, Kind.ERROR, List.of(), null, message, false, false);
        a.status = Status.FAILED;
        a.message = message;
        return a;
    }

    public ObjectRef object() { return object; }
    public Kind kind() { return kind; }
    public List<String> statements() { return statements; }
    public DataProducer dataProducer() { return dataProducer; }
    public String detail() { return detail; }
    public boolean upsert() { return upsert; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public Status status() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public DataCounts dataCounts() { return dataCounts; }
    public void setDataCounts(DataCounts dataCounts) { this.dataCounts = dataCounts; }

    public String message() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean hasData() { return dataProducer != null; }

    @Override
    public String toString() {
        return kind + " " + object + (detail == null || detail.isEmpty() ? "" : " — " + detail);
    }
}

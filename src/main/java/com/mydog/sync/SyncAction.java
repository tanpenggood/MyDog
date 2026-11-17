package com.mydog.sync;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    /**
     * Tables in the target schema this object reads from — a view's base tables, a trigger's one
     * table. Empty when nothing was found. The executor will not try to create the object while one
     * of them is missing, because MySQL rejects it and DDL does not roll back.
     */
    private final Set<String> dependsOn;

    private boolean selected;
    private Status status = Status.PENDING;
    private DataCounts dataCounts;
    private String message;

    private SyncAction(ObjectRef object, Kind kind, List<String> statements,
                       DataProducer dataProducer, String detail, boolean selected, boolean upsert,
                       Collection<String> dependsOn) {
        this.object = object;
        this.kind = kind;
        this.statements = List.copyOf(statements);
        this.dataProducer = dataProducer;
        this.detail = detail;
        this.selected = selected;
        this.upsert = upsert;
        this.dependsOn = Set.copyOf(dependsOn);
    }

    public static SyncAction ddl(ObjectRef object, Kind kind, List<String> statements, String detail) {
        return ddl(object, kind, statements, detail, Set.of());
    }

    /** @param dependsOn target-schema tables that must exist before these statements can run */
    public static SyncAction ddl(ObjectRef object, Kind kind, List<String> statements, String detail,
                                 Collection<String> dependsOn) {
        return new SyncAction(object, kind, statements, null, detail, true, false, dependsOn);
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
        return new SyncAction(object, Kind.DATA, statements, producer, detail, true, upsert,
                Set.of());
    }

    public static SyncAction skipped(ObjectRef object, String detail) {
        return new SyncAction(object, Kind.SKIP, List.of(), null, detail, false, false, Set.of());
    }

    public static SyncAction error(ObjectRef object, String message) {
        SyncAction a = new SyncAction(object, Kind.ERROR, List.of(), null, message, false, false,
                Set.of());
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

    /** Target-schema tables this object needs before it can be created; empty if none. */
    public Set<String> dependsOn() { return dependsOn; }

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

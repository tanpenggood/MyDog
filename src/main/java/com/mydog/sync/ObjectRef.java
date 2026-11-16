package com.mydog.sync;

/** Identity of a syncable object, used as a map key across the engine and the UI. */
public record ObjectRef(ObjectKind kind, String schema, String name) {

    public ObjectRef {
        schema = schema == null ? "" : schema;
        name = name == null ? "" : name;
    }

    public String qualified() {
        return schema.isEmpty() ? name : schema + "." + name;
    }

    @Override
    public String toString() {
        return kind + " " + qualified();
    }
}

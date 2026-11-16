package com.mydog.metadata.model;

/** A stored function or procedure. */
public record RoutineDef(
        String schema,
        String name,
        Kind kind,
        String body,             // ROUTINE_BODY, the BEGIN...END block
        String createStatement) { // SHOW CREATE ROUTINE output

    public enum Kind { FUNCTION, PROCEDURE }

    /** MySQL routine names are not overloaded: schema + name identifies one. */
    public String signature() {
        return schema + "." + name;
    }
}

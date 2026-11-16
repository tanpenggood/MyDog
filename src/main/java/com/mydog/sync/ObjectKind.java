package com.mydog.sync;

/** Kinds of object MyDog can sync, in the order they must be applied. */
public enum ObjectKind {
    TABLE,
    VIEW,
    PROCEDURE,
    FUNCTION,
    TRIGGER;

    /** Human-readable label key for i18n. */
    public String messageKey() {
        return switch (this) {
            case TABLE -> "sync.kind.table";
            case VIEW -> "sync.kind.view";
            case PROCEDURE -> "sync.kind.procedure";
            case FUNCTION -> "sync.kind.function";
            case TRIGGER -> "sync.kind.trigger";
        };
    }
}

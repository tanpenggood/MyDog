package com.mydog.sync;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * One cancel switch shared by the compare, the export and the execution of a plan.
 *
 * <p>It has to be shared rather than owned by the executor: a cancel pressed while a million rows
 * are being merged has to reach the reader loop inside {@link TableDataProducer}, and the same flag
 * also has to stop the wizard from starting the next object. Two flags would mean the button stops
 * one of those two things — usually the one the user is not watching.
 *
 * <p>Setting it is enough; nothing waits on it. The readers check at key boundaries, the executor
 * checks between actions, and a commit already issued stays issued — MySQL DDL cannot be rolled
 * back and row chunks are committed on purpose so that partial progress is real progress.
 */
public final class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public void reset() {
        cancelled.set(false);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public BooleanSupplier supplier() {
        return cancelled::get;
    }
}

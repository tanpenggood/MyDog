package com.mydog.ui.sync;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.junit.jupiter.api.Assumptions;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared plumbing for the tests that need the real toolkit: the wizard model's tasks and the widget
 * click-through.
 *
 * <p>{@code Task} is created in its default {@code Task.Mode.PLATFORM}, which is what delivers
 * progress, message and value changes on the FX thread — the reason the wizard can bind a progress
 * bar and append to a log directly. The other half of that contract is that the state and value
 * getters refuse to be read from any other thread, so a test cannot shortcut the toolkit either.
 */
public final class FxTestSupport {

    private static boolean toolkitReady;

    private FxTestSupport() {
    }

    /** Starts JavaFX, or aborts the test when this machine cannot run a window server. */
    public static void ensureToolkit() {
        if (toolkitReady) return;
        try {
            Platform.setImplicitExit(false);
            Platform.startup(() -> {
            });
            toolkitReady = true;
        } catch (IllegalStateException alreadyStarted) {
            toolkitReady = true;      // another IT in this JVM already brought it up
        } catch (Throwable cannotStart) {
            Assumptions.abort("no display available for the JavaFX toolkit ("
                    + cannotStart + ") — the wizard UI tests are skipped");
        }
    }

    /** Runs on the FX thread and waits for it, propagating failures to the caller. */
    public static void runOnFx(Runnable body) {
        fx(() -> {
            body.run();
            return null;
        });
    }

    /** Runs {@code body} on the FX thread and returns its result to the calling thread. */
    public static <T> T fx(Callable<T> body) {
        if (Platform.isFxApplicationThread()) {
            try {
                return body.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(body.call());
            } catch (Exception | Error e) {
                done.completeExceptionally(e);
            }
        });
        try {
            return done.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("FX thread is not responding", e);
        }
    }

    /**
     * Runs a wizard task the way the wizard does — JDBC on a worker thread, result observed on the
     * FX thread — and returns its value.
     */
    static <T> T run(Task<T> task) throws Exception {
        return settle(task).get(5, TimeUnit.MINUTES);
    }

    /** Runs a task expected to blow up and hands back what it threw, as the wizard would show it. */
    static Throwable failureOf(Task<?> task) throws Exception {
        try {
            settle(task).get(5, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        throw new AssertionError("task was expected to fail");
    }

    private static <T> CompletableFuture<T> settle(Task<T> task) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        runOnFx(() -> {
            task.setOnSucceeded(e -> outcome.complete(task.getValue()));
            task.setOnFailed(e -> outcome.completeExceptionally(task.getException()));
            Thread worker = new Thread(task, "mydog-test-worker");
            worker.setDaemon(true);
            worker.start();
        });
        return outcome;
    }

    /** Waits until {@code condition} holds on the FX thread, then returns. */
    public static void waitUntil(String what, Callable<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            boolean ready = fx(condition);
            if (ready) return;
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

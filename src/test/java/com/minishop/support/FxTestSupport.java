package com.minishop.support;

import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.testfx.api.FxToolkit;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Helpers for the {@code @Tag("ui")} tests (QĐ11 of docs/plans/desktop-d1.md). They run on the real JavaFX toolkit and
 * use TestFX only to start it ({@link FxToolkit}); no robot is ever created, so the tests never move the mouse or
 * press a key on the developer's machine, they only look up nodes and state.
 *
 * <p>The JavaFX toolkit can start only once per JVM, and surefire runs all test classes in one JVM, so every class
 * calls {@link #startToolkit()} (a no-op after the first call) and closes its own windows in {@code @AfterAll}.
 */
public final class FxTestSupport {

    /** Longest any wait for the FX thread or for a window may take before the test fails. */
    public static final long TIMEOUT_SECONDS = 15;

    private FxTestSupport() {
    }

    /** Starts the toolkit if needed and returns TestFX's primary stage (not shown). */
    public static Stage startToolkit() {
        try {
            Stage stage = FxToolkit.registerPrimaryStage();
            // Closing the last window must not shut the toolkit down: it cannot be started again in the same JVM.
            Platform.setImplicitExit(false);
            return stage;
        } catch (TimeoutException e) {
            throw new IllegalStateException("The JavaFX toolkit did not start in time", e);
        }
    }

    /** Runs the task on the FX thread, waits for it, and returns its result (or rethrows what it threw). */
    public static <T> T callOnFx(Callable<T> task) {
        try {
            if (Platform.isFxApplicationThread()) {
                return task.call();
            }
            FutureTask<T> future = new FutureTask<>(task);
            Platform.runLater(future);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw unchecked(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("The FX thread did not run the task in time", e);
        } catch (Exception e) {
            throw unchecked(e);
        }
    }

    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }

    public static void runOnFx(Runnable task) {
        callOnFx(() -> {
            task.run();
            return null;
        });
    }

    /** Waits until everything already queued on the FX thread has run. */
    public static void flushFx() {
        runOnFx(() -> { });
    }

    /** Polls the condition (on the calling thread) until it is true, or fails after {@link #TIMEOUT_SECONDS}. */
    public static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** Waits for a latch, failing with a clear message instead of hanging the build. */
    public static void await(String what, CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The showing windows, read on the FX thread. */
    public static List<Window> showingWindows() {
        return callOnFx(() -> Window.getWindows().stream().filter(Window::isShowing).toList());
    }

    /** Hides every window that is still showing. */
    public static void hideAllWindows() {
        if (!Platform.isFxApplicationThread()) {
            runOnFx(FxTestSupport::hideAllWindows);
            return;
        }
        for (Window window : List.copyOf(Window.getWindows())) {
            window.hide();
        }
    }
}

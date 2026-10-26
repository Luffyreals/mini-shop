package com.minishop.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Test double of {@link BackgroundTasks} for presenter tests (QĐ5): runs {@code work} and the callbacks
 * synchronously on the calling thread, so a presenter can be tested with plain JUnit and no JavaFX toolkit.
 *
 * <p>It follows the same contract as {@link FxBackgroundTasks}: a failure without {@code onFailure}, or an
 * exception thrown by a callback, is not rethrown but recorded in {@link #unhandled()} (the real implementation hands
 * it to the uncaught handler). A cancelled handle never calls a callback. Because everything is synchronous,
 * {@code cancel()} can only matter for a handle that is cancelled from inside {@code work} itself.
 */
public final class DirectBackgroundTasks implements BackgroundTasks {

    private final List<Throwable> unhandled = new ArrayList<>();
    private int submitted;

    @Override
    public <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess, Consumer<? super Throwable> onFailure) {
        submitted++;
        DirectHandle handle = new DirectHandle();
        T result;
        try {
            result = work.call();
        } catch (Exception e) {
            if (!handle.isCancelled()) {
                deliverFailure(e, onFailure);
            }
            return handle;
        }
        if (!handle.isCancelled() && onSuccess != null) {
            try {
                onSuccess.accept(result);
            } catch (RuntimeException e) {
                unhandled.add(e);
            }
        }
        return handle;
    }

    private void deliverFailure(Throwable failure, Consumer<? super Throwable> onFailure) {
        if (onFailure == null) {
            unhandled.add(failure);
            return;
        }
        try {
            onFailure.accept(failure);
        } catch (RuntimeException e) {
            unhandled.add(e);
        }
    }

    /** Errors that the real implementation would send to the uncaught handler, in order. */
    public List<Throwable> unhandled() {
        return List.copyOf(unhandled);
    }

    /** How many tasks were submitted (to assert "the service was not called twice"). */
    public int submittedCount() {
        return submitted;
    }

    private static final class DirectHandle implements Handle {

        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}

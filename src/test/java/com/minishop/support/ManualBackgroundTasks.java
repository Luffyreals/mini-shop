package com.minishop.support;

import com.minishop.desktop.BackgroundTasks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * A {@link BackgroundTasks} for presenter tests that need to see the state <b>while</b> the work is pending (a busy
 * flag, a stale result arriving late). Nothing runs on {@code submit}; the test decides when each task runs with
 * {@link #run(int)} or {@link #runAll()}, on the calling thread, so no JavaFX toolkit and no real thread is involved.
 *
 * <p>Like the real {@code FxBackgroundTasks}, a cancelled handle never delivers its callbacks, unless the instance
 * was built with {@link #ignoringCancel()}: that variant stands in for the small window in which a task has already
 * been queued for delivery when {@code cancel()} is called, so a test can check the second line of defence of a
 * presenter (a generation counter) on its own.
 */
public final class ManualBackgroundTasks implements BackgroundTasks {

    private final boolean honorCancel;
    private final List<Task<?>> tasks = new ArrayList<>();

    private ManualBackgroundTasks(boolean honorCancel) {
        this.honorCancel = honorCancel;
    }

    /** Cancelled tasks never deliver, like the real implementation. */
    public static ManualBackgroundTasks honoringCancel() {
        return new ManualBackgroundTasks(true);
    }

    /** Cancelled tasks still deliver (the window between "queued for delivery" and "cancelled"). */
    public static ManualBackgroundTasks ignoringCancel() {
        return new ManualBackgroundTasks(false);
    }

    @Override
    public <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess, Consumer<? super Throwable> onFailure) {
        Task<T> task = new Task<>(work, onSuccess, onFailure);
        tasks.add(task);
        return task.handle;
    }

    /** How many tasks were submitted so far, run or not. */
    public int submittedCount() {
        return tasks.size();
    }

    /** How many submitted tasks have not run yet. */
    public int pendingCount() {
        return (int) tasks.stream().filter(task -> !task.done).count();
    }

    /** The handle returned for the {@code index}-th submitted task (0-based). */
    public Handle handle(int index) {
        return tasks.get(index).handle;
    }

    /**
     * Runs the {@code index}-th submitted task (0-based): calls its work, then {@code onSuccess} or
     * {@code onFailure}. A task runs at most once.
     */
    public void run(int index) {
        tasks.get(index).run(honorCancel);
    }

    /** Runs every task that has not run yet, in submission order. */
    public void runAll() {
        for (int i = 0; i < tasks.size(); i++) {
            if (!tasks.get(i).done) {
                run(i);
            }
        }
    }

    private static final class Task<T> {

        private final Callable<T> work;
        private final Consumer<? super T> onSuccess;
        private final Consumer<? super Throwable> onFailure;
        private final CancelHandle handle = new CancelHandle();
        private boolean done;

        private Task(Callable<T> work, Consumer<? super T> onSuccess, Consumer<? super Throwable> onFailure) {
            this.work = work;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }

        private void run(boolean honorCancel) {
            if (done) {
                throw new IllegalStateException("The task already ran");
            }
            done = true;
            T result;
            try {
                result = work.call();
            } catch (Exception e) {
                if (onFailure != null && !(honorCancel && handle.isCancelled())) {
                    onFailure.accept(e);
                }
                return;
            }
            if (onSuccess != null && !(honorCancel && handle.isCancelled())) {
                onSuccess.accept(result);
            }
        }
    }

    private static final class CancelHandle implements Handle {

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

package com.minishop.desktop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The test double of {@link BackgroundTasks} must follow the contract the presenters rely on. */
class DirectBackgroundTasksTest {

    private final DirectBackgroundTasks tasks = new DirectBackgroundTasks();

    @Test
    void submit_runsWorkThenOnSuccessOnTheCallingThread() {
        List<String> order = new ArrayList<>();
        AtomicReference<Thread> workThread = new AtomicReference<>();

        tasks.submit(() -> {
            order.add("work");
            workThread.set(Thread.currentThread());
            return 42;
        }, result -> order.add("success " + result), failure -> order.add("failure"));

        assertThat(order).containsExactly("work", "success 42");
        assertThat(workThread.get()).isSameAs(Thread.currentThread());
        assertThat(tasks.submittedCount()).isEqualTo(1);
        assertThat(tasks.unhandled()).isEmpty();
    }

    @Test
    void submit_whenWorkThrows_callsOnFailureOnly() {
        List<Throwable> failures = new ArrayList<>();
        List<Object> successes = new ArrayList<>();
        IllegalStateException boom = new IllegalStateException("boom");

        tasks.submit(() -> {
            throw boom;
        }, successes::add, failures::add);

        assertThat(failures).containsExactly(boom);
        assertThat(successes).isEmpty();
        assertThat(tasks.unhandled()).isEmpty();
    }

    @Test
    void submit_whenWorkThrowsWithoutOnFailure_recordsItAsUnhandled() {
        IllegalStateException boom = new IllegalStateException("boom");

        tasks.submit(() -> {
            throw boom;
        }, result -> { });

        assertThat(tasks.unhandled()).containsExactly(boom);
    }

    @Test
    void submit_whenACallbackThrows_recordsItAsUnhandledAndDoesNotRethrow() {
        IllegalStateException fromSuccess = new IllegalStateException("success callback");
        IllegalStateException fromFailure = new IllegalStateException("failure callback");

        tasks.submit(() -> 1, result -> {
            throw fromSuccess;
        }, null);
        tasks.submit(() -> {
            throw new RuntimeException("work");
        }, null, failure -> {
            throw fromFailure;
        });

        assertThat(tasks.unhandled()).containsExactly(fromSuccess, fromFailure);
    }

    @Test
    void handle_cancelIsIdempotentAndVisible() {
        BackgroundTasks.Handle handle = tasks.submit(() -> "done", result -> { });

        assertThat(handle.isCancelled()).isFalse();
        handle.cancel();
        handle.cancel();

        assertThat(handle.isCancelled()).isTrue();
    }
}

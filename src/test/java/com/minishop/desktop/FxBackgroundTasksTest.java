package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link FxBackgroundTasks} on the real JavaFX toolkit (QĐ5, QĐ11): work runs on a named virtual thread, callbacks
 * on the FX thread, an unhandled failure reaches the uncaught handler, {@code cancel()} suppresses callbacks and
 * {@code close()} stops the executor.
 */
@Tag("ui")
class FxBackgroundTasksTest {

    private FxBackgroundTasks tasks;
    private Thread.UncaughtExceptionHandler previousHandler;
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    @AfterAll
    static void closeWindows() {
        FxTestSupport.hideAllWindows();
    }

    @BeforeEach
    void setUp() {
        tasks = new FxBackgroundTasks();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
    }

    @AfterEach
    void tearDown() {
        tasks.close();
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    @Test
    void submit_runsWorkOffTheFxThreadOnANamedVirtualThread() {
        AtomicReference<Thread> workThread = new AtomicReference<>();
        AtomicBoolean workOnFxThread = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        tasks.submit(() -> {
            workThread.set(Thread.currentThread());
            workOnFxThread.set(Platform.isFxApplicationThread());
            return "ok";
        }, result -> done.countDown(), error -> done.countDown());
        FxTestSupport.await("the work to finish", done);

        assertThat(workOnFxThread).isFalse();
        assertThat(workThread.get().isVirtual()).isTrue();
        assertThat(workThread.get().getName()).startsWith(FxBackgroundTasks.THREAD_NAME_PREFIX);
    }

    @Test
    void submit_whenWorkSucceeds_deliversTheResultOnTheFxThread() {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicBoolean onFxThread = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        tasks.submit(() -> "kết quả", result -> {
            received.set(result);
            onFxThread.set(Platform.isFxApplicationThread());
            done.countDown();
        }, error -> done.countDown());
        FxTestSupport.await("onSuccess", done);

        assertThat(received.get()).isEqualTo("kết quả");
        assertThat(onFxThread).isTrue();
        assertThat(uncaught).isEmpty();
    }

    @Test
    void submit_whenWorkFails_deliversTheErrorToOnFailureOnTheFxThread() {
        IllegalStateException failure = new IllegalStateException("boom");
        AtomicReference<Throwable> received = new AtomicReference<>();
        AtomicBoolean onFxThread = new AtomicBoolean();
        AtomicBoolean successCalled = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        tasks.submit(() -> {
            throw failure;
        }, result -> successCalled.set(true), error -> {
            received.set(error);
            onFxThread.set(Platform.isFxApplicationThread());
            done.countDown();
        });
        FxTestSupport.await("onFailure", done);

        assertThat(received.get()).isSameAs(failure);
        assertThat(onFxThread).isTrue();
        assertThat(successCalled).isFalse();
        assertThat(uncaught).isEmpty();
    }

    @Test
    void submit_whenWorkFailsWithoutOnFailure_goesToTheUncaughtHandler() {
        IllegalStateException failure = new IllegalStateException("no handler for me");

        tasks.submit(() -> {
            throw failure;
        }, result -> { }, null);
        FxTestSupport.await("the uncaught handler", () -> !uncaught.isEmpty());

        assertThat(uncaught).containsExactly(failure);
    }

    @Test
    void submit_whenOnSuccessThrows_goesToTheUncaughtHandler() {
        IllegalArgumentException failure = new IllegalArgumentException("callback bug");

        tasks.submit(() -> "x", result -> {
            throw failure;
        }, error -> { });
        FxTestSupport.await("the uncaught handler", () -> !uncaught.isEmpty());

        assertThat(uncaught).containsExactly(failure);
    }

    @Test
    void submit_whenOnFailureThrows_goesToTheUncaughtHandler() {
        IllegalArgumentException failure = new IllegalArgumentException("callback bug");

        tasks.submit(() -> {
            throw new IllegalStateException("work failed");
        }, result -> { }, error -> {
            throw failure;
        });
        FxTestSupport.await("the uncaught handler", () -> !uncaught.isEmpty());

        assertThat(uncaught).containsExactly(failure);
    }

    @Test
    void submit_withNullCallbacks_stillCompletesWithoutError() throws Exception {
        CountDownLatch worked = new CountDownLatch(1);

        tasks.submit(() -> {
            worked.countDown();
            return "x";
        }, null, null);
        FxTestSupport.await("the work", worked);
        Thread.sleep(200);
        FxTestSupport.flushFx();

        assertThat(uncaught).isEmpty();
    }

    @Test
    void submit_withNullWork_isRejected() {
        assertThatNullPointerException().isThrownBy(() -> tasks.submit(null, result -> { }, error -> { }));
    }

    @Test
    void cancel_beforeWorkEnds_suppressesBothCallbacks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workFinished = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean();

        BackgroundTasks.Handle succeeding = tasks.submit(() -> {
            started.countDown();
            release.await();
            workFinished.countDown();
            return "late";
        }, result -> callbackCalled.set(true), error -> callbackCalled.set(true));
        FxTestSupport.await("the work to start", started);
        succeeding.cancel();
        release.countDown();
        FxTestSupport.await("the cancelled work to end", workFinished);

        CountDownLatch started2 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        CountDownLatch workFinished2 = new CountDownLatch(1);
        BackgroundTasks.Handle failing = tasks.submit(() -> {
            started2.countDown();
            release2.await();
            workFinished2.countDown();
            throw new IllegalStateException("late failure");
        }, result -> callbackCalled.set(true), error -> callbackCalled.set(true));
        FxTestSupport.await("the second work to start", started2);
        failing.cancel();
        release2.countDown();
        FxTestSupport.await("the second cancelled work to end", workFinished2);

        // The Task reports its end through Platform.runLater a moment after call() returns; give it that moment,
        // then drain the FX queue. A callback that wrongly fired would have run by now.
        Thread.sleep(300);
        FxTestSupport.flushFx();

        assertThat(succeeding.isCancelled()).isTrue();
        assertThat(failing.isCancelled()).isTrue();
        assertThat(callbackCalled).isFalse();
        assertThat(uncaught).isEmpty();
    }

    @Test
    void cancel_whenWorkFailsWithoutOnFailure_doesNotReachTheUncaughtHandler() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workFinished = new CountDownLatch(1);

        BackgroundTasks.Handle handle = tasks.submit(() -> {
            started.countDown();
            release.await();
            workFinished.countDown();
            throw new IllegalStateException("late failure");
        }, result -> { }, null);
        FxTestSupport.await("the work to start", started);
        handle.cancel();
        release.countDown();
        FxTestSupport.await("the work to end", workFinished);
        Thread.sleep(300);
        FxTestSupport.flushFx();

        assertThat(uncaught).isEmpty();
    }

    @Test
    void handle_isNotCancelledUntilCancelIsCalled() {
        CountDownLatch done = new CountDownLatch(1);

        BackgroundTasks.Handle handle = tasks.submit(() -> "x", result -> done.countDown(), error -> done.countDown());
        FxTestSupport.await("onSuccess", done);

        assertThat(handle.isCancelled()).isFalse();
    }

    @Test
    void close_waitsForRunningWorkAndThenRejectsNewWork() {
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);

        tasks.submit(() -> {
            started.countDown();
            Thread.sleep(200);
            finished.set(true);
            return "x";
        }, null, null);
        FxTestSupport.await("the work to start", started);
        tasks.close();

        assertThat(finished).as("close waits for work that ends in time").isTrue();
        assertThatIllegalStateException()
                .isThrownBy(() -> tasks.submit(() -> "x", result -> { }, error -> { }));
    }

    @Test
    void close_interruptsWorkThatDoesNotEnd() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        tasks.submit(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return "x";
        }, null, error -> { });
        FxTestSupport.await("the work to start", started);

        long begin = System.nanoTime();
        tasks.close();
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        FxTestSupport.await("the stuck work to be interrupted", interrupted);
        assertThat(waitedMillis).as("close gives work a few seconds, not forever").isLessThan(10_000);
    }
}

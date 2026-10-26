package com.minishop.desktop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.minishop.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AC-009-12: one ERROR event per unexpected error, never the SQL message of H2 (it carries whole rows), and one
 * dialog at a time. The dialog itself is a fake sink here; the real one is {@link ErrorDialog}.
 */
class ErrorReporterTest {

    private static final String SECRET = "Row ('a@b.c', '{bcrypt}$2a$10$secret')";

    private final List<ErrorReporter.DialogRequest> requests = new ArrayList<>();
    private final Logger logger = LogCapture.newLogger("error-reporter-test");

    private ErrorReporter reporter() {
        return new ErrorReporter(requests::add, logger);
    }

    @Test
    void report_writesExactlyOneErrorEventWithTheThreadName() {
        try (LogCapture capture = LogCapture.of(logger)) {
            reporter().report("minishop-bg-3", new IllegalStateException("boom"));

            assertThat(capture.events()).hasSize(1);
            ILoggingEvent event = capture.events().getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("minishop-bg-3");
        }
    }

    @Test
    void report_withoutSqlException_logsTheOriginalThrowable() {
        try (LogCapture capture = LogCapture.of(logger)) {
            IllegalStateException failure = new IllegalStateException("boom");

            reporter().report("main", failure);

            assertThat(((ThrowableProxy) capture.events().getFirst().getThrowableProxy()).getThrowable())
                    .isSameAs(failure);
        }
    }

    @Test
    void report_withSqlException_logsTheThrowableWithoutTheDatabaseMessage() {
        try (LogCapture capture = LogCapture.of(logger)) {
            SQLException sql = new SQLException("Unique index violation: " + SECRET, "23505", 23505);

            reporter().report("main", new RuntimeException("save failed " + SECRET, sql));

            ILoggingEvent event = capture.events().getFirst();
            assertThat(event.getFormattedMessage()).doesNotContain("a@b.c", "bcrypt");
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertThat(proxy.getMessage()).doesNotContain("a@b.c", "bcrypt", "Unique index");
            }
            assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("java.sql.SQLException");
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        }
    }

    @Test
    void report_requestsTheDialogWithTheGenericMessage() {
        ErrorReporter reporter = reporter();

        reporter.report("main", new RuntimeException("boom"));

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().message()).isEqualTo("Đã có lỗi xảy ra. Vui lòng thử lại.");
        assertThat(reporter.isDialogOpen()).isTrue();
    }

    @Test
    void report_threeErrorsInARow_requestOneDialogButLogThree() {
        try (LogCapture capture = LogCapture.of(logger)) {
            ErrorReporter reporter = reporter();

            reporter.report("main", new RuntimeException("1"));
            reporter.report("main", new RuntimeException("2"));
            reporter.report("main", new RuntimeException("3"));

            assertThat(requests).hasSize(1);
            assertThat(capture.events(Level.ERROR)).hasSize(3);
        }
    }

    @Test
    void report_afterTheDialogIsClosed_requestsANewDialog() {
        ErrorReporter reporter = reporter();
        reporter.report("main", new RuntimeException("1"));

        requests.getFirst().onClosed().run();

        assertThat(reporter.isDialogOpen()).isFalse();
        reporter.report("main", new RuntimeException("2"));
        assertThat(requests).hasSize(2);
        assertThat(reporter.isDialogOpen()).isTrue();
    }

    @Test
    void report_whenTheSinkThrows_resetsTheFlagLogsAWarnAndDoesNotRethrow() {
        try (LogCapture capture = LogCapture.of(logger)) {
            AtomicInteger calls = new AtomicInteger();
            Consumer<ErrorReporter.DialogRequest> failingSink = request -> {
                calls.incrementAndGet();
                throw new IllegalStateException("Toolkit not initialized");
            };
            ErrorReporter reporter = new ErrorReporter(failingSink, logger);

            assertThatCode(() -> reporter.report("main", new RuntimeException("boom"))).doesNotThrowAnyException();

            assertThat(reporter.isDialogOpen()).isFalse();
            assertThat(capture.events(Level.ERROR)).hasSize(1);
            assertThat(capture.events(Level.WARN)).hasSize(1);
            reporter.report("main", new RuntimeException("again"));
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void report_withNullError_doesNothing() {
        try (LogCapture capture = LogCapture.of(logger)) {
            ErrorReporter reporter = reporter();

            reporter.report("main", null);

            assertThat(capture.events()).isEmpty();
            assertThat(requests).isEmpty();
            assertThat(reporter.isDialogOpen()).isFalse();
        }
    }

    @Test
    void report_fromManyThreadsAtOnce_requestsExactlyOneDialog() throws Exception {
        AtomicInteger sinkCalls = new AtomicInteger();
        ErrorReporter reporter = new ErrorReporter(request -> sinkCalls.incrementAndGet(), logger);
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads); LogCapture ignored = LogCapture.of(logger)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    reporter.report("worker", new RuntimeException("boom"));
                    return null;
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(sinkCalls).hasValue(1);
    }

    @Test
    void constructor_withNullSink_isRejected() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ErrorReporter(null, logger));
    }
}

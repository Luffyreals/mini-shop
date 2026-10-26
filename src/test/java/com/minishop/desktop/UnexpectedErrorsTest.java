package com.minishop.desktop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.minishop.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** AC-009-12: the single door for an unexpected error goes through the app's uncaught-exception handler. */
class UnexpectedErrorsTest {

    private static final String SECRET = "Map entry with value ('a@b.c', '{bcrypt}hash')";

    @Test
    void report_withAnError_forwardsTheSameThrowableAndTheCurrentThreadToTheHandler() {
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        UnexpectedErrors unexpectedErrors = new UnexpectedErrors(() -> (thread, error) -> {
            threads.add(thread);
            errors.add(error);
        });
        IllegalStateException error = new IllegalStateException("boom");

        unexpectedErrors.report(error);

        assertThat(threads).containsExactly(Thread.currentThread());
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).isSameAs(error);
    }

    @Test
    void report_withNull_doesNothingAndNeverAsksForTheHandler() {
        List<Throwable> errors = new ArrayList<>();
        int[] asked = {0};
        UnexpectedErrors unexpectedErrors = new UnexpectedErrors(() -> {
            asked[0]++;
            return (thread, error) -> errors.add(error);
        });

        try (LogCapture capture = LogCapture.of(LoggerFactory.getLogger(UnexpectedErrors.class))) {
            assertThatCode(() -> unexpectedErrors.report(null)).doesNotThrowAnyException();

            assertThat(capture.events()).isEmpty();
        }
        assertThat(errors).isEmpty();
        assertThat(asked[0]).isZero();
    }

    @Test
    void report_withoutAHandler_logsExactlyOneRedactedErrorAndDoesNotThrow() {
        UnexpectedErrors unexpectedErrors = new UnexpectedErrors(() -> null);
        RuntimeException error = new RuntimeException("wrapper " + SECRET, new SQLException(SECRET));

        try (LogCapture capture = LogCapture.of(LoggerFactory.getLogger(UnexpectedErrors.class))) {
            assertThatCode(() -> unexpectedErrors.report(error)).doesNotThrowAnyException();

            assertThat(capture.events()).hasSize(1);
            ILoggingEvent event = capture.events().getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).doesNotContain("a@b.c").doesNotContain("bcrypt");
            assertThat(event.getThrowableProxy()).isNotNull();
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertThat(proxy.getMessage()).doesNotContain("a@b.c").doesNotContain("bcrypt");
            }
        }
    }
}

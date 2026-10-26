package com.minishop.desktop;

import com.minishop.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** QĐ6: every thread that dies from an exception ends up in {@link ErrorReporter}, with its name. */
class UncaughtErrorHandlerTest {

    private final List<ErrorReporter.DialogRequest> requests = new ArrayList<>();
    private final Logger logger = LogCapture.newLogger("uncaught-handler-test");
    private final UncaughtErrorHandler handler = new UncaughtErrorHandler(new ErrorReporter(requests::add, logger));

    @Test
    void uncaughtException_logsOneErrorWithTheThreadNameAndRequestsADialog() {
        try (LogCapture capture = LogCapture.of(logger)) {
            Thread thread = new Thread(() -> { }, "minishop-bg-7");

            handler.uncaughtException(thread, new IllegalStateException("boom"));

            assertThat(capture.events()).hasSize(1);
            assertThat(capture.events().getFirst().getFormattedMessage()).contains("minishop-bg-7");
            assertThat(requests).hasSize(1);
        }
    }

    @Test
    void uncaughtException_withNullThread_usesUnknownAsTheName() {
        try (LogCapture capture = LogCapture.of(logger)) {
            handler.uncaughtException(null, new IllegalStateException("boom"));

            assertThat(capture.events().getFirst().getFormattedMessage()).contains("unknown");
        }
    }

    @Test
    void uncaughtException_withNullError_doesNotThrowAndDoesNothing() {
        try (LogCapture capture = LogCapture.of(logger)) {
            assertThatCode(() -> handler.uncaughtException(Thread.currentThread(), null)).doesNotThrowAnyException();

            assertThat(capture.events()).isEmpty();
            assertThat(requests).isEmpty();
        }
    }
}

package com.minishop.desktop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.minishop.support.LogCapture;
import com.zaxxer.hikari.pool.HikariPool;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.BeanCreationException;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-009-2: only one window, decided by H2's own file lock (error 90020) found somewhere in the startup failure. */
class StartupFailureTest {

    private static final int ALREADY_OPEN = 90020;
    private static final String SECRET = "Row ('a@b.c', '{bcrypt}$2a$10$secret')";

    private static SQLException alreadyOpen() {
        return new SQLException("Database may be already in use: " + SECRET, "90020", ALREADY_OPEN);
    }

    @Test
    void classify_withNull_isOther() {
        assertThat(StartupFailure.classify(null)).isEqualTo(StartupFailure.OTHER);
    }

    @Test
    void classify_withPlainException_isOther() {
        assertThat(StartupFailure.classify(new IllegalStateException("boom"))).isEqualTo(StartupFailure.OTHER);
    }

    @Test
    void classify_withDatabaseAlreadyOpenAtTheTop_isAlreadyRunning() {
        assertThat(StartupFailure.classify(alreadyOpen())).isEqualTo(StartupFailure.ALREADY_RUNNING);
    }

    @Test
    void classify_withCodeThreeLevelsDeep_isAlreadyRunning() {
        Throwable failure = new BeanCreationException("dataSource",
                new IllegalStateException("wrapper", new HikariPool.PoolInitializationException(alreadyOpen())));

        assertThat(StartupFailure.classify(failure)).isEqualTo(StartupFailure.ALREADY_RUNNING);
    }

    @Test
    void classify_withAnotherSqlErrorCode_isOther() {
        SQLException other = new SQLException("Syntax error", "42000", 42000);

        assertThat(StartupFailure.classify(new BeanCreationException("flyway", other)))
                .isEqualTo(StartupFailure.OTHER);
    }

    @Test
    void classify_withCode90020OnANonSqlException_isOther() {
        // only an SQLException carries the H2 code; a message that merely says so proves nothing
        assertThat(StartupFailure.classify(new RuntimeException("Database may be already in use [90020]")))
                .isEqualTo(StartupFailure.OTHER);
    }

    @Test
    void classify_withCodeBeyondTwentyLevels_isOther() {
        Throwable chain = alreadyOpen();
        for (int i = 0; i < 25; i++) {
            chain = new RuntimeException("level " + i, chain);
        }

        assertThat(StartupFailure.classify(chain)).isEqualTo(StartupFailure.OTHER);
    }

    @Test
    void classify_withCodeAtTheLastSearchedLevel_isAlreadyRunning() {
        Throwable chain = alreadyOpen();
        for (int i = 0; i < 19; i++) {
            chain = new RuntimeException("level " + i, chain);
        }

        assertThat(StartupFailure.classify(chain)).isEqualTo(StartupFailure.ALREADY_RUNNING);
    }

    @Test
    void classify_withACauseCycle_terminatesAsOther() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(StartupFailure.classify(a)).isEqualTo(StartupFailure.OTHER);
    }

    @Test
    void classify_withACauseCycleThatReachesTheCode_isAlreadyRunning() {
        SQLException sql = alreadyOpen();
        RuntimeException wrapper = new RuntimeException("wrapper", sql);
        sql.initCause(wrapper);

        assertThat(StartupFailure.classify(wrapper)).isEqualTo(StartupFailure.ALREADY_RUNNING);
    }

    @Test
    void message_alreadyRunning_isTheAc009_2Text() {
        assertThat(StartupFailure.ALREADY_RUNNING.message()).isEqualTo("Mini Shop đang chạy ở một cửa sổ khác.");
    }

    @Test
    void message_other_isTheGenericErrorText() {
        assertThat(StartupFailure.OTHER.message()).isEqualTo("Đã có lỗi xảy ra. Vui lòng thử lại.");
    }

    @Test
    void log_alreadyRunning_writesOneWarnWithoutThrowableOrDatabaseMessage() {
        Logger logger = LogCapture.newLogger("startup-failure-test");
        try (LogCapture capture = LogCapture.of(logger)) {
            StartupFailure.ALREADY_RUNNING.log(logger, new BeanCreationException("dataSource", alreadyOpen()));

            List<ILoggingEvent> events = capture.events();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(events.getFirst().getThrowableProxy()).isNull();
            assertThat(events.getFirst().getFormattedMessage()).doesNotContain("a@b.c", "bcrypt");
        }
    }

    @Test
    void log_other_writesOneErrorWithTheRedactedThrowable() {
        Logger logger = LogCapture.newLogger("startup-failure-test");
        try (LogCapture capture = LogCapture.of(logger)) {
            SQLException sql = new SQLException(SECRET, "23505", 23505);
            StartupFailure.OTHER.log(logger, new BeanCreationException("flyway " + SECRET, sql));

            List<ILoggingEvent> events = capture.events();
            assertThat(events).hasSize(1);
            ILoggingEvent event = events.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage())
                    .isEqualTo("org.springframework.beans.factory.BeanCreationException");
            assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("java.sql.SQLException");
        }
    }

    @Test
    void log_other_withoutSqlException_keepsTheOriginalThrowable() {
        Logger logger = LogCapture.newLogger("startup-failure-test");
        try (LogCapture capture = LogCapture.of(logger)) {
            IllegalStateException failure = new IllegalStateException("plain");
            StartupFailure.OTHER.log(logger, failure);

            assertThat(capture.events()).hasSize(1);
            assertThat(((ThrowableProxy) capture.events().getFirst().getThrowableProxy()).getThrowable())
                    .isSameAs(failure);
        }
    }
}

package com.minishop.common;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013 (kept by ADR-0014): H2 puts whole rows (emails, password hashes) into its error messages, so an
 * exception with an {@link SQLException} in its cause chain is logged only after every message is replaced by the
 * class name. Nothing else is touched.
 */
class SafeExceptionLogTest {

    private static final String SECRET = "Row ('a@b.c', '{bcrypt}$2a$10$secret')";

    @Test
    void withoutDatabaseMessages_withSqlExceptionInChain_replacesEveryMessageByTheClassName() {
        SQLException sql = new SQLException(SECRET, "23505", 23505);
        RuntimeException middle = new IllegalStateException("middle " + SECRET, sql);
        RuntimeException top = new RuntimeException("top " + SECRET, middle);

        Throwable safe = SafeExceptionLog.withoutDatabaseMessages(top);

        assertThat(safe).isNotSameAs(top).isInstanceOf(SafeExceptionLog.RedactedException.class);
        assertThat(safe.getMessage()).isEqualTo("java.lang.RuntimeException");
        assertThat(safe.getCause().getMessage()).isEqualTo("java.lang.IllegalStateException");
        assertThat(safe.getCause().getCause().getMessage()).isEqualTo("java.sql.SQLException");
        assertThat(safe.getCause().getCause().getCause()).isNull();
        assertThat(messages(safe)).noneMatch(message -> message.contains("a@b.c") || message.contains("bcrypt"));
    }

    @Test
    void withoutDatabaseMessages_withSqlExceptionInChain_keepsTheStackFramesOfEveryLevel() {
        SQLException sql = new SQLException(SECRET);
        RuntimeException top = new RuntimeException("top", sql);

        Throwable safe = SafeExceptionLog.withoutDatabaseMessages(top);

        assertThat(safe.getStackTrace()).isNotEmpty().isEqualTo(top.getStackTrace());
        assertThat(safe.getCause().getStackTrace()).isNotEmpty().isEqualTo(sql.getStackTrace());
    }

    @Test
    void withoutDatabaseMessages_whenTheTopExceptionIsTheSqlException_redactsIt() {
        SQLException sql = new SQLException(SECRET);

        Throwable safe = SafeExceptionLog.withoutDatabaseMessages(sql);

        assertThat(safe).isNotSameAs(sql);
        assertThat(safe.getMessage()).isEqualTo("java.sql.SQLException");
    }

    @Test
    void withoutDatabaseMessages_redactsTheCausesBelowTheSqlExceptionToo() {
        Exception below = new IllegalArgumentException("below " + SECRET);
        SQLException sql = new SQLException(SECRET, below);

        Throwable safe = SafeExceptionLog.withoutDatabaseMessages(new RuntimeException("top", sql));

        assertThat(messages(safe)).containsExactly(
                "java.lang.RuntimeException", "java.sql.SQLException", "java.lang.IllegalArgumentException");
    }

    @Test
    void withoutDatabaseMessages_withoutSqlException_returnsTheSameInstance() {
        RuntimeException failure = new RuntimeException("message stays", new IllegalStateException("cause stays"));

        assertThat(SafeExceptionLog.withoutDatabaseMessages(failure)).isSameAs(failure);
    }

    @Test
    void withoutDatabaseMessages_withNull_returnsNull() {
        assertThat(SafeExceptionLog.withoutDatabaseMessages(null)).isNull();
    }

    @Test
    void withoutDatabaseMessages_withSqlExceptionBeyondTwentyLevels_leavesTheExceptionAlone() {
        Throwable chain = new SQLException(SECRET);
        for (int i = 0; i < 60; i++) {
            chain = new RuntimeException("level " + i, chain);
        }

        assertThat(SafeExceptionLog.withoutDatabaseMessages(chain)).isSameAs(chain);
    }

    @Test
    void withoutDatabaseMessages_withSqlExceptionAndAVeryLongChain_terminatesAndCapsTheCopy() {
        Throwable longChain = new RuntimeException("end");
        for (int i = 0; i < 60; i++) {
            longChain = new RuntimeException("level " + i, longChain);
        }
        Throwable top = new RuntimeException("top", new SQLException(SECRET, longChain));

        Throwable safe = SafeExceptionLog.withoutDatabaseMessages(top);

        assertThat(safe).isInstanceOf(SafeExceptionLog.RedactedException.class);
        assertThat(messages(safe)).hasSizeLessThanOrEqualTo(21).allMatch(message -> message.startsWith("java."));
    }

    @Test
    void withoutDatabaseMessages_withACauseCycleThroughTheSqlException_terminates() {
        SQLException sql = new SQLException(SECRET);
        RuntimeException other = new RuntimeException("other " + SECRET, sql);
        sql.initCause(other);

        Throwable safe = SafeExceptionLog.withoutDatabaseMessages(other);

        assertThat(safe).isInstanceOf(SafeExceptionLog.RedactedException.class);
        assertThat(messages(safe)).hasSizeLessThanOrEqualTo(21).noneMatch(message -> message.contains("a@b.c"));
    }

    @Test
    void withoutDatabaseMessages_withACauseCycleWithoutSqlException_returnsTheSameInstanceAndTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(SafeExceptionLog.withoutDatabaseMessages(a)).isSameAs(a);
    }

    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 100; depth++) {
            messages.add(current.getMessage());
            current = current.getCause();
        }
        return messages;
    }
}

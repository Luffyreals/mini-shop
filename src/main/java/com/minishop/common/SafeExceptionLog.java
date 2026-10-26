package com.minishop.common;

import java.sql.SQLException;

/**
 * Makes an exception safe to log (ADR-0013). H2 writes whole rows into lock-timeout and constraint messages
 * ({@code Map entry ... with key <3> and value (..., 'a@b.c', '{bcrypt}...')}), which would put personal data and
 * password hashes into the log.
 *
 * <p>Logging code passes the exception through {@link #withoutDatabaseMessages} and logs the result, never the
 * original. Not a bean: only static methods.
 */
public final class SafeExceptionLog {

    private static final int MAX_CAUSE_DEPTH = 20;

    private SafeExceptionLog() {
    }

    /**
     * For an exception whose cause chain (at most 20 levels) contains an {@link SQLException}: the same cause chain
     * and stack frames with every message replaced by the class name. Any other exception is returned unchanged
     * (the same instance). {@code null} gives {@code null}.
     */
    public static Throwable withoutDatabaseMessages(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException) {
                return redacted(ex, 0);
            }
            current = current.getCause();
        }
        return ex;
    }

    private static Throwable redacted(Throwable original, int depth) {
        Throwable cause = original.getCause();
        Throwable redactedCause = cause != null && cause != original && depth < MAX_CAUSE_DEPTH
                ? redacted(cause, depth + 1) : null;
        RedactedException copy = new RedactedException(original.getClass().getName(), redactedCause);
        copy.setStackTrace(original.getStackTrace());
        return copy;
    }

    /** Stands in for a logged exception; its message is the original class name only. */
    public static final class RedactedException extends RuntimeException {

        RedactedException(String originalClassName, Throwable cause) {
            super(originalClassName, cause, false, true);
        }
    }
}

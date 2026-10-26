package com.minishop.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collects what one logger writes while a test runs (Logback {@link ListAppender}). Use it in try-with-resources:
 * it detaches the appender and restores the logger on close, so no other test sees the changes. The logger is not
 * forwarded to its parents while captured, so a test never fills the console.
 */
public final class LogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;
    private final boolean previousAdditive;

    private LogCapture(ch.qos.logback.classic.Logger logger) {
        this.logger = logger;
        this.previousLevel = logger.getLevel();
        this.previousAdditive = logger.isAdditive();
        logger.setLevel(Level.ALL);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
    }

    /** Captures this logger, for example {@code LoggerFactory.getLogger(SomeClass.class)} or {@link #newLogger}. */
    public static LogCapture of(Logger logger) {
        return new LogCapture((ch.qos.logback.classic.Logger) logger);
    }

    /** A logger with a name that no other test uses, to hand to code that takes a {@link Logger}. */
    public static Logger newLogger(String namePrefix) {
        return LoggerFactory.getLogger(namePrefix + "-" + System.nanoTime());
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<ILoggingEvent> events(Level level) {
        return events().stream().filter(event -> event.getLevel().equals(level)).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }
}

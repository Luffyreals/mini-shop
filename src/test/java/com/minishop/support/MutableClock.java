package com.minishop.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that a test moves by hand, so "15 minutes later" costs nothing (phase 8 plan, QĐ10: integration tests never
 * wait for a real window). Not thread safe on purpose: tests move it from the test thread only.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock() {
        this(Instant.parse("2026-10-25T14:00:00Z"));
    }

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}

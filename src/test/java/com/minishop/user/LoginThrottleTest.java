package com.minishop.user;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.minishop.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failed-login limits (BR-008-3, AC-008-7..10): 5 attempts per email and 20 per client IP in a sliding 15 minute
 * window, through the one atomic gate {@link LoginThrottle#tryAcquire}. Plain unit test with a hand-written mutable
 * clock, so "15 minutes later" costs nothing.
 * <p>"A failure" in these tests is an attempt that was let through and whose password was wrong: the gate has already
 * counted it, so the test just calls {@code tryAcquire} and keeps nothing. "A success" is an attempt whose
 * {@link LoginThrottle.Attempt} is given back through {@code recordSuccess}.
 */
class LoginThrottleTest {

    private static final String EMAIL = "user@x.vn";
    private static final String IP = "198.51.100.7";

    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(clock);

    private final Logger throttleLogger = (Logger) LoggerFactory.getLogger(LoginThrottle.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        originalLevel = throttleLogger.getLevel();
        throttleLogger.setLevel(Level.WARN);
        appender.start();
        throttleLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        throttleLogger.detachAppender(appender);
        appender.stop();
        throttleLogger.setLevel(originalLevel);
    }

    /** One attempt that must be let through (asserts it, so a surprise refusal fails the test where it happens). */
    private LoginThrottle.Attempt pass(String email, String ip) {
        Optional<LoginThrottle.Attempt> attempt = throttle.tryAcquire(email, ip);
        assertThat(attempt).as("the attempt is let through").isPresent();
        return attempt.get();
    }

    private void fail(String email, String ip, int times) {
        for (int i = 0; i < times; i++) {
            pass(email, ip);
        }
    }

    /** Attempts of the same email, each from another IP so the IP limit never interferes. */
    private void failEmail(String email, int times) {
        for (int i = 0; i < times; i++) {
            pass(email, "203.0.113." + (i + 1));
        }
    }

    /** Attempts from the same IP, each with another email so the email limit never interferes. */
    private void failIp(String ip, int times) {
        for (int i = 0; i < times; i++) {
            pass("many" + i + "@x.vn", ip);
        }
    }

    // ------------------------------------------------------------------ constants (AC-008-10: no config keys)

    @Test
    void limits_areTheSpecifiedConstants() {
        assertThat(LoginThrottle.MAX_FAILURES_PER_EMAIL).isEqualTo(5);
        assertThat(LoginThrottle.MAX_FAILURES_PER_IP).isEqualTo(20);
        assertThat(LoginThrottle.WINDOW).isEqualTo(Duration.ofMinutes(15));
        assertThat(LoginThrottle.MAX_TRACKED_KEYS).isEqualTo(10_000);
    }

    // ------------------------------------------------------------------ email limit (AC-008-7)

    @Test
    void isBlocked_whenNothingRecorded_isFalseAndCreatesNoEntry() {
        assertThat(throttle.isBlocked(EMAIL, IP)).isFalse();
        assertThat(throttle.isBlocked("nobody@x.vn", null)).isFalse();

        assertThat(throttle.trackedEmailKeys()).isZero();
        assertThat(throttle.trackedIpKeys()).isZero();
    }

    @Test
    void tryAcquire_whenNothingIsBlocked_letsTheAttemptThroughAndCountsItOnBothKeys() {
        Optional<LoginThrottle.Attempt> attempt = throttle.tryAcquire(EMAIL, IP);

        assertThat(attempt).isPresent();
        assertThat(throttle.trackedEmailKeys()).isOne();
        assertThat(throttle.trackedIpKeys()).isOne();
        assertThat(throttle.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    void tryAcquire_fourTimes_doesNotBlockTheEmail() {
        failEmail(EMAIL, 4);

        assertThat(throttle.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    void tryAcquire_fifthTime_isStillLetThroughButBlocksFromThatMoment() {
        failEmail(EMAIL, 4);

        Optional<LoginThrottle.Attempt> fifth = throttle.tryAcquire(EMAIL, "203.0.113.5");

        assertThat(fifth).as("the 5th attempt still gets its password checked").isPresent();
        assertThat(throttle.isBlocked(EMAIL, "192.0.2.99")).as("blocked at once").isTrue();
        assertThat(throttle.tryAcquire(EMAIL, "203.0.113.6")).as("the 6th is refused").isEmpty();
    }

    @Test
    void tryAcquire_whenEmailBlocked_isRefusedFromAnyIpAndOtherEmailsAreNotAffected() {
        failEmail(EMAIL, 5);

        assertThat(throttle.tryAcquire(EMAIL, "192.0.2.99")).isEmpty();
        assertThat(throttle.tryAcquire(EMAIL, null)).isEmpty();
        assertThat(throttle.tryAcquire("other@x.vn", "192.0.2.99")).as("another email is not affected").isPresent();
    }

    @Test
    void block_lastsExactlyFifteenMinutes() {
        failEmail(EMAIL, 5);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(throttle.isBlocked(EMAIL, IP)).as("at 14:59").isTrue();
        assertThat(throttle.tryAcquire(EMAIL, IP)).as("at 14:59").isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.isBlocked(EMAIL, IP)).as("at 15:00").isFalse();
        assertThat(throttle.tryAcquire(EMAIL, IP)).as("at 15:00").isPresent();
    }

    @Test
    void afterBlockExpires_countingRestartsFromZero() {
        failEmail(EMAIL, 5);
        clock.advance(LoginThrottle.WINDOW);

        failEmail(EMAIL, 4);
        assertThat(throttle.isBlocked(EMAIL, IP)).as("4 new attempts are not enough").isFalse();

        fail(EMAIL, "203.0.113.50", 1);
        assertThat(throttle.isBlocked(EMAIL, IP)).as("the 5th new attempt blocks again").isTrue();
    }

    @Test
    void tryAcquire_whileBlocked_countsNothingAndDoesNotExtendTheBlock() {
        failEmail(EMAIL, 5);
        clock.advance(Duration.ofMinutes(10));

        for (int i = 0; i < 10; i++) {
            assertThat(throttle.tryAcquire(EMAIL, "203.0.113." + (100 + i))).isEmpty();
        }

        clock.advance(Duration.ofMinutes(5));
        assertThat(throttle.isBlocked(EMAIL, IP)).as("15 minutes after the first block: over").isFalse();
        failEmail(EMAIL, 4);
        assertThat(throttle.isBlocked(EMAIL, IP)).as("refused attempts left nothing behind").isFalse();
    }

    @Test
    void tryAcquire_whenEmailBlocked_createsNoIpKey() {
        failEmail(EMAIL, 5);
        int ipKeys = throttle.trackedIpKeys();

        for (int i = 0; i < 30; i++) {
            assertThat(throttle.tryAcquire(EMAIL, "192.0.2." + i)).isEmpty();
        }

        assertThat(throttle.trackedIpKeys()).as("a refusal creates nothing").isEqualTo(ipKeys);
    }

    @Test
    void slidingWindow_fourAttemptsThenFifthSixteenMinutesLater_doesNotBlock() {
        failEmail(EMAIL, 4);
        clock.advance(Duration.ofMinutes(16));

        fail(EMAIL, "203.0.113.60", 1);

        assertThat(throttle.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    void slidingWindow_attemptExactlyFifteenMinutesOldNoLongerCounts() {
        failEmail(EMAIL, 4);
        clock.advance(Duration.ofMinutes(15));

        fail(EMAIL, "203.0.113.60", 1);

        assertThat(throttle.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    void slidingWindow_fifthAttemptJustInsideTheWindow_blocks() {
        failEmail(EMAIL, 4);
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));

        fail(EMAIL, "203.0.113.60", 1);

        assertThat(throttle.isBlocked(EMAIL, IP)).isTrue();
    }

    @Test
    void slidingWindow_attemptsSpreadOverTime_countOnlyTheRecentOnes() {
        // minutes 0, 5, 10: three attempts; minutes 16 and 17 push the first one out, so only 4 are inside the window
        fail(EMAIL, "203.0.113.1", 1);
        clock.advance(Duration.ofMinutes(5));
        fail(EMAIL, "203.0.113.2", 1);
        clock.advance(Duration.ofMinutes(5));
        fail(EMAIL, "203.0.113.3", 1);
        clock.advance(Duration.ofMinutes(6));
        fail(EMAIL, "203.0.113.4", 1);
        clock.advance(Duration.ofMinutes(1));
        fail(EMAIL, "203.0.113.5", 1);

        assertThat(throttle.isBlocked(EMAIL, IP)).isFalse();

        fail(EMAIL, "203.0.113.6", 1);
        assertThat(throttle.isBlocked(EMAIL, IP)).as("now 5 inside the last 15 minutes").isTrue();
    }

    // ------------------------------------------------------------------ success resets the email (AC-008-9)

    @Test
    void recordSuccess_afterTwoFailures_resetsTheEmailCounter() {
        failEmail(EMAIL, 2);
        LoginThrottle.Attempt third = pass(EMAIL, "203.0.113.3");

        throttle.recordSuccess(third);

        failEmail(EMAIL, 4);
        assertThat(throttle.isBlocked(EMAIL, IP)).as("4 attempts after the success are fine").isFalse();
        fail(EMAIL, "203.0.113.70", 1);
        assertThat(throttle.isBlocked(EMAIL, IP)).as("the 5th attempt after the success blocks").isTrue();
    }

    @Test
    void recordSuccess_whenTheSuccessIsTheFifthAttempt_alsoLiftsTheEmailBlock() {
        failEmail(EMAIL, 4);
        LoginThrottle.Attempt fifth = pass(EMAIL, "203.0.113.5"); // blocks the email, password turns out right
        assertThat(throttle.isBlocked(EMAIL, null)).isTrue();

        throttle.recordSuccess(fifth);

        assertThat(throttle.isBlocked(EMAIL, null)).as("the account owner just logged in").isFalse();
        assertThat(throttle.trackedEmailKeys()).isZero();
    }

    @Test
    void recordSuccess_removesExactlyThatAttemptsEntryFromTheIpKey_andASecondCallIsANoOp() {
        pass("a1@x.vn", IP);
        LoginThrottle.Attempt success = pass("a2@x.vn", IP);
        pass("a3@x.vn", IP);

        throttle.recordSuccess(success);
        throttle.recordSuccess(success); // idempotent: must not remove a second entry

        // 2 entries are left (a1, a3): 17 more make 19, the 18th more makes 20
        for (int i = 0; i < 17; i++) {
            pass("b" + i + "@x.vn", IP);
        }
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("19 counted entries").isFalse();

        pass("b17@x.vn", IP);
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("the 20th counted entry blocks the IP").isTrue();
    }

    @Test
    void recordSuccess_clearsTheEmailKeyAndDropsAnIpKeyThatBecomesEmpty() {
        LoginThrottle.Attempt attempt = pass(EMAIL, IP);
        assertThat(throttle.trackedEmailKeys()).isOne();
        assertThat(throttle.trackedIpKeys()).isOne();

        throttle.recordSuccess(attempt);

        assertThat(throttle.trackedEmailKeys()).isZero();
        assertThat(throttle.trackedIpKeys()).isZero();
    }

    @Test
    void recordSuccess_afterEighteenIpFailuresAndOneSuccess_theIpCountIsUnchanged() {
        // 18 failures, then one success (counted, then given back): the IP is still at 18, not 17 and not 19
        failIp(IP, 18);
        LoginThrottle.Attempt success = pass("owner@x.vn", IP);
        throttle.recordSuccess(success);

        pass("late1@x.vn", IP);
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("19 failures").isFalse();
        pass("late2@x.vn", IP);
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("20 failures").isTrue();
    }

    @Test
    void recordSuccess_whenTheSuccessItselfBringsTheIpToItsLimit_theIpBlockStays() {
        failIp(IP, 19);
        LoginThrottle.Attempt twentieth = pass("owner@x.vn", IP); // the 20th: blocks the IP, password turns out right
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isTrue();

        throttle.recordSuccess(twentieth);

        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("one login cannot erase 19 failures").isTrue();
        assertThat(throttle.tryAcquire("fresh@x.vn", IP)).isEmpty();
    }

    @Test
    void recordSuccess_forAnAttemptCountedBeforeAParallelRequestBlockedTheIp_leavesTheBlock() {
        LoginThrottle.Attempt early = pass("early@x.vn", IP);
        failIp(IP, 19); // together with "early" these are 20: the IP is blocked and its timestamps are cleared
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isTrue();

        throttle.recordSuccess(early);

        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isTrue();
    }

    @Test
    void recordSuccess_byEmail_isTheFallbackThatClearsOnlyTheEmail() {
        failEmail(EMAIL, 3);
        failIp(IP, 10);

        throttle.recordSuccess(EMAIL);

        failEmail(EMAIL, 4);
        assertThat(throttle.isBlocked(EMAIL, null)).as("4 attempts after the fallback are fine").isFalse();
        // the IP still holds all 10 entries: 9 more make 19, the 10th more makes 20
        for (int i = 10; i < 19; i++) {
            pass("many" + i + "@x.vn", IP);
        }
        assertThat(throttle.isBlocked("brand-new@x.vn", IP)).isFalse();
        pass("many19@x.vn", IP);
        assertThat(throttle.isBlocked("brand-new@x.vn", IP)).as("the IP counted every attempt").isTrue();
    }

    @Test
    void recordSuccess_byEmail_forUnknownEmailOrNull_doesNothing() {
        throttle.recordSuccess("never-seen@x.vn");
        throttle.recordSuccess((String) null);
        throttle.recordSuccess("   ");

        assertThat(throttle.trackedEmailKeys()).isZero();
    }

    @Test
    void recordSuccess_byEmail_normalisesTheEmail() {
        failEmail("user@x.vn", 3);

        throttle.recordSuccess("  USER@X.VN ");

        assertThat(throttle.trackedEmailKeys()).isZero();
    }

    // ------------------------------------------------------------------ normalisation and missing parts

    @Test
    void email_isNormalisedBeforeCounting() {
        pass(" USER@X.VN ", "203.0.113.1");
        pass("user@x.vn", "203.0.113.2");
        pass("User@X.vn", "203.0.113.3");
        pass("user@x.vn\t", "203.0.113.4");
        pass("USER@x.vn", "203.0.113.5");

        assertThat(throttle.trackedEmailKeys()).isOne();
        assertThat(throttle.isBlocked("user@x.vn", null)).isTrue();
        assertThat(throttle.isBlocked("  UsEr@X.Vn  ", null)).isTrue();
    }

    @Test
    void blankOrNullEmail_countsOnlyTheIp() {
        for (int i = 0; i < 19; i++) {
            Optional<LoginThrottle.Attempt> attempt =
                    throttle.tryAcquire(i % 3 == 0 ? null : i % 3 == 1 ? "" : "   ", IP);
            assertThat(attempt).isPresent();
        }

        assertThat(throttle.trackedEmailKeys()).isZero();
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("19 attempts: not yet").isFalse();

        assertThat(throttle.tryAcquire(null, IP)).isPresent();

        assertThat(throttle.isBlocked("fresh@x.vn", IP)).as("the 20th blocks the IP").isTrue();
        assertThat(throttle.isBlocked(null, IP)).isTrue();
        assertThat(throttle.tryAcquire(null, IP)).isEmpty();
        assertThat(throttle.trackedEmailKeys()).isZero();
    }

    @Test
    void nullOrBlankIp_countsOnlyTheEmail() {
        assertThat(throttle.tryAcquire(EMAIL, null)).isPresent();
        assertThat(throttle.tryAcquire(EMAIL, "")).isPresent();
        assertThat(throttle.tryAcquire(EMAIL, "  ")).isPresent();
        assertThat(throttle.tryAcquire(EMAIL, null)).isPresent();

        assertThat(throttle.trackedIpKeys()).isZero();
        assertThat(throttle.isBlocked(EMAIL, null)).isFalse();

        assertThat(throttle.tryAcquire(EMAIL, null)).isPresent();

        assertThat(throttle.isBlocked(EMAIL, null)).isTrue();
        assertThat(throttle.trackedIpKeys()).isZero();
    }

    @Test
    void recordSuccess_forAnAttemptWithoutEmailOrIp_isHarmless() {
        LoginThrottle.Attempt noEmail = pass(null, IP);
        LoginThrottle.Attempt noIp = pass(EMAIL, null);

        throttle.recordSuccess(noEmail);
        throttle.recordSuccess(noIp);

        assertThat(throttle.trackedEmailKeys()).isZero();
        assertThat(throttle.trackedIpKeys()).isZero();
    }

    @Test
    void ip_isStrippedBeforeCounting() {
        for (int i = 0; i < 20; i++) {
            pass("m" + i + "@x.vn", i % 2 == 0 ? IP : " " + IP + " ");
        }

        assertThat(throttle.trackedIpKeys()).isOne();
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isTrue();
    }

    // ------------------------------------------------------------------ IP limit (AC-008-8)

    @Test
    void ip_nineteenAttemptsWithDifferentEmails_doNotBlock() {
        failIp(IP, 19);

        assertThat(throttle.isBlocked("new@x.vn", IP)).isFalse();
        assertThat(throttle.tryAcquire("new@x.vn", IP)).isPresent();
    }

    @Test
    void ip_twentyAttemptsWithDifferentEmails_blockEveryEmailFromThatIp() {
        failIp(IP, 20);

        assertThat(throttle.tryAcquire("new@x.vn", IP)).isEmpty();
        assertThat(throttle.tryAcquire("another@x.vn", IP)).isEmpty();
        assertThat(throttle.tryAcquire("new@x.vn", "198.51.100.8")).as("another IP is free").isPresent();
    }

    @Test
    void ip_blockLastsFifteenMinutesAndThenCountsFromZero() {
        failIp(IP, 20);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(throttle.isBlocked("new@x.vn", IP)).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.isBlocked("new@x.vn", IP)).isFalse();

        for (int i = 0; i < 19; i++) {
            pass("again" + i + "@x.vn", IP);
        }
        assertThat(throttle.isBlocked("new@x.vn", IP)).as("19 after the block: not blocked").isFalse();
    }

    @Test
    void ip_attemptsWithTheSameEmail_blockTheEmailFirst() {
        fail(EMAIL, IP, 5);

        assertThat(throttle.isBlocked(EMAIL, "192.0.2.1")).isTrue();
        assertThat(throttle.isBlocked("other@x.vn", IP)).as("5 attempts are far from 20 for the IP").isFalse();
    }

    // ------------------------------------------------------------------ a refusal is all or nothing

    @Test
    void refusalBecauseOfTheIp_leavesTheEmailKeyUntouched() {
        failIp(IP, 20);
        int emailKeys = throttle.trackedEmailKeys();

        for (int i = 0; i < 10; i++) {
            assertThat(throttle.tryAcquire("victim@x.vn", IP)).isEmpty();
        }

        assertThat(throttle.trackedEmailKeys()).as("no email key was created by the refusals").isEqualTo(emailKeys);
        // and the email was not counted: it still needs 5 attempts from another IP to be blocked
        failEmail("victim@x.vn", 4);
        assertThat(throttle.isBlocked("victim@x.vn", null)).isFalse();
    }

    @Test
    void refusalBecauseOfTheEmail_leavesTheIpKeyUntouched() {
        failEmail(EMAIL, 5);

        for (int i = 0; i < 10; i++) {
            assertThat(throttle.tryAcquire(EMAIL, IP)).isEmpty();
        }

        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isFalse();
        // the IP was not counted: it still needs 20 attempts to be blocked
        failIp(IP, 19);
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isFalse();
        pass("last@x.vn", IP);
        assertThat(throttle.isBlocked("fresh@x.vn", IP)).isTrue();
    }

    // ------------------------------------------------------------------ bounded memory

    @Test
    void longEmail_isCutAt255CodePointsSoTheKeyStaysSmall() {
        String base = "a".repeat(255);
        // five spellings that differ only after code point 255 share one key
        pass(base + "x".repeat(745), "203.0.113.1");
        pass(base + "yyy", "203.0.113.2");
        pass(base + "z", "203.0.113.3");
        pass(base, "203.0.113.4");
        pass(base + "another tail", "203.0.113.5");

        assertThat(throttle.trackedEmailKeys()).isOne();
        assertThat(throttle.isBlocked(base + "completely different tail", null)).isTrue();
    }

    @Test
    void longEmail_cutNeverSplitsAnEmojiAtTheBoundary() {
        String prefix = "a".repeat(254);
        for (int i = 0; i < 5; i++) {
            pass(prefix + "😀" + "tail" + i, "203.0.113." + (i + 1));
        }

        assertThat(throttle.isBlocked(prefix + "😀", null)).isTrue();
        assertThat(throttle.trackedEmailKeys()).isOne();
    }

    @Test
    void manyKeys_neverExceedTheTableLimit() {
        for (int i = 0; i <= LoginThrottle.MAX_TRACKED_KEYS; i++) {
            pass("u" + i + "@x.vn", "10.%d.%d.%d".formatted(i / 65_536, (i / 256) % 256, i % 256));
        }

        assertThat(throttle.trackedEmailKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.trackedIpKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
    }

    @Test
    void manyKeys_dropTheLeastRecentlyUsedKeyThatIsNotBlocked() {
        // "stale" and "busy" are each one attempt short of a block; neither is blocked
        failEmail("stale@x.vn", 4);
        failEmail("busy@x.vn", 4);
        for (int i = 0; i < LoginThrottle.MAX_TRACKED_KEYS - 2; i++) {
            pass("u" + i + "@x.vn", null);
        }
        assertThat(throttle.trackedEmailKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        // reading a key counts as use: "busy" (older than most) becomes the most recent, "stale" the oldest
        assertThat(throttle.isBlocked("busy@x.vn", null)).isFalse();

        for (int i = 0; i < 100; i++) {
            pass("later" + i + "@x.vn", null);
        }

        assertThat(throttle.trackedEmailKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        // "stale" was dropped, so its 4 attempts are forgotten: one more is only its 1st
        pass("stale@x.vn", "192.0.2.10");
        assertThat(throttle.isBlocked("stale@x.vn", null)).as("dropped, counting restarted").isFalse();
        // "busy" survived with its 4 attempts: one more is its 5th
        pass("busy@x.vn", "192.0.2.11");
        assertThat(throttle.isBlocked("busy@x.vn", null)).as("kept its 4 attempts").isTrue();
    }

    @Test
    void manyKeys_aFloodOfDistinctEmails_doesNotLiftAnActiveEmailBlock() {
        failEmail("victim@x.vn", 5);
        assertThat(throttle.isBlocked("victim@x.vn", null)).isTrue();
        // do not touch "victim" again while far more keys than the table holds arrive
        for (int i = 0; i < 2 * LoginThrottle.MAX_TRACKED_KEYS; i++) {
            pass("flood" + i + "@x.vn", null);
        }

        assertThat(throttle.trackedEmailKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.isBlocked("victim@x.vn", null)).as("a flood must not unblock an email").isTrue();
        assertThat(throttle.tryAcquire("victim@x.vn", "192.0.2.1")).isEmpty();
    }

    @Test
    void manyKeys_aFloodOfDistinctIps_doesNotLiftAnActiveIpBlock() {
        failIp(IP, 20);
        assertThat(throttle.isBlocked("x@x.vn", IP)).isTrue();

        for (int i = 0; i < 2 * LoginThrottle.MAX_TRACKED_KEYS; i++) {
            pass(null, "10.%d.%d.%d".formatted(i / 65_536, (i / 256) % 256, i % 256));
        }

        assertThat(throttle.trackedIpKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.isBlocked("x@x.vn", IP)).as("a flood must not unblock an IP").isTrue();
    }

    @Test
    void manyKeys_whenEveryOtherKeyIsBlocked_dropsTheLeastRecentlyUsedBlockedOneAndKeepsTheNewKey() {
        // fill the table with blocked emails: the first one blocked is the least recently used
        for (int i = 0; i < LoginThrottle.MAX_TRACKED_KEYS; i++) {
            for (int attempt = 0; attempt < 5; attempt++) {
                pass("blocked" + i + "@x.vn", null);
            }
        }
        assertThat(throttle.trackedEmailKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.isBlocked("blocked0@x.vn", null)).isTrue(); // touches blocked0: now it is the newest
        assertThat(throttle.isBlocked("blocked1@x.vn", null)).isTrue(); // blocked2 is now the oldest ... see below

        pass("newcomer@x.vn", null); // one over the cap, every other key blocked

        assertThat(throttle.trackedEmailKeys()).as("the bound itself must hold").isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.isBlocked("blocked2@x.vn", null)).as("the least recently used blocked key went").isFalse();
        assertThat(throttle.isBlocked("blocked0@x.vn", null)).isTrue();
        assertThat(throttle.isBlocked("blocked1@x.vn", null)).isTrue();
        // the key that was just added is never the one dropped: it holds 1 attempt, 4 more block it
        failEmail("newcomer@x.vn", 3);
        assertThat(throttle.isBlocked("newcomer@x.vn", null)).isFalse();
        pass("newcomer@x.vn", "192.0.2.77");
        assertThat(throttle.isBlocked("newcomer@x.vn", null)).as("5th attempt on the new key").isTrue();
    }

    @Test
    void manyKeys_anExpiredBlockCountsAsNotBlockedWhenTheTableIsFull() {
        failEmail("expired@x.vn", 5);
        clock.advance(LoginThrottle.WINDOW); // the block is over, but nobody has touched the key since

        for (int i = 0; i < LoginThrottle.MAX_TRACKED_KEYS; i++) {
            pass("fill" + i + "@x.vn", null);
        }

        assertThat(throttle.trackedEmailKeys()).isEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.isBlocked("expired@x.vn", null)).isFalse();
    }

    // ------------------------------------------------------------------ logging (no personal data)

    @Test
    void block_writesOneWarnPerNewBlockWithoutEmailOrIp() {
        failEmail(EMAIL, 4);
        assertThat(appender.list).isEmpty();

        pass(EMAIL, "203.0.113.5");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("Login throttling: an email reached 5 failed attempts in 15 minutes; "
                        + "blocked for 15 minutes");
    }

    @Test
    void ipBlock_writesOneWarnAndRefusedAttemptsWriteNothing() {
        failIp(IP, 20);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .isEqualTo("Login throttling: a client IP reached 20 failed attempts in 15 minutes; "
                        + "blocked for 15 minutes");

        for (int i = 0; i < 50; i++) {
            assertThat(throttle.tryAcquire("x" + i + "@x.vn", IP)).isEmpty();
        }
        throttle.isBlocked("x@x.vn", IP);
        assertThat(appender.list).as("refused attempts do not flood the log").hasSize(1);
    }

    @Test
    void recordSuccess_writesNothing() {
        LoginThrottle.Attempt attempt = pass(EMAIL, IP);

        throttle.recordSuccess(attempt);
        throttle.recordSuccess(EMAIL);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void logLines_neverContainTheEmailOrTheIp() {
        fail("secret.person@example.com", "192.0.2.123", 5);
        failIp("192.0.2.200", 20);

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("secret.person").doesNotContain("example.com")
                .doesNotContain("192.0.2.").doesNotContain("@"));
    }

    // ------------------------------------------------------------------ thread safety

    @Test
    void concurrentAttemptsOfOneEmail_atMostFiveAreLetThrough() throws Exception {
        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int round = 0; round < 50; round++) {
                LoginThrottle fresh = new LoginThrottle(clock);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger permits = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < attempts; t++) {
                    String ip = "203.0.113." + (t + 1); // distinct, so the IP limit never decides
                    futures.add(pool.submit(() -> {
                        go.await(5, TimeUnit.SECONDS);
                        if (fresh.tryAcquire(EMAIL, ip).isPresent()) {
                            permits.incrementAndGet();
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                assertThat(permits.get()).as("round %d: only 5 passwords may be checked", round).isEqualTo(5);
                assertThat(fresh.isBlocked(EMAIL, null)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentAttemptsFromOneIp_atMostTwentyAreLetThrough() throws Exception {
        int attempts = 80;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int round = 0; round < 25; round++) {
                LoginThrottle fresh = new LoginThrottle(clock);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger permits = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < attempts; t++) {
                    String email = "p" + t + "@x.vn"; // distinct, so the email limit never decides
                    futures.add(pool.submit(() -> {
                        go.await(5, TimeUnit.SECONDS);
                        if (fresh.tryAcquire(email, IP).isPresent()) {
                            permits.incrementAndGet();
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                assertThat(permits.get()).as("round %d", round).isEqualTo(20);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentMixedCalls_neverThrowAndKeepTheTablesBounded() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int worker = t;
                futures.add(pool.submit(() -> {
                    go.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 3_000; i++) {
                        String email = "w" + worker + "u" + (i % 700) + "@x.vn";
                        String ip = "10.0." + worker + "." + (i % 250);
                        throttle.isBlocked(email, ip);
                        Optional<LoginThrottle.Attempt> attempt = throttle.tryAcquire(email, ip);
                        if (i % 7 == 0) {
                            attempt.ifPresentOrElse(throttle::recordSuccess, () -> throttle.recordSuccess(email));
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(throttle.trackedEmailKeys()).isLessThanOrEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
        assertThat(throttle.trackedIpKeys()).isLessThanOrEqualTo(LoginThrottle.MAX_TRACKED_KEYS);
    }
}

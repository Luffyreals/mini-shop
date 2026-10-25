package com.minishop.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Limits failed logins (BR-008-3, AC-008-7..10): 5 failures per email and 20 failures per client IP within a
 * sliding window of 15 minutes.
 * <p><b>Why the attempt is counted before the password is checked.</b> If the gate only <em>read</em> the state and the
 * failure were counted <em>after</em> the (slow) bcrypt check, N parallel requests would all pass the gate and all get
 * their password checked. So the gate is one atomic operation, {@link #tryAcquire}: it refuses when either key is
 * blocked, and otherwise counts the attempt <em>provisionally</em> at once, under the same lock. A wrong password then
 * needs nothing more (the count is already there); a right password gives the provisional count back through
 * {@link #recordSuccess(Attempt)}. The request that brings a key to its limit (the 5th for an email) is still allowed to
 * have its password checked; the key is blocked from that moment, so the 6th is refused without any check.
 * <p><b>Rules</b>
 * <ul>
 *   <li>Each key (normalised email, IP) keeps the timestamps of its most recent attempts. Counting an attempt first
 *   drops timestamps older than {@link #WINDOW}. A sliding window matches "5 failures in 15 minutes" exactly; a
 *   fixed window would allow up to 8 in a row around a boundary.</li>
 *   <li>When a key reaches its limit it is blocked until {@code now + WINDOW} and its timestamps are cleared, so the
 *   count restarts from 0 when the block ends. While a key is blocked, {@link #tryAcquire} refuses even for the right
 *   password, because it is called before the password is looked at, and a refused attempt is counted nowhere
 *   (it never extends a block).</li>
 *   <li>A refusal is all-or-nothing: if the IP is blocked, the email key is not touched, and the other way round.</li>
 *   <li>{@link #recordSuccess(Attempt)} clears the email key and removes only <em>that attempt's</em> provisional entry
 *   from the IP key, so successful logins never add up towards the IP limit, while the failures of the same IP stay
 *   counted (BR-008-3). If the attempt itself brought the IP to its limit, the block stays: it is not undone, so
 *   an attacker cannot erase 19 failures with one login to an account of their own.</li>
 *   <li>A blank email counts only the IP; a {@code null} or blank IP counts only the email. Emails are normalised with
 *   {@link EmailNormalizer} (the same as registration and login) and cut at 255 code points.</li>
 * </ul>
 * <p><b>Memory</b> is bounded: each table (email, IP) holds at most {@link #MAX_TRACKED_KEYS} keys and each key holds at
 * most 20 timestamps. When a table is over the cap, the least recently used key that is <em>not currently blocked</em>
 * is dropped first, so a flood of distinct keys cannot lift an active block early; only when every other key is blocked
 * is the least recently used blocked key dropped, because the bound itself must hold. Every method is
 * {@code synchronized}; for one machine and a handful of testers a plain lock is enough.
 * <p><b>No configuration keys</b>: the limits are constants, so the behaviour is the same in every profile and nobody
 * can switch it off by mistake (AC-008-10). The state lives in memory and starts at zero when the app restarts.
 * <p>The log never contains an email or an IP, and a refused attempt is not logged (only the moment a key becomes
 * blocked), so an attacker cannot flood the log.
 */
public final class LoginThrottle {

    public static final int MAX_FAILURES_PER_EMAIL = 5;
    public static final int MAX_FAILURES_PER_IP = 20;
    /** Both the counting window and the block duration. */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED_KEYS = 10_000;

    private static final int MAX_EMAIL_KEY_LENGTH = 255;
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final Clock clock;
    // Access-ordered: iteration starts at the least recently used key.
    private final Map<String, Attempts> byEmail = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Attempts> byIp = new LinkedHashMap<>(16, 0.75f, true);

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * The atomic gate in front of the password check. Returns empty when the email or the client IP is blocked: the
     * caller must refuse the attempt without checking the password, and nothing is counted. Otherwise the attempt is
     * counted at once for the email and for the IP (a key that reaches its limit becomes blocked now) and the returned
     * {@link Attempt} must be passed to {@link #recordSuccess(Attempt)} if the password turns out to be right. When
     * the password is wrong, the caller does nothing: the count is already there.
     */
    public synchronized Optional<Attempt> tryAcquire(String rawEmail, String clientIp) {
        Instant now = clock.instant();
        String emailKey = emailKey(rawEmail);
        String ipKey = ipKey(clientIp);
        if (isBlocked(byEmail, emailKey, now) || isBlocked(byIp, ipKey, now)) {
            return Optional.empty();
        }
        count(byEmail, emailKey, MAX_FAILURES_PER_EMAIL, "an email", now);
        count(byIp, ipKey, MAX_FAILURES_PER_IP, "a client IP", now);
        return Optional.of(new Attempt(emailKey, ipKey, now));
    }

    /**
     * A successful login: clears the failure count of that email and removes this attempt's provisional entry from the
     * IP key (the other entries of the IP stay). Does nothing for an attempt that was already released.
     */
    public synchronized void recordSuccess(Attempt attempt) {
        if (attempt.released) {
            return;
        }
        attempt.released = true;
        if (attempt.emailKey != null) {
            byEmail.remove(attempt.emailKey);
        }
        if (attempt.ipKey != null) {
            Attempts entry = byIp.get(attempt.ipKey);
            if (entry != null) {
                entry.failures.removeFirstOccurrence(attempt.at);
                if (entry.blockedUntil == null && entry.failures.isEmpty()) {
                    byIp.remove(attempt.ipKey);
                }
            }
        }
    }

    /**
     * A successful login without a known attempt (the filter did not run): clears the failure count of that email only.
     * The IP key is left as it is.
     */
    public synchronized void recordSuccess(String email) {
        String key = emailKey(email);
        if (key != null) {
            byEmail.remove(key);
        }
    }

    /** True when the email or the client IP is currently blocked. Never counts anything. */
    public synchronized boolean isBlocked(String rawEmail, String clientIp) {
        Instant now = clock.instant();
        return isBlocked(byEmail, emailKey(rawEmail), now) || isBlocked(byIp, ipKey(clientIp), now);
    }

    /** Number of email keys currently tracked (for tests of the memory bound). */
    synchronized int trackedEmailKeys() {
        return byEmail.size();
    }

    /** Number of IP keys currently tracked (for tests of the memory bound). */
    synchronized int trackedIpKeys() {
        return byIp.size();
    }

    private static boolean isBlocked(Map<String, Attempts> table, String key, Instant now) {
        if (key == null) {
            return false;
        }
        Attempts entry = table.get(key);
        if (entry == null || entry.blockedUntil == null) {
            return false;
        }
        if (now.isBefore(entry.blockedUntil)) {
            return true;
        }
        table.remove(key);
        return false;
    }

    /** Counts one attempt for a key that is known not to be blocked. */
    private static void count(Map<String, Attempts> table, String key, int limit, String what, Instant now) {
        if (key == null) {
            return;
        }
        Attempts entry = table.get(key);
        if (entry == null) {
            entry = new Attempts();
            table.put(key, entry);
            evictBeyondCap(table, key, now);
        }
        Instant cutoff = now.minus(WINDOW);
        while (!entry.failures.isEmpty() && !entry.failures.peekFirst().isAfter(cutoff)) {
            entry.failures.removeFirst();
        }
        entry.failures.addLast(now);
        if (entry.failures.size() >= limit) {
            entry.failures.clear();
            entry.blockedUntil = now.plus(WINDOW);
            log.warn("Login throttling: {} reached {} failed attempts in {} minutes; blocked for {} minutes",
                    what, limit, WINDOW.toMinutes(), WINDOW.toMinutes());
        }
    }

    /**
     * Keeps a table within {@link #MAX_TRACKED_KEYS}: drops the least recently used key that is not blocked (an expired
     * block counts as not blocked); if every other key is blocked, drops the least recently used one. The key that was
     * just added is never the one dropped.
     */
    private static void evictBeyondCap(Map<String, Attempts> table, String justAdded, Instant now) {
        if (table.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        String eldestBlocked = null;
        for (Iterator<Map.Entry<String, Attempts>> it = table.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Attempts> candidate = it.next();
            if (candidate.getKey().equals(justAdded)) {
                continue;
            }
            Instant blockedUntil = candidate.getValue().blockedUntil;
            if (blockedUntil == null || !now.isBefore(blockedUntil)) {
                it.remove();
                return;
            }
            if (eldestBlocked == null) {
                eldestBlocked = candidate.getKey();
            }
        }
        if (eldestBlocked != null) {
            table.remove(eldestBlocked);
        }
    }

    private static String emailKey(String rawEmail) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (email.isEmpty()) {
            return null;
        }
        if (email.codePointCount(0, email.length()) > MAX_EMAIL_KEY_LENGTH) {
            email = email.substring(0, email.offsetByCodePoints(0, MAX_EMAIL_KEY_LENGTH));
        }
        return email;
    }

    private static String ipKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        return clientIp.strip();
    }

    /**
     * Handle for one attempt that {@link #tryAcquire} let through and counted. Created only by the throttle; the
     * caller keeps it between the gate and the authentication result and gives it to
     * {@link #recordSuccess(Attempt)} on success. Its state is guarded by the throttle's lock.
     */
    public static final class Attempt {
        private final String emailKey;
        private final String ipKey;
        private final Instant at;
        private boolean released;

        private Attempt(String emailKey, String ipKey, Instant at) {
            this.emailKey = emailKey;
            this.ipKey = ipKey;
            this.at = at;
        }
    }

    private static final class Attempts {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant blockedUntil;
    }
}

package com.minishop.user;

import com.minishop.common.SafeExceptionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Login (spec 003 rules, spec 009 AC-009-6). Registration stays in {@link UserService} and logout is
 * {@code AppSession.signOut()}. This service never touches {@link AppSession}: the session is changed on the
 * JavaFX thread by the caller, after this method returns {@link LoginResult.Success}.
 *
 * <p>Called through {@code BackgroundTasks} by the login dialog's presenter, never on the JavaFX thread.
 */
@Service
class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt looks at 72 bytes only; see {@link #login}. */
    private static final int MAX_PASSWORD_BYTES = 72;
    /**
     * A pasted giant string is refused before it is cleaned or sent to the database. Generous on purpose: the
     * stored email is at most 254 characters, but the raw input may still carry spaces around it.
     */
    private static final int MAX_RAW_EMAIL_CHARS = 1000;
    /** Compared against the fake hash when the real password cannot be compared, only to spend the same time. */
    private static final String PLACEHOLDER_PASSWORD = "placeholder-password-never-valid";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<LoginHook> hooks;

    /** A valid hash of a random password nobody knows; created on first use, see {@link #fakeHash}. */
    private volatile String fakeHash;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, ObjectProvider<LoginHook> hooks) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.hooks = hooks;
    }

    /**
     * Checks the credentials and, on success, runs the {@link LoginHook}s and returns the user.
     *
     * <p><b>Why {@code NOT_SUPPORTED}:</b> a hook such as the guest cart merge must commit in its own transaction
     * <b>before</b> the guest cart is cleared (BR-004-7). If this method opened a transaction, the merge would join
     * it; a failing merge would mark the outer transaction rollback-only and this method would throw
     * {@code UnexpectedRollbackException} although the login is fine. It is written out so nobody adds a class-level
     * {@code @Transactional} later.
     *
     * <p><b>Why a fake hash:</b> an unknown email and a wrong password must cost about the same time and give the
     * same result (BR-003-5), or the response time tells an attacker which emails exist. So {@code matches} runs
     * <b>exactly once</b> on both branches, against the real hash or against the fake one. The fake hash is created
     * at the start of every call, so even the very first call spends the same on both branches.
     *
     * <p><b>Why a limit of 72 bytes:</b> {@code BCrypt.checkpw} ignores everything after the first 72 bytes, so a
     * longer input that starts with the real password would match. Passwords of that size cannot exist (the policy
     * stops them at registration), so such an input is refused before comparing, with the same result as a wrong
     * password and no exception.
     *
     * <p>Nothing here logs an email or a password; a refused login logs one DEBUG line without a reason.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    LoginResult login(LoginForm form) {
        String hashForUnknownUser = fakeHash();

        if (form.email().length() > MAX_RAW_EMAIL_CHARS) {
            return rejected();
        }
        String email = EmailNormalizer.normalize(form.email());
        String password = form.password();
        if (email.isEmpty() || password.isEmpty()) {
            return rejected();
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            passwordEncoder.matches(PLACEHOLDER_PASSWORD, hashForUnknownUser);
            return rejected();
        }

        Optional<User> found = users.findByEmail(email);
        String hash = found.map(User::getPasswordHash).orElse(hashForUnknownUser);
        boolean matches = passwordEncoder.matches(password, hash);
        if (found.isEmpty() || !matches) {
            return rejected();
        }

        User user = found.get();
        CurrentUser current = new CurrentUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
        runHooks(current);
        log.info("User {} logged in", current.id());
        return new LoginResult.Success(current);
    }

    private static LoginResult rejected() {
        log.debug("Login rejected");
        return new LoginResult.InvalidCredentials();
    }

    /** One broken hook must not fail the login or stop the next hook (BR-004-7). */
    private void runHooks(CurrentUser user) {
        List<LoginHook> ordered = hooks.orderedStream().toList();
        for (LoginHook hook : ordered) {
            try {
                hook.afterLogin(user);
            } catch (RuntimeException e) {
                log.warn("Login hook {} failed for user {}", hook.getClass().getSimpleName(), user.id(),
                        SafeExceptionLog.withoutDatabaseMessages(e));
            }
        }
    }

    private String fakeHash() {
        String hash = fakeHash;
        if (hash == null) {
            synchronized (this) {
                hash = fakeHash;
                if (hash == null) {
                    hash = passwordEncoder.encode(UUID.randomUUID().toString());
                    fakeHash = hash;
                }
            }
        }
        return hash;
    }
}

package com.minishop.user;

import com.minishop.common.AccessDeniedException;
import com.minishop.common.AdminAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Who is using the app right now (ADR-0014, QĐ1 of the D2 plan). One process has one person in front of it, so this
 * singleton is "the session". Plain Java: no JavaFX type, so services can read it from any thread.
 *
 * <p><b>Threads:</b> the state is one {@code volatile} reference to an immutable {@link CurrentUser}, so any thread
 * reads a whole value, old or new, never a half-written one. It is <b>written only on the JavaFX thread</b>:
 * {@link #signIn} and {@link #signOut} are called from callbacks of {@code BackgroundTasks} or from a menu action.
 *
 * <p><b>Who may change it:</b> {@code signIn} and {@code signOut} are package-private, so only the {@code user}
 * package can change the session. Another feature cannot "log in" with a made-up {@code CurrentUser}; the compiler
 * stops it. Reading is public.
 *
 * <p><b>Observers:</b> {@link SessionListener} beans are found lazily on the first notification (through an
 * {@code ObjectProvider}), so a listener may inject this class without a dependency cycle.
 */
@Component
public class AppSession implements AdminAccess {

    private static final Logger log = LoggerFactory.getLogger(AppSession.class);

    private final Supplier<List<SessionListener>> listenerSource;
    private volatile List<SessionListener> listeners;
    /** The logged-in user, or {@code null} for a guest. */
    private volatile CurrentUser current;

    @Autowired
    AppSession(ObjectProvider<SessionListener> listenerProvider) {
        this(() -> listenerProvider.orderedStream().toList());
    }

    /** For tests: a fixed list of listeners instead of the Spring context. */
    AppSession(Supplier<List<SessionListener>> listenerSource) {
        this.listenerSource = Objects.requireNonNull(listenerSource);
    }

    /** The logged-in user, or empty for a guest. */
    public Optional<CurrentUser> currentUser() {
        return Optional.ofNullable(current);
    }

    public boolean isLoggedIn() {
        return current != null;
    }

    /**
     * Returns the id of the logged-in admin.
     *
     * @throws AccessDeniedException for a guest or a user whose role is not {@link Role#ADMIN}
     */
    @Override
    public long requireAdmin() {
        CurrentUser user = current;
        if (user == null || user.role() != Role.ADMIN) {
            throw new AccessDeniedException();
        }
        return user.id();
    }

    /**
     * Logs {@code user} in and tells the listeners {@code (null, user)}. JavaFX thread only.
     *
     * @throws NullPointerException  when {@code user} is {@code null}
     * @throws IllegalStateException when someone is already logged in
     */
    void signIn(CurrentUser user) {
        Objects.requireNonNull(user, "user");
        CurrentUser previous = current;
        if (previous != null) {
            throw new IllegalStateException("Already signed in");
        }
        current = user;
        log.debug("Session signed in: user {}, role {}", user.id(), user.role());
        notifyListeners(null, user);
    }

    /** Back to guest and tells the listeners {@code (previous, null)}. Does nothing for a guest. JavaFX thread only. */
    void signOut() {
        CurrentUser previous = current;
        if (previous == null) {
            return;
        }
        current = null;
        log.debug("Session signed out");
        notifyListeners(previous, null);
    }

    /**
     * Calls every listener in order even if one fails, then rethrows the first error with the later ones attached as
     * suppressed, so no listener is skipped and the failure still reaches the uncaught-error handler (AC-009-12).
     */
    private void notifyListeners(CurrentUser previous, CurrentUser now) {
        RuntimeException first = null;
        for (SessionListener listener : listeners()) {
            try {
                listener.sessionChanged(previous, now);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private List<SessionListener> listeners() {
        List<SessionListener> found = listeners;
        if (found == null) {
            found = List.copyOf(listenerSource.get());
            listeners = found;
        }
        return found;
    }
}

package com.minishop.user;

/**
 * Observes sign-in and sign-out. A listener is a Spring bean; {@link AppSession} finds all of them lazily, so a
 * listener may inject {@code AppSession} itself.
 *
 * <p><b>When and where:</b> called synchronously, in {@code @Order} order, on the thread that changed the session,
 * which is the JavaFX thread, after the new state is already visible through {@link AppSession#currentUser()}. A
 * presenter may therefore set its JavaFX properties straight from {@link #sessionChanged}. If one listener throws,
 * the others still run and the first error is rethrown afterwards (it reaches the uncaught-error handler).
 */
@FunctionalInterface
public interface SessionListener {

    /**
     * The session changed.
     *
     * @param previous the user before the change, {@code null} for a guest
     * @param current  the user after the change, {@code null} for a guest
     */
    void sessionChanged(CurrentUser previous, CurrentUser current);
}

package com.minishop.user;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.common.AccessDeniedException;
import com.minishop.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AppSession}: the one session of the process (QĐ1, QĐ2, QĐ12 of the D2 plan; AC-009-9 base, AC-009-10).
 * No Spring context: the listeners come from the package-private test constructor.
 */
class AppSessionTest {

    private static final CurrentUser USER = new CurrentUser(7L, "secret.user@minishop.local", "Nguyễn Văn Bí Mật", Role.USER);
    private static final CurrentUser ADMIN = new CurrentUser(1L, "secret.admin@minishop.local", "Quản Trị Bí Mật", Role.ADMIN);

    /** One notification as a listener saw it. */
    private record Call(String listener, CurrentUser previous, CurrentUser current) {
    }

    private static AppSession sessionWith(SessionListener... listeners) {
        return new AppSession(() -> List.of(listeners));
    }

    // ------------------------------------------------------------------ state

    @Test
    void newSession_isAGuest() {
        AppSession session = sessionWith();

        assertThat(session.isLoggedIn()).isFalse();
        assertThat(session.currentUser()).isEmpty();
    }

    @Test
    void signIn_makesTheUserCurrent() {
        AppSession session = sessionWith();

        session.signIn(USER);

        assertThat(session.isLoggedIn()).isTrue();
        assertThat(session.currentUser()).isEqualTo(Optional.of(USER));
    }

    @Test
    void signOut_returnsToGuest() {
        AppSession session = sessionWith();
        session.signIn(USER);

        session.signOut();

        assertThat(session.isLoggedIn()).isFalse();
        assertThat(session.currentUser()).isEmpty();
    }

    @Test
    void signIn_whenAlreadySignedIn_throwsAndKeepsTheFirstUser() {
        List<Call> calls = new ArrayList<>();
        AppSession session = sessionWith((p, c) -> calls.add(new Call("l", p, c)));
        session.signIn(USER);
        calls.clear();

        assertThatThrownBy(() -> session.signIn(ADMIN)).isInstanceOf(IllegalStateException.class);

        assertThat(session.currentUser()).contains(USER);
        assertThat(calls).as("no notification for the refused sign-in").isEmpty();
    }

    @Test
    void signIn_withNull_throwsNullPointerException() {
        AppSession session = sessionWith();

        assertThatThrownBy(() -> session.signIn(null)).isInstanceOf(NullPointerException.class);
        assertThat(session.isLoggedIn()).isFalse();
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void signInThenSignOut_notifiesNullUserThenUserNull() {
        List<Call> calls = new ArrayList<>();
        AppSession session = sessionWith((p, c) -> calls.add(new Call("l", p, c)));

        session.signIn(USER);
        session.signOut();

        assertThat(calls).containsExactly(new Call("l", null, USER), new Call("l", USER, null));
    }

    @Test
    void listeners_areCalledInTheOrderOfTheList() {
        List<Call> calls = new ArrayList<>();
        AppSession session = sessionWith(
                (p, c) -> calls.add(new Call("first", p, c)),
                (p, c) -> calls.add(new Call("second", p, c)),
                (p, c) -> calls.add(new Call("third", p, c)));

        session.signIn(USER);

        assertThat(calls).extracting(Call::listener).containsExactly("first", "second", "third");
    }

    @Test
    void listeners_alreadySeeTheNewStateWhenCalled() {
        List<Optional<CurrentUser>> seen = new ArrayList<>();
        AppSession[] holder = new AppSession[1];
        holder[0] = sessionWith((p, c) -> seen.add(holder[0].currentUser()));

        holder[0].signIn(USER);
        holder[0].signOut();

        assertThat(seen).containsExactly(Optional.of(USER), Optional.empty());
    }

    @Test
    void signOut_asAGuest_notifiesNobody() {
        List<Call> calls = new ArrayList<>();
        AppSession session = sessionWith((p, c) -> calls.add(new Call("l", p, c)));

        session.signOut();
        session.signOut();

        assertThat(calls).isEmpty();
    }

    @Test
    void listenersAreLookedUpLazilyAndOnlyOnce() {
        AtomicInteger lookups = new AtomicInteger();
        AppSession session = new AppSession(() -> {
            lookups.incrementAndGet();
            return List.<SessionListener>of((p, c) -> {
            });
        });
        assertThat(lookups).as("not at construction").hasValue(0);

        session.signIn(USER);
        session.signOut();
        session.signIn(ADMIN);

        assertThat(lookups).hasValue(1);
    }

    @Test
    void aFailingListener_doesNotStopTheOthers_andTheFirstErrorIsRethrownWithTheLaterOnesSuppressed() {
        List<String> ran = new ArrayList<>();
        IllegalStateException firstError = new IllegalStateException("first");
        IllegalArgumentException secondError = new IllegalArgumentException("second");
        AppSession session = sessionWith(
                (p, c) -> {
                    ran.add("a");
                    throw firstError;
                },
                (p, c) -> ran.add("b"),
                (p, c) -> {
                    ran.add("c");
                    throw secondError;
                },
                (p, c) -> ran.add("d"));

        assertThatThrownBy(() -> session.signIn(USER)).isSameAs(firstError);

        assertThat(ran).as("every listener ran").containsExactly("a", "b", "c", "d");
        assertThat(firstError.getSuppressed()).containsExactly(secondError);
        assertThat(session.currentUser()).as("the state changed before the listeners ran").contains(USER);
    }

    @Test
    void aFailingListenerOnSignOut_isRethrownAndTheSessionIsAlreadyAGuest() {
        IllegalStateException error = new IllegalStateException("boom");
        List<Call> calls = new ArrayList<>();
        AppSession session = sessionWith(
                (p, c) -> {
                    if (c == null) {
                        throw error;
                    }
                },
                (p, c) -> calls.add(new Call("after", p, c)));
        session.signIn(USER);
        calls.clear();

        assertThatThrownBy(session::signOut).isSameAs(error);

        assertThat(session.isLoggedIn()).isFalse();
        assertThat(calls).containsExactly(new Call("after", USER, null));
    }

    // ------------------------------------------------------------------ requireAdmin (AC-009-9 base)

    @Test
    void requireAdmin_forAGuest_throwsAccessDenied() {
        AppSession session = sessionWith();

        assertThatThrownBy(session::requireAdmin).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireAdmin_forAUser_throwsAccessDeniedWithoutPersonalData() {
        AppSession session = sessionWith();
        session.signIn(USER);

        assertThatThrownBy(session::requireAdmin)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Admin role required")
                .message().doesNotContain(USER.email()).doesNotContain(USER.fullName());
    }

    @Test
    void requireAdmin_forAnAdmin_returnsTheAdminId() {
        AppSession session = sessionWith();
        session.signIn(ADMIN);

        assertThat(session.requireAdmin()).isEqualTo(1L);
    }

    @Test
    void requireAdmin_afterTheAdminSignsOut_throwsAgain() {
        AppSession session = sessionWith();
        session.signIn(ADMIN);
        session.signOut();

        assertThatThrownBy(session::requireAdmin).isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> session.signIn(USER)).doesNotThrowAnyException();
        assertThatThrownBy(session::requireAdmin).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ log

    @Test
    void logLines_neverContainEmailOrName() {
        try (LogCapture capture = LogCapture.of(LoggerFactory.getLogger(AppSession.class))) {
            AppSession session = sessionWith();

            session.signIn(USER);
            session.signOut();
            session.signIn(ADMIN);

            List<ILoggingEvent> events = capture.events();
            assertThat(events).isNotEmpty();
            assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(USER.email()).doesNotContain(USER.fullName())
                            .doesNotContain(ADMIN.email()).doesNotContain(ADMIN.fullName()));
        }
    }
}

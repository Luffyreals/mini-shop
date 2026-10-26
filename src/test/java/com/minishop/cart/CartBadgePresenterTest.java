package com.minishop.cart;

import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.support.ManualBackgroundTasks;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CartBadgePresenter}: the number on the cart icon follows the session (AC-009-5 badge part, AC-009-10;
 * QĐ11 of the D2 plan), and a late answer for an old session never overwrites a newer one. No JavaFX toolkit: the
 * shell presenter is the real one (only {@code javafx.beans}), the cart service and the session are mocks.
 * {@code AppSession.signIn} is package-private in {@code user}, so the session is a mock and the tests call
 * {@code sessionChanged} the way the real session does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartBadgePresenterTest {

    private static final CurrentUser USER = new CurrentUser(7L, "user@minishop.local", "Khách hàng mẫu", Role.USER);
    private static final CartOwner.Customer USER_CART = new CartOwner.Customer(7L);

    @Mock
    private CartService cartService;
    @Mock
    private AppSession session;
    @Mock
    private UnexpectedErrors errors;

    private final RecordingGuestCartStore store = new RecordingGuestCartStore();
    private final ShellPresenter shell = new ShellPresenter();
    private CurrentCartOwner currentOwner;

    @BeforeEach
    void setUp() {
        currentOwner = new CurrentCartOwner(session, store);
        when(session.currentUser()).thenReturn(Optional.empty());
    }

    private void logInAs(CurrentUser user) {
        when(session.currentUser()).thenReturn(Optional.of(user));
    }

    private void logOut() {
        when(session.currentUser()).thenReturn(Optional.empty());
    }

    private CartBadgePresenter presenterWith(com.minishop.desktop.BackgroundTasks tasks) {
        return new CartBadgePresenter(cartService, currentOwner, tasks, errors);
    }

    // ------------------------------------------------------------------ AC-009-5 / AC-009-10

    @Test
    void sessionChanged_toAUserWithThreeItemsInTheDatabaseCart_showsThree() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        presenter.contribute(shell);
        when(cartService.totalQuantity(USER_CART)).thenReturn(3);

        logInAs(USER);
        presenter.sessionChanged(null, USER);

        assertThat(shell.cartCountProperty().get()).isEqualTo(3);
        assertThat(shell.cartBadgeTextBinding().get()).isEqualTo("3");
        verify(cartService).totalQuantity(USER_CART);
        verifyNoInteractions(errors);
    }

    @Test
    void sessionChanged_toGuest_afterALogin_showsZeroForTheEmptyGuestCart() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        presenter.contribute(shell);
        when(cartService.totalQuantity(USER_CART)).thenReturn(3);
        when(cartService.totalQuantity(new CartOwner.Guest(store))).thenReturn(0);
        logInAs(USER);
        presenter.sessionChanged(null, USER);
        assertThat(shell.cartCountProperty().get()).isEqualTo(3);

        logOut();
        presenter.sessionChanged(USER, null);

        assertThat(shell.cartCountProperty().get()).isZero();
        assertThat(shell.cartBadgeTextBinding().get()).isEqualTo("0");
    }

    @Test
    void contribute_readsTheCartOfTheCurrentSessionOnce() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        logInAs(USER);
        when(cartService.totalQuantity(USER_CART)).thenReturn(5);

        presenter.contribute(shell);

        assertThat(shell.cartCountProperty().get()).isEqualTo(5);
        verify(cartService).totalQuantity(USER_CART);
    }

    @Test
    void refresh_forAGuest_asksForTheGuestCartAndCreatesNoStoreEntry() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        presenter.contribute(shell);
        ArgumentCaptor<CartOwner> owner = ArgumentCaptor.forClass(CartOwner.class);

        presenter.refresh();

        verify(cartService, org.mockito.Mockito.atLeastOnce()).totalQuantity(owner.capture());
        assertThat(owner.getValue()).isEqualTo(new CartOwner.Guest(store));
        assertThat(store.getOrCreateCalls()).isZero();
    }

    @Test
    void refresh_picksUpAChangeOfTheCartAfterAWrite() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        presenter.contribute(shell);
        logInAs(USER);
        when(cartService.totalQuantity(USER_CART)).thenReturn(1, 4);

        presenter.refresh();
        assertThat(shell.cartCountProperty().get()).isEqualTo(1);
        presenter.refresh();

        assertThat(shell.cartCountProperty().get()).isEqualTo(4);
    }

    @Test
    void refresh_withAnOver99Cart_showsTheCappedBadge() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        presenter.contribute(shell);
        logInAs(USER);
        when(cartService.totalQuantity(USER_CART)).thenReturn(150);

        presenter.refresh();

        assertThat(shell.cartBadgeTextBinding().get()).isEqualTo("99+");
    }

    // ------------------------------------------------------------------ before the top bar exists

    @Test
    void refresh_andSessionChanged_beforeContribute_doNothing() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());

        presenter.refresh();
        presenter.sessionChanged(null, USER);

        verifyNoInteractions(cartService);
        assertThat(shell.cartCountProperty().get()).isZero();
    }

    @Test
    void contribute_withNull_throwsNullPointerException() {
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());

        assertThatNullPointerException().isThrownBy(() -> presenter.contribute(null));
    }

    // ------------------------------------------------------------------ stale answers

    @Test
    void refresh_whileAnEarlierOneIsPending_cancelsTheEarlierOne() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartBadgePresenter presenter = presenterWith(manual);
        presenter.contribute(shell);

        presenter.refresh();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(manual.handle(0).isCancelled()).as("the one from contribute").isTrue();
        assertThat(manual.handle(1).isCancelled()).isFalse();
    }

    @Test
    void anOldAnswerArrivingLate_isCancelledAndNeverOverwritesTheNewerBadge() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartBadgePresenter presenter = presenterWith(manual);
        presenter.contribute(shell);
        when(cartService.totalQuantity(USER_CART)).thenReturn(3);
        when(cartService.totalQuantity(new CartOwner.Guest(store))).thenReturn(0);
        // Log in, then log out at once, before any answer came back.
        logInAs(USER);
        presenter.sessionChanged(null, USER);
        logOut();
        presenter.sessionChanged(USER, null);
        assertThat(manual.submittedCount()).isEqualTo(3);

        manual.run(2);
        manual.run(1);
        manual.run(0);

        assertThat(shell.cartCountProperty().get()).as("the guest answer of the last refresh wins").isZero();
    }

    @Test
    void anOldAnswerThatWasAlreadyQueuedForDelivery_isIgnoredByTheGenerationCounter() {
        // cancel() cannot stop a callback that is already queued on the FX thread; the counter must.
        ManualBackgroundTasks manual = ManualBackgroundTasks.ignoringCancel();
        CartBadgePresenter presenter = presenterWith(manual);
        when(cartService.totalQuantity(USER_CART)).thenReturn(3);
        when(cartService.totalQuantity(new CartOwner.Guest(store))).thenReturn(0);
        logInAs(USER);
        presenter.contribute(shell);
        logOut();
        presenter.sessionChanged(USER, null);

        manual.run(1);
        manual.run(0);

        assertThat(shell.cartCountProperty().get()).as("not 3 from the old login").isZero();
    }

    @Test
    void aCancelledAnswer_neverTouchesTheBadge() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartBadgePresenter presenter = presenterWith(manual);
        presenter.contribute(shell);
        shell.setCartCount(8);
        presenter.refresh();

        manual.run(0);

        assertThat(shell.cartCountProperty().get()).as("cancelled, so untouched").isEqualTo(8);
        verify(cartService).totalQuantity(any());
    }

    @Test
    void refresh_readsTheOwnerWhenCalled_notWhenTheBackgroundWorkRuns() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartBadgePresenter presenter = presenterWith(manual);
        logInAs(USER);
        presenter.contribute(shell);
        when(cartService.totalQuantity(any())).thenReturn(2);
        logOut();

        manual.run(0);

        verify(cartService).totalQuantity(USER_CART);
        verify(cartService, never()).totalQuantity(new CartOwner.Guest(store));
        assertThat(shell.cartCountProperty().get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ failures

    @Test
    void refresh_whenTheServiceFails_reportsTheErrorOnceAndLeavesTheBadgeAlone() {
        RuntimeException failure = new IllegalStateException("database is down");
        CartBadgePresenter presenter = presenterWith(new DirectBackgroundTasks());
        presenter.contribute(shell);
        shell.setCartCount(4);
        when(cartService.totalQuantity(any())).thenThrow(failure);

        presenter.refresh();

        verify(errors).report(failure);
        assertThat(shell.cartCountProperty().get()).isEqualTo(4);
    }
}

package com.minishop.order;

import com.minishop.cart.CheckoutEntry;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.user.AccountMenu;
import com.minishop.user.AccountMenuItem;
import com.minishop.user.LoginPrompt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OrdersMenuPresenter}: the account-menu entry "Đơn hàng của tôi" and the checkout entry point used by the cart
 * drawer both go through {@link LoginPrompt#requireLogin} (AC-009-4, AC-005-2, -6, -25; QĐ3 of D4). No JavaFX toolkit:
 * the login prompt is a mock that either runs the action at once (logged in) or only remembers it (guest).
 */
@ExtendWith(MockitoExtension.class)
class OrdersMenuPresenterTest {

    @Mock
    private AccountMenu accountMenu;
    @Mock
    private LoginPrompt loginPrompt;
    @Mock
    private Navigator navigator;

    private final ShellPresenter shell = new ShellPresenter();

    private OrdersMenuPresenter presenter() {
        return new OrdersMenuPresenter(accountMenu, loginPrompt, navigator);
    }

    /** A signed-in session: {@code requireLogin} runs the action immediately. */
    private void loggedIn() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(loginPrompt).requireLogin(any());
    }

    private Runnable ordersAction(OrdersMenuPresenter presenter) {
        presenter.contribute(shell);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(accountMenu).setAction(eq(AccountMenuItem.ORDERS), action.capture());
        return action.getValue();
    }

    private Runnable rememberedByThePrompt() {
        ArgumentCaptor<Runnable> remembered = ArgumentCaptor.forClass(Runnable.class);
        verify(loginPrompt).requireLogin(remembered.capture());
        return remembered.getValue();
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void contribute_givesTheOrdersEntryOfTheAccountMenuAnAction() {
        OrdersMenuPresenter presenter = presenter();

        presenter.contribute(shell);

        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(accountMenu).setAction(eq(AccountMenuItem.ORDERS), action.capture());
        assertThat(action.getValue()).isNotNull();
        verifyNoMoreInteractions(accountMenu);
    }

    @Test
    void contribute_doesNotNavigateOrAskForLoginByItself() {
        presenter().contribute(shell);

        verifyNoInteractions(navigator, loginPrompt);
    }

    @Test
    void presenter_isTheCheckoutEntryTheCartDrawerCalls() {
        assertThat(presenter()).isInstanceOf(CheckoutEntry.class);
    }

    // ------------------------------------------------------------------ "Đơn hàng của tôi" (AC-005-25)

    @Test
    void ordersAction_whenLoggedIn_opensTheFirstPageOfTheOrdersAtOnce() {
        loggedIn();
        Runnable action = ordersAction(presenter());

        action.run();

        verify(navigator).navigateTo(OrdersRoute.first());
        assertThat(OrdersRoute.first().page()).isEqualTo(1);
    }

    @Test
    void ordersAction_asAGuest_navigatesOnlyWhenTheRememberedActionRunsAfterLogin() {
        Runnable action = ordersAction(presenter());

        action.run();

        verifyNoInteractions(navigator);
        Runnable afterLogin = rememberedByThePrompt();
        verifyNoInteractions(navigator);

        afterLogin.run();

        verify(navigator).navigateTo(OrdersRoute.first());
    }

    @Test
    void ordersAction_asAGuest_whenTheDialogIsClosedWithoutLoggingIn_neverNavigates() {
        Runnable action = ordersAction(presenter());

        action.run();
        rememberedByThePrompt();

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void ordersAction_everyClickGoesThroughRequireLogin() {
        Runnable action = ordersAction(presenter());

        action.run();
        action.run();

        verify(loginPrompt, times(2)).requireLogin(any());
        verifyNoInteractions(navigator);
    }

    // ------------------------------------------------------------------ "Đặt hàng" (AC-009-4, AC-005-2, -6)

    @Test
    void open_whenLoggedIn_opensTheCheckoutAtOnce() {
        loggedIn();

        presenter().open();

        verify(navigator).navigateTo(new CheckoutRoute());
    }

    @Test
    void open_asAGuest_asksForLoginFirstAndOpensTheCheckoutOnlyAfterTheRememberedActionRuns() {
        presenter().open();

        verifyNoInteractions(navigator);
        Runnable afterLogin = rememberedByThePrompt();

        afterLogin.run();

        verify(navigator).navigateTo(new CheckoutRoute());
    }

    @Test
    void open_asAGuest_whoClosesTheDialog_neverReachesTheCheckout() {
        presenter().open();

        rememberedByThePrompt();

        verifyNoInteractions(navigator);
    }

    @Test
    void open_whileTheCheckoutIsAlreadyShowing_doesNotNavigateSoTheTypedFormSurvives() {
        loggedIn();
        when(navigator.currentRoute()).thenReturn(new CheckoutRoute());

        presenter().open();

        verify(loginPrompt).requireLogin(any());
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void open_whileAnotherScreenIsShowing_stillOpensTheCheckout() {
        loggedIn();
        when(navigator.currentRoute()).thenReturn(OrdersRoute.first());

        presenter().open();

        verify(navigator).navigateTo(new CheckoutRoute());
    }

    @Test
    void open_asAGuestOnTheCheckout_decidesWhenTheRememberedActionRuns() {
        presenter().open();
        Runnable afterLogin = rememberedByThePrompt();
        when(navigator.currentRoute()).thenReturn(new CheckoutRoute());

        afterLogin.run();

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void open_neverNavigatesToAnythingButTheCheckout() {
        loggedIn();

        presenter().open();

        verify(navigator).currentRoute();
        verify(navigator).navigateTo(new CheckoutRoute());
        verifyNoMoreInteractions(navigator);
    }
}

package com.minishop.admin;

import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.user.AccountMenu;
import com.minishop.user.AccountMenuItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link AdminMenuPresenter} (AC-009-8, QĐ2 of the D5 plan) and {@link AdminNavPresenter} (AC-006-4): the entry
 * points of the admin area. No JavaFX toolkit; the account menu and navigator are mocks.
 */
@ExtendWith(MockitoExtension.class)
class AdminMenuPresenterTest {

    @Mock
    private AccountMenu accountMenu;
    @Mock
    private Navigator navigator;
    @Mock
    private ShellPresenter shell;

    // ------------------------------------------------------------------ AdminMenuPresenter

    @Test
    void contribute_attachesAnActionToTheAdminAccountMenuEntry() {
        new AdminMenuPresenter(accountMenu, navigator).contribute(shell);

        verify(accountMenu).setAction(eq(AccountMenuItem.ADMIN), any(Runnable.class));
        verifyNoMoreInteractions(accountMenu);
    }

    @Test
    void contribute_doesNotOpenAnythingYet() {
        new AdminMenuPresenter(accountMenu, navigator).contribute(shell);

        verifyNoInteractions(navigator);
    }

    @Test
    void contribute_doesNotTouchTheOtherAccountMenuEntries() {
        new AdminMenuPresenter(accountMenu, navigator).contribute(shell);

        verify(accountMenu, never()).setAction(eq(AccountMenuItem.ORDERS), any(Runnable.class));
    }

    @Test
    void theAdminAction_opensTheDashboard() {
        new AdminMenuPresenter(accountMenu, navigator).contribute(shell);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(accountMenu).setAction(eq(AccountMenuItem.ADMIN), action.capture());

        action.getValue().run();

        verify(navigator).navigateTo(new AdminDashboardRoute());
    }

    @Test
    void theAdminAction_opensTheDashboardEachTimeItRuns() {
        new AdminMenuPresenter(accountMenu, navigator).contribute(shell);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(accountMenu).setAction(eq(AccountMenuItem.ADMIN), action.capture());

        action.getValue().run();
        action.getValue().run();

        verify(navigator, org.mockito.Mockito.times(2)).navigateTo(new AdminDashboardRoute());
    }

    // ------------------------------------------------------------------ AdminNavPresenter

    @Test
    void navOpen_eachSection_navigatesToTheFirstRouteOfThatSection() {
        AdminNavPresenter nav = new AdminNavPresenter(navigator);

        nav.open(AdminSection.DASHBOARD);
        nav.open(AdminSection.ORDERS);
        nav.open(AdminSection.PRODUCTS);
        nav.open(AdminSection.CATEGORIES);

        ArgumentCaptor<com.minishop.desktop.Route> routes = ArgumentCaptor.forClass(com.minishop.desktop.Route.class);
        verify(navigator, org.mockito.Mockito.times(4)).navigateTo(routes.capture());
        assertThat(routes.getAllValues()).containsExactly(new AdminDashboardRoute(), AdminOrdersRoute.all(),
                AdminProductsRoute.first(), new AdminCategoriesRoute());
    }

    @Test
    void navOpen_theSectionAlreadyShowing_navigatesAgain_soTheScreenReloadsFromTheFirstPage() {
        AdminNavPresenter nav = new AdminNavPresenter(navigator);

        nav.open(AdminSection.PRODUCTS);
        nav.open(AdminSection.PRODUCTS);

        verify(navigator, org.mockito.Mockito.times(2)).navigateTo(AdminProductsRoute.first());
    }

    @Test
    void navOpen_withNull_throwsNullPointerException() {
        AdminNavPresenter nav = new AdminNavPresenter(navigator);

        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> nav.open(null));
        verifyNoInteractions(navigator);
    }
}

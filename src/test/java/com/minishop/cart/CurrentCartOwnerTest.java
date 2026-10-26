package com.minishop.cart;

import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CurrentCartOwner}: the one place that decides who owns the cart (BR-004-8 on the desktop). The real
 * {@code AppSession} cannot be built here (its test constructor is package-private in {@code user}), so it is mocked.
 */
class CurrentCartOwnerTest {

    private final AppSession session = mock(AppSession.class);
    private final RecordingGuestCartStore store = new RecordingGuestCartStore();
    private final CurrentCartOwner currentOwner = new CurrentCartOwner(session, store);

    @Test
    void get_forAGuest_isTheGuestOwnerWithTheStore_andCreatesNothing() {
        when(session.currentUser()).thenReturn(Optional.empty());

        CartOwner owner = currentOwner.get();

        assertThat(owner).isInstanceOfSatisfying(CartOwner.Guest.class,
                guest -> assertThat(guest.store()).isSameAs(store));
        assertThat(store.getOrCreateCalls()).isZero();
        assertThat(store.saveCalls()).isZero();
        assertThat(store.find()).isEmpty();
    }

    @Test
    void get_forALoggedInUser_isTheCustomerWithThatId() {
        when(session.currentUser()).thenReturn(Optional.of(new CurrentUser(42L, "u@x.test", "U", Role.USER)));

        assertThat(currentOwner.get()).isEqualTo(new CartOwner.Customer(42L));
    }

    @Test
    void get_forAnAdmin_isAlsoTheCustomerOfTheirOwnCart() {
        when(session.currentUser()).thenReturn(Optional.of(new CurrentUser(1L, "a@x.test", "A", Role.ADMIN)));

        assertThat(currentOwner.get()).isEqualTo(new CartOwner.Customer(1L));
    }

    @Test
    void get_readsTheSessionAgainOnEveryCall() {
        when(session.currentUser())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new CurrentUser(5L, "u@x.test", "U", Role.USER)))
                .thenReturn(Optional.empty());

        assertThat(currentOwner.get()).isInstanceOf(CartOwner.Guest.class);
        assertThat(currentOwner.get()).isEqualTo(new CartOwner.Customer(5L));
        assertThat(currentOwner.get()).isInstanceOf(CartOwner.Guest.class);
    }
}

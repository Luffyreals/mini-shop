package com.minishop.cart;

import com.minishop.catalog.Category;
import com.minishop.catalog.Product;
import com.minishop.user.Role;
import com.minishop.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

/** API công khai hẹp của cart cho order (BR-005-1, 2, 3): thứ tự khóa, thứ tự dòng và quy tắc "đặt được". */
@ExtendWith(MockitoExtension.class)
class CartCheckoutTest {

    private static final long USER_ID = 7L;

    @Mock
    CartRepository cartRepository;

    CartCheckout cartCheckout;

    private final User user = new User("buyer@cart.test", "hash", "Buyer", Role.USER);

    @BeforeEach
    void setUp() {
        cartCheckout = new CartCheckout(cartRepository);
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    private static Product product(long id, String name, String price, int stock, boolean active) {
        Product p = new Product(new Category("Cat", null), name, null, new BigDecimal(price), stock);
        ReflectionTestUtils.setField(p, "id", id);
        p.setActive(active);
        return p;
    }

    private static void addItem(Cart cart, Product product, int quantity, long itemId) {
        CartItem item = cart.addItem(product, quantity);
        ReflectionTestUtils.setField(item, "id", itemId);
    }

    /** Items are added in a different order from their ids to prove the sort. */
    private Cart cartWithTwoItems() {
        Cart cart = new Cart(user);
        addItem(cart, product(3, "Third by item id", "20000", 5, true), 2, 12L);
        addItem(cart, product(9, "First by item id", "10000", 9, true), 1, 11L);
        return cart;
    }

    // ---------- lockAndRead ----------

    /** BR-005-3 / ADR-0008: the users row is locked before the cart is read. */
    @Test
    void lockAndRead_locksOwnerBeforeReadingCart() {
        when(cartRepository.lockOwner(USER_ID)).thenReturn(Optional.of(user));
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.of(cartWithTwoItems()));

        cartCheckout.lockAndRead(USER_ID);

        InOrder order = inOrder(cartRepository);
        order.verify(cartRepository).lockOwner(USER_ID);
        order.verify(cartRepository).findWithItemsByUserId(USER_ID);
    }

    @Test
    void lockAndRead_withUnknownUser_throwsIllegalStateAndReadsNoCart() {
        when(cartRepository.lockOwner(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartCheckout.lockAndRead(USER_ID)).isInstanceOf(IllegalStateException.class);

        verify(cartRepository, never()).findWithItemsByUserId(anyLong());
    }

    @Test
    void lockAndRead_returnsPlainLinesSortedByCartItemId() {
        when(cartRepository.lockOwner(USER_ID)).thenReturn(Optional.of(user));
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.of(cartWithTwoItems()));

        List<CheckoutLine> lines = cartCheckout.lockAndRead(USER_ID);

        assertThat(lines).extracting(CheckoutLine::name).containsExactly("First by item id", "Third by item id");
        assertThat(lines.getFirst()).isEqualTo(
                new CheckoutLine(9L, "First by item id", new BigDecimal("10000"), 1, true, 9));
        assertThat(lines.get(1).quantity()).isEqualTo(2);
        assertThat(lines.get(1).stock()).isEqualTo(5);
    }

    @Test
    void lockAndRead_withoutCart_returnsEmptyList() {
        when(cartRepository.lockOwner(USER_ID)).thenReturn(Optional.of(user));
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(cartCheckout.lockAndRead(USER_ID)).isEmpty();
    }

    // ---------- read ----------

    @Test
    void read_doesNotLockAndReturnsLinesInCartOrder() {
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.of(cartWithTwoItems()));

        List<CheckoutLine> lines = cartCheckout.read(USER_ID);

        assertThat(lines).extracting(CheckoutLine::productId).containsExactly(9L, 3L);
        verify(cartRepository, never()).lockOwner(anyLong());
    }

    @Test
    void read_withoutCart_returnsEmptyList() {
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(cartCheckout.read(USER_ID)).isEmpty();
    }

    // ---------- clear ----------

    @Test
    void clear_deletesItemsOfUserOnly() {
        cartCheckout.clear(USER_ID);

        verify(cartRepository).deleteItemsByUserId(USER_ID);
        verifyNoMoreInteractions(cartRepository);
    }

    // ---------- CheckoutLine ----------

    /** BR-005-2: orderable only when active, stock > 0 and quantity <= stock. */
    @ParameterizedTest(name = "active={0} stock={1} quantity={2} -> {3}")
    @CsvSource({
            "true,  5, 1, true",
            "true,  5, 5, true",
            "true,  1, 1, true",
            "true,  5, 6, false",
            "true,  0, 1, false",
            "false, 5, 1, false",
            "false, 0, 1, false",
            "false, 1, 5, false"})
    void orderable_onlyWhenActiveInStockAndQuantityWithinStock(boolean active, int stock, int quantity,
                                                               boolean expected) {
        CheckoutLine line = new CheckoutLine(1, "P", BigDecimal.TEN, quantity, active, stock);

        assertThat(line.orderable()).isEqualTo(expected);
    }

    @Test
    void lineTotal_isUnitPriceTimesQuantity() {
        CheckoutLine line = new CheckoutLine(1, "P", new BigDecimal("125000"), 3, true, 10);

        assertThat(line.lineTotal()).isEqualByComparingTo("375000");
    }
}

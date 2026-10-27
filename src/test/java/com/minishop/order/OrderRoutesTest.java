package com.minishop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The route records of the order screens (QĐ2 of the D4 plan): validation, defaults and equality. */
class OrderRoutesTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void ordersRoute_withPageBelowOne_throwsIllegalArgument(int page) {
        assertThatIllegalArgumentException().isThrownBy(() -> new OrdersRoute(page));
    }

    @Test
    void ordersRoute_first_isPageOne() {
        assertThat(OrdersRoute.first().page()).isEqualTo(1);
        assertThat(OrdersRoute.first()).isEqualTo(new OrdersRoute(1));
    }

    @Test
    void ordersRoute_equalityFollowsThePage() {
        assertThat(new OrdersRoute(3)).isEqualTo(new OrdersRoute(3)).hasSameHashCodeAs(new OrdersRoute(3));
        assertThat(new OrdersRoute(3)).isNotEqualTo(new OrdersRoute(2));
    }

    @Test
    void orderDetailRoute_withNullBack_goesBackToTheFirstPageOfOrders() {
        assertThat(new OrderDetailRoute(1, null, false).back()).isEqualTo(OrdersRoute.first());
    }

    @Test
    void orderDetailRoute_keepsAGivenBackPage() {
        assertThat(new OrderDetailRoute(1, new OrdersRoute(4), true).back()).isEqualTo(new OrdersRoute(4));
    }

    @Test
    void orderDetailRoute_equalityIncludesTheNormalizedBackAndTheJustPlacedFlag() {
        assertThat(new OrderDetailRoute(5, null, false)).isEqualTo(new OrderDetailRoute(5, OrdersRoute.first(), false))
                .hasSameHashCodeAs(new OrderDetailRoute(5, OrdersRoute.first(), false));
        assertThat(new OrderDetailRoute(5, null, false)).isNotEqualTo(new OrderDetailRoute(5, null, true));
        assertThat(new OrderDetailRoute(5, null, false)).isNotEqualTo(new OrderDetailRoute(6, null, false));
        assertThat(new OrderDetailRoute(5, null, false)).isNotEqualTo(new OrderDetailRoute(5, new OrdersRoute(2), false));
    }

    @Test
    void checkoutRoute_instancesAreEqual() {
        assertThat(new CheckoutRoute()).isEqualTo(new CheckoutRoute());
    }
}

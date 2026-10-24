package com.minishop.order;

import com.minishop.user.Role;
import com.minishop.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Luồng trạng thái đơn (AC-005-31; BR-005-8, BR-005-9): đủ 25 cặp (from, to). */
class OrderStatusTest {

    private record Pair(OrderStatus from, OrderStatus to) {
    }

    private static final Set<Pair> ALLOWED = Set.of(
            new Pair(OrderStatus.PENDING, OrderStatus.CONFIRMED),
            new Pair(OrderStatus.CONFIRMED, OrderStatus.SHIPPING),
            new Pair(OrderStatus.SHIPPING, OrderStatus.DELIVERED),
            new Pair(OrderStatus.PENDING, OrderStatus.CANCELLED),
            new Pair(OrderStatus.CONFIRMED, OrderStatus.CANCELLED));

    static Stream<Arguments> allPairs() {
        return EnumSet.allOf(OrderStatus.class).stream()
                .flatMap(from -> EnumSet.allOf(OrderStatus.class).stream()
                        .map(to -> Arguments.of(from, to, ALLOWED.contains(new Pair(from, to)))));
    }

    private static Order orderWith(OrderStatus status) {
        Order order = new Order(new User("a@order.test", "hash", "A", Role.USER), "A", "0912345678", "Addr",
                BigDecimal.TEN);
        if (status != OrderStatus.PENDING) {
            org.springframework.test.util.ReflectionTestUtils.setField(order, "status", status);
        }
        return order;
    }

    @Test
    void allPairs_coverTwentyFiveCombinationsWithFiveAllowed() {
        assertThat(allPairs().count()).isEqualTo(25);
        assertThat(ALLOWED).hasSize(5);
    }

    /** AC-005-31 */
    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @MethodSource("allPairs")
    void canTransitionTo_allowsOnlyTheFiveDefinedSteps(OrderStatus from, OrderStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    /** BR-005-9 */
    @Test
    void label_isTheVietnameseTextOfEachStatus() {
        assertThat(OrderStatus.PENDING.label()).isEqualTo("Chờ xử lý");
        assertThat(OrderStatus.CONFIRMED.label()).isEqualTo("Đã xác nhận");
        assertThat(OrderStatus.SHIPPING.label()).isEqualTo("Đang giao");
        assertThat(OrderStatus.DELIVERED.label()).isEqualTo("Đã giao");
        assertThat(OrderStatus.CANCELLED.label()).isEqualTo("Đã hủy");
    }

    @Test
    void paymentMethodLabel_isCodText() {
        assertThat(PaymentMethod.COD.label()).isEqualTo("Thanh toán khi nhận hàng (COD)");
    }

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @MethodSource("allPairs")
    void transitionTo_changesStatusOnlyWhenAllowed(OrderStatus from, OrderStatus to, boolean allowed) {
        Order order = orderWith(from);

        if (allowed) {
            order.transitionTo(to);
            assertThat(order.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> order.transitionTo(to)).isInstanceOf(IllegalStateException.class);
            assertThat(order.getStatus()).isEqualTo(from);
        }
    }

    @Test
    void newOrder_startsPendingWithCodPayment() {
        Order order = orderWith(OrderStatus.PENDING);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.COD);
    }

    /** AC-006-41 */
    @Test
    void actionLabel_isTheButtonTextOfTheStepIntoEachStatus() {
        assertThat(OrderStatus.CONFIRMED.actionLabel()).isEqualTo("Xác nhận đơn");
        assertThat(OrderStatus.SHIPPING.actionLabel()).isEqualTo("Bắt đầu giao hàng");
        assertThat(OrderStatus.DELIVERED.actionLabel()).isEqualTo("Xác nhận đã giao");
        assertThat(OrderStatus.CANCELLED.actionLabel()).isEqualTo("Hủy đơn");
    }

    @Test
    void actionLabel_ofPending_throwsBecausePendingIsNeverATarget() {
        assertThatThrownBy(OrderStatus.PENDING::actionLabel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void actionLabel_existsForEveryStatusThatIsATransitionTarget() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                if (from.canTransitionTo(to)) {
                    assertThat(to.actionLabel()).isNotBlank();
                }
            }
        }
    }

    /** AC-006-45, AC-006-39: a status is its exact enum name; case, spaces inside and junk are rejected. */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void orderInputStatus_acceptsTheExactNameWithSurroundingSpaces(OrderStatus status) {
        assertThat(OrderInput.status(status.name())).contains(status);
        assertThat(OrderInput.status("  " + status.name() + "	")).contains(status);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "abc", "pending", "Pending", "PEN DING", "CONFIRMED,SHIPPING", "0", "Chờ xử lý"})
    void orderInputStatus_rejectsEverythingElse(String raw) {
        assertThat(OrderInput.status(raw)).isEmpty();
    }
}

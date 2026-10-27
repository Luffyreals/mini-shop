package com.minishop.order;

import com.minishop.catalog.Category;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductRepository;
import com.minishop.common.AdminAccess;
import com.minishop.common.NotFoundException;
import com.minishop.user.Role;
import com.minishop.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Admin order rules (AC-006-6, 8, 38..45; BR-006-12..14). Repositories are mocks; the race between admin and
 * customer and the double cancel are proven on PostgreSQL in TW5.
 */
@ExtendWith(MockitoExtension.class)
class OrderAdminServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long ORDER_ID = 5L;

    @Mock
    OrderRepository orderRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    AdminAccess adminAccess;

    OrderAdminService service;

    private final User customer = new User("buyer@order.test", "hash", "Nguyễn Văn An", Role.USER);
    private final Category category = new Category("Cat", null);
    private long nextItemId = 1;

    @BeforeEach
    void setUp() {
        lenient().when(adminAccess.requireAdmin()).thenReturn(ADMIN_ID);
        service = new OrderAdminService(orderRepository, productRepository, adminAccess);
        ReflectionTestUtils.setField(customer, "id", 2L);
    }

    // ---------- helpers ----------

    private Product product(long id) {
        Product p = new Product(category, "Product " + id, null, BigDecimal.TEN, 10);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** An order with the given status and items {@code (productId, quantity)} added in the given order. */
    private Order order(OrderStatus status, long id, long[]... items) {
        Order order = new Order(customer, "Trần Thị Bình", "0912345678", "12 Lê Lợi, Quận 1", new BigDecimal("450000"));
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-10-24T17:30:00Z"));
        ReflectionTestUtils.setField(order, "updatedAt", Instant.parse("2026-10-25T01:00:00Z"));
        for (long[] item : items) {
            OrderItem oi = order.addItem(product(item[0]), "Name " + item[0], new BigDecimal("1000"), (int) item[1]);
            ReflectionTestUtils.setField(oi, "id", nextItemId++);
        }
        return order;
    }

    private void stubLock(Order order) {
        when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private void verifyNoStockRestored() {
        verify(productRepository, never()).incrementStock(anyLong(), anyInt());
        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
    }

    // ---------- changeStatus: allowed steps ----------

    static Stream<Arguments> forwardSteps() {
        return Stream.of(
                Arguments.of(OrderStatus.PENDING, "CONFIRMED", "Đã xác nhận"),
                Arguments.of(OrderStatus.CONFIRMED, "SHIPPING", "Đang giao"),
                Arguments.of(OrderStatus.SHIPPING, "DELIVERED", "Đã giao"));
    }

    /** AC-006-42, 43 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("forwardSteps")
    void changeStatus_forwardStep_changesStatusWithoutTouchingStock(OrderStatus from, String target, String label) {
        Order order = order(from, ORDER_ID, new long[]{3, 2});
        stubLock(order);

        StatusChangeResult result = service.changeStatus(ORDER_ID, target);

        assertThat(result).isEqualTo(new StatusChangeResult.Changed(label));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.valueOf(target));
        verifyNoStockRestored();
    }

    /** AC-006-44, BR-006-13: stock goes back in ascending product id, whatever the order of the lines. */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED"})
    void changeStatus_cancel_restoresStockInAscendingProductIdOrder(OrderStatus from) {
        Order order = order(from, ORDER_ID, new long[]{9, 1}, new long[]{3, 2});
        stubLock(order);
        when(productRepository.incrementStock(anyLong(), anyInt())).thenReturn(1);

        StatusChangeResult result = service.changeStatus(ORDER_ID, "CANCELLED");

        assertThat(result).isEqualTo(new StatusChangeResult.Cancelled());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        InOrder inOrder = inOrder(orderRepository, productRepository);
        inOrder.verify(orderRepository).lockById(ORDER_ID);
        inOrder.verify(productRepository).incrementStock(3L, 2);
        inOrder.verify(productRepository).incrementStock(9L, 1);
        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void changeStatus_cancel_whenARestoreAffectsNoRow_throwsSoTheTransactionRollsBack() {
        Order order = order(OrderStatus.PENDING, ORDER_ID, new long[]{3, 2});
        stubLock(order);
        when(productRepository.incrementStock(3L, 2)).thenReturn(0);

        assertThatThrownBy(() -> service.changeStatus(ORDER_ID, "CANCELLED"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changeStatus_acceptsTargetWithSurroundingSpaces() {
        stubLock(order(OrderStatus.PENDING, ORDER_ID, new long[]{3, 2}));

        assertThat(service.changeStatus(ORDER_ID, " CONFIRMED "))
                .isEqualTo(new StatusChangeResult.Changed("Đã xác nhận"));
    }

    // ---------- changeStatus: refused steps ----------

    static Stream<Arguments> refusedSteps() {
        return Stream.of(
                Arguments.of(OrderStatus.SHIPPING, "CANCELLED", "Đang giao", "Đã hủy"),
                Arguments.of(OrderStatus.DELIVERED, "CANCELLED", "Đã giao", "Đã hủy"),
                Arguments.of(OrderStatus.DELIVERED, "PENDING", "Đã giao", "Chờ xử lý"),
                Arguments.of(OrderStatus.DELIVERED, "SHIPPING", "Đã giao", "Đang giao"),
                Arguments.of(OrderStatus.CANCELLED, "CONFIRMED", "Đã hủy", "Đã xác nhận"),
                Arguments.of(OrderStatus.CANCELLED, "CANCELLED", "Đã hủy", "Đã hủy"),
                Arguments.of(OrderStatus.PENDING, "SHIPPING", "Chờ xử lý", "Đang giao"),
                Arguments.of(OrderStatus.PENDING, "DELIVERED", "Chờ xử lý", "Đã giao"),
                Arguments.of(OrderStatus.PENDING, "PENDING", "Chờ xử lý", "Chờ xử lý"),
                Arguments.of(OrderStatus.CONFIRMED, "PENDING", "Đã xác nhận", "Chờ xử lý"),
                Arguments.of(OrderStatus.CONFIRMED, "DELIVERED", "Đã xác nhận", "Đã giao"),
                Arguments.of(OrderStatus.SHIPPING, "CONFIRMED", "Đang giao", "Đã xác nhận"));
    }

    /** AC-006-45: nothing is written, and the labels come from the status enum. */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("refusedSteps")
    void changeStatus_stepNotInTheTable_returnsNotAllowedAndWritesNothing(OrderStatus from, String target,
                                                                         String fromLabel, String toLabel) {
        Order order = order(from, ORDER_ID, new long[]{3, 2});
        stubLock(order);

        StatusChangeResult result = service.changeStatus(ORDER_ID, target);

        assertThat(result).isEqualTo(new StatusChangeResult.NotAllowed(fromLabel, toLabel));
        assertThat(order.getStatus()).isEqualTo(from);
        verifyNoStockRestored();
    }

    /** AC-006-45, BR-006-13: a bad value never locks anything. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"abc", "", "   ", "pending", "Confirmed", "CONFIRMED;", "0"})
    void changeStatus_invalidTarget_returnsInvalidStatusWithoutLockingOrWriting(String raw) {
        StatusChangeResult result = service.changeStatus(ORDER_ID, raw);

        assertThat(result).isEqualTo(new StatusChangeResult.InvalidStatus());
        verifyNoInteractions(orderRepository, productRepository);
    }

    /** AC-006-5 */
    @Test
    void changeStatus_whenOrderMissing_throwsNotFoundAndRestoresNothing() {
        when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(ORDER_ID, "CONFIRMED"))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(productRepository);
    }

    /** BR-006-13: the admin lock is by id only and is never the customer (IDOR) lock. */
    @Test
    void changeStatus_locksByIdAndNeverUsesTheCustomerLock() {
        stubLock(order(OrderStatus.PENDING, ORDER_ID, new long[]{3, 2}));

        service.changeStatus(ORDER_ID, "CONFIRMED");

        verify(orderRepository).lockById(ORDER_ID);
        verify(orderRepository, never()).lockByIdAndUserId(anyLong(), anyLong());
        verify(orderRepository, never()).findAdminDetailById(anyLong());
    }

    // ---------- getOrder ----------

    static Stream<Arguments> actionsPerStatus() {
        return Stream.of(
                Arguments.of(OrderStatus.PENDING, List.of("CONFIRMED", "CANCELLED"),
                        List.of("Xác nhận đơn", "Hủy đơn")),
                Arguments.of(OrderStatus.CONFIRMED, List.of("SHIPPING", "CANCELLED"),
                        List.of("Bắt đầu giao hàng", "Hủy đơn")),
                Arguments.of(OrderStatus.SHIPPING, List.of("DELIVERED"), List.of("Xác nhận đã giao")),
                Arguments.of(OrderStatus.DELIVERED, List.of(), List.of()),
                Arguments.of(OrderStatus.CANCELLED, List.of(), List.of()));
    }

    /** AC-006-41, BR-006-12: the buttons are derived from {@code canTransitionTo}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("actionsPerStatus")
    void getOrder_actionsFollowTheTransitionTableInOrder(OrderStatus status, List<String> targets,
                                                         List<String> labels) {
        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.of(order(status, ORDER_ID, new long[]{3, 2})));

        AdminOrderDetail detail = service.getOrder(ORDER_ID);

        assertThat(detail.actions()).extracting(StatusAction::targetStatus).containsExactlyElementsOf(targets);
        assertThat(detail.actions()).extracting(StatusAction::label).containsExactlyElementsOf(labels);
        assertThat(detail.isFinalStatus()).isEqualTo(targets.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void getOrder_everyActionIsAStepTheTableAllows(OrderStatus status) {
        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.of(order(status, ORDER_ID, new long[]{3, 2})));

        AdminOrderDetail detail = service.getOrder(ORDER_ID);

        for (OrderStatus target : OrderStatus.values()) {
            boolean shown = detail.actions().stream().anyMatch(a -> a.targetStatus().equals(target.name()));
            assertThat(shown).as("%s -> %s", status, target).isEqualTo(status.canTransitionTo(target));
        }
    }

    /** AC-006-49: only the irreversible steps carry a confirm message, with the exact spec text. */
    @Test
    void getOrder_confirmMessagesOnlyForDeliveredAndCancelled() {
        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.PENDING, ORDER_ID, new long[]{3, 2})));
        AdminOrderDetail pending = service.getOrder(ORDER_ID);
        assertThat(pending.actions()).extracting(StatusAction::confirmMessage)
                .containsExactly(null, "Hủy đơn hàng #5 và hoàn lại tồn kho?");
        assertThat(pending.actions()).extracting(StatusAction::danger).containsExactly(false, true);

        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED, ORDER_ID, new long[]{3, 2})));
        assertThat(service.getOrder(ORDER_ID).actions()).extracting(StatusAction::confirmMessage)
                .containsExactly(null, "Hủy đơn hàng #5 và hoàn lại tồn kho?");

        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.of(order(OrderStatus.SHIPPING, ORDER_ID, new long[]{3, 2})));
        AdminOrderDetail shipping = service.getOrder(ORDER_ID);
        assertThat(shipping.actions()).extracting(StatusAction::confirmMessage)
                .containsExactly("Đánh dấu đơn hàng #5 đã giao? Không thể hoàn tác.");
        assertThat(shipping.actions()).extracting(StatusAction::danger).containsExactly(false);
    }

    /** AC-006-40 */
    @Test
    void getOrder_exposesAccountReceiverSnapshotLinesAndTimestamps() {
        Order order = order(OrderStatus.SHIPPING, ORDER_ID, new long[]{9, 1}, new long[]{3, 2});
        order.setNote("Giao giờ hành chính");
        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.of(order));

        AdminOrderDetail detail = service.getOrder(ORDER_ID);

        assertThat(detail.id()).isEqualTo(ORDER_ID);
        assertThat(detail.customerName()).isEqualTo("Nguyễn Văn An");
        assertThat(detail.customerEmail()).isEqualTo("buyer@order.test");
        assertThat(detail.receiverName()).isEqualTo("Trần Thị Bình");
        assertThat(detail.phone()).isEqualTo("0912345678");
        assertThat(detail.shippingAddress()).isEqualTo("12 Lê Lợi, Quận 1");
        assertThat(detail.note()).isEqualTo("Giao giờ hành chính");
        assertThat(detail.statusLabel()).isEqualTo("Đang giao");
        assertThat(detail.statusKey()).isEqualTo("shipping");
        assertThat(detail.paymentMethodLabel()).isEqualTo(PaymentMethod.COD.label());
        assertThat(detail.totalAmount()).isEqualByComparingTo("450000");
        assertThat(detail.createdAt()).isEqualTo(Instant.parse("2026-10-24T17:30:00Z"));
        assertThat(detail.updatedAt()).isEqualTo(Instant.parse("2026-10-25T01:00:00Z"));
        assertThat(detail.lines()).containsExactly(
                new OrderLineView("Name 9", new BigDecimal("1000"), 1, new BigDecimal("1000")),
                new OrderLineView("Name 3", new BigDecimal("1000"), 2, new BigDecimal("2000")));
    }

    @Test
    void getOrder_whenMissing_throwsNotFound() {
        when(orderRepository.findAdminDetailById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(ORDER_ID)).isInstanceOf(NotFoundException.class);
    }

    // ---------- listOrders ----------

    @SuppressWarnings("unchecked")
    private Page<Order> pageOf(Pageable pageable, long total, Order... orders) {
        return new PageImpl<>(List.of(orders), pageable, total);
    }

    /** AC-006-39 */
    @Test
    void listOrders_withStatusAndPage_queriesByStatusPageIndexOneSizeTwentyNewestFirst() {
        when(orderRepository.findByStatus(any(OrderStatus.class), any(Pageable.class)))
                .thenAnswer(inv -> pageOf(inv.getArgument(1), 25, order(OrderStatus.PENDING, 7)));

        AdminOrderPage page = service.listOrders("PENDING", "2");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByStatus(any(OrderStatus.class), captor.capture());
        verify(orderRepository, never()).findAllBy(any());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().toList())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(page.status()).isEqualTo("PENDING");
        assertThat(page.statusLabel()).isEqualTo("Chờ xử lý");
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    /** AC-006-38, 39: a bad status behaves as "Tất cả"; a bad page as page 1. */
    @Test
    void listOrders_withBadStatusAndBadPage_listsAllOrdersOnPageOne() {
        when(orderRepository.findAllBy(any(Pageable.class)))
                .thenAnswer(inv -> pageOf(inv.getArgument(0), 1, order(OrderStatus.DELIVERED, 7)));

        AdminOrderPage page = service.listOrders("abc", "xyz");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAllBy(captor.capture());
        verify(orderRepository, never()).findByStatus(any(), any());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(page.status()).isNull();
        assertThat(page.statusLabel()).isEqualTo("Tất cả");
        assertThat(page.page()).isEqualTo(1);
    }

    /** AC-006-39: exactly one filter option is selected, and the list is "Tất cả" plus the five labels. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED", "junk"})
    void listOrders_filtersHaveAllPlusFiveStatusesWithExactlyOneSelected(String rawStatus) {
        lenient().when(orderRepository.findAllBy(any(Pageable.class)))
                .thenAnswer(inv -> pageOf(inv.getArgument(0), 0));
        lenient().when(orderRepository.findByStatus(any(), any(Pageable.class)))
                .thenAnswer(inv -> pageOf(inv.getArgument(1), 0));

        AdminOrderPage page = service.listOrders(rawStatus, null);

        assertThat(page.filters()).extracting(StatusFilterOption::label)
                .containsExactly("Tất cả", "Chờ xử lý", "Đã xác nhận", "Đang giao", "Đã giao", "Đã hủy");
        assertThat(page.filters()).extracting(StatusFilterOption::value)
                .containsExactly(null, "PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED");
        assertThat(page.filters()).filteredOn(StatusFilterOption::selected).hasSize(1);
        String expectedSelected = rawStatus == null || rawStatus.equals("junk") ? null : rawStatus;
        assertThat(page.filters()).filteredOn(StatusFilterOption::selected).extracting(StatusFilterOption::value)
                .containsExactly(expectedSelected);
    }

    @Test
    void listOrders_mapsRowsWithAccountAndStatusKey() {
        when(orderRepository.findAllBy(any(Pageable.class)))
                .thenAnswer(inv -> pageOf(inv.getArgument(0), 1, order(OrderStatus.CONFIRMED, 7)));

        AdminOrderPage page = service.listOrders(null, null);

        assertThat(page.orders()).containsExactly(new AdminOrderRow(7, Instant.parse("2026-10-24T17:30:00Z"),
                "Nguyễn Văn An", "buyer@order.test", "Trần Thị Bình", new BigDecimal("450000"), "Đã xác nhận",
                "confirmed"));
        assertThat(page.isEmpty()).isFalse();
    }

    @Test
    void listOrders_whenPageIsBeyondTheEnd_fallsBackToPageOne() {
        when(orderRepository.findAllBy(any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            return pageable.getPageNumber() == 0
                    ? pageOf(pageable, 25, order(OrderStatus.PENDING, 7))
                    : pageOf(pageable, 25);
        });

        AdminOrderPage page = service.listOrders(null, "9");

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.orders()).hasSize(1);
    }

    @Test
    void listOrders_withNoOrders_isEmpty() {
        when(orderRepository.findAllBy(any(Pageable.class))).thenAnswer(inv -> pageOf(inv.getArgument(0), 0));

        assertThat(service.listOrders(null, null).isEmpty()).isTrue();
    }

    // ---------- dashboard ----------

    /** AC-006-6: all five statuses in enum order, zeros included; revenue from the DELIVERED sum. */
    @Test
    void dashboard_hasAllFiveStatusesInOrderWithZerosAndTheDeliveredRevenue() {
        when(orderRepository.countByStatus()).thenReturn(List.of(
                new StatusTotal(OrderStatus.DELIVERED, 2),
                new StatusTotal(OrderStatus.PENDING, 3),
                new StatusTotal(OrderStatus.CANCELLED, 1),
                new StatusTotal(OrderStatus.CONFIRMED, 1)));
        when(orderRepository.sumTotalByStatus(OrderStatus.DELIVERED)).thenReturn(new BigDecimal("450000"));
        when(orderRepository.findTop5ByOrderByCreatedAtDescIdDesc()).thenReturn(List.of());

        OrderDashboard dashboard = service.dashboard();

        assertThat(dashboard.counts()).containsExactly(
                new StatusCount("PENDING", "Chờ xử lý", 3),
                new StatusCount("CONFIRMED", "Đã xác nhận", 1),
                new StatusCount("SHIPPING", "Đang giao", 0),
                new StatusCount("DELIVERED", "Đã giao", 2),
                new StatusCount("CANCELLED", "Đã hủy", 1));
        assertThat(dashboard.deliveredRevenue()).isEqualByComparingTo("450000");
        assertThat(dashboard.latest()).isEmpty();
    }

    @Test
    void dashboard_withNoOrders_hasFiveZerosAndZeroRevenue() {
        when(orderRepository.countByStatus()).thenReturn(List.of());
        when(orderRepository.sumTotalByStatus(OrderStatus.DELIVERED)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findTop5ByOrderByCreatedAtDescIdDesc()).thenReturn(List.of());

        OrderDashboard dashboard = service.dashboard();

        assertThat(dashboard.counts()).hasSize(5).extracting(StatusCount::count).containsOnly(0L);
        assertThat(dashboard.deliveredRevenue()).isEqualByComparingTo("0");
    }

    /** AC-006-8 */
    @Test
    void dashboard_latestAreTheRowsOfTheTopFiveQueryInItsOrder() {
        when(orderRepository.countByStatus()).thenReturn(List.of());
        when(orderRepository.sumTotalByStatus(OrderStatus.DELIVERED)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findTop5ByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(
                order(OrderStatus.PENDING, 9), order(OrderStatus.SHIPPING, 8)));

        OrderDashboard dashboard = service.dashboard();

        assertThat(dashboard.latest()).extracting(AdminOrderRow::id).containsExactly(9L, 8L);
        assertThat(dashboard.latest()).extracting(AdminOrderRow::statusKey).containsExactly("pending", "shipping");
    }
}

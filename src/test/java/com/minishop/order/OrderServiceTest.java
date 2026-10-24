package com.minishop.order;

import com.minishop.cart.CartCheckout;
import com.minishop.cart.CheckoutLine;
import com.minishop.catalog.Category;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductRepository;
import com.minishop.common.NotFoundException;
import com.minishop.order.PlaceOrderException.EmptyCart;
import com.minishop.order.PlaceOrderException.InsufficientStock;
import com.minishop.order.PlaceOrderException.NotOrderable;
import com.minishop.order.PlaceOrderException.PriceChanged;
import com.minishop.user.Role;
import com.minishop.user.User;
import com.minishop.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Quy tắc nghiệp vụ của {@link OrderService} (AC-005-3..5, 7, 10..14, 17, 18, 20..24, 27, 28; BR-005-3..5, 8, 10).
 * Repository và {@link CartCheckout} là mock; rollback thật và tranh chấp nằm ở test PostgreSQL (TW5).
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final long USER_ID = 2L;

    @Mock
    CartCheckout cartCheckout;

    @Mock
    OrderRepository orderRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    UserRepository userRepository;

    OrderService service;

    private final User user = new User("buyer@order.test", "hash", "Nguyễn Văn An", Role.USER);
    private final Category category = new Category("Cat", null);
    private long nextItemId = 1;

    @BeforeEach
    void setUp() {
        service = new OrderService(cartCheckout, orderRepository, productRepository, userRepository);
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    // ---------- helpers ----------

    private static CheckoutLine line(long productId, String name, String price, int quantity, int stock) {
        return new CheckoutLine(productId, name, new BigDecimal(price), quantity, true, stock);
    }

    private static CheckoutLine hiddenLine(long productId, String name, String price, int quantity, int stock) {
        return new CheckoutLine(productId, name, new BigDecimal(price), quantity, false, stock);
    }

    private static CheckoutForm form() {
        return new CheckoutForm("Nguyễn Văn An", "0912 345 678", "12 Lê Lợi\nQuận 1", "Giao giờ hành chính");
    }

    private Product product(long id) {
        Product p = new Product(category, "Product " + id, null, BigDecimal.TEN, 10);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** Cart lines A (2 x 100 000, stock 5) and B (1 x 250 000, stock 1): total 450 000. */
    private List<CheckoutLine> twoLines() {
        return List.of(line(3, "Áo thun", "100000", 2, 5), line(9, "Quần jean", "250000", 1, 1));
    }

    /** Stubs everything a successful placement touches. */
    private void stubSuccessfulPlacement(List<CheckoutLine> lines) {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(lines);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        lenient().when(productRepository.getReferenceById(anyLong()))
                .thenAnswer(inv -> product(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 42L);
            return o;
        });
        lenient().when(productRepository.decrementStock(anyLong(), anyInt())).thenReturn(1);
    }

    private void verifyNothingWritten() {
        verify(orderRepository, never()).save(any());
        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
        verify(cartCheckout, never()).clear(anyLong());
    }

    /** A stored order with the given status and items {@code (productId, quantity)}, in the given order. */
    private Order storedOrder(OrderStatus status, long id, long[][] items) {
        Order order = new Order(user, "An", "0912345678", "Addr", new BigDecimal("1000"));
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-10-24T17:30:00Z"));
        for (long[] item : items) {
            OrderItem oi = order.addItem(product(item[0]), "Name " + item[0], new BigDecimal("1000"), (int) item[1]);
            ReflectionTestUtils.setField(oi, "id", nextItemId++);
        }
        return order;
    }

    // ---------- prepareCheckout / prefilledForm ----------

    /** AC-005-3 */
    @Test
    void prepareCheckout_withTwoLines_computesLinesAndTotalWithoutLocking() {
        when(cartCheckout.read(USER_ID)).thenReturn(twoLines());

        CheckoutView view = service.prepareCheckout(USER_ID);

        assertThat(view.lines()).containsExactly(
                new CheckoutLineView("Áo thun", new BigDecimal("100000"), 2, new BigDecimal("200000")),
                new CheckoutLineView("Quần jean", new BigDecimal("250000"), 1, new BigDecimal("250000")));
        assertThat(view.total()).isEqualByComparingTo("450000");
        assertThat(view.orderable()).isTrue();
        assertThat(view.isEmpty()).isFalse();
        verify(cartCheckout, never()).lockAndRead(anyLong());
    }

    /** AC-005-4 */
    @Test
    void prepareCheckout_withEmptyCart_isEmptyAndNotOrderable() {
        when(cartCheckout.read(USER_ID)).thenReturn(List.of());

        CheckoutView view = service.prepareCheckout(USER_ID);

        assertThat(view.isEmpty()).isTrue();
        assertThat(view.orderable()).isFalse();
        assertThat(view.total()).isEqualByComparingTo("0");
    }

    /** AC-005-5 */
    @Test
    void prepareCheckout_withDiscontinuedLine_isNotOrderable() {
        when(cartCheckout.read(USER_ID)).thenReturn(
                List.of(line(3, "A", "1000", 1, 5), hiddenLine(9, "B", "1000", 1, 5)));

        assertThat(service.prepareCheckout(USER_ID).orderable()).isFalse();
    }

    /** AC-005-5 */
    @Test
    void prepareCheckout_withZeroStockLine_isNotOrderable() {
        when(cartCheckout.read(USER_ID)).thenReturn(List.of(line(3, "A", "1000", 1, 0)));

        assertThat(service.prepareCheckout(USER_ID).orderable()).isFalse();
    }

    /** AC-005-5 */
    @Test
    void prepareCheckout_withQuantityAboveStock_isNotOrderable() {
        when(cartCheckout.read(USER_ID)).thenReturn(List.of(line(3, "A", "1000", 3, 2)));

        assertThat(service.prepareCheckout(USER_ID).orderable()).isFalse();
    }

    /** AC-005-3 */
    @Test
    void prefilledForm_usesFullNameAndPhoneOfUser() {
        user.setPhone("0987654321");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CheckoutForm form = service.prefilledForm(USER_ID);

        assertThat(form.receiverName()).isEqualTo("Nguyễn Văn An");
        assertThat(form.phone()).isEqualTo("0987654321");
        assertThat(form.shippingAddress()).isEmpty();
        assertThat(form.note()).isNull();
    }

    /** AC-005-3 */
    @Test
    void prefilledForm_whenUserHasNoPhone_usesEmptyString() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.prefilledForm(USER_ID).phone()).isEmpty();
    }

    // ---------- placeOrder: success ----------

    /** AC-005-7, AC-005-10, BR-005-3, BR-005-12, BR-005-13 */
    @Test
    void placeOrder_withValidCart_savesOrderThenDecrementsStockThenClearsCartInOrder() {
        stubSuccessfulPlacement(twoLines());

        long id = service.placeOrder(USER_ID, form(), new BigDecimal("450000"));

        assertThat(id).isEqualTo(42L);
        InOrder order = inOrder(cartCheckout, orderRepository, productRepository);
        order.verify(cartCheckout).lockAndRead(USER_ID);
        order.verify(orderRepository).save(any(Order.class));
        order.verify(productRepository).decrementStock(3L, 2);
        order.verify(productRepository).decrementStock(9L, 1);
        order.verify(cartCheckout).clear(USER_ID);
    }

    /** AC-005-7, AC-005-10, BR-005-12, BR-005-13: snapshot, status, payment and normalized phone. */
    @Test
    void placeOrder_savesPendingCodOrderWithSnapshotAndNormalizedPhone() {
        stubSuccessfulPlacement(twoLines());

        service.placeOrder(USER_ID, form(), new BigDecimal("450000"));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getPaymentMethod()).isEqualTo(PaymentMethod.COD);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("450000");
        assertThat(saved.getPhone()).isEqualTo("0912345678");
        assertThat(saved.getReceiverName()).isEqualTo("Nguyễn Văn An");
        assertThat(saved.getShippingAddress()).isEqualTo("12 Lê Lợi\nQuận 1");
        assertThat(saved.getNote()).isEqualTo("Giao giờ hành chính");
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getItems()).extracting(OrderItem::getProductName).containsExactly("Áo thun", "Quần jean");
        assertThat(saved.getItems()).extracting(OrderItem::getUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100000"), new BigDecimal("250000"));
        assertThat(saved.getItems()).extracting(OrderItem::getQuantity).containsExactly(2, 1);
        assertThat(saved.getItems()).extracting(i -> i.getProduct().getId()).containsExactly(3L, 9L);
    }

    /** AC-005-18: the order owner is the parameter, never something from the form. */
    @Test
    void placeOrder_usesUserIdParameterForOrderOwner() {
        stubSuccessfulPlacement(twoLines());

        service.placeOrder(USER_ID, form(), new BigDecimal("450000"));

        verify(userRepository).getReferenceById(USER_ID);
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
    }

    @Test
    void placeOrder_withoutNote_savesNullNote() {
        stubSuccessfulPlacement(twoLines());

        service.placeOrder(USER_ID, new CheckoutForm("An", "0912345678", "Addr", null), new BigDecimal("450000"));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getNote()).isNull();
    }

    /** AC-005-17, BR-005-4: stock is decremented in ascending product id, whatever the cart order. */
    @Test
    void placeOrder_decrementsStockInAscendingProductIdOrder() {
        stubSuccessfulPlacement(List.of(line(9, "B", "250000", 1, 1), line(3, "A", "100000", 2, 5)));

        service.placeOrder(USER_ID, form(), new BigDecimal("450000"));

        InOrder order = inOrder(productRepository);
        order.verify(productRepository).decrementStock(3L, 2);
        order.verify(productRepository).decrementStock(9L, 1);
    }

    /** BR-005-3: the order keeps the cart order for its lines even though stock goes by product id. */
    @Test
    void placeOrder_keepsCartOrderForOrderLines() {
        stubSuccessfulPlacement(List.of(line(9, "B", "250000", 1, 1), line(3, "A", "100000", 2, 5)));

        service.placeOrder(USER_ID, form(), new BigDecimal("450000"));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getItems()).extracting(OrderItem::getProductName).containsExactly("B", "A");
    }

    /** BR-005-5: compareTo, not equals, so 450000.0 equals 450000. */
    @Test
    void placeOrder_withExpectedTotalOfDifferentScale_isAccepted() {
        stubSuccessfulPlacement(twoLines());

        long id = service.placeOrder(USER_ID, form(), new BigDecimal("450000.0"));

        assertThat(id).isEqualTo(42L);
    }

    /** BR-005-10 */
    @Test
    void placeOrder_neverRestoresStock() {
        stubSuccessfulPlacement(twoLines());

        service.placeOrder(USER_ID, form(), new BigDecimal("450000"));

        verify(productRepository, never()).incrementStock(anyLong(), anyInt());
    }

    // ---------- placeOrder: rejections ----------

    /** AC-005-14 */
    @Test
    void placeOrder_withEmptyCart_throwsEmptyCartAndWritesNothing() {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), new BigDecimal("0")))
                .isInstanceOf(EmptyCart.class);

        verifyNothingWritten();
    }

    /** AC-005-12: a discontinued line wins even when another line is also short. */
    @Test
    void placeOrder_withDiscontinuedLine_throwsNotOrderableEvenIfAnotherLineExceedsStock() {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(
                List.of(line(3, "Short", "1000", 5, 1), hiddenLine(9, "Gone", "1000", 1, 5)));

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), new BigDecimal("6000")))
                .isInstanceOf(NotOrderable.class);

        verifyNothingWritten();
    }

    /** AC-005-11 */
    @Test
    void placeOrder_withQuantityAboveStock_throwsInsufficientStockWithLowestProductIdName() {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(List.of(
                line(9, "Nine", "1000", 5, 1), line(3, "Three", "1000", 5, 2), line(5, "Five", "1000", 1, 9)));

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), new BigDecimal("11000")))
                .isInstanceOfSatisfying(InsufficientStock.class, e -> assertThat(e.productName()).isEqualTo("Three"));

        verifyNothingWritten();
    }

    /** AC-005-12, QĐ4: stock 0 counts as insufficient stock with the product name. */
    @Test
    void placeOrder_withZeroStockLine_throwsInsufficientStockWithName() {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(List.of(
                line(9, "Nine", "1000", 1, 0), line(3, "Three", "1000", 1, 4)));

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), new BigDecimal("2000")))
                .isInstanceOfSatisfying(InsufficientStock.class, e -> assertThat(e.productName()).isEqualTo("Nine"));

        verifyNothingWritten();
    }

    /** AC-005-11: the conditional UPDATE is the real guarantee; failure must stop before clearing the cart. */
    @Test
    void placeOrder_whenSecondDecrementAffectsNoRow_throwsInsufficientStockAndDoesNotClearCart() {
        stubSuccessfulPlacement(twoLines());
        when(productRepository.decrementStock(9L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), new BigDecimal("450000")))
                .isInstanceOfSatisfying(InsufficientStock.class, e -> assertThat(e.productName()).isEqualTo("Quần jean"));

        verify(productRepository).decrementStock(3L, 2);
        verify(cartCheckout, never()).clear(anyLong());
    }

    /** AC-005-13 */
    @Test
    void placeOrder_withLowerExpectedTotal_throwsPriceChangedWithCurrentViewAndWritesNothing() {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(twoLines());

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), new BigDecimal("400000")))
                .isInstanceOfSatisfying(PriceChanged.class, e -> {
                    assertThat(e.current().total()).isEqualByComparingTo("450000");
                    assertThat(e.current().lines()).hasSize(2);
                });

        verifyNothingWritten();
    }

    /** BR-005-5: a missing expected total counts as a changed price. */
    @Test
    void placeOrder_withNullExpectedTotal_throwsPriceChanged() {
        when(cartCheckout.lockAndRead(USER_ID)).thenReturn(twoLines());

        assertThatThrownBy(() -> service.placeOrder(USER_ID, form(), null))
                .isInstanceOfSatisfying(PriceChanged.class,
                        e -> assertThat(e.current().total()).isEqualByComparingTo("450000"));

        verifyNothingWritten();
    }

    // ---------- listOrders ----------

    private Page<Order> pageOf(int pageIndex, long total, Order... orders) {
        return new PageImpl<>(List.of(orders), PageRequest.of(pageIndex, 10), total);
    }

    /** AC-005-20, AC-005-21 */
    @Test
    void listOrders_withInvalidPage_queriesFirstPageSortedNewestFirst() {
        Order o = storedOrder(OrderStatus.PENDING, 5, new long[][]{{3, 1}});
        when(orderRepository.findByUserId(anyLong(), any(Pageable.class))).thenReturn(pageOf(0, 1, o));

        OrderListView view = service.listOrders(USER_ID, "abc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByUserId(org.mockito.ArgumentMatchers.eq(USER_ID), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.orders()).hasSize(1);
        assertThat(view.isEmpty()).isFalse();
    }

    /** AC-005-20 */
    @Test
    void listOrders_mapsStatusLabelAndKeyAndTotals() {
        Order o = storedOrder(OrderStatus.PENDING, 5, new long[][]{{3, 1}});
        when(orderRepository.findByUserId(anyLong(), any(Pageable.class))).thenReturn(pageOf(0, 1, o));

        OrderSummaryView summary = service.listOrders(USER_ID, null).orders().getFirst();

        assertThat(summary.id()).isEqualTo(5L);
        assertThat(summary.statusLabel()).isEqualTo("Chờ xử lý");
        assertThat(summary.statusKey()).isEqualTo("pending");
        assertThat(summary.totalAmount()).isEqualByComparingTo("1000");
        assertThat(summary.createdAt()).isEqualTo(Instant.parse("2026-10-24T17:30:00Z"));
    }

    /** AC-005-21 */
    @Test
    void listOrders_withPageTwo_queriesPageIndexOne() {
        Order o = storedOrder(OrderStatus.DELIVERED, 5, new long[][]{{3, 1}});
        when(orderRepository.findByUserId(anyLong(), any(Pageable.class))).thenReturn(pageOf(1, 11, o));

        OrderListView view = service.listOrders(USER_ID, "2");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByUserId(org.mockito.ArgumentMatchers.eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(view.page()).isEqualTo(2);
        assertThat(view.totalPages()).isEqualTo(2);
        assertThat(view.totalElements()).isEqualTo(11);
    }

    /** AC-005-21: a page past the end falls back to page 1 instead of showing "no orders". */
    @Test
    void listOrders_withPageBeyondEnd_requeriesFirstPage() {
        Order o = storedOrder(OrderStatus.PENDING, 5, new long[][]{{3, 1}});
        Page<Order> beyond = new PageImpl<>(List.of(), PageRequest.of(4, 10), 1);
        when(orderRepository.findByUserId(anyLong(), any(Pageable.class))).thenReturn(beyond, pageOf(0, 1, o));

        OrderListView view = service.listOrders(USER_ID, "5");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository, org.mockito.Mockito.times(2))
                .findByUserId(org.mockito.ArgumentMatchers.eq(USER_ID), captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(4, 0);
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.orders()).hasSize(1);
        assertThat(view.isEmpty()).isFalse();
    }

    /** AC-005-22 */
    @Test
    void listOrders_forUserWithoutOrders_isEmptyAndQueriesOnce() {
        when(orderRepository.findByUserId(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(new ArrayList<Order>(), PageRequest.of(0, 10), 0));

        OrderListView view = service.listOrders(USER_ID, "1");

        assertThat(view.isEmpty()).isTrue();
        verify(orderRepository).findByUserId(anyLong(), any(Pageable.class));
        verifyNoMoreInteractions(orderRepository);
    }

    // ---------- getOrder ----------

    /** AC-005-23 */
    @Test
    void getOrder_returnsSnapshotLinesSortedByItemIdAndPaymentLabel() {
        Order order = storedOrder(OrderStatus.PENDING, 5, new long[][]{{9, 1}, {3, 2}});
        // Items come back unordered from the database: reverse them to prove the service sorts by id.
        List<OrderItem> items = new ArrayList<>(order.getItems());
        java.util.Collections.reverse(items);
        ReflectionTestUtils.setField(order, "items", items);
        when(orderRepository.findDetailByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(order));

        OrderDetailView view = service.getOrder(USER_ID, 5L);

        assertThat(view.id()).isEqualTo(5L);
        assertThat(view.lines()).extracting(OrderLineView::productName).containsExactly("Name 9", "Name 3");
        assertThat(view.lines()).extracting(OrderLineView::lineTotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1000"), new BigDecimal("2000"));
        assertThat(view.paymentMethodLabel()).isEqualTo("Thanh toán khi nhận hàng (COD)");
        assertThat(view.receiverName()).isEqualTo("An");
        assertThat(view.phone()).isEqualTo("0912345678");
        assertThat(view.shippingAddress()).isEqualTo("Addr");
        assertThat(view.statusLabel()).isEqualTo("Chờ xử lý");
        assertThat(view.statusKey()).isEqualTo("pending");
    }

    /** AC-005-23, BR-005-8: the cancel button flag follows the customer rule (PENDING only). */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void getOrder_cancellableOnlyWhenPending(OrderStatus status) {
        when(orderRepository.findDetailByIdAndUserId(5L, USER_ID))
                .thenReturn(Optional.of(storedOrder(status, 5, new long[][]{{3, 1}})));

        assertThat(service.getOrder(USER_ID, 5L).cancellable()).isEqualTo(status == OrderStatus.PENDING);
    }

    /** AC-005-24: someone else's order looks exactly like a missing one (the query filters by user). */
    @Test
    void getOrder_whenRepositoryReturnsEmpty_throwsNotFound() {
        when(orderRepository.findDetailByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(USER_ID, 99L)).isInstanceOf(NotFoundException.class);
    }

    // ---------- cancelByCustomer ----------

    /** AC-005-27, BR-005-10 */
    @Test
    void cancelByCustomer_pendingOrder_cancelsAndRestoresStockInAscendingProductIdOrder() {
        Order order = storedOrder(OrderStatus.PENDING, 5, new long[][]{{9, 1}, {3, 2}});
        when(orderRepository.lockByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(order));
        when(productRepository.incrementStock(anyLong(), anyInt())).thenReturn(1);

        CancelResult result = service.cancelByCustomer(USER_ID, 5L);

        assertThat(result).isEqualTo(CancelResult.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        InOrder inOrder = inOrder(productRepository);
        inOrder.verify(productRepository).incrementStock(3L, 2);
        inOrder.verify(productRepository).incrementStock(9L, 1);
        verifyNoMoreInteractions(productRepository);
    }

    /** AC-005-28, BR-005-8 */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED"})
    void cancelByCustomer_nonPendingOrder_isNotCancellableAndChangesNothing(OrderStatus status) {
        Order order = storedOrder(status, 5, new long[][]{{3, 2}});
        when(orderRepository.lockByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(order));

        CancelResult result = service.cancelByCustomer(USER_ID, 5L);

        assertThat(result).isEqualTo(CancelResult.NOT_CANCELLABLE);
        assertThat(order.getStatus()).isEqualTo(status);
        verifyNoInteractions(productRepository);
    }

    /** AC-005-24 */
    @Test
    void cancelByCustomer_whenNoOrderForUser_throwsNotFoundAndRestoresNothing() {
        when(orderRepository.lockByIdAndUserId(5L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelByCustomer(USER_ID, 5L)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(productRepository);
    }

    /** AC-005-24: a failed restore must roll back, not be ignored. */
    @Test
    void cancelByCustomer_whenIncrementAffectsNoRow_throwsIllegalState() {
        Order order = storedOrder(OrderStatus.PENDING, 5, new long[][]{{3, 2}});
        when(orderRepository.lockByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(order));
        when(productRepository.incrementStock(3L, 2)).thenReturn(0);

        assertThatThrownBy(() -> service.cancelByCustomer(USER_ID, 5L)).isInstanceOf(IllegalStateException.class);
    }

    /** BR-005-10: cancelling never decrements stock and never touches the cart. */
    @Test
    void cancelByCustomer_neverDecrementsStockOrTouchesCart() {
        Order order = storedOrder(OrderStatus.PENDING, 5, new long[][]{{3, 2}});
        when(orderRepository.lockByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(order));
        when(productRepository.incrementStock(anyLong(), anyInt())).thenReturn(1);

        service.cancelByCustomer(USER_ID, 5L);

        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
        verifyNoInteractions(cartCheckout);
    }

    /** BR-005-8 */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void customerMayCancel_isTrueOnlyForPending(OrderStatus status) {
        assertThat(OrderService.customerMayCancel(status)).isEqualTo(status == OrderStatus.PENDING);
    }
}

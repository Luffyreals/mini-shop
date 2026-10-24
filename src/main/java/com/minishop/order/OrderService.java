package com.minishop.order;

import com.minishop.cart.CartCheckout;
import com.minishop.cart.CheckoutLine;
import com.minishop.catalog.ProductRepository;
import com.minishop.common.NotFoundException;
import com.minishop.user.User;
import com.minishop.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Checkout, order history and customer cancel. Lock order (ADR-0009): placing an order locks the
 * {@code users} row, then {@code products} by ascending id; cancelling locks the {@code orders} row, then
 * {@code products} by ascending id.
 */
@Service
@Transactional
class OrderService {

    static final int PAGE_SIZE = 10;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final CartCheckout cartCheckout;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    OrderService(CartCheckout cartCheckout, OrderRepository orderRepository, ProductRepository productRepository,
                 UserRepository userRepository) {
        this.cartCheckout = cartCheckout;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    /** The checkout page at current prices. No lock, no write. */
    @Transactional(readOnly = true)
    CheckoutView prepareCheckout(long userId) {
        return toView(cartCheckout.read(userId));
    }

    /** Receiver name and phone from the profile (empty phone when none); address and note are empty. */
    @Transactional(readOnly = true)
    CheckoutForm prefilledForm(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        return CheckoutForm.prefill(user.getFullName(), user.getPhone() == null ? "" : user.getPhone());
    }

    /**
     * Places one COD order from the user's DB cart, or throws a {@link PlaceOrderException} (which rolls
     * everything back). Statement order matters, see the numbered steps.
     *
     * @param expectedTotal the total the customer saw; {@code null} counts as a changed price
     * @return the new order id
     */
    long placeOrder(long userId, CheckoutForm form, BigDecimal expectedTotal) {
        // 1. Lock the users row, then read the cart (ADR-0008).
        List<CheckoutLine> lines = cartCheckout.lockAndRead(userId);

        // 2. An empty cart: nothing to order, or a concurrent request already ordered it.
        if (lines.isEmpty()) {
            log.debug("Order rejected: empty cart, user={}", userId);
            throw new PlaceOrderException.EmptyCart();
        }

        // 3. Early, readable checks (the real stock guarantee is step 5).
        CheckoutView view = toView(lines);
        if (lines.stream().anyMatch(l -> !l.active())) {
            log.debug("Order rejected: discontinued product, user={}", userId);
            throw new PlaceOrderException.NotOrderable();
        }
        List<CheckoutLine> byProduct = lines.stream()
                .sorted(Comparator.comparingLong(CheckoutLine::productId))
                .toList();
        for (CheckoutLine line : byProduct) {
            if (line.quantity() > line.stock()) {
                log.debug("Order rejected: insufficient stock, user={}, product={}", userId, line.productId());
                throw new PlaceOrderException.InsufficientStock(line.name());
            }
        }
        if (view.totalDiffersFrom(expectedTotal)) {
            log.debug("Order rejected: price changed, user={}", userId);
            throw new PlaceOrderException.PriceChanged(view);
        }

        // 4. Save the order first: decrementStock clears the persistence context.
        Order order = new Order(userRepository.getReferenceById(userId), form.receiverName(),
                form.normalizedPhone(), form.shippingAddress(), view.total());
        order.setNote(form.note());
        for (CheckoutLine line : lines) {
            order.addItem(productRepository.getReferenceById(line.productId()), line.name(), line.unitPrice(),
                    line.quantity());
        }
        orderRepository.save(order);
        long orderId = order.getId();

        // 5. Decrement stock in ascending product id (no deadlock). From the first call on, the persistence
        // context is cleared, so iterate the plain CheckoutLine list and read no lazy association.
        for (CheckoutLine line : byProduct) {
            if (productRepository.decrementStock(line.productId(), line.quantity()) == 0) {
                log.debug("Order rolled back: stock update failed, user={}, product={}", userId, line.productId());
                throw new PlaceOrderException.InsufficientStock(line.name());
            }
        }

        // 6. Bulk delete of the cart lines (not cart.removeItem on a detached entity).
        cartCheckout.clear(userId);

        log.info("Order placed: id={}, user={}, lines={}", orderId, userId, lines.size());
        return orderId;
    }

    @Transactional(readOnly = true)
    OrderListView listOrders(long userId, String rawPage) {
        int page = OrderInput.page(rawPage);
        Page<Order> result = findPage(userId, page);
        if (result.getContent().isEmpty() && result.getTotalElements() > 0) {
            page = 1;
            result = findPage(userId, page);
        }
        List<OrderSummaryView> orders = result.getContent().stream()
                .map(o -> new OrderSummaryView(o.getId(), o.getCreatedAt(), o.getTotalAmount(),
                        o.getStatus().label(), statusKey(o.getStatus())))
                .toList();
        return new OrderListView(orders, page, result.getTotalPages(), result.getTotalElements());
    }

    private Page<Order> findPage(long userId, int page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return orderRepository.findByUserId(userId, PageRequest.of(page - 1, PAGE_SIZE, sort));
    }

    /** @throws NotFoundException when the order does not exist or belongs to someone else */
    @Transactional(readOnly = true)
    OrderDetailView getOrder(long userId, long orderId) {
        Order order = orderRepository.findDetailByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        List<OrderLineView> lines = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(i -> new OrderLineView(i.getProductName(), i.getUnitPrice(), i.getQuantity(),
                        i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()))))
                .toList();
        OrderStatus status = order.getStatus();
        return new OrderDetailView(order.getId(), order.getCreatedAt(), status.label(), statusKey(status),
                order.getPaymentMethod().label(), order.getReceiverName(), order.getPhone(),
                order.getShippingAddress(), order.getNote(), lines, order.getTotalAmount(),
                customerMayCancel(status));
    }

    /**
     * Cancels the user's own PENDING order and gives the stock back, once, even for concurrent requests
     * (the second waits on the order row lock, then sees CANCELLED).
     *
     * @throws NotFoundException when the order does not exist or belongs to someone else
     */
    CancelResult cancelByCustomer(long userId, long orderId) {
        // 1. Lock the orders row of the owner.
        Order order = orderRepository.lockByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));

        // 2. The customer rule is stricter than the general transition table.
        if (!customerMayCancel(order.getStatus())) {
            log.debug("Cancel rejected: id={}, user={}, status={}", orderId, userId, order.getStatus());
            return CancelResult.NOT_CANCELLABLE;
        }

        // 3-5. Transition and give the stock back (shared with the admin cancel, see OrderCancellation).
        int lines = OrderCancellation.cancelAndRestoreStock(order, productRepository);

        log.info("Order cancelled: id={}, user={}, lines={}", orderId, userId, lines);
        return CancelResult.CANCELLED;
    }

    /** The only place that decides whether a customer may cancel; used by the button flag and the action. */
    static boolean customerMayCancel(OrderStatus status) {
        return status == OrderStatus.PENDING;
    }

    private static String statusKey(OrderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static CheckoutView toView(List<CheckoutLine> lines) {
        List<CheckoutLineView> views = lines.stream()
                .map(l -> new CheckoutLineView(l.name(), l.unitPrice(), l.quantity(), l.lineTotal()))
                .toList();
        BigDecimal total = lines.stream().map(CheckoutLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean orderable = !lines.isEmpty() && lines.stream().allMatch(CheckoutLine::orderable);
        return new CheckoutView(views, total, orderable);
    }
}

package com.minishop.order;

import com.minishop.catalog.ProductRepository;
import com.minishop.common.AdminAccess;
import com.minishop.common.NotFoundException;
import com.minishop.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * API quản trị hẹp của {@code order}: danh sách, chi tiết, dashboard và đổi trạng thái đơn. Trạng thái và
 * trang đến dạng chuỗi thô và được parse ở đây, nên {@link OrderStatus} và bảng chuyển trạng thái không
 * ra khỏi package. Đổi trạng thái khóa dòng {@code orders} trước, rồi {@code products} tăng dần (ADR-0009).
 *
 * <p>Mọi phương thức public gọi {@code requireAdmin()} đầu tiên (AC-009-9, ADR-0014); không khóa, không ghi khi
 * bị từ chối.
 */
@Service
@Transactional
public class OrderAdminService {

    static final int PAGE_SIZE = 20;

    private static final String ALL_LABEL = "Tất cả";

    private static final Logger log = LoggerFactory.getLogger(OrderAdminService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    private final AdminAccess adminAccess;

    OrderAdminService(OrderRepository orderRepository, ProductRepository productRepository,
                      AdminAccess adminAccess) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.adminAccess = adminAccess;
    }

    /** Một trang đơn, mới nhất trước; trạng thái hoặc trang sai thì coi như "Tất cả" / trang 1. */
    @Transactional(readOnly = true)
    public AdminOrderPage listOrders(String rawStatus, String rawPage) {
        adminAccess.requireAdmin();
        Optional<OrderStatus> filter = OrderInput.status(rawStatus);
        int page = OrderInput.page(rawPage);
        Page<Order> result = findPage(filter, page);
        if (result.getContent().isEmpty() && result.getTotalElements() > 0) {
            page = 1;
            result = findPage(filter, page);
        }
        List<AdminOrderRow> rows = result.getContent().stream().map(OrderAdminService::toRow).toList();
        List<StatusFilterOption> filters = new ArrayList<>();
        filters.add(new StatusFilterOption(null, ALL_LABEL, filter.isEmpty()));
        for (OrderStatus status : OrderStatus.values()) {
            filters.add(new StatusFilterOption(status.name(), status.label(), filter.filter(status::equals).isPresent()));
        }
        return new AdminOrderPage(rows, filter.map(Enum::name).orElse(null),
                filter.map(OrderStatus::label).orElse(ALL_LABEL), List.copyOf(filters), page,
                result.getTotalPages(), result.getTotalElements());
    }

    private Page<Order> findPage(Optional<OrderStatus> filter, int page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        PageRequest pageable = PageRequest.of(page - 1, PAGE_SIZE, sort);
        return filter.isPresent()
                ? orderRepository.findByStatus(filter.get(), pageable)
                : orderRepository.findAllBy(pageable);
    }

    /** @throws NotFoundException when the order does not exist */
    @Transactional(readOnly = true)
    public AdminOrderDetail getOrder(long orderId) {
        adminAccess.requireAdmin();
        Order order = orderRepository.findAdminDetailById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        List<OrderLineView> lines = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(i -> new OrderLineView(i.getProductName(), i.getUnitPrice(), i.getQuantity(),
                        i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()))))
                .toList();
        OrderStatus current = order.getStatus();
        // Buttons come straight from the transition table (BR-006-12); enum order puts "next step" before "cancel".
        List<StatusAction> actions = Arrays.stream(OrderStatus.values())
                .filter(current::canTransitionTo)
                .map(target -> toAction(target, order.getId()))
                .toList();
        User user = order.getUser();
        return new AdminOrderDetail(order.getId(), order.getCreatedAt(), order.getUpdatedAt(), current.label(),
                statusKey(current), order.getPaymentMethod().label(), user.getFullName(), user.getEmail(),
                order.getReceiverName(), order.getPhone(), order.getShippingAddress(), order.getNote(), lines,
                order.getTotalAmount(), actions);
    }

    private static StatusAction toAction(OrderStatus target, long orderId) {
        String confirm = switch (target) {
            case CANCELLED -> "Hủy đơn hàng #" + orderId + " và hoàn lại tồn kho?";
            case DELIVERED -> "Đánh dấu đơn hàng #" + orderId + " đã giao? Không thể hoàn tác.";
            case PENDING, CONFIRMED, SHIPPING -> null;
        };
        return new StatusAction(target.name(), target.actionLabel(), confirm, target == OrderStatus.CANCELLED);
    }

    /** Fixed number of statements: counts, delivered revenue, latest 5 (AC-006-9). */
    @Transactional(readOnly = true)
    public OrderDashboard dashboard() {
        adminAccess.requireAdmin();
        Map<OrderStatus, Long> totals = new EnumMap<>(OrderStatus.class);
        for (StatusTotal t : orderRepository.countByStatus()) {
            totals.put(t.status(), t.count());
        }
        List<StatusCount> counts = Arrays.stream(OrderStatus.values())
                .map(s -> new StatusCount(s.name(), s.label(), totals.getOrDefault(s, 0L)))
                .toList();
        BigDecimal revenue = orderRepository.sumTotalByStatus(OrderStatus.DELIVERED);
        List<AdminOrderRow> latest = orderRepository.findTop5ByOrderByCreatedAtDescIdDesc().stream()
                .map(OrderAdminService::toRow)
                .toList();
        return new OrderDashboard(counts, revenue, latest);
    }

    /**
     * Moves an order to the target status under the same rules as everyone else. Statement order (ADR-0009):
     * lock the orders row, check the transition table, then (cancel only) give the stock back.
     * Nothing is written on the {@code NotAllowed} and {@code InvalidStatus} paths, so they return a result
     * instead of throwing.
     *
     * @throws NotFoundException when the order does not exist
     */
    public StatusChangeResult changeStatus(long orderId, String rawTargetStatus) {
        long adminId = adminAccess.requireAdmin();
        // 1. Parse first: a bad value neither locks nor writes.
        Optional<OrderStatus> parsed = OrderInput.status(rawTargetStatus);
        if (parsed.isEmpty()) {
            log.debug("Admin order status rejected: invalid target, admin={}, order={}", adminId, orderId);
            return new StatusChangeResult.InvalidStatus();
        }
        OrderStatus target = parsed.get();

        // 2. Lock the orders row (any owner), then read the committed status.
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        OrderStatus current = order.getStatus();

        // 3. The shared transition table.
        if (!current.canTransitionTo(target)) {
            log.debug("Admin order status rejected: admin={}, order={}, from={}, to={}",
                    adminId, orderId, current, target);
            return new StatusChangeResult.NotAllowed(current.label(), target.label());
        }

        // 4. Cancel restores stock (the persistence context is cleared inside); the rest is a plain transition.
        StatusChangeResult result;
        if (target == OrderStatus.CANCELLED) {
            OrderCancellation.cancelAndRestoreStock(order, productRepository);
            result = new StatusChangeResult.Cancelled();
        } else {
            order.transitionTo(target);
            result = new StatusChangeResult.Changed(target.label());
        }

        log.info("Admin order status: admin={}, order={}, from={}, to={}", adminId, orderId, current, target);
        return result;
    }

    private static AdminOrderRow toRow(Order o) {
        User user = o.getUser();
        return new AdminOrderRow(o.getId(), o.getCreatedAt(), user.getFullName(), user.getEmail(),
                o.getReceiverName(), o.getTotalAmount(), o.getStatus().label(), statusKey(o.getStatus()));
    }

    private static String statusKey(OrderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}

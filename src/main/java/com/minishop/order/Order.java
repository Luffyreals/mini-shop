package com.minishop.order;

import com.minishop.catalog.Product;
import com.minishop.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "receiver_name", nullable = false, length = 100)
    private String receiverName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    @Column(length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    Order(User user, String receiverName, String phone, String shippingAddress, BigDecimal totalAmount) {
        this.user = user;
        this.receiverName = receiverName;
        this.phone = phone;
        this.shippingAddress = shippingAddress;
        this.totalAmount = totalAmount;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    Long getId() {
        return id;
    }

    User getUser() {
        return user;
    }

    OrderStatus getStatus() {
        return status;
    }

    String getReceiverName() {
        return receiverName;
    }

    String getPhone() {
        return phone;
    }

    String getShippingAddress() {
        return shippingAddress;
    }

    String getNote() {
        return note;
    }

    void setNote(String note) {
        this.note = note;
    }

    PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    BigDecimal getTotalAmount() {
        return totalAmount;
    }

    /** Chỉ đọc: dòng đơn chỉ được thêm qua {@link #addItem} để cascade lưu cùng đơn hàng. */
    List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** Thêm dòng đơn với tên và giá chụp lại tại thời điểm mua (snapshot). */
    OrderItem addItem(Product product, String productName, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem(this, product, productName, unitPrice, quantity);
        items.add(item);
        return item;
    }

    /**
     * Đổi trạng thái theo {@link OrderStatus#canTransitionTo}; chốt chặn ở domain cho cả khách và admin.
     *
     * @throws IllegalStateException nếu bước chuyển không hợp lệ
     */
    void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot change order status from " + status + " to " + next);
        }
        this.status = next;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}

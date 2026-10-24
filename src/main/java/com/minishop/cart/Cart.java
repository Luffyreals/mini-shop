package com.minishop.cart;

import com.minishop.catalog.Product;
import com.minishop.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "carts")
class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Cart() {
    }

    Cart(User user) {
        this.user = user;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    Long getId() {
        return id;
    }

    User getUser() {
        return user;
    }

    /** Chỉ đọc: thêm/xóa dòng phải qua {@link #addItem} / {@link #removeItem} để cascade hoạt động đúng. */
    List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    CartItem addItem(Product product, int quantity) {
        CartItem item = new CartItem(this, product, quantity);
        items.add(item);
        return item;
    }

    void removeItem(CartItem item) {
        items.remove(item);
    }

    /** So id sản phẩm qua {@code getProduct().getId()}, không khởi tạo proxy của Product. */
    Optional<CartItem> findItem(Long productId) {
        return items.stream()
                .filter(i -> i.getProduct().getId().equals(productId))
                .findFirst();
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}

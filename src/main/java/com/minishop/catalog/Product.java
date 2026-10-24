package com.minishop.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Sản phẩm. Xóa mềm bằng {@code active=false} vì OrderItem cũ vẫn tham chiếu tới. */
@Entity
@Table(name = "products")
// Write only changed columns: a full-column UPDATE would overwrite a hide/show (or other change) committed by
// another transaction between this one's load and flush with this transaction's stale copy.
@org.hibernate.annotations.DynamicUpdate
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 4000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    /**
     * Không cho Hibernate ghi cột này trong UPDATE thông thường: nếu không, admin lưu sản phẩm
     * (đã load từ trước) sẽ ghi đè lượng kho vừa bị {@code decrementStock} trừ. Mọi thay đổi tồn kho
     * sau khi tạo phải qua truy vấn {@code @Modifying} riêng (ADR-0005).
     */
    @Column(nullable = false, updatable = false)
    private int stock;

    @Column(name = "image_path", length = 255)
    private String imagePath;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(Category category, String name, String description, BigDecimal price, int stock) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
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

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Sửa thông tin hiển thị (admin). Cố ý không có tham số {@code stock}, {@code active}, {@code imagePath}:
     * tồn kho chỉ đổi bằng truy vấn riêng (ADR-0005, ADR-0010), ảnh và trạng thái có setter riêng.
     */
    void updateDetails(Category category, String name, String description, BigDecimal price) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

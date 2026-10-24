package com.minishop.cart;

import com.minishop.catalog.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * API công khai hẹp của {@code cart} cho {@code order}. Chỉ trả {@link CheckoutLine} (dữ liệu thuần), nên
 * {@code order} không thấy entity hay cách {@code cart} lưu dữ liệu, và cũng không thấy API sửa giỏ.
 *
 * <p>Thứ tự bắt buộc khi đặt hàng: {@link #lockAndRead} trước, rồi các bước của đơn, và {@link #clear}
 * sau cùng, tất cả trong cùng một transaction của {@code order} (ADR-0008).
 *
 * <p>{@code lockAndRead} và {@code clear} là {@code MANDATORY}: khóa dòng {@code users} chỉ có ý nghĩa nếu
 * được giữ tới khi đơn commit. Gọi ngoài transaction, Spring sẽ mở và commit một transaction riêng quanh
 * lời gọi, khóa nhả ngay; {@code MANDATORY} biến lỗi lập trình đó thành exception ngay lập tức.
 */
@Service
public class CartCheckout {

    private final CartRepository cartRepository;

    CartCheckout(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    /** Đọc giỏ để hiển thị trang thanh toán, không khóa. Thứ tự thêm vào giỏ; không có giỏ thì rỗng. */
    @Transactional(readOnly = true)
    public List<CheckoutLine> read(long userId) {
        return load(userId);
    }

    /**
     * Khóa dòng {@code users} rồi mới đọc giỏ, nên hai đơn của cùng một người chạy tuần tự.
     *
     * @throws IllegalStateException nếu không có người dùng
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CheckoutLine> lockAndRead(long userId) {
        cartRepository.lockOwner(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        return load(userId);
    }

    /** Xóa mọi dòng giỏ của người đó (giữ dòng {@code carts}). Chỉ gọi sau {@link #lockAndRead}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(long userId) {
        cartRepository.deleteItemsByUserId(userId);
    }

    private List<CheckoutLine> load(long userId) {
        return cartRepository.findWithItemsByUserId(userId)
                .map(cart -> cart.getItems().stream()
                        .sorted(Comparator.comparing(CartItem::getId))
                        .map(i -> toLine(i.getProduct(), i.getQuantity()))
                        .toList())
                .orElse(List.of());
    }

    private static CheckoutLine toLine(Product product, int quantity) {
        return new CheckoutLine(product.getId(), product.getName(), product.getPrice(), quantity,
                product.isActive(), product.getStock());
    }
}

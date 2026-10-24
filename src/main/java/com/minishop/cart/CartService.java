package com.minishop.cart;

import com.minishop.cart.CartChange.Outcome;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductImages;
import com.minishop.catalog.ProductRepository;
import com.minishop.common.NotFoundException;
import com.minishop.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Toàn bộ quy tắc nghiệp vụ của giỏ hàng. Giỏ chỉ lưu id sản phẩm và số lượng; giá luôn là giá hiện tại
 * và tồn kho không bao giờ bị thao tác giỏ thay đổi (service này chỉ đọc {@link Product}).
 *
 * <p>Mọi thao tác ghi giỏ DB khóa dòng {@code users} của chủ giỏ trước tiên (ADR-0008), nên các thao tác
 * của cùng một người chạy tuần tự và các unique constraint không bao giờ bị vi phạm qua service này.
 */
@Service
@Transactional
class CartService {

    static final int MAX_LINE_QUANTITY = 99;

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ProductImages productImages;

    CartService(CartRepository cartRepository, ProductRepository productRepository, ProductImages productImages) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.productImages = productImages;
    }

    // ---------------------------------------------------------------- queries

    /** Không bao giờ sửa giỏ, kể cả khi thấy dòng ngừng bán hay vượt tồn kho. */
    @Transactional(readOnly = true)
    CartView view(CartOwner owner) {
        List<CartLineView> lines = switch (owner) {
            case CartOwner.Customer c -> cartRepository.findWithItemsByUserId(c.userId())
                    .map(cart -> cart.getItems().stream()
                            .sorted(Comparator.comparing(CartItem::getId))
                            .map(i -> toLine(i.getProduct(), i.getQuantity()))
                            .toList())
                    .orElse(List.of());
            case CartOwner.Guest g -> guestLines(g.store());
        };
        BigDecimal total = BigDecimal.ZERO;
        for (CartLineView line : lines) {
            if (line.lineTotal() != null) {
                total = total.add(line.lineTotal());
            }
        }
        return new CartView(lines, total);
    }

    /** Khách: không mở transaction, không lấy connection, không tạo session. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    int totalQuantity(CartOwner owner) {
        return switch (owner) {
            case CartOwner.Customer c -> (int) cartRepository.sumQuantityByUserId(c.userId());
            case CartOwner.Guest g -> g.store().find().map(SessionCart::totalQuantity).orElse(0);
        };
    }

    // ----------------------------------------------------------------- writes

    CartChange add(CartOwner owner, long productId, int quantity) {
        requireValidQuantity(quantity);
        return switch (owner) {
            case CartOwner.Customer c -> {
                User user = lockOwner(c.userId());
                Product product = activeProduct(productId);
                Optional<Cart> existing = cartRepository.findWithItemsByUserId(c.userId());
                Optional<CartItem> item = existing.flatMap(cart -> cart.findItem(productId));
                int current = item.map(CartItem::getQuantity).orElse(0);
                CartChange change = computeAdd(product.getStock(), current, quantity);
                if (change.outcome() != Outcome.OUT_OF_STOCK) {
                    Cart cart = existing.orElseGet(() -> cartRepository.save(new Cart(user)));
                    if (item.isPresent()) {
                        item.get().setQuantity(change.quantity());
                    } else {
                        cart.addItem(product, change.quantity());
                    }
                    cart.touch();
                }
                log.debug("Cart add: user={}, product={}, outcome={}", c.userId(), productId, change.outcome());
                yield change;
            }
            case CartOwner.Guest g -> {
                Product product = activeProduct(productId);
                int current = g.store().find().map(s -> s.quantityOf(productId)).orElse(0);
                CartChange change = computeAdd(product.getStock(), current, quantity);
                if (change.outcome() != Outcome.OUT_OF_STOCK) {
                    SessionCart cart = g.store().getOrCreate();
                    cart.put(productId, change.quantity());
                    g.store().save(cart);
                }
                yield change;
            }
        };
    }

    CartChange update(CartOwner owner, long productId, int quantity) {
        requireValidQuantity(quantity);
        return switch (owner) {
            case CartOwner.Customer c -> {
                lockOwner(c.userId());
                Optional<Cart> cart = cartRepository.findWithItemsByUserId(c.userId());
                Optional<CartItem> item = cart.flatMap(ct -> ct.findItem(productId));
                if (item.isEmpty()) {
                    yield new CartChange(Outcome.NOT_IN_CART, 0);
                }
                Product product = item.get().getProduct();
                if (!product.isActive()) {
                    throw new NotFoundException("Product not available: " + productId);
                }
                CartChange change = computeUpdate(product.getStock(), item.get().getQuantity(), quantity);
                if (change.outcome() != Outcome.OUT_OF_STOCK) {
                    item.get().setQuantity(change.quantity());
                    cart.get().touch();
                }
                yield change;
            }
            case CartOwner.Guest g -> {
                int current = g.store().find().map(s -> s.quantityOf(productId)).orElse(0);
                if (current == 0) {
                    yield new CartChange(Outcome.NOT_IN_CART, 0);
                }
                Product product = activeProduct(productId);
                CartChange change = computeUpdate(product.getStock(), current, quantity);
                if (change.outcome() != Outcome.OUT_OF_STOCK) {
                    SessionCart cart = g.store().getOrCreate();
                    cart.put(productId, change.quantity());
                    g.store().save(cart);
                }
                yield change;
            }
        };
    }

    /** Idempotent, và không kiểm sản phẩm nên xóa được cả dòng đã ngừng bán. */
    void remove(CartOwner owner, long productId) {
        switch (owner) {
            case CartOwner.Customer c -> {
                lockOwner(c.userId());
                cartRepository.findWithItemsByUserId(c.userId()).ifPresent(cart ->
                        cart.findItem(productId).ifPresent(item -> {
                            cart.removeItem(item);
                            cart.touch();
                        }));
            }
            case CartOwner.Guest g -> g.store().find().ifPresent(cart -> {
                cart.remove(productId);
                g.store().save(cart);
            });
        }
    }

    /**
     * Gộp giỏ khách vào giỏ DB: với mỗi dòng của khách, bỏ qua sản phẩm đã ẩn hoặc hết hàng, còn lại đặt
     * {@code min(DB + khách, tồn kho, 99)}. Không xóa giỏ session; người gọi làm việc đó sau khi commit.
     */
    void mergeGuestCart(long userId, SessionCart guestCart) {
        Map<Long, Integer> guestLines = guestCart.lines();
        if (guestLines.isEmpty()) {
            return;
        }
        User user = lockOwner(userId);
        Map<Long, Product> products = new HashMap<>();
        for (Product p : productRepository.findAllById(guestLines.keySet())) {
            products.put(p.getId(), p);
        }
        Cart cart = cartRepository.findWithItemsByUserId(userId).orElse(null);
        boolean changed = false;
        for (Map.Entry<Long, Integer> line : guestLines.entrySet()) {
            Product product = products.get(line.getKey());
            if (product == null || !product.isActive() || product.getStock() == 0) {
                continue;
            }
            Optional<CartItem> item = cart == null ? Optional.empty() : cart.findItem(line.getKey());
            int merged = Math.min(item.map(CartItem::getQuantity).orElse(0) + line.getValue(),
                    Math.min(product.getStock(), MAX_LINE_QUANTITY));
            if (item.isPresent()) {
                item.get().setQuantity(merged);
            } else {
                if (cart == null) {
                    cart = cartRepository.save(new Cart(user));
                }
                cart.addItem(product, merged);
            }
            changed = true;
        }
        if (changed) {
            cart.touch();
        }
        log.debug("Cart merge: user={}, guestLines={}", userId, guestLines.size());
    }

    // ---------------------------------------------------------------- helpers

    private static void requireValidQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_LINE_QUANTITY) {
            throw new IllegalArgumentException("quantity must be 1.." + MAX_LINE_QUANTITY + ": " + quantity);
        }
    }

    private User lockOwner(Long userId) {
        return cartRepository.lockOwner(userId)
                .orElseThrow(() -> new IllegalStateException("Cart owner not found: " + userId));
    }

    private Product activeProduct(long productId) {
        return productRepository.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new NotFoundException("Product not available: " + productId));
    }

    private static CartChange computeAdd(int stock, int current, int quantity) {
        if (stock == 0) {
            return new CartChange(Outcome.OUT_OF_STOCK, current);
        }
        int limit = Math.min(stock, MAX_LINE_QUANTITY);
        int desired = current + quantity;
        if (desired <= limit) {
            return new CartChange(Outcome.OK, desired);
        }
        return new CartChange(limit == stock ? Outcome.CAPPED_AT_STOCK : Outcome.CAPPED_AT_MAX, limit);
    }

    private static CartChange computeUpdate(int stock, int current, int quantity) {
        if (stock == 0) {
            return new CartChange(Outcome.OUT_OF_STOCK, current);
        }
        if (quantity > stock) {
            return new CartChange(Outcome.CAPPED_AT_STOCK, stock);
        }
        return new CartChange(Outcome.OK, quantity);
    }

    private List<CartLineView> guestLines(GuestCartStore store) {
        Optional<SessionCart> cart = store.find();
        if (cart.isEmpty() || cart.get().isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> lines = cart.get().lines();
        Map<Long, Product> products = new HashMap<>();
        for (Product p : productRepository.findAllById(lines.keySet())) {
            products.put(p.getId(), p);
        }
        List<CartLineView> result = new ArrayList<>();
        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            Product product = products.get(line.getKey());
            if (product != null) {
                result.add(toLine(product, line.getValue()));
            }
        }
        return result;
    }

    private CartLineView toLine(Product product, int quantity) {
        CartLineStatus status = CartLineStatus.of(product.isActive(), product.getStock(), quantity);
        boolean purchasable = status == CartLineStatus.AVAILABLE || status == CartLineStatus.LOW_STOCK;
        BigDecimal lineTotal = purchasable ? product.getPrice().multiply(BigDecimal.valueOf(quantity)) : null;
        int availableStock = status == CartLineStatus.LOW_STOCK ? product.getStock() : 0;
        return new CartLineView(product.getId(), product.getName(), productImages.toUrl(product.getImagePath()),
                product.getPrice(), quantity, lineTotal, status, availableStock);
    }
}

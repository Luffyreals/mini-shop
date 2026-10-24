package com.minishop.cart;

import com.minishop.cart.CartChange.Outcome;
import com.minishop.catalog.Category;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductImages;
import com.minishop.catalog.ProductRepository;
import com.minishop.common.NotFoundException;
import com.minishop.storage.StorageService;
import com.minishop.user.Role;
import com.minishop.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Quy tắc nghiệp vụ của {@link CartService} (AC-004-2..8, 11..16, 20..22; BR-004-1..7).
 *
 * <p>Repository là mock; giỏ khách là {@link InMemoryGuestCartStore}; giỏ DB là {@link Cart} thật nằm trong
 * bộ nhớ. Hầu hết case chạy cho cả hai loại chủ giỏ ({@code customer=false} là khách).
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    CartRepository cartRepository;

    @Mock
    ProductRepository productRepository;

    CartService service;

    private final Map<Long, Product> catalog = new HashMap<>();
    private final User user = new User("buyer@cart.test", "hash", "Buyer", Role.USER);
    private long nextItemId = 1;

    private static ProductImages productImages() {
        StorageService storage = org.mockito.Mockito.mock(StorageService.class);
        lenient().when(storage.toUrl(any())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            return path == null || path.isBlank() ? null : path;
        });
        return new ProductImages(storage);
    }

    @BeforeEach
    void setUp() {
        service = new CartService(cartRepository, productRepository, productImages());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        lenient().when(productRepository.findByIdAndActiveTrue(anyLong())).thenAnswer(inv -> {
            Product p = catalog.get(inv.<Long>getArgument(0));
            return p != null && p.isActive() ? Optional.of(p) : Optional.empty();
        });
        lenient().when(productRepository.findAllById(any())).thenAnswer(inv -> {
            List<Product> found = new ArrayList<>();
            for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                if (catalog.containsKey(id)) {
                    found.add(catalog.get(id));
                }
            }
            return found;
        });
    }

    // ---------- helpers ----------

    private Product product(long id, String price, int stock) {
        Product p = new Product(new Category("Cat", null), "Product " + id, null, new BigDecimal(price), stock);
        ReflectionTestUtils.setField(p, "id", id);
        catalog.put(id, p);
        return p;
    }

    private Product hidden(long id, int stock) {
        Product p = product(id, "1000", stock);
        p.setActive(false);
        return p;
    }

    /** Giỏ của một chủ giỏ cụ thể, cùng cách dựng dữ liệu và đọc kết quả cho cả hai loại. */
    private final class Fixture {
        final boolean customer;
        final InMemoryGuestCartStore store = new InMemoryGuestCartStore();
        Cart cart;
        final CartOwner owner;

        Fixture(boolean customer) {
            this.customer = customer;
            if (customer) {
                owner = new CartOwner.Customer(USER_ID);
                lenient().when(cartRepository.lockOwner(USER_ID)).thenReturn(Optional.of(user));
                lenient().when(cartRepository.findWithItemsByUserId(USER_ID))
                        .thenAnswer(inv -> Optional.ofNullable(cart));
                lenient().when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> {
                    cart = inv.getArgument(0);
                    return cart;
                });
            } else {
                owner = new CartOwner.Guest(store);
            }
        }

        Fixture with(Product product, int quantity) {
            if (customer) {
                if (cart == null) {
                    cart = new Cart(user);
                }
                CartItem item = cart.addItem(product, quantity);
                ReflectionTestUtils.setField(item, "id", nextItemId++);
            } else {
                store.seed(product.getId(), quantity);
            }
            return this;
        }

        int quantityOf(long productId) {
            if (customer) {
                return cart == null ? 0 : cart.findItem(productId).map(CartItem::getQuantity).orElse(0);
            }
            return store.find().map(c -> c.quantityOf(productId)).orElse(0);
        }

        int lineCount() {
            if (customer) {
                return cart == null ? 0 : cart.getItems().size();
            }
            return store.find().map(c -> c.lines().size()).orElse(0);
        }

        /** Khách: không getOrCreate/save. User: không có giỏ mới, hoặc giỏ chưa bị touch. */
        boolean wroteNothing() {
            return customer ? cart == null || cart.getUpdatedAt() == null
                    : store.getOrCreateCalls() == 0 && store.saveCalls() == 0;
        }
    }

    // ---------- add ----------

    /** AC-004-2: thêm vào giỏ trống. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_toEmptyCart_createsLineAndReturnsOk(boolean customer) {
        Fixture fx = new Fixture(customer);
        product(1, "50000", 10);

        CartChange change = service.add(fx.owner, 1, 2);

        assertThat(change).isEqualTo(new CartChange(Outcome.OK, 2));
        assertThat(fx.quantityOf(1)).isEqualTo(2);
        assertThat(fx.lineCount()).isEqualTo(1);
    }

    /** AC-004-2, BR-004-6: giỏ khách nằm trong session, không chạm CartRepository. */
    @Test
    void guestOperations_neverTouchCartRepository() {
        Fixture fx = new Fixture(false);
        product(1, "50000", 10);

        service.add(fx.owner, 1, 2);
        service.update(fx.owner, 1, 3);
        service.view(fx.owner);
        service.totalQuantity(fx.owner);
        service.remove(fx.owner, 1);

        verifyNoInteractions(cartRepository);
    }

    /** AC-004-3, BR-004-2: thêm lại cùng sản phẩm thì cộng dồn, không tạo dòng thứ hai. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_sameProductAgain_sumsQuantityInOneLine(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 10), 2);

        CartChange change = service.add(fx.owner, 1, 3);

        assertThat(change).isEqualTo(new CartChange(Outcome.OK, 5));
        assertThat(fx.quantityOf(1)).isEqualTo(5);
        assertThat(fx.lineCount()).isEqualTo(1);
    }

    /** AC-004-4, BR-004-1: vượt tồn kho thì đặt bằng tồn kho. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_beyondStock_capsAtStock(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 4), 3);

        CartChange change = service.add(fx.owner, 1, 5);

        assertThat(change.outcome()).isEqualTo(Outcome.CAPPED_AT_STOCK);
        assertThat(change.quantity()).isEqualTo(4);
        assertThat(fx.quantityOf(1)).isEqualTo(4);
    }

    /** AC-004-4, BR-004-1: dòng đang 5 mà kho chỉ còn 2, thêm 1 thì dòng giảm xuống 2. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_whenLineAlreadyAboveStock_shrinksLineToStock(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 2), 5);

        CartChange change = service.add(fx.owner, 1, 1);

        assertThat(change).isEqualTo(new CartChange(Outcome.CAPPED_AT_STOCK, 2));
        assertThat(fx.quantityOf(1)).isEqualTo(2);
    }

    /** BR-004-1 (tối đa 99 mỗi dòng, câu hỏi mở 1): kho 200, có 60, thêm 50. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_beyondMaxLineQuantity_capsAt99(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 200), 60);

        CartChange change = service.add(fx.owner, 1, 50);

        assertThat(change).isEqualTo(new CartChange(Outcome.CAPPED_AT_MAX, 99));
        assertThat(fx.quantityOf(1)).isEqualTo(99);
    }

    /** BR-004-1: kho đúng 99 mà vượt thì báo chạm tồn kho, không phải chạm 99. */
    @Test
    void add_whenStockEquals99AndExceeded_reportsStockNotMax() {
        Fixture fx = new Fixture(false).with(product(1, "50000", 99), 98);

        CartChange change = service.add(fx.owner, 1, 5);

        assertThat(change).isEqualTo(new CartChange(Outcome.CAPPED_AT_STOCK, 99));
    }

    /** BR-004-1: đặt vừa đúng tồn kho là OK. */
    @Test
    void add_exactlyUpToStock_isOk() {
        Fixture fx = new Fixture(false).with(product(1, "50000", 5), 2);

        assertThat(service.add(fx.owner, 1, 3)).isEqualTo(new CartChange(Outcome.OK, 5));
    }

    /** AC-004-5: hết hàng thì không ghi gì; khách không bị tạo session, user không có giỏ mới. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_whenOutOfStock_writesNothing(boolean customer) {
        Fixture fx = new Fixture(customer);
        product(1, "50000", 0);

        CartChange change = service.add(fx.owner, 1, 1);

        assertThat(change.outcome()).isEqualTo(Outcome.OUT_OF_STOCK);
        assertThat(fx.lineCount()).isZero();
        assertThat(fx.wroteNothing()).isTrue();
        if (customer) {
            verify(cartRepository, never()).save(any());
        } else {
            assertThat(fx.store.getOrCreateCalls()).isZero();
            assertThat(fx.store.saveCalls()).isZero();
        }
    }

    /** AC-004-5: hết hàng và dòng đã có sẵn thì dòng giữ nguyên. */
    @Test
    void add_whenOutOfStockAndLineExists_leavesLineUntouched() {
        Fixture fx = new Fixture(false).with(product(1, "50000", 0), 3);

        CartChange change = service.add(fx.owner, 1, 1);

        assertThat(change.outcome()).isEqualTo(Outcome.OUT_OF_STOCK);
        assertThat(fx.quantityOf(1)).isEqualTo(3);
        assertThat(fx.store.saveCalls()).isZero();
    }

    /** AC-004-6: sản phẩm đã ẩn hoặc không có thì NotFoundException, giỏ không đổi, không dùng session. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_hiddenOrUnknownProduct_throwsNotFoundWithoutWriting(boolean customer) {
        Fixture fx = new Fixture(customer);
        hidden(1, 5);

        assertThatThrownBy(() -> service.add(fx.owner, 1, 1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.add(fx.owner, 999, 1)).isInstanceOf(NotFoundException.class);

        assertThat(fx.lineCount()).isZero();
        assertThat(fx.wroteNothing()).isTrue();
        if (customer) {
            verify(cartRepository, never()).save(any());
        } else {
            assertThat(fx.store.getOrCreateCalls()).isZero();
            assertThat(fx.store.saveCalls()).isZero();
            assertThat(fx.store.find()).isEmpty();
        }
    }

    /** BR-004-1: service chặn số lượng ngoài 1..99 (lỗi lập trình). */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void add_withQuantityOutOfRange_throwsIllegalArgument(boolean customer) {
        Fixture fx = new Fixture(customer);
        product(1, "50000", 500);

        assertThatThrownBy(() -> service.add(fx.owner, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(fx.owner, 1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(fx.owner, 1, -3)).isInstanceOf(IllegalArgumentException.class);

        assertThat(fx.lineCount()).isZero();
    }

    /** AC-004-8, AC-004-20: user chưa có giỏ thì khóa trước, đọc giỏ, rồi lưu giỏ mới có đúng một dòng. */
    @Test
    void add_forCustomerWithoutCart_locksOwnerBeforeReadingAndCreatesCartWithOneLine() {
        Fixture fx = new Fixture(true);
        product(1, "50000", 10);

        service.add(fx.owner, 1, 2);

        InOrder order = inOrder(cartRepository);
        order.verify(cartRepository).lockOwner(USER_ID);
        order.verify(cartRepository).findWithItemsByUserId(USER_ID);
        order.verify(cartRepository).save(any(Cart.class));
        ArgumentCaptor<Cart> saved = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getItems()).hasSize(1);
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
        assertThat(fx.cart).isSameAs(saved.getValue());
    }

    /** AC-004-20: thêm vào giỏ đã có thì không tạo giỏ thứ hai, và cập nhật updated_at. */
    @Test
    void add_forCustomerWithCart_doesNotSaveANewCartAndTouchesIt() {
        Fixture fx = new Fixture(true).with(product(1, "50000", 10), 1);

        service.add(fx.owner, 1, 1);

        verify(cartRepository, never()).save(any());
        assertThat(fx.cart.getUpdatedAt()).isNotNull();
    }

    /** AC-004-20: chủ giỏ không tồn tại là lỗi trạng thái, không phải 404. */
    @Test
    void add_forCustomerThatDoesNotExist_throwsIllegalState() {
        product(1, "50000", 10);
        when(cartRepository.lockOwner(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(new CartOwner.Customer(USER_ID), 1, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- update ----------

    /** AC-004-11. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void update_withinStock_setsQuantity(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 10), 2);

        CartChange change = service.update(fx.owner, 1, 6);

        assertThat(change).isEqualTo(new CartChange(Outcome.OK, 6));
        assertThat(fx.quantityOf(1)).isEqualTo(6);
    }

    /** AC-004-11: vượt tồn kho thì đặt bằng tồn kho. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void update_beyondStock_capsAtStock(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 3), 2);

        CartChange change = service.update(fx.owner, 1, 10);

        assertThat(change).isEqualTo(new CartChange(Outcome.CAPPED_AT_STOCK, 3));
        assertThat(fx.quantityOf(1)).isEqualTo(3);
    }

    /** AC-004-11: kho 0 thì không đổi dòng. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void update_whenOutOfStock_leavesLineUnchanged(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 0), 4);

        CartChange change = service.update(fx.owner, 1, 2);

        assertThat(change.outcome()).isEqualTo(Outcome.OUT_OF_STOCK);
        assertThat(fx.quantityOf(1)).isEqualTo(4);
    }

    /** AC-004-11: không có dòng trong giỏ thì NOT_IN_CART và không tạo dòng. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void update_productNotInCart_returnsNotInCartAndWritesNothing(boolean customer) {
        Fixture fx = new Fixture(customer);
        product(1, "50000", 10);

        CartChange change = service.update(fx.owner, 1, 2);

        assertThat(change.outcome()).isEqualTo(Outcome.NOT_IN_CART);
        assertThat(fx.lineCount()).isZero();
        assertThat(fx.wroteNothing()).isTrue();
        if (customer) {
            verify(cartRepository, never()).save(any());
        }
    }

    /** AC-004-11, AC-004-14: dòng của sản phẩm đã ẩn thì NotFoundException, số lượng giữ nguyên. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void update_hiddenProduct_throwsNotFoundAndKeepsLine(boolean customer) {
        Fixture fx = new Fixture(customer).with(hidden(1, 5), 3);

        assertThatThrownBy(() -> service.update(fx.owner, 1, 2)).isInstanceOf(NotFoundException.class);

        assertThat(fx.quantityOf(1)).isEqualTo(3);
    }

    /** BR-004-1. */
    @Test
    void update_withQuantityOutOfRange_throwsIllegalArgument() {
        Fixture fx = new Fixture(false).with(product(1, "50000", 500), 2);

        assertThatThrownBy(() -> service.update(fx.owner, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(fx.owner, 1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fx.quantityOf(1)).isEqualTo(2);
    }

    // ---------- remove ----------

    /** AC-004-12. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void remove_existingLine_removesOnlyThatLine(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 10), 2).with(product(2, "10000", 10), 1);

        service.remove(fx.owner, 1);

        assertThat(fx.quantityOf(1)).isZero();
        assertThat(fx.quantityOf(2)).isEqualTo(1);
    }

    /** AC-004-12: xóa lũy đẳng, kể cả khi giỏ chưa tồn tại; không tạo session hay giỏ. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void remove_missingLineOrMissingCart_isIdempotentAndCreatesNothing(boolean customer) {
        Fixture fx = new Fixture(customer);

        service.remove(fx.owner, 1);
        service.remove(fx.owner, 1);

        assertThat(fx.lineCount()).isZero();
        if (customer) {
            verify(cartRepository, never()).save(any());
        } else {
            assertThat(fx.store.getOrCreateCalls()).isZero();
            assertThat(fx.store.find()).isEmpty();
        }
    }

    /** AC-004-12: xóa dòng không có trong giỏ đang có hàng thì giữ nguyên các dòng khác. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void remove_lineNotInNonEmptyCart_keepsOtherLines(boolean customer) {
        Fixture fx = new Fixture(customer).with(product(1, "50000", 10), 2);

        service.remove(fx.owner, 99);

        assertThat(fx.quantityOf(1)).isEqualTo(2);
    }

    /** AC-004-12, AC-004-14: xóa được cả dòng đã ngừng bán (không kiểm sản phẩm). */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void remove_discontinuedLine_removesIt(boolean customer) {
        Fixture fx = new Fixture(customer).with(hidden(1, 5), 2);

        service.remove(fx.owner, 1);

        assertThat(fx.lineCount()).isZero();
        verify(productRepository, never()).findByIdAndActiveTrue(anyLong());
    }

    // ---------- view ----------

    /** AC-004-13, BR-004-3: đơn giá luôn là giá hiện tại. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void view_usesCurrentPrice(boolean customer) {
        Product p = product(1, "100000", 10);
        Fixture fx = new Fixture(customer).with(p, 2);
        assertThat(service.view(fx.owner).lines().getFirst().unitPrice()).isEqualByComparingTo("100000");

        ReflectionTestUtils.setField(p, "price", new BigDecimal("120000"));
        CartView view = service.view(fx.owner);

        assertThat(view.lines().getFirst().unitPrice()).isEqualByComparingTo("120000");
        assertThat(view.lines().getFirst().lineTotal()).isEqualByComparingTo("240000");
        assertThat(view.total()).isEqualByComparingTo("240000");
    }

    /** AC-004-14, BR-004-5: bốn trạng thái, tổng chỉ cộng dòng mua được, LOW_STOCK vẫn được tính. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void view_classifiesLinesAndTotalsOnlyPurchasableOnes(boolean customer) {
        Fixture fx = new Fixture(customer)
                .with(hidden(2, 9), 1)                    // B: ngừng bán
                .with(product(3, "20000", 0), 2)          // C: hết hàng
                .with(product(4, "30000", 2), 5)          // D: 5 > kho 2
                .with(product(5, "40000", 50), 3);        // E: bình thường

        CartView view = service.view(fx.owner);

        assertThat(view.lines()).extracting(CartLineView::productId).containsExactly(2L, 3L, 4L, 5L);
        assertThat(view.lines()).extracting(CartLineView::status).containsExactly(
                CartLineStatus.DISCONTINUED, CartLineStatus.OUT_OF_STOCK,
                CartLineStatus.LOW_STOCK, CartLineStatus.AVAILABLE);
        CartLineView b = view.lines().get(0);
        CartLineView c = view.lines().get(1);
        CartLineView d = view.lines().get(2);
        CartLineView e = view.lines().get(3);
        assertThat(b.lineTotal()).isNull();
        assertThat(c.lineTotal()).isNull();
        assertThat(b.isPurchasable()).isFalse();
        assertThat(c.isPurchasable()).isFalse();
        assertThat(d.isPurchasable()).isTrue();
        assertThat(d.availableStock()).isEqualTo(2);
        assertThat(d.quantity()).isEqualTo(5);
        assertThat(d.lineTotal()).isEqualByComparingTo("150000");
        assertThat(b.availableStock()).isZero();
        assertThat(c.availableStock()).isZero();
        assertThat(e.availableStock()).isZero();
        assertThat(e.lineTotal()).isEqualByComparingTo("120000");
        assertThat(view.total()).isEqualByComparingTo("270000");
        assertThat(view.isEmpty()).isFalse();
    }

    /** AC-004-14: DISCONTINUED thắng OUT_OF_STOCK; OUT_OF_STOCK thắng LOW_STOCK. */
    @Test
    void view_statusPrecedence_discontinuedThenOutOfStockThenLowStock() {
        Fixture fx = new Fixture(false)
                .with(hidden(1, 0), 3)               // ẩn và kho 0 -> DISCONTINUED
                .with(product(2, "1000", 0), 3);     // kho 0, số lượng > kho -> OUT_OF_STOCK

        CartView view = service.view(fx.owner);

        assertThat(view.lines()).extracting(CartLineView::status)
                .containsExactly(CartLineStatus.DISCONTINUED, CartLineStatus.OUT_OF_STOCK);
        assertThat(view.lines().get(0).isDiscontinued()).isTrue();
        assertThat(view.lines().get(1).isOutOfStock()).isTrue();
        assertThat(view.total()).isEqualByComparingTo("0");
    }

    /** AC-004-14, BR-004-5: số lượng bằng kho là AVAILABLE, hơn kho một đơn vị là LOW_STOCK. */
    @Test
    void view_quantityEqualToStock_isAvailableAndOneAboveIsLowStock() {
        Fixture fx = new Fixture(false).with(product(1, "1000", 3), 3).with(product(2, "1000", 3), 4);

        CartView view = service.view(fx.owner);

        assertThat(view.lines()).extracting(CartLineView::status)
                .containsExactly(CartLineStatus.AVAILABLE, CartLineStatus.LOW_STOCK);
        assertThat(view.lines().get(1).isLowStock()).isTrue();
    }

    /** AC-004-14, câu hỏi mở 7: xem giỏ không bao giờ sửa giỏ khách. */
    @Test
    void view_forGuest_neverMutatesCartOrCreatesSession() {
        Fixture fx = new Fixture(false)
                .with(hidden(1, 5), 1)
                .with(product(2, "1000", 0), 2)
                .with(product(3, "1000", 2), 5);

        service.view(fx.owner);

        assertThat(fx.store.getOrCreateCalls()).isZero();
        assertThat(fx.store.saveCalls()).isZero();
        assertThat(fx.store.clearCalls()).isZero();
        assertThat(fx.store.find().orElseThrow().lines())
                .containsExactly(Map.entry(1L, 1), Map.entry(2L, 2), Map.entry(3L, 5));
    }

    /** AC-004-14, câu hỏi mở 7: xem giỏ không sửa giỏ DB (không lưu, không khóa, không đổi số lượng). */
    @Test
    void view_forCustomer_neverMutatesCart() {
        Fixture fx = new Fixture(true)
                .with(hidden(1, 5), 1)
                .with(product(2, "1000", 0), 2)
                .with(product(3, "1000", 2), 5);

        service.view(fx.owner);

        verify(cartRepository, never()).save(any());
        verify(cartRepository, never()).lockOwner(anyLong());
        assertThat(fx.cart.getItems()).extracting(CartItem::getQuantity).containsExactly(1, 2, 5);
        assertThat(fx.cart.getUpdatedAt()).isNull();
    }

    /** QĐ6: người dùng đăng nhập thấy các dòng theo thứ tự thêm vào (id tăng dần). */
    @Test
    void view_forCustomer_ordersLinesByItemId() {
        Fixture fx = new Fixture(true).with(product(30, "1000", 5), 1).with(product(10, "1000", 5), 1);

        CartView view = service.view(fx.owner);

        assertThat(view.lines()).extracting(CartLineView::productId).containsExactly(30L, 10L);
    }

    /** QĐ6: chưa có giỏ thì view rỗng, tổng 0, không truy vấn sản phẩm. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void view_withoutCart_isEmptyWithZeroTotal(boolean customer) {
        Fixture fx = new Fixture(customer);

        CartView view = service.view(fx.owner);

        assertThat(view.isEmpty()).isTrue();
        assertThat(view.total()).isEqualByComparingTo("0");
        verify(productRepository, never()).findAllById(any());
    }

    /** QĐ6: khách tốn đúng một câu truy vấn sản phẩm cho cả giỏ. */
    @Test
    void view_forGuest_loadsAllProductsWithSingleQuery() {
        Fixture fx = new Fixture(false).with(product(1, "1000", 5), 1).with(product(2, "1000", 5), 1);

        service.view(fx.owner);

        verify(productRepository, times(1)).findAllById(any());
        verify(productRepository, never()).findById(anyLong());
        verify(productRepository, never()).findByIdAndActiveTrue(anyLong());
    }

    // ---------- totalQuantity ----------

    /** AC-004-22: khách đọc từ session, không chạm repository, không tạo session. */
    @Test
    void totalQuantity_forGuest_sumsSessionAndNeverCreatesSession() {
        Fixture empty = new Fixture(false);
        assertThat(service.totalQuantity(empty.owner)).isZero();
        assertThat(empty.store.getOrCreateCalls()).isZero();

        Fixture fx = new Fixture(false).with(product(1, "1000", 5), 4).with(hidden(2, 0), 1);
        assertThat(service.totalQuantity(fx.owner)).isEqualTo(5);
        assertThat(fx.store.getOrCreateCalls()).isZero();
        assertThat(fx.store.saveCalls()).isZero();

        verifyNoInteractions(cartRepository, productRepository);
    }

    /** AC-004-22: user gọi đúng một truy vấn tổng, không khóa. */
    @Test
    void totalQuantity_forCustomer_callsSumQueryOnce() {
        when(cartRepository.sumQuantityByUserId(USER_ID)).thenReturn(5L);

        int total = service.totalQuantity(new CartOwner.Customer(USER_ID));

        assertThat(total).isEqualTo(5);
        verify(cartRepository, times(1)).sumQuantityByUserId(USER_ID);
        verify(cartRepository, never()).lockOwner(anyLong());
        verifyNoInteractions(productRepository);
    }

    // ---------- locking (AC-004-20, ADR-0008) ----------

    /** AC-004-20: mọi thao tác ghi giỏ DB khóa chủ giỏ trước khi đọc giỏ. */
    @Test
    void customerWrites_lockOwnerBeforeReadingCart() {
        Fixture fx = new Fixture(true).with(product(1, "1000", 10), 2);
        product(2, "1000", 10);

        service.add(fx.owner, 2, 1);
        service.update(fx.owner, 1, 3);
        service.remove(fx.owner, 2);
        service.mergeGuestCart(USER_ID, guestCart(1, 1));

        InOrder order = inOrder(cartRepository);
        for (int i = 0; i < 4; i++) {
            order.verify(cartRepository).lockOwner(USER_ID);
            order.verify(cartRepository).findWithItemsByUserId(USER_ID);
        }
    }

    /** AC-004-20: update/remove khi chưa có giỏ vẫn khóa trước. */
    @Test
    void updateAndRemove_forCustomerWithoutCart_stillLockFirst() {
        Fixture fx = new Fixture(true);

        service.update(fx.owner, 1, 1);
        service.remove(fx.owner, 1);

        InOrder order = inOrder(cartRepository);
        order.verify(cartRepository).lockOwner(USER_ID);
        order.verify(cartRepository).findWithItemsByUserId(USER_ID);
        order.verify(cartRepository).lockOwner(USER_ID);
        order.verify(cartRepository).findWithItemsByUserId(USER_ID);
    }

    // ---------- mergeGuestCart ----------

    private static SessionCart guestCart(long... idAndQuantity) {
        SessionCart cart = new SessionCart();
        for (int i = 0; i < idAndQuantity.length; i += 2) {
            cart.put(idAndQuantity[i], (int) idAndQuantity[i + 1]);
        }
        return cart;
    }

    /** AC-004-15: DB {A:3}, khách {A:2, B:1}, kho A=4 thì kết quả {A:4, B:1}. */
    @Test
    void mergeGuestCart_sumsAndCapsAtStock() {
        Fixture fx = new Fixture(true).with(product(1, "1000", 4), 3);
        product(2, "1000", 10);

        service.mergeGuestCart(USER_ID, guestCart(1, 2, 2, 1));

        assertThat(fx.quantityOf(1)).isEqualTo(4);
        assertThat(fx.quantityOf(2)).isEqualTo(1);
        assertThat(fx.lineCount()).isEqualTo(2);
        verify(cartRepository, never()).save(any());
        assertThat(fx.cart.getUpdatedAt()).isNotNull();
    }

    /** AC-004-15, BR-004-1: tổng vượt 99 thì còn 99 (kho lớn hơn 99). */
    @Test
    void mergeGuestCart_capsAt99WhenStockIsLarger() {
        Fixture fx = new Fixture(true).with(product(1, "1000", 500), 90);

        service.mergeGuestCart(USER_ID, guestCart(1, 50));

        assertThat(fx.quantityOf(1)).isEqualTo(99);
    }

    /** AC-004-15: chưa có giỏ DB thì tạo giỏ, một lần, với các dòng hợp lệ. */
    @Test
    void mergeGuestCart_withoutDbCart_createsCartWithEligibleLines() {
        Fixture fx = new Fixture(true);
        product(1, "1000", 5);
        product(2, "1000", 5);

        service.mergeGuestCart(USER_ID, guestCart(1, 2, 2, 3));

        verify(cartRepository, times(1)).save(any(Cart.class));
        assertThat(fx.quantityOf(1)).isEqualTo(2);
        assertThat(fx.quantityOf(2)).isEqualTo(3);
        assertThat(fx.cart.getUser()).isSameAs(user);
        assertThat(fx.cart.getUpdatedAt()).isNotNull();
    }

    /** AC-004-16, BR-004-7: bỏ qua sản phẩm hết hàng, đã ẩn, không tồn tại; không ném lỗi. */
    @Test
    void mergeGuestCart_skipsHiddenOutOfStockAndMissingProducts() {
        Fixture fx = new Fixture(true).with(product(1, "1000", 10), 1);
        hidden(2, 5);
        product(3, "1000", 0);
        product(4, "1000", 10);

        service.mergeGuestCart(USER_ID, guestCart(2, 1, 3, 1, 999, 1, 4, 2));

        assertThat(fx.lineCount()).isEqualTo(2);
        assertThat(fx.quantityOf(1)).isEqualTo(1);
        assertThat(fx.quantityOf(4)).isEqualTo(2);
        assertThat(fx.quantityOf(2)).isZero();
        assertThat(fx.quantityOf(3)).isZero();
        assertThat(fx.quantityOf(999)).isZero();
    }

    /** AC-004-16: không có dòng hợp lệ nào thì không tạo giỏ DB rỗng. */
    @Test
    void mergeGuestCart_whenNoLineEligible_doesNotCreateCart() {
        Fixture fx = new Fixture(true);
        hidden(1, 5);
        product(2, "1000", 0);

        service.mergeGuestCart(USER_ID, guestCart(1, 1, 2, 1, 999, 1));

        verify(cartRepository, never()).save(any());
        assertThat(fx.cart).isNull();
    }

    /** AC-004-16: không có dòng hợp lệ thì giỏ DB đang có không bị đổi (kể cả updated_at). */
    @Test
    void mergeGuestCart_whenNoLineEligible_leavesExistingCartUntouched() {
        Fixture fx = new Fixture(true).with(product(1, "1000", 10), 2);
        hidden(2, 5);

        service.mergeGuestCart(USER_ID, guestCart(2, 1));

        assertThat(fx.quantityOf(1)).isEqualTo(2);
        assertThat(fx.cart.getUpdatedAt()).isNull();
    }

    /** AC-004-15: giỏ khách rỗng thì không làm gì: không khóa, không truy vấn. */
    @Test
    void mergeGuestCart_withEmptyGuestCart_doesNothing() {
        service.mergeGuestCart(USER_ID, new SessionCart());

        verifyNoInteractions(cartRepository, productRepository);
    }

    /** AC-004-15: tải mọi sản phẩm bằng đúng một findAllById, không N+1. */
    @Test
    void mergeGuestCart_loadsProductsWithSingleFindAllById() {
        new Fixture(true);
        product(1, "1000", 5);
        product(2, "1000", 5);
        product(3, "1000", 5);

        service.mergeGuestCart(USER_ID, guestCart(1, 1, 2, 1, 3, 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(productRepository, times(1)).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
        verify(productRepository, never()).findById(anyLong());
        verify(productRepository, never()).findByIdAndActiveTrue(anyLong());
    }

    /** AC-004-20: gộp cũng khóa chủ giỏ trước khi đọc giỏ. */
    @Test
    void mergeGuestCart_locksOwnerBeforeReadingCart() {
        new Fixture(true);
        product(1, "1000", 5);

        service.mergeGuestCart(USER_ID, guestCart(1, 1));

        InOrder order = inOrder(cartRepository);
        order.verify(cartRepository).lockOwner(USER_ID);
        order.verify(cartRepository).findWithItemsByUserId(USER_ID);
    }

    /** BR-004-7: service không sửa giỏ khách (người gọi xóa sau khi commit). */
    @Test
    void mergeGuestCart_doesNotModifyGuestCart() {
        new Fixture(true);
        product(1, "1000", 5);
        SessionCart guest = guestCart(1, 2);

        service.mergeGuestCart(USER_ID, guest);

        assertThat(guest.lines()).containsExactly(Map.entry(1L, 2));
    }

    // ---------- stock is never written (AC-004-21, BR-004-4) ----------

    /** AC-004-21, BR-004-4: không thao tác giỏ nào ghi tồn kho. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void cartOperations_neverDecrementStock(boolean customer) {
        Product p = product(1, "1000", 10);
        Fixture fx = new Fixture(customer);

        service.add(fx.owner, 1, 3);
        service.update(fx.owner, 1, 5);
        service.view(fx.owner);
        service.totalQuantity(fx.owner);
        service.remove(fx.owner, 1);
        if (customer) {
            service.mergeGuestCart(USER_ID, guestCart(1, 2));
        }

        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
        assertThat(p.getStock()).isEqualTo(10);
    }

    // ---------- checkout button flag (AC-005-1) ----------

    private static CartLineView lineWithStatus(CartLineStatus status) {
        return new CartLineView(1L, "P", null, BigDecimal.TEN, 1, BigDecimal.TEN, status, 0);
    }

    /** AC-005-1 */
    @Test
    void cartView_isOrderable_whenEveryLineIsAvailable() {
        CartView view = new CartView(
                List.of(lineWithStatus(CartLineStatus.AVAILABLE), lineWithStatus(CartLineStatus.AVAILABLE)),
                BigDecimal.TEN);

        assertThat(view.isOrderable()).isTrue();
    }

    /** AC-005-1 */
    @ParameterizedTest
    @EnumSource(value = CartLineStatus.class, names = {"LOW_STOCK", "OUT_OF_STOCK", "DISCONTINUED"})
    void cartView_isNotOrderable_whenAnyLineIsNotAvailable(CartLineStatus bad) {
        CartView view = new CartView(List.of(lineWithStatus(CartLineStatus.AVAILABLE), lineWithStatus(bad)),
                BigDecimal.TEN);

        assertThat(view.isOrderable()).isFalse();
    }

    /** AC-005-1 */
    @Test
    void cartView_isNotOrderable_whenEmpty() {
        assertThat(new CartView(List.of(), BigDecimal.ZERO).isOrderable()).isFalse();
    }

    /** AC-005-1: the flag derived from a real view of the cart, for both kinds of owner. */
    @ParameterizedTest(name = "customer={0}")
    @ValueSource(booleans = {false, true})
    void view_isOrderableOnlyWhenNoLineIsShortOrHidden(boolean customer) {
        Fixture fx = new Fixture(customer);
        product(1, "1000", 5);
        fx.with(catalog.get(1L), 2);
        assertThat(service.view(fx.owner).isOrderable()).isTrue();

        product(2, "1000", 1);
        fx.with(catalog.get(2L), 3);
        assertThat(service.view(fx.owner).isOrderable()).isFalse();
    }

    /** AC-005-1 / BR-005-2: the order in which {@link CartLineStatus#of} looks at the product. */
    @ParameterizedTest(name = "active={0} stock={1} quantity={2} -> {3}")
    @CsvSource({
            "true,  5, 5, AVAILABLE",
            "true,  5, 1, AVAILABLE",
            "true,  5, 6, LOW_STOCK",
            "true,  0, 1, OUT_OF_STOCK",
            "false, 5, 1, DISCONTINUED",
            "false, 0, 1, DISCONTINUED",
            "false, 1, 9, DISCONTINUED"})
    void cartLineStatusOf_checksDiscontinuedThenOutOfStockThenLowStock(boolean active, int stock, int quantity,
                                                                       CartLineStatus expected) {
        assertThat(CartLineStatus.of(active, stock, quantity)).isEqualTo(expected);
    }
}

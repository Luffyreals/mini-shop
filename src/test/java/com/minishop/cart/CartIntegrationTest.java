package com.minishop.cart;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giỏ hàng trên H2 thật (QĐ12): ghi/đọc qua {@link CartService}, truy vấn, khóa đồng thời và tồn kho không đổi.
 * Không @Transactional (race cần transaction thật), không @MockitoSpyBean (dùng chung context với test tích hợp khác).
 */
@SpringBootTest
class CartIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    CartService cartService;

    CartTestData data;

    @BeforeEach
    void setUp() {
        data = new CartTestData(jdbc, encoder);
        data.cleanUp();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private static CartOwner customer(CartTestData.TestUser u) {
        return new CartOwner.Customer(u.id());
    }

    private static SessionCart guestCart(long... productIdAndQuantity) {
        SessionCart cart = new SessionCart();
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            cart.put(productIdAndQuantity[i], (int) productIdAndQuantity[i + 1]);
        }
        return cart;
    }

    // ------------------------------------------------------------------ guest

    /** AC-004-2: khách thêm hàng thì giỏ nằm trong store (session), không có dòng nào trong carts/cart_items. */
    @Test
    void guestAdd_writesNoCartRowsAndKeepsTheLinesInTheStore() {
        long a = data.createProduct("A", 100_000, 10);
        int carts = data.totalRows("carts");
        int items = data.totalRows("cart_items");
        RecordingGuestCartStore store = new RecordingGuestCartStore();
        CartOwner guest = new CartOwner.Guest(store);

        CartChange change = cartService.add(guest, a, 2);

        assertThat(change).isEqualTo(new CartChange(CartChange.Outcome.OK, 2));
        assertThat(cartService.totalQuantity(guest)).isEqualTo(2);
        assertThat(cartService.view(guest).lines()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(a);
            assertThat(line.quantity()).isEqualTo(2);
        });
        assertThat(data.totalRows("carts")).isEqualTo(carts);
        assertThat(data.totalRows("cart_items")).isEqualTo(items);
    }

    /** Spec §8: khách xem giỏ trống không tạo giỏ trong store (không tạo session). */
    @Test
    void guest_viewingAndCountingAnEmptyCart_createsNothingInTheStore() {
        RecordingGuestCartStore store = new RecordingGuestCartStore();
        CartOwner guest = new CartOwner.Guest(store);

        assertThat(cartService.view(guest).isEmpty()).isTrue();
        assertThat(cartService.totalQuantity(guest)).isZero();

        assertThat(store.getOrCreateCalls()).isZero();
        assertThat(store.saveCalls()).isZero();
        assertThat(store.find()).isEmpty();
    }

    // --------------------------------------------------------------- customer

    /** AC-004-8 */
    @Test
    void firstAdd_forUserWithoutCart_createsExactlyOneCartAndOneItem() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        assertThat(data.cartCount(u.id())).isZero();

        cartService.add(customer(u), a, 3);

        assertThat(data.cartCount(u.id())).isEqualTo(1);
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 3));
    }

    /** AC-004-13 */
    @Test
    void view_afterPriceChange_showsNewPriceAndTotal() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 2});

        CartView before = cartService.view(customer(u));
        assertThat(before.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isEqualByComparingTo("100000");
            assertThat(line.lineTotal()).isEqualByComparingTo("200000");
        });
        assertThat(before.total()).isEqualByComparingTo("200000");

        data.setPrice(a, 120_000);

        CartView after = cartService.view(customer(u));
        assertThat(after.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isEqualByComparingTo("120000");
            assertThat(line.lineTotal()).isEqualByComparingTo("240000");
        });
        assertThat(after.total()).isEqualByComparingTo("240000");
    }

    /** AC-004-14: nhãn từng trạng thái, tổng chỉ gồm dòng mua được, và xem giỏ không sửa giỏ. */
    @Test
    void view_withHiddenOutOfStockAndLowStockLines_labelsThemTotalsOnlyBuyableOnesAndDoesNotChangeRows() {
        var u = data.createUser();
        long a = data.createProduct("A ok", 100_000, 10);
        long b = data.createProduct("B hidden", 70_000, 10);
        long c = data.createProduct("C soldout", 80_000, 10);
        long d = data.createProduct("D low", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 4}, new long[]{b, 1}, new long[]{c, 1}, new long[]{d, 5});
        data.setActive(b, false);
        data.setStock(c, 0);
        data.setStock(d, 2);
        List<Map<String, Object>> before = jdbc.queryForList(
                "select i.id, i.cart_id, i.product_id, i.quantity from cart_items i join carts k on k.id = i.cart_id "
                        + "where k.user_id = ? order by i.id", u.id());
        Object updatedAtBefore = jdbc.queryForObject("select updated_at from carts where user_id = ?",
                java.sql.Timestamp.class, u.id());

        CartView view = cartService.view(customer(u));

        assertThat(view.lines()).extracting(CartLineView::productId, CartLineView::status)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(a, CartLineStatus.AVAILABLE),
                        org.assertj.core.api.Assertions.tuple(b, CartLineStatus.DISCONTINUED),
                        org.assertj.core.api.Assertions.tuple(c, CartLineStatus.OUT_OF_STOCK),
                        org.assertj.core.api.Assertions.tuple(d, CartLineStatus.LOW_STOCK));
        assertThat(view.lines()).filteredOn(l -> l.productId() == d).singleElement()
                .satisfies(l -> assertThat(l.availableStock()).isEqualTo(2));
        // Chỉ dòng A (4 x 100.000) và dòng D (5 x 100.000, còn mua được ở mức hiện có) tính vào tổng.
        assertThat(view.total()).isEqualByComparingTo("900000");
        assertThat(view.isOrderable()).isFalse();
        List<Map<String, Object>> after = jdbc.queryForList(
                "select i.id, i.cart_id, i.product_id, i.quantity from cart_items i join carts k on k.id = i.cart_id "
                        + "where k.user_id = ? order by i.id", u.id());
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("select updated_at from carts where user_id = ?",
                java.sql.Timestamp.class, u.id())).isEqualTo(updatedAtBefore);
    }

    /** AC-004-22: số trên header là tổng số lượng của người dùng (cart.CartService#totalQuantity). */
    @Test
    void totalQuantity_forLoggedInUser_sumsEveryLine() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        long b = data.createProduct("B", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 2}, new long[]{b, 3});

        assertThat(cartService.totalQuantity(customer(u))).isEqualTo(5);
    }

    /** AC-004-22: người dùng chưa có giỏ thì số lượng là 0 và không tạo giỏ. */
    @Test
    void totalQuantity_forUserWithoutCart_isZeroAndCreatesNoCart() {
        var u = data.createUser();

        assertThat(cartService.totalQuantity(customer(u))).isZero();
        assertThat(data.cartCount(u.id())).isZero();
    }

    /** AC-004-18 */
    @Test
    void twoUsers_cartsAreIsolated() {
        var u1 = data.createUser();
        var u2 = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        long b = data.createProduct("B", 100_000, 10);
        long q = data.createProduct("Q only for user two", 100_000, 10);
        data.createCart(u1.id(), new long[]{a, 1});
        data.createCart(u2.id(), new long[]{b, 4}, new long[]{q, 2});
        Map<Long, Integer> u2Before = data.cartLines(u2.id());

        cartService.add(customer(u1), b, 2);
        cartService.update(customer(u1), a, 7);
        cartService.remove(customer(u1), b);
        cartService.remove(customer(u1), q);                       // not in u1's cart: nothing happens
        CartChange foreign = cartService.update(customer(u1), q, 9);

        assertThat(foreign.outcome()).isEqualTo(CartChange.Outcome.NOT_IN_CART);
        assertThat(data.cartLines(u1.id())).containsExactly(Map.entry(a, 7));
        assertThat(data.cartLines(u2.id())).isEqualTo(u2Before);
        assertThat(cartService.view(customer(u1)).lines()).extracting(CartLineView::productId).containsExactly(a);
        assertThat(cartService.view(customer(u2)).lines()).extracting(CartLineView::productId)
                .containsExactly(Math.min(b, q), Math.max(b, q));
    }

    // ------------------------------------------------- merge the guest cart

    /** AC-004-15: DB {A:3}, khách {A:2, B:1}, kho A = 4 thì ra {A:4, B:1}; giỏ khách không bị sửa. */
    @Test
    void mergeGuestCart_sumsCapsAtStockAndLeavesTheGuestCartAlone() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 4);
        long b = data.createProduct("B", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 3});
        SessionCart guest = guestCart(a, 2, b, 1);

        cartService.mergeGuestCart(u.id(), guest);

        assertThat(data.cartCount(u.id())).isEqualTo(1);
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(Math.min(a, b), Math.min(a, b) == a ? 4 : 1),
                Map.entry(Math.max(a, b), Math.max(a, b) == a ? 4 : 1));
        assertThat(cartService.totalQuantity(customer(u))).isEqualTo(5);
        assertThat(guest.lines()).containsExactly(Map.entry(a, 2), Map.entry(b, 1));
    }

    /** AC-004-16: dòng của khách đã ẩn hoặc hết hàng bị bỏ qua, các dòng còn lại vẫn được gộp. */
    @Test
    void mergeGuestCart_withHiddenAndOutOfStockLines_skipsThemAndMergesTheRest() {
        var u = data.createUser();
        long ok = data.createProduct("OK", 100_000, 10);
        long hidden = data.createProduct("Hidden", 100_000, 10);
        long soldOut = data.createProduct("Sold out", 100_000, 10);
        data.setActive(hidden, false);
        data.setStock(soldOut, 0);

        cartService.mergeGuestCart(u.id(), guestCart(ok, 1, hidden, 1, soldOut, 1));

        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(ok, 1));
    }

    /** BR-004-6: giỏ khách rỗng, hoặc không có dòng nào gộp được, thì không tạo dòng nào trong carts. */
    @Test
    void mergeGuestCart_withNothingToMerge_createsNoCartRow() {
        var u = data.createUser();
        long hidden = data.createProduct("Hidden", 100_000, 10);
        data.setActive(hidden, false);
        int before = data.totalRows("carts");

        cartService.mergeGuestCart(u.id(), new SessionCart());
        cartService.mergeGuestCart(u.id(), guestCart(hidden, 2));

        assertThat(data.cartCount(u.id())).isZero();
        assertThat(data.totalRows("carts")).isEqualTo(before);
    }

    // ------------------------------------------------------------------- race

    /** AC-004-20: hai luồng cùng thêm cho một người, 20 vòng, chưa có giỏ. */
    @Test
    void race_twoThreadsAddWithoutExistingCart_oneCartOneLineSummedQuantity() throws Exception {
        long p = data.createProduct("Race", 100_000, 50);
        long start = System.nanoTime();
        for (int round = 0; round < 20; round++) {
            var u = data.createUser();
            raceAdd(u.id(), p, 3);
            assertThat(data.cartCount(u.id())).as("round %d carts", round).isEqualTo(1);
            assertThat(data.cartLines(u.id())).as("round %d lines", round).containsExactly(Map.entry(p, 6));
        }
        System.out.printf("AC-004-20 race without cart: 20 rounds in %d ms%n",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    /** AC-004-20: đã có dòng 2, mỗi luồng thêm 3 thì ra 8, không mất cập nhật. */
    @Test
    void race_twoThreadsAddWithExistingLine_noLostUpdate() throws Exception {
        long p = data.createProduct("Race", 100_000, 50);
        long start = System.nanoTime();
        for (int round = 0; round < 20; round++) {
            var u = data.createUser();
            data.createCart(u.id(), new long[]{p, 2});
            raceAdd(u.id(), p, 3);
            assertThat(data.cartCount(u.id())).as("round %d carts", round).isEqualTo(1);
            assertThat(data.cartLines(u.id())).as("round %d lines", round).containsExactly(Map.entry(p, 8));
        }
        System.out.printf("AC-004-20 race with line: 20 rounds in %d ms%n",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    /** AC-004-20: có giỏ nhưng chưa có dòng của sản phẩm này. */
    @Test
    void race_twoThreadsAddWithExistingCartWithoutLine_oneLine() throws Exception {
        long p = data.createProduct("Race", 100_000, 50);
        long other = data.createProduct("Other", 100_000, 50);
        for (int round = 0; round < 20; round++) {
            var u = data.createUser();
            data.createCart(u.id(), new long[]{other, 1});
            raceAdd(u.id(), p, 3);
            assertThat(data.cartCount(u.id())).as("round %d carts", round).isEqualTo(1);
            assertThat(data.cartLines(u.id())).as("round %d lines", round)
                    .containsExactly(Map.entry(Math.min(p, other), Math.min(p, other) == p ? 6 : 1),
                            Map.entry(Math.max(p, other), Math.max(p, other) == p ? 6 : 1));
        }
    }

    /** AC-004-20: kho 5, mỗi luồng thêm 4 thì bị chặn ở 5. */
    @Test
    void race_twoThreadsAddBeyondStock_cappedAtStock() throws Exception {
        long p = data.createProduct("Race", 100_000, 5);
        long start = System.nanoTime();
        for (int round = 0; round < 20; round++) {
            var u = data.createUser();
            raceAdd(u.id(), p, 4);
            assertThat(data.cartCount(u.id())).as("round %d carts", round).isEqualTo(1);
            assertThat(data.cartLines(u.id())).as("round %d lines", round).containsExactly(Map.entry(p, 5));
        }
        System.out.printf("AC-004-20 race capped at stock: 20 rounds in %d ms%n",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    /** Hai luồng gọi CartService.add cùng lúc (CountDownLatch), mọi exception đều làm test hỏng. */
    private void raceAdd(long userId, long productId, int quantity) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CartChange>> futures = List.of(
                    pool.submit(() -> addWhenReleased(ready, go, userId, productId, quantity)),
                    pool.submit(() -> addWhenReleased(ready, go, userId, productId, quantity)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<CartChange> f : futures) {
                f.get(30, TimeUnit.SECONDS);   // rethrows any exception from the worker
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private CartChange addWhenReleased(CountDownLatch ready, CountDownLatch go, long userId, long productId,
                                       int quantity) throws InterruptedException {
        ready.countDown();
        go.await();
        return cartService.add(new CartOwner.Customer(userId), productId, quantity);
    }

    // ------------------------------------------------------------------ stock

    /** AC-004-21 */
    @Test
    void cartOperations_neverChangeProductsStock() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 4);
        long b = data.createProduct("B", 100_000, 9);
        long c = data.createProduct("C", 100_000, 3);
        List<String> before = data.stockSnapshot();

        CartOwner customer = new CartOwner.Customer(u.id());
        cartService.add(customer, a, 2);
        cartService.add(customer, a, 5);             // capped at stock
        cartService.add(customer, b, 3);
        cartService.update(customer, b, 8);
        cartService.update(customer, b, 50);         // capped at stock
        cartService.remove(customer, b);

        CartOwner guest = new CartOwner.Guest(new RecordingGuestCartStore());
        cartService.add(guest, a, 1);
        cartService.add(guest, c, 2);
        cartService.update(guest, c, 3);
        cartService.remove(guest, a);

        SessionCart guestCart = new SessionCart();
        guestCart.put(a, 3);
        guestCart.put(c, 3);
        cartService.mergeGuestCart(u.id(), guestCart);

        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 4), Map.entry(c, 3));
        assertThat(data.stockSnapshot()).isEqualTo(before);
    }
}

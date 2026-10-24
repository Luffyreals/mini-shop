package com.minishop.cart;

import com.minishop.TestcontainersConfiguration;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giỏ hàng trên PostgreSQL thật (QĐ12): ghi/đọc qua MockMvc, truy vấn, khóa đồng thời và tồn kho không đổi.
 * Không @Transactional (race cần transaction thật), không @MockitoSpyBean (dùng chung context với LoginFlowTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CartIntegrationTest {

    @Autowired
    MockMvc mockMvc;

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

    private static RequestPostProcessor loggedIn(CartTestData.TestUser u) {
        return user(new CurrentUser(u.id(), u.email(), null, "Cart Tester", Role.USER));
    }

    private String productName(long id) {
        return jdbc.queryForObject("select name from products where id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ guest

    /** Spec §8: khách duyệt sản phẩm và xem giỏ trống không tạo session. */
    @Test
    void guest_browsingAndEmptyCart_createsNoSession() throws Exception {
        var products = mockMvc.perform(get("/products")).andExpect(status().isOk()).andReturn();
        var cart = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giỏ hàng của bạn đang trống.")))
                .andReturn();

        assertThat(products.getRequest().getSession(false)).isNull();
        assertThat(cart.getRequest().getSession(false)).isNull();
    }

    /** AC-004-2 */
    @Test
    void guestAdd_writesNoCartRowsAndShowsNoticeAndHeader() throws Exception {
        long a = data.createProduct("A", 100_000, 10);
        int carts = data.totalRows("carts");
        int items = data.totalRows("cart_items");
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/cart/items").with(csrf()).session(session)
                        .param("productId", String.valueOf(a)).param("quantity", "2"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/products/" + a).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã thêm vào giỏ hàng.")))
                .andExpect(content().string(containsString("Xem giỏ hàng")))
                .andExpect(content().string(containsString("Giỏ hàng (2)")));
        assertThat(data.totalRows("carts")).isEqualTo(carts);
        assertThat(data.totalRows("cart_items")).isEqualTo(items);
    }

    // --------------------------------------------------------------- customer

    /** AC-004-8 */
    @Test
    void firstAdd_forUserWithoutCart_createsExactlyOneCartAndOneItem() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        assertThat(data.cartCount(u.id())).isZero();

        mockMvc.perform(post("/cart/items").with(loggedIn(u)).with(csrf())
                        .param("productId", String.valueOf(a)).param("quantity", "3"))
                .andExpect(status().is3xxRedirection());

        assertThat(data.cartCount(u.id())).isEqualTo(1);
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 3));
    }

    /** AC-004-13 */
    @Test
    void cartPage_afterPriceChange_showsNewPrice() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 2});

        mockMvc.perform(get("/cart").with(loggedIn(u)))
                .andExpect(content().string(containsString("100.000 ₫")))
                .andExpect(content().string(containsString("200.000 ₫")));

        data.setPrice(a, 120_000);

        mockMvc.perform(get("/cart").with(loggedIn(u)))
                .andExpect(content().string(containsString("120.000 ₫")))
                .andExpect(content().string(containsString("240.000 ₫")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("100.000 ₫"))));
    }

    /** AC-004-14: nhãn từng trạng thái, tổng chỉ gồm dòng mua được, và GET không sửa giỏ. */
    @Test
    void cartPage_withHiddenOutOfStockAndLowStockLines_showsLabelsAndDoesNotChangeRows() throws Exception {
        var u = data.createUser();
        long b = data.createProduct("B hidden", 70_000, 10);
        long c = data.createProduct("C soldout", 80_000, 10);
        long d = data.createProduct("D low", 100_000, 10);
        data.createCart(u.id(), new long[]{b, 1}, new long[]{c, 1}, new long[]{d, 5});
        data.setActive(b, false);
        data.setStock(c, 0);
        data.setStock(d, 2);
        List<Map<String, Object>> before = jdbc.queryForList(
                "select i.id, i.cart_id, i.product_id, i.quantity from cart_items i join carts k on k.id = i.cart_id "
                        + "where k.user_id = ? order by i.id", u.id());
        Object updatedAtBefore = jdbc.queryForObject("select updated_at from carts where user_id = ?",
                java.sql.Timestamp.class, u.id());

        String html = mockMvc.perform(get("/cart").with(loggedIn(u)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Sản phẩm đã ngừng bán").contains("Hết hàng").contains("Chỉ còn 2 sản phẩm");
        assertThat(html).containsPattern("Tổng cộng</th>\\s*<td colspan=\"2\"><span class=\"price\">500\\.000 ₫");
        List<Map<String, Object>> after = jdbc.queryForList(
                "select i.id, i.cart_id, i.product_id, i.quantity from cart_items i join carts k on k.id = i.cart_id "
                        + "where k.user_id = ? order by i.id", u.id());
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("select updated_at from carts where user_id = ?",
                java.sql.Timestamp.class, u.id())).isEqualTo(updatedAtBefore);
    }

    /** AC-004-22 */
    @Test
    void header_forLoggedInUser_showsTotalQuantity() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        long b = data.createProduct("B", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 2}, new long[]{b, 3});

        mockMvc.perform(get("/").with(loggedIn(u)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giỏ hàng (5)")));
    }

    /** AC-004-18 */
    @Test
    void twoUsers_cartsAreIsolated() throws Exception {
        var u1 = data.createUser();
        var u2 = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        long b = data.createProduct("B", 100_000, 10);
        long q = data.createProduct("Q only for user two", 100_000, 10);
        data.createCart(u1.id(), new long[]{a, 1});
        data.createCart(u2.id(), new long[]{b, 4}, new long[]{q, 2});
        Map<Long, Integer> u2Before = data.cartLines(u2.id());

        mockMvc.perform(post("/cart/items").with(loggedIn(u1)).with(csrf())
                .param("productId", String.valueOf(b)).param("quantity", "2"));
        mockMvc.perform(post("/cart/items/" + a).with(loggedIn(u1)).with(csrf()).param("quantity", "7"));
        mockMvc.perform(post("/cart/items/" + b + "/delete").with(loggedIn(u1)).with(csrf()));
        mockMvc.perform(post("/cart/items/" + q + "/delete").with(loggedIn(u1)).with(csrf()));
        mockMvc.perform(post("/cart/items/" + q).with(loggedIn(u1)).with(csrf()).param("quantity", "9"));

        assertThat(data.cartLines(u1.id())).containsExactly(Map.entry(a, 7));
        assertThat(data.cartLines(u2.id())).isEqualTo(u2Before);
        String page = mockMvc.perform(get("/cart").with(loggedIn(u1))).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain(productName(q));
        Long u2CartId = jdbc.queryForObject("select id from carts where user_id = ?", Long.class, u2.id());
        assertThat(page).doesNotContain("cart_id").doesNotContain("cart/" + u2CartId);
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

        CartOwner guest = new CartOwner.Guest(new InMemoryGuestCartStore());
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

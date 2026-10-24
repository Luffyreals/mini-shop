package com.minishop.order;

import com.minishop.TestcontainersConfiguration;
import com.minishop.cart.CartCheckout;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Đặt hàng, xem và hủy đơn trên PostgreSQL thật (QĐ14 plan GĐ5): luồng qua MockMvc, rollback giữa chừng tất
 * định, đếm truy vấn, và các cuộc đua. Không @Transactional (race cần transaction thật), không
 * @MockitoSpyBean (dùng chung context với test tích hợp khác). Chỉ dùng dữ liệu {@link OrderTestData}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OrderIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    OrderService orderService;

    @Autowired
    CartCheckout cartCheckout;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    OrderTestData data;

    @BeforeEach
    void setUp() {
        data = new OrderTestData(jdbc, encoder);
        data.cleanUp();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // ---------------------------------------------------------------- helpers

    private static RequestPostProcessor loggedIn(OrderTestData.TestUser u) {
        return user(new CurrentUser(u.id(), u.email(), null, "Order Tester", Role.USER));
    }

    private static RequestPostProcessor loggedInAdmin(OrderTestData.TestUser u) {
        return user(new CurrentUser(u.id(), u.email(), null, "Order Admin", Role.ADMIN));
    }

    private static CheckoutForm form() {
        return new CheckoutForm("Người nhận", "0912345678", "12 Lê Lợi", null);
    }

    private static long idOf(String redirectUrl) {
        return Long.parseLong(redirectUrl.substring(redirectUrl.lastIndexOf('/') + 1));
    }

    private String detailHtml(OrderTestData.TestUser u, long orderId) throws Exception {
        return mockMvc.perform(get("/orders/" + orderId).with(loggedIn(u))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private record Outcome<T>(T value, Throwable error) {
        boolean failed() {
            return error != null;
        }
    }

    /** Chạy hai tác vụ cùng lúc (CountDownLatch); exception được thu lại thay vì ném, để test tự kiểm. */
    private <T> List<Outcome<T>> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<>(null, t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void logTime(String what, long startNanos) {
        System.out.printf("%s: %d ms%n", what, (System.nanoTime() - startNanos) / 1_000_000);
    }

    // ------------------------------------------------------- AC-005-7, 10

    @Test
    void postCheckout_withValidCart_createsOneOrderDecrementsStockAndClearsCart() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 5);
        long b = data.createProduct("B", 250_000, 1);
        data.createCart(u.id(), new long[]{a, 2}, new long[]{b, 1});
        MockHttpSession session = new MockHttpSession();

        String location = mockMvc.perform(post("/checkout").with(loggedIn(u)).with(csrf()).session(session)
                        .param("receiverName", "Nguyễn Văn A").param("phone", "0912 345 678")
                        .param("shippingAddress", "12 Lê Lợi\nQuận 1").param("note", "Giao giờ hành chính")
                        .param("expectedTotal", "450000"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("/orders/");
        long orderId = idOf(location);
        assertThat(data.orderIds(u.id())).containsExactly(orderId);
        Map<String, Object> row = data.orderRow(orderId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("payment_method")).isEqualTo("COD");
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("450000");
        assertThat(row.get("phone")).isEqualTo("0912345678");
        assertThat(row.get("receiver_name")).isEqualTo("Nguyễn Văn A");
        assertThat(row.get("shipping_address")).isEqualTo("12 Lê Lợi\nQuận 1");
        assertThat(row.get("note")).isEqualTo("Giao giờ hành chính");
        assertThat(row.get("user_id")).isEqualTo(u.id());

        List<Map<String, Object>> items = data.orderItems(orderId);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("product_id")).isEqualTo(a);
        assertThat(items.get(0).get("product_name")).isEqualTo(data.productName(a));
        assertThat((BigDecimal) items.get(0).get("unit_price")).isEqualByComparingTo("100000");
        assertThat(items.get(0).get("quantity")).isEqualTo(2);
        assertThat(items.get(1).get("product_id")).isEqualTo(b);
        assertThat((BigDecimal) items.get(1).get("unit_price")).isEqualByComparingTo("250000");
        assertThat(items.get(1).get("quantity")).isEqualTo(1);

        assertThat(data.stock(a)).isEqualTo(3);
        assertThat(data.stock(b)).isZero();
        assertThat(data.cartLines(u.id())).isEmpty();

        // Trang chi tiết: flash thành công, và header đã về "Giỏ hàng" (không số).
        String html = mockMvc.perform(get(location).with(loggedIn(u)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đặt hàng thành công. Mã đơn của bạn là #" + orderId + ".")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(">Giỏ hàng</a>").doesNotContain("Giỏ hàng (");
    }

    // ------------------------------------------------------------ AC-005-8

    @Test
    void orderDetail_afterProductPriceAndNameChange_stillShowsSnapshot() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("Snapshot", 100_000, 5);
        String oldName = data.productName(a);
        data.createCart(u.id(), new long[]{a, 2});
        long orderId = orderService.placeOrder(u.id(), form(), new BigDecimal("200000"));

        data.setPrice(a, 999_000);
        data.setName(a, "Order test renamed after purchase");

        String html = detailHtml(u, orderId);
        assertThat(html).contains(oldName).contains("100.000 ₫").contains("200.000 ₫")
                .doesNotContain("renamed after purchase").doesNotContain("999.000");
    }

    // ----------------------------------------------- AC-005-11: rollback

    /**
     * Một connection khác giữ khóa dòng B (chưa commit), nên luồng đặt hàng trừ A xong rồi đứng chờ ở B. Khi
     * connection kia commit stock = 0, câu trừ B trả 0 dòng và cả đơn (kể cả lần trừ A) phải rollback.
     */
    @Test
    void placeOrder_whenSecondProductSoldOutMidOrder_throwsAndRollsBackWholeOrder() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 5);
        long b = data.createProduct("B", 250_000, 1);
        data.createCart(u.id(), new long[]{a, 1}, new long[]{b, 1});
        long start = System.nanoTime();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection blocker = dataSource.getConnection();
        try {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.executeUpdate("update products set stock = 0 where id = " + b);   // giữ khóa dòng B, chưa commit
            }
            Future<Long> placing = pool.submit(() -> orderService.placeOrder(u.id(), form(), new BigDecimal("350000")));

            awaitBackendWaitingOnLock();
            assertThat(placing.isDone()).as("order thread must be blocked at product B").isFalse();
            blocker.commit();

            assertThatThrownBy(() -> placing.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(PlaceOrderException.InsufficientStock.class)
                    .cause().satisfies(e -> assertThat(((PlaceOrderException.InsufficientStock) e).productName())
                            .isEqualTo(data.productName(b)));
        } finally {
            blocker.rollback();   // no-op after commit; releases the lock if the test failed early
            blocker.close();
            pool.shutdownNow();
        }

        assertThat(data.stock(a)).as("the decrement of A must be rolled back").isEqualTo(5);
        assertThat(data.stock(b)).isZero();
        assertThat(data.orderCount(u.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_items where product_id in (?, ?)", Integer.class, a, b))
                .isZero();
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 1), Map.entry(b, 1));
        logTime("AC-005-11 mid-order rollback", start);
    }

    private void awaitBackendWaitingOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no backend waiting on a lock within 10 s");
    }

    // ------------------------------------------------------- AC-005-15

    @Test
    void race_twoPlaceOrdersFromSameCart_exactlyOneOrderAndOneEmptyCart() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var u = data.createUser();
            long a = data.createProduct("Race", 100_000, 50);
            data.createCart(u.id(), new long[]{a, 1});

            List<Outcome<Long>> results = race(
                    () -> orderService.placeOrder(u.id(), form(), new BigDecimal("100000")),
                    () -> orderService.placeOrder(u.id(), form(), new BigDecimal("100000")));

            assertThat(results).as("round %d", round).filteredOn(r -> !r.failed()).hasSize(1);
            assertThat(results).filteredOn(Outcome::failed).singleElement()
                    .satisfies(r -> assertThat(r.error()).isInstanceOf(PlaceOrderException.EmptyCart.class));
            assertThat(data.orderCount(u.id())).isEqualTo(1);
            assertThat(data.stock(a)).isEqualTo(49);
            assertThat(data.cartLines(u.id())).isEmpty();
        }
        logTime("AC-005-15 same-cart race, " + ROUNDS + " rounds", start);
    }

    // ------------------------------------------------------- AC-005-16

    @Test
    void race_twoUsersOrderLastItem_oneWinsOtherGetsNamedInsufficientStock() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var u1 = data.createUser();
            var u2 = data.createUser();
            long a = data.createProduct("Last", 100_000, 1);
            data.createCart(u1.id(), new long[]{a, 1});
            data.createCart(u2.id(), new long[]{a, 1});

            List<Outcome<Long>> results = race(
                    () -> orderService.placeOrder(u1.id(), form(), new BigDecimal("100000")),
                    () -> orderService.placeOrder(u2.id(), form(), new BigDecimal("100000")));

            assertThat(results).as("round %d", round).filteredOn(r -> !r.failed()).hasSize(1);
            assertThat(results).filteredOn(Outcome::failed).singleElement().satisfies(r -> {
                assertThat(r.error()).isInstanceOf(PlaceOrderException.InsufficientStock.class);
                assertThat(((PlaceOrderException.InsufficientStock) r.error()).productName())
                        .isEqualTo(data.productName(a));
            });
            assertThat(data.orderCount(u1.id()) + data.orderCount(u2.id())).isEqualTo(1);
            assertThat(data.stock(a)).isZero();
            // The loser keeps the cart, the winner's is empty.
            assertThat(data.cartLines(u1.id()).size() + data.cartLines(u2.id()).size()).isEqualTo(1);
        }
        logTime("AC-005-16 last-item race, " + ROUNDS + " rounds", start);
    }

    // ------------------------------------------------------- AC-005-17

    @Test
    void race_twoUsersOrderSameProductsInOppositeCartOrder_bothSucceedWithoutDeadlock() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var u1 = data.createUser();
            var u2 = data.createUser();
            long a = data.createProduct("Dead A", 100_000, 50);
            long b = data.createProduct("Dead B", 100_000, 50);
            data.createCart(u1.id(), new long[]{a, 1}, new long[]{b, 1});
            data.createCart(u2.id(), new long[]{b, 1}, new long[]{a, 1});

            List<Outcome<Long>> results = race(
                    () -> orderService.placeOrder(u1.id(), form(), new BigDecimal("200000")),
                    () -> orderService.placeOrder(u2.id(), form(), new BigDecimal("200000")));

            assertThat(results).as("round %d", round).noneMatch(Outcome::failed);
            assertThat(data.stock(a)).isEqualTo(48);
            assertThat(data.stock(b)).isEqualTo(48);
            assertThat(data.orderCount(u1.id())).isEqualTo(1);
            assertThat(data.orderCount(u2.id())).isEqualTo(1);
        }
        logTime("AC-005-17 opposite-order race, " + ROUNDS + " rounds", start);
    }

    // ------------------------------------------------------- AC-005-18

    @Test
    void postCheckout_withExtraParameters_ignoresThemAndUsesLoggedInUserAndDbPrices() throws Exception {
        var u = data.createUser();
        var other = data.createUser();
        long a = data.createProduct("A", 100_000, 5);
        data.createCart(u.id(), new long[]{a, 2});

        String location = mockMvc.perform(post("/checkout").with(loggedIn(u)).with(csrf())
                        .param("receiverName", "A").param("phone", "0912345678").param("shippingAddress", "B")
                        .param("expectedTotal", "200000")
                        .param("userId", String.valueOf(other.id())).param("status", "DELIVERED")
                        .param("totalAmount", "1").param("unitPrice", "1").param("paymentMethod", "X")
                        .param("id", "5"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        long orderId = idOf(location);
        assertThat(data.orderCount(other.id())).isZero();
        Map<String, Object> row = data.orderRow(orderId);
        assertThat(row.get("user_id")).isEqualTo(u.id());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("payment_method")).isEqualTo("COD");
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("200000");
        assertThat((BigDecimal) data.orderItems(orderId).get(0).get("unit_price")).isEqualByComparingTo("100000");
    }

    // ------------------------------------------------------- AC-005-13

    @Test
    void postCheckout_whenPriceChangedAfterPageWasShown_rendersAgainAndPlacesNothing() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 5);
        data.createCart(u.id(), new long[]{a, 2});
        data.setPrice(a, 110_000);

        mockMvc.perform(post("/checkout").with(loggedIn(u)).with(csrf())
                        .param("receiverName", "A").param("phone", "0912345678").param("shippingAddress", "B")
                        .param("expectedTotal", "200000"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giá sản phẩm vừa thay đổi.")))
                .andExpect(content().string(containsString("220.000 ₫")))
                .andExpect(content().string(containsString("value=\"220000\"")));

        assertThat(data.orderCount(u.id())).isZero();
        assertThat(data.stock(a)).isEqualTo(5);
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 2));
    }

    // ------------------------------------------------------- AC-005-20

    @Test
    void orderList_showsOnlyOwnOrdersNewestFirst() throws Exception {
        var u1 = data.createUser();
        var u2 = data.createUser();
        long a = data.createProduct("A", 100_000, 50);
        long first = data.insertOrder(u1.id(), "PENDING", new long[]{a, 1});
        long second = data.insertOrder(u1.id(), "CANCELLED", new long[]{a, 1});
        long third = data.insertOrder(u1.id(), "DELIVERED", new long[]{a, 2});
        long foreign = data.insertOrder(u2.id(), "PENDING", new long[]{a, 1});

        String html = mockMvc.perform(get("/orders").with(loggedIn(u1))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher m = Pattern.compile("href=\"/orders/(\\d+)\"").matcher(html);
        List<Long> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        assertThat(ids).containsExactly(third, second, first).doesNotContain(foreign);
        assertThat(html).contains("Chờ xử lý").contains("Đã hủy").contains("Đã giao");
    }

    @Test
    void orderList_pagination_tenPerPageAndBadPagesFallBackToFirst() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 50);
        for (int i = 0; i < 11; i++) {
            data.insertOrder(u.id(), "PENDING", new long[]{a, 1});
        }
        List<Long> all = data.orderIds(u.id());

        assertThat(listedIds(u, "/orders")).containsExactlyElementsOf(all.subList(0, 10));
        assertThat(listedIds(u, "/orders?page=2")).containsExactly(all.get(10));
        assertThat(listedIds(u, "/orders?page=abc")).containsExactlyElementsOf(all.subList(0, 10));
        assertThat(listedIds(u, "/orders?page=999")).containsExactlyElementsOf(all.subList(0, 10));
    }

    private List<Long> listedIds(OrderTestData.TestUser u, String url) throws Exception {
        String html = mockMvc.perform(get(url).with(loggedIn(u))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("href=\"/orders/(\\d+)\"").matcher(html);
        List<Long> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    // ------------------------------------------------------- AC-005-24

    @Test
    void otherUsersAndAdmins_cannotViewOrCancelSomeoneElsesOrder() throws Exception {
        var owner = data.createUser();
        var intruder = data.createUser();
        var admin = data.createUser();
        long a = data.createProduct("A", 100_000, 3);
        long orderId = data.insertOrder(owner.id(), "PENDING", new long[]{a, 2});

        for (RequestPostProcessor who : List.of(loggedIn(intruder), loggedInAdmin(admin))) {
            mockMvc.perform(get("/orders/" + orderId).with(who)).andExpect(status().isNotFound())
                    .andExpect(content().string(containsString("Không tìm thấy đơn hàng")));
            mockMvc.perform(post("/orders/" + orderId + "/cancel").with(who).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/orders/999999999").with(loggedIn(owner))).andExpect(status().isNotFound());

        assertThat(data.orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(data.stock(a)).isEqualTo(3);
        // The owner still sees it.
        assertThat(detailHtml(owner, orderId)).contains("Đơn hàng #" + orderId);
    }

    // ------------------------------------------------------- AC-005-26

    @Test
    void listOrders_withThreeOrdersOfTwoLines_issuesNoPerOrderQueries() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 50);
        long b = data.createProduct("B", 100_000, 50);
        for (int i = 0; i < 3; i++) {
            data.insertOrder(u.id(), "PENDING", new long[]{a, 1}, new long[]{b, 1});
        }

        // A short first page needs no COUNT: one SELECT, and no query for items or user.
        assertThat(statementsOf(() -> orderService.listOrders(u.id(), null))).isEqualTo(1);
    }

    @Test
    void listOrders_withFullPage_issuesExactlySelectAndCount() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 50);
        long b = data.createProduct("B", 100_000, 50);
        for (int i = 0; i < 11; i++) {
            data.insertOrder(u.id(), "PENDING", new long[]{a, 1}, new long[]{b, 1});
        }

        assertThat(statementsOf(() -> orderService.listOrders(u.id(), null))).isEqualTo(2);
    }

    @Test
    void getOrder_loadsOrderAndLinesInOneStatement() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 50);
        long b = data.createProduct("B", 100_000, 50);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{a, 1}, new long[]{b, 1});

        long statements = statementsOf(() -> assertThat(orderService.getOrder(u.id(), orderId).lines()).hasSize(2));

        assertThat(statements).isLessThanOrEqualTo(2).isEqualTo(1);
    }

    private long statementsOf(Runnable action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            action.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    // ------------------------------------------------------- AC-005-27

    @Test
    void postCancel_onPendingOrder_cancelsRestoresStockAndRemovesButton() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 5);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{a, 2});
        data.setStock(a, 3);   // the order already took 2 of 5
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/orders/" + orderId + "/cancel").with(loggedIn(u)).with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/" + orderId));

        assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(data.stock(a)).isEqualTo(5);
        String html = mockMvc.perform(get("/orders/" + orderId).with(loggedIn(u)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã hủy đơn hàng #" + orderId + ".")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("Hủy đơn hàng").doesNotContain("/cancel");
    }

    @Test
    void postCancel_onNonPendingOrder_isRefusedAndStockUnchanged() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 3);
        long orderId = data.insertOrder(u.id(), "CONFIRMED", new long[]{a, 2});
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/orders/" + orderId + "/cancel").with(loggedIn(u)).with(csrf()).session(session))
                .andExpect(redirectedUrl("/orders/" + orderId));

        assertThat(data.orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(data.stock(a)).isEqualTo(3);
        mockMvc.perform(get("/orders/" + orderId).with(loggedIn(u)).session(session))
                .andExpect(content().string(containsString("Đơn hàng này không thể hủy nữa.")));
    }

    // ------------------------------------------------------- AC-005-29

    @Test
    void cancel_whenProductWasHiddenAfterOrdering_stillRestoresStock() {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 5);
        data.createCart(u.id(), new long[]{a, 2});
        long orderId = orderService.placeOrder(u.id(), form(), new BigDecimal("200000"));
        assertThat(data.stock(a)).isEqualTo(3);
        data.setActive(a, false);

        CancelResult result = orderService.cancelByCustomer(u.id(), orderId);

        assertThat(result).isEqualTo(CancelResult.CANCELLED);
        assertThat(data.stock(a)).isEqualTo(5);
        assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------- AC-005-30

    @Test
    void race_twoCancelsOfSameOrder_oneCancelsOtherRefusedStockRestoredOnce() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var u = data.createUser();
            long a = data.createProduct("Cancel A", 100_000, 10);
            long b = data.createProduct("Cancel B", 100_000, 10);
            data.createCart(u.id(), new long[]{a, 2}, new long[]{b, 3});
            long orderId = orderService.placeOrder(u.id(), form(), new BigDecimal("500000"));
            assertThat(data.stock(a)).isEqualTo(8);
            assertThat(data.stock(b)).isEqualTo(7);

            List<Outcome<CancelResult>> results = race(
                    () -> orderService.cancelByCustomer(u.id(), orderId),
                    () -> orderService.cancelByCustomer(u.id(), orderId));

            assertThat(results).as("round %d", round).noneMatch(Outcome::failed);
            assertThat(results).extracting(Outcome::value)
                    .containsExactlyInAnyOrder(CancelResult.CANCELLED, CancelResult.NOT_CANCELLABLE);
            assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
            assertThat(data.stock(a)).isEqualTo(10);
            assertThat(data.stock(b)).isEqualTo(10);
        }
        logTime("AC-005-30 cancel race, " + ROUNDS + " rounds", start);
    }

    // ------------------------------------------------------------ QĐ1

    @Test
    void lockAndRead_outsideTransaction_isRejected() {
        var u = data.createUser();

        assertThatThrownBy(() -> cartCheckout.lockAndRead(u.id()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> cartCheckout.clear(u.id()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}

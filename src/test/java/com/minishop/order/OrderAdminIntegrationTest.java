package com.minishop.order;

import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.support.AdminTestData;
import com.minishop.support.AdminTestData.Outcome;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin order handling on a real PostgreSQL (AC-006-35, 36, 38, 42, 44, 46..48, 51): list and detail
 * (statement counts, order), cancel with stock restore, the customer's view after a confirm, and the five races
 * of QĐ14 (20 rounds each, fresh data per round). Also runs the new order queries ({@code countByStatus},
 * {@code sumTotalByStatus}, {@code findTop5…}) on the real database, as schema-guardian asked.
 * No @Transactional: races need real transactions. Only {@link AdminTestData} rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderAdminIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderAdminService orderAdminService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    ProductAdminService productAdminService;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

    AdminTestData data;
    AdminTestData.TestUser admin;
    long category;

    @BeforeEach
    void setUp() {
        data = new AdminTestData(jdbc, encoder, Path.of(uploadDirSetting).toAbsolutePath().normalize());
        data.cleanUp();
        admin = data.createUser(Role.ADMIN);
        category = data.createCategory("Order admin");
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // ---------------------------------------------------------------- helpers

    private RequestPostProcessor loggedInAdmin() {
        return user(new CurrentUser(admin.id(), admin.email(), null, admin.fullName(), Role.ADMIN));
    }

    private static RequestPostProcessor loggedInCustomer(AdminTestData.TestUser u) {
        return user(new CurrentUser(u.id(), u.email(), null, u.fullName(), Role.USER));
    }

    private static CheckoutForm form() {
        return new CheckoutForm("Người nhận", "0912345678", "12 Lê Lợi", null);
    }

    private AdminTestData.TestUser customer() {
        return data.createUser(Role.USER);
    }

    private static void logTime(String what, long startNanos) {
        System.out.printf("%s: %d ms%n", what, (System.nanoTime() - startNanos) / 1_000_000);
    }

    // -------------------------------------------------- AC-006-38 (list)

    @Test
    void listOrders_withShortPage_issuesOneStatementWithoutPerOrderQueries() {
        for (int i = 0; i < 3; i++) {
            var u = customer();
            long a = data.createProduct(category, "List " + i, 100_000, 50, true);
            data.insertOrder(u.id(), "PENDING", new long[]{a, 1});
        }

        // A first page shorter than 20 needs no COUNT: one SELECT with the user fetched by join, none per order.
        long statements = AdminTestData.statementsOf(entityManagerFactory,
                () -> assertThat(orderAdminService.listOrders(null, null).orders()).hasSize(3));

        assertThat(statements).isEqualTo(1);
    }

    @Test
    void listOrders_withFullPage_issuesExactlySelectAndCount() {
        for (int i = 0; i < 21; i++) {
            data.insertOrderWithTotal(customer().id(), "PENDING", new BigDecimal("100000"));
        }

        long statements = AdminTestData.statementsOf(entityManagerFactory,
                () -> assertThat(orderAdminService.listOrders(null, null).orders()).hasSize(20));

        assertThat(statements).isEqualTo(2);
    }

    @Test
    void listOrders_with25Orders_pagesTwentyNewestFirstWithIdAsTieBreaker() {
        var u = customer();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(data.insertOrderWithTotal(u.id(), i % 5 == 0 ? "CANCELLED" : "PENDING", new BigDecimal("100000")));
        }
        // Same created_at everywhere (far in the future so mine are first): only the id decides the order.
        data.setOrderCreatedAt(u.id(), Instant.now().plus(2, ChronoUnit.DAYS));
        List<Long> newestFirst = new ArrayList<>(ids);
        java.util.Collections.reverse(newestFirst);

        assertThat(orderAdminService.listOrders(null, null).orders()).extracting(AdminOrderRow::id)
                .containsExactlyElementsOf(newestFirst.subList(0, 20));
        assertThat(orderAdminService.listOrders(null, "2").orders()).extracting(AdminOrderRow::id)
                .startsWith(newestFirst.subList(20, 25).toArray(Long[]::new));
        assertThat(orderAdminService.listOrders(null, "abc").orders()).extracting(AdminOrderRow::id)
                .containsExactlyElementsOf(newestFirst.subList(0, 20));

        AdminOrderPage cancelled = orderAdminService.listOrders("CANCELLED", null);
        assertThat(cancelled.orders()).extracting(AdminOrderRow::id)
                .containsExactly(newestFirst.stream().filter(id -> ids.indexOf(id) % 5 == 0).toArray(Long[]::new));
        assertThat(cancelled.orders()).allMatch(o -> o.statusLabel().equals("Đã hủy"));
    }

    @Test
    void getOrder_loadsOrderLinesAndAccountInOneStatement() {
        var u = customer();
        long a = data.createProduct(category, "Detail A", 100_000, 50, true);
        long b = data.createProduct(category, "Detail B", 100_000, 50, true);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{a, 1}, new long[]{b, 2});

        long statements = AdminTestData.statementsOf(entityManagerFactory, () -> {
            AdminOrderDetail detail = orderAdminService.getOrder(orderId);
            assertThat(detail.lines()).hasSize(2);
            assertThat(detail.customerEmail()).isEqualTo(u.email());
        });

        assertThat(statements).isEqualTo(1);
    }

    // ---------------------------------------------------------- AC-006-44

    @Test
    void postStatusCancelled_onConfirmedOrder_cancelsRestoresStockAndFlashes() throws Exception {
        var u = customer();
        long a = data.createProduct(category, "Cancel A", 100_000, 3, true);   // 5 before the order took 2
        long orderId = data.insertOrder(u.id(), "CONFIRMED", new long[]{a, 2});

        mockMvc.perform(post("/admin/orders/" + orderId + "/status").with(loggedInAdmin()).with(csrf())
                        .param("status", "CANCELLED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders/" + orderId))
                .andExpect(flash().attribute("notice", "Đã hủy đơn hàng #" + orderId + " và hoàn lại tồn kho."))
                .andExpect(flash().attribute("noticeKind", "success"));

        assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(data.stock(a)).isEqualTo(5);
    }

    @Test
    void cancel_ofOrderWhoseProductWasHidden_stillRestoresStock() {
        var u = customer();
        long a = data.createProduct(category, "Hidden cancel", 100_000, 3, true);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{a, 2});
        data.setActive(a, false);

        StatusChangeResult result = orderAdminService.changeStatus(admin.id(), orderId, "CANCELLED");

        assertThat(result).isInstanceOf(StatusChangeResult.Cancelled.class);
        assertThat(data.stock(a)).isEqualTo(5);
        assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void cancel_ofOrderWithTwoProducts_restoresBothStocks() {
        var u = customer();
        long a = data.createProduct(category, "Two A", 100_000, 1, true);
        long b = data.createProduct(category, "Two B", 100_000, 0, true);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{b, 4}, new long[]{a, 2});

        assertThat(orderAdminService.changeStatus(admin.id(), orderId, "CANCELLED"))
                .isInstanceOf(StatusChangeResult.Cancelled.class);

        assertThat(data.stock(a)).isEqualTo(3);
        assertThat(data.stock(b)).isEqualTo(4);
    }

    // ---------------------------------------------------------- AC-006-42

    @Test
    void postStatusConfirmed_onPendingOrder_customerSeesNewStatusAndNoCancelButton() throws Exception {
        var u = customer();
        long a = data.createProduct(category, "Confirm", 100_000, 5, true);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{a, 1});
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/orders/" + orderId).with(loggedInCustomer(u)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hủy đơn hàng")));

        mockMvc.perform(post("/admin/orders/" + orderId + "/status").with(loggedInAdmin()).with(csrf())
                        .param("status", "CONFIRMED"))
                .andExpect(flash().attribute("notice",
                        "Đã chuyển đơn hàng #" + orderId + " sang trạng thái Đã xác nhận."));

        String html = mockMvc.perform(get("/orders/" + orderId).with(loggedInCustomer(u)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Đã xác nhận").doesNotContain("Hủy đơn hàng").doesNotContain("/cancel");
        assertThat(data.stock(a)).isEqualTo(5);
    }

    // ---------------------------------------------------------- AC-006-48

    @Test
    void adminOpeningAnotherUsersOrderOnTheCustomerPath_is404ButAdminPathWorks() throws Exception {
        var owner = customer();
        long a = data.createProduct(category, "Foreign", 100_000, 5, true);
        long orderId = data.insertOrder(owner.id(), "PENDING", new long[]{a, 1});

        mockMvc.perform(get("/orders/" + orderId).with(loggedInAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(get("/orders").with(loggedInAdmin())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/orders/" + orderId))));
        mockMvc.perform(get("/admin/orders/" + orderId).with(loggedInAdmin())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đơn hàng #" + orderId)));
    }

    private static void pause(long nanos) {
        java.util.concurrent.locks.LockSupport.parkNanos(nanos);
    }

    // ------------------ the two orders of events behind the races, fixed (no timing)

    @Test
    void lastUnits_orderPlacedFirst_thenAdminDecrease_adminGetsNotEnoughAndStockIsZero() {
        var u = customer();
        long a = data.createProduct(category, "Seq order first", 100_000, 2, true);
        data.createCart(u.id(), new long[]{a, 2});
        String name = data.productName(a);

        orderService.placeOrder(u.id(), form(), new BigDecimal("200000"));

        assertThat(productAdminService.adjustStock(admin.id(), a, -2))
                .isEqualTo(new StockAdjustResult.NotEnough(name, 2, 0));
        assertThat(data.stock(a)).isZero();
    }

    @Test
    void lastUnits_adminDecreaseFirst_thenOrder_customerGetsInsufficientStockAndKeepsCart() {
        var u = customer();
        long a = data.createProduct(category, "Seq admin first", 100_000, 2, true);
        data.createCart(u.id(), new long[]{a, 2});
        String name = data.productName(a);

        assertThat(productAdminService.adjustStock(admin.id(), a, -2))
                .isEqualTo(new StockAdjustResult.Adjusted(name, 0));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orderService.placeOrder(u.id(), form(), new BigDecimal("200000")))
                .isInstanceOfSatisfying(PlaceOrderException.InsufficientStock.class,
                        e -> assertThat(e.productName()).isEqualTo(name));
        assertThat(data.stock(a)).isZero();
        assertThat(data.orderCount(u.id())).isZero();
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 2));
    }

    @Test
    void pendingOrder_customerCancelsFirst_thenAdminConfirm_adminGetsNotAllowedAndStockRestoredOnce() {
        var u = customer();
        long a = data.createProduct(category, "Seq cancel first", 100_000, 10, true);
        data.createCart(u.id(), new long[]{a, 2});
        long orderId = orderService.placeOrder(u.id(), form(), new BigDecimal("200000"));

        assertThat(orderService.cancelByCustomer(u.id(), orderId)).isEqualTo(CancelResult.CANCELLED);

        assertThat(orderAdminService.changeStatus(admin.id(), orderId, "CONFIRMED"))
                .isEqualTo(new StatusChangeResult.NotAllowed("Đã hủy", "Đã xác nhận"));
        assertThat(data.stock(a)).isEqualTo(10);
    }

    @Test
    void pendingOrder_adminConfirmsFirst_thenCustomerCancel_customerIsRefusedAndStockUnchanged() {
        var u = customer();
        long a = data.createProduct(category, "Seq confirm first", 100_000, 10, true);
        data.createCart(u.id(), new long[]{a, 2});
        long orderId = orderService.placeOrder(u.id(), form(), new BigDecimal("200000"));

        assertThat(orderAdminService.changeStatus(admin.id(), orderId, "CONFIRMED"))
                .isEqualTo(new StatusChangeResult.Changed("Đã xác nhận"));

        assertThat(orderService.cancelByCustomer(u.id(), orderId)).isEqualTo(CancelResult.NOT_CANCELLABLE);
        assertThat(data.stock(a)).isEqualTo(8);
    }

    // ----------------------------------------------- the races (QĐ14)

    /** AC-006-35: +10 and two customers ordering 2 each, started together: nothing is lost, final stock is 11. */
    @Test
    void race_adminAddsStockWhileTwoCustomersOrder_finalStockIsEleven() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var u1 = customer();
            var u2 = customer();
            long a = data.createProduct(category, "Plus race", 100_000, 5, true);
            data.createCart(u1.id(), new long[]{a, 2});
            data.createCart(u2.id(), new long[]{a, 2});

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> productAdminService.adjustStock(admin.id(), a, 10),
                    () -> orderService.placeOrder(u1.id(), form(), new BigDecimal("200000")),
                    () -> orderService.placeOrder(u2.id(), form(), new BigDecimal("200000")));

            assertThat(results).as("round %d: %s", round, results).noneMatch(Outcome::failed);
            assertThat(results.get(0).value()).isInstanceOf(StockAdjustResult.Adjusted.class);
            assertThat(data.stock(a)).as("round %d", round).isEqualTo(11);
            assertThat(data.orderCount(u1.id())).isEqualTo(1);
            assertThat(data.orderCount(u2.id())).isEqualTo(1);
        }
        logTime("AC-006-35 admin +10 vs two orders, " + ROUNDS + " rounds", start);
    }

    /** AC-006-36: stock 2, admin -2 against an order of 2: exactly one wins, stock ends at 0, never negative. */
    @Test
    void race_adminDecreasesStockWhileCustomerOrdersAll_exactlyOneWinsAndStockIsZero() throws Exception {
        long start = System.nanoTime();
        int adminWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            var u = customer();
            long jitter = round * 3_000_000L;   // spreads the start so both sides get to be first over the rounds
            long a = data.createProduct(category, "Minus race", 100_000, 2, true);
            data.createCart(u.id(), new long[]{a, 2});
            String name = data.productName(a);

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> {
                        pause(jitter);
                        return productAdminService.adjustStock(admin.id(), a, -2);
                    },
                    () -> orderService.placeOrder(u.id(), form(), new BigDecimal("200000")));

            Outcome<Object> adjust = results.get(0);
            Outcome<Object> order = results.get(1);
            assertThat(adjust.failed()).as("round %d: adjust must not throw: %s", round, adjust.error()).isFalse();
            if (adjust.value() instanceof StockAdjustResult.Adjusted) {
                adminWon++;
                assertThat(order.error()).as("round %d", round).isInstanceOf(PlaceOrderException.InsufficientStock.class);
                assertThat(((PlaceOrderException.InsufficientStock) order.error()).productName()).isEqualTo(name);
                assertThat(data.orderCount(u.id())).isZero();
            } else {
                assertThat(adjust.value()).as("round %d", round).isEqualTo(new StockAdjustResult.NotEnough(name, 2, 0));
                assertThat(order.failed()).as("round %d: %s", round, order.error()).isFalse();
                assertThat(data.orderCount(u.id())).isEqualTo(1);
            }
            assertThat(data.stock(a)).as("round %d", round).isZero();
        }
        logTime("AC-006-36 admin -2 vs order of 2, " + ROUNDS + " rounds (admin won " + adminWon + ")", start);
    }

    /** AC-006-46: confirm against the customer's cancel of the same PENDING order: one wins, no 500. */
    @Test
    void race_adminConfirmsWhileCustomerCancels_oneWinsAndStockIsRestoredOnlyIfCancelled() throws Exception {
        long start = System.nanoTime();
        int customerWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            var u = customer();
            long jitter = round * 300_000L;
            long a = data.createProduct(category, "Confirm race", 100_000, 10, true);
            data.createCart(u.id(), new long[]{a, 2});
            long orderId = orderService.placeOrder(u.id(), form(), new BigDecimal("200000"));
            assertThat(data.stock(a)).isEqualTo(8);

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> {
                        pause(jitter);
                        return orderAdminService.changeStatus(admin.id(), orderId, "CONFIRMED");
                    },
                    () -> orderService.cancelByCustomer(u.id(), orderId));

            assertThat(results).as("round %d: %s", round, results).noneMatch(Outcome::failed);
            Object adminResult = results.get(0).value();
            Object customerResult = results.get(1).value();
            if (customerResult == CancelResult.CANCELLED) {
                customerWon++;
                assertThat(adminResult).isEqualTo(new StatusChangeResult.NotAllowed("Đã hủy", "Đã xác nhận"));
                assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
                assertThat(data.stock(a)).as("round %d", round).isEqualTo(10);
            } else {
                assertThat(customerResult).isEqualTo(CancelResult.NOT_CANCELLABLE);
                assertThat(adminResult).isEqualTo(new StatusChangeResult.Changed("Đã xác nhận"));
                assertThat(data.orderStatus(orderId)).isEqualTo("CONFIRMED");
                assertThat(data.stock(a)).as("round %d", round).isEqualTo(8);
            }
        }
        logTime("AC-006-46 admin confirm vs customer cancel, " + ROUNDS + " rounds (customer won " + customerWon + ")",
                start);
    }

    /** AC-006-47: two tabs cancel the same CONFIRMED order: stock comes back exactly once. */
    @Test
    void race_twoAdminCancelsOfSameOrder_oneCancelsOtherRefusedStockRestoredOnce() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var u = customer();
            long a = data.createProduct(category, "Cancel A", 100_000, 8, true);
            long b = data.createProduct(category, "Cancel B", 100_000, 7, true);
            long orderId = data.insertOrder(u.id(), "CONFIRMED", new long[]{a, 2}, new long[]{b, 3});

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> orderAdminService.changeStatus(admin.id(), orderId, "CANCELLED"),
                    () -> orderAdminService.changeStatus(admin.id(), orderId, "CANCELLED"));

            assertThat(results).as("round %d: %s", round, results).noneMatch(Outcome::failed);
            assertThat(results).extracting(Outcome::value).containsExactlyInAnyOrder(
                    new StatusChangeResult.Cancelled(), new StatusChangeResult.NotAllowed("Đã hủy", "Đã hủy"));
            assertThat(data.orderStatus(orderId)).isEqualTo("CANCELLED");
            assertThat(data.stock(a)).as("round %d", round).isEqualTo(10);
            assertThat(data.stock(b)).as("round %d", round).isEqualTo(10);
        }
        logTime("AC-006-47 two admin cancels, " + ROUNDS + " rounds", start);
    }

    /** Admin cancel against a customer placing the next order of the same product: ADR-0009 lock order, no deadlock. */
    @Test
    void race_adminCancelAgainstAnotherCustomersOrderOfSameProducts_bothSucceedWithoutDeadlock() throws Exception {
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            var owner = customer();
            var buyer = customer();
            long a = data.createProduct(category, "Dead A", 100_000, 10, true);
            long b = data.createProduct(category, "Dead B", 100_000, 10, true);
            long orderId = data.insertOrder(owner.id(), "PENDING", new long[]{b, 1}, new long[]{a, 1});
            data.createCart(buyer.id(), new long[]{b, 1}, new long[]{a, 1});

            List<Outcome<Object>> results = AdminTestData.race(
                    () -> orderAdminService.changeStatus(admin.id(), orderId, "CANCELLED"),
                    () -> orderService.placeOrder(buyer.id(), form(), new BigDecimal("200000")));

            assertThat(results).as("round %d: %s", round, results).noneMatch(Outcome::failed);
            assertThat(data.stock(a)).isEqualTo(10);
            assertThat(data.stock(b)).isEqualTo(10);
        }
        logTime("admin cancel vs order of same products, " + ROUNDS + " rounds", start);
    }

    // ------------------------------------------- runtime of the new queries

    /** schema-guardian: {@code select new StatusTotal}, {@code coalesce(sum)} and the LIMIT 5 query on PostgreSQL. */
    @Test
    void countByStatus_andSumTotalByStatus_withAndWithoutOrdersOfAStatus() {
        var u = customer();
        for (int i = 0; i < 3; i++) {
            data.insertOrderWithTotal(u.id(), "PENDING", new BigDecimal("100000"));
        }
        data.insertOrderWithTotal(u.id(), "CONFIRMED", new BigDecimal("100000"));
        data.insertOrderWithTotal(u.id(), "DELIVERED", new BigDecimal("300000"));
        data.insertOrderWithTotal(u.id(), "DELIVERED", new BigDecimal("150000"));
        data.insertOrderWithTotal(u.id(), "CANCELLED", new BigDecimal("100000"));

        Map<OrderStatus, Long> counts = new java.util.EnumMap<>(OrderStatus.class);
        orderRepository.countByStatus().forEach(t -> counts.put(t.status(), t.count()));
        for (OrderStatus s : OrderStatus.values()) {
            Long expected = jdbc.queryForObject("select count(*) from orders where status = ?", Long.class, s.name());
            assertThat(counts.getOrDefault(s, 0L)).as(s.name()).isEqualTo(expected);
        }
        assertThat(counts.get(OrderStatus.PENDING)).isGreaterThanOrEqualTo(3);
        assertThat(orderRepository.sumTotalByStatus(OrderStatus.DELIVERED))
                .isEqualByComparingTo(jdbc.queryForObject(
                        "select sum(total_amount) from orders where status = 'DELIVERED'", BigDecimal.class))
                .isGreaterThanOrEqualTo(new BigDecimal("450000"));

        // Nothing at all in two statuses (deleted inside a transaction that is rolled back): no row for them,
        // and the sum is 0, not null.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("delete from order_items where order_id in "
                    + "(select id from orders where status in ('DELIVERED', 'CANCELLED'))");
            jdbc.update("delete from orders where status in ('DELIVERED', 'CANCELLED')");
            assertThat(orderRepository.countByStatus()).extracting(StatusTotal::status)
                    .doesNotContain(OrderStatus.DELIVERED, OrderStatus.CANCELLED);
            assertThat(orderRepository.sumTotalByStatus(OrderStatus.DELIVERED)).isNotNull()
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(orderAdminService.dashboard().deliveredRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(orderAdminService.dashboard().counts()).extracting(StatusCount::count)
                    .element(3).isEqualTo(0L);
            tx.setRollbackOnly();
        });
        assertThat(data.orderCount(u.id())).isEqualTo(7);
    }

    @Test
    void findTop5_returnsFiveNewestWithTheAccountLoadedInOneStatement() {
        var u = customer();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ids.add(data.insertOrderWithTotal(u.id(), "PENDING", new BigDecimal("100000")));
        }
        data.setOrderCreatedAt(u.id(), Instant.now().plus(2, ChronoUnit.DAYS));
        List<Long> newestFirst = new ArrayList<>(ids);
        java.util.Collections.reverse(newestFirst);

        long statements = AdminTestData.statementsOf(entityManagerFactory, () -> {
            List<Order> top = orderRepository.findTop5ByOrderByCreatedAtDescIdDesc();
            assertThat(top).extracting(Order::getId).containsExactlyElementsOf(newestFirst.subList(0, 5));
            assertThat(top).allMatch(o -> o.getUser().getEmail().equals(u.email()));
        });

        assertThat(statements).isEqualTo(1);
    }
}

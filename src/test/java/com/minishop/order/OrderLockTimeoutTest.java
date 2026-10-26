package com.minishop.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-008-13, BR-008-5 (ADR-0009, ADR-0013): placing and cancelling an order wait for a locked row at most 5 seconds,
 * then the whole transaction rolls back (a {@link PessimisticLockingFailureException}) and nothing is changed.
 *
 * <p>Moved from {@code LockTimeoutIntegrationTest} (ADR-0014), which called {@code POST /checkout} and
 * {@code POST /orders/{id}/cancel}; here the package-private {@link OrderService} is called directly. One case per
 * lock: {@code orders} (customer cancel) and the conditional {@code UPDATE products} of {@code decrementStock}
 * (checkout), which has no {@code @Lock} at all and is therefore the case a per-query JPA hint would have missed.
 *
 * <p>Deterministic by construction: the "other transaction" is a plain JDBC {@link Connection} of the test thread that
 * holds the row lock; the service call runs on the same thread and simply blocks until H2 gives up on the lock.
 * After the call, the holder writes a small change and commits, which proves the first transaction is not harmed by
 * the second one's timeout.
 *
 * <p>All data is private to this class (emails {@code @orderlock.test}, names starting with {@code Order lock}) and
 * removed in {@code @BeforeEach} and {@code @AfterEach}; seed accounts and products are never touched.
 */
@SpringBootTest
class OrderLockTimeoutTest {

    private static final Duration AT_LEAST = Duration.ofMillis(4_500);
    private static final Duration LESS_THAN = Duration.ofSeconds(15);

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encoder;

    private final List<Connection> holders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() throws SQLException {
        for (Connection holder : holders) {
            try {
                holder.rollback(); // no-op after commit; releases the lock when a test failed early
            } finally {
                holder.close();
            }
        }
        holders.clear();
        cleanUp();
    }

    @Test
    void cancelByCustomer_whenOrdersRowStaysLocked_failsAfterAboutFiveSecondsAndKeepsOrderAndStock() throws Exception {
        long userId = createUser();
        long productId = createProduct(createCategory(), 100_000, 10);
        long orderId = createOrder(userId, productId, 2);
        Connection holder = holdRow("select id from orders where id = ? for update", orderId);

        Timed result = timed(() -> orderService.cancelByCustomer(userId, orderId));

        assertTimedOut(result);
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                .isEqualTo("PENDING");
        assertThat(stock(productId)).as("stock not restored by a rolled back cancel").isEqualTo(10);

        commitChange(holder, "update orders set note = 'held' where id = ?", orderId);
        assertThat(jdbc.queryForObject("select note from orders where id = ?", String.class, orderId))
                .as("the first transaction completed normally").isEqualTo("held");
    }

    /**
     * The holder locks the product row with FOR UPDATE; H2 checks the foreign key of {@code order_items} without
     * waiting for that lock. So the order is saved, and the call waits exactly at the conditional
     * {@code UPDATE products} of {@code decrementStock}: the statement the plan calls out because it has no
     * {@code @Lock}.
     */
    @Test
    void placeOrder_whenProductRowStaysLocked_failsAtConditionalStockUpdateAndRollsBackEverything() throws Exception {
        long userId = createUser();
        long productId = createProduct(createCategory(), 100_000, 5);
        createCart(userId, productId, 1);
        Connection holder = holdRow("select id from products where id = ? for update", productId);
        CheckoutForm form = new CheckoutForm("Nguoi Nhan Bi Mat", "0912345678", "12 Duong Bi Mat", null);

        Timed result = timed(() -> orderService.placeOrder(userId, form, new BigDecimal("100000")));

        assertTimedOut(result);
        assertThat(Arrays.stream(result.failure().getStackTrace()).map(StackTraceElement::getMethodName))
                .as("the wait was at the stock UPDATE").contains("decrementStock");
        assertThat(count("select count(*) from orders where user_id = ?", userId)).as("no order").isZero();
        assertThat(count("select count(*) from order_items where product_id = ?", productId)).isZero();
        assertThat(stock(productId)).as("stock unchanged").isEqualTo(5);
        assertThat(cartLines(userId)).as("cart kept").isEqualTo(Map.of(productId, 1));

        commitChange(holder, "update products set description = 'held' where id = ?", productId);
        assertThat(jdbc.queryForObject("select description from products where id = ?", String.class, productId))
                .as("the first transaction completed normally").isEqualTo("held");
    }

    // ------------------------------------------------------------------ assertions

    private static void assertTimedOut(Timed result) {
        assertThat(result.elapsed()).as("waited for the 5 second lock timeout").isGreaterThanOrEqualTo(AT_LEAST)
                .isLessThan(LESS_THAN);
        System.out.printf("LOCK-TIMEOUT-TIME OrderService took %d ms%n", result.elapsed().toMillis());
        assertThat(result.failure()).isInstanceOf(PessimisticLockingFailureException.class);
    }

    // ------------------------------------------------------------------ timing and locking

    private record Timed(Throwable failure, Duration elapsed) {
    }

    private interface Action {
        void run() throws Exception;
    }

    /** Runs the call, which must fail, and measures how long it took. */
    private static Timed timed(Action action) {
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            action.run();
        } catch (Throwable e) {
            failure = e;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(failure).as("the call was expected to fail with a lock timeout, but it succeeded").isNotNull();
        return new Timed(failure, elapsed);
    }

    /** A second transaction (own connection, autocommit off) that takes the row lock and keeps it. */
    private Connection holdRow(String lockingSelect, long id) throws SQLException {
        Connection connection = dataSource.getConnection();
        holders.add(connection);
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(lockingSelect)) {
            statement.setLong(1, id);
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).as("the row to lock exists").isTrue();
            }
        }
        return connection;
    }

    private static void commitChange(Connection holder, String update, long id) throws SQLException {
        try (PreparedStatement statement = holder.prepareStatement(update)) {
            statement.setLong(1, id);
            assertThat(statement.executeUpdate()).isOne();
        }
        holder.commit();
    }

    // ------------------------------------------------------------------ private test data

    private long createUser() {
        String email = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@orderlock.test";
        return jdbc.queryForObject("select id from final table (insert into users (email, password_hash, full_name, role) "
                + "values (?, ?, 'Order Lock Tester', 'USER'))", Long.class, email, encoder.encode("Test-pass-1"));
    }

    private long createCategory() {
        return jdbc.queryForObject("select id from final table (insert into categories (name) values (?))", Long.class,
                "Order lock category " + UUID.randomUUID().toString().substring(0, 8));
    }

    private long createProduct(long categoryId, long price, int stock) {
        return jdbc.queryForObject("select id from final table (insert into products (category_id, name, price, stock, active) "
                        + "values (?, ?, ?, ?, true))", Long.class,
                categoryId, "Order lock product " + UUID.randomUUID().toString().substring(0, 8), price, stock);
    }

    private void createCart(long userId, long productId, int quantity) {
        Long cartId = jdbc.queryForObject("select id from final table (insert into carts (user_id) values (?))",
                Long.class, userId);
        jdbc.update("insert into cart_items (cart_id, product_id, quantity) values (?, ?, ?)",
                cartId, productId, quantity);
    }

    private long createOrder(long userId, long productId, int quantity) {
        Long orderId = jdbc.queryForObject("select id from final table (insert into orders (user_id, status, receiver_name, phone, "
                + "shipping_address, payment_method, total_amount) values (?, 'PENDING', 'Nguoi nhan', '0912345678', "
                + "'Dia chi', 'COD', ?))", Long.class, userId, 100_000L * quantity);
        jdbc.update("insert into order_items (order_id, product_id, product_name, unit_price, quantity) "
                + "select ?, p.id, p.name, p.price, ? from products p where p.id = ?", orderId, quantity, productId);
        return orderId;
    }

    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private Map<Long, Integer> cartLines(long userId) {
        Map<Long, Integer> lines = new TreeMap<>();
        jdbc.query("select i.product_id, i.quantity from cart_items i join carts c on c.id = i.cart_id "
                + "where c.user_id = ?", rs -> {
            lines.put(rs.getLong(1), rs.getInt(2));
        }, userId);
        return lines;
    }

    private void cleanUp() {
        String users = "select id from users where email like '%@orderlock.test'";
        String products = "select id from products where name like 'Order lock product %'";
        jdbc.update("delete from order_items where order_id in (select id from orders where user_id in (" + users + "))");
        jdbc.update("delete from order_items where product_id in (" + products + ")");
        jdbc.update("delete from orders where user_id in (" + users + ")");
        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id in (" + users + "))");
        jdbc.update("delete from cart_items where product_id in (" + products + ")");
        jdbc.update("delete from carts where user_id in (" + users + ")");
        jdbc.update("delete from products where name like 'Order lock product %'");
        jdbc.update("delete from categories where name like 'Order lock category %'");
        jdbc.update("delete from users where email like '%@orderlock.test'");
    }
}

package com.minishop;

import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-008-13, BR-008-5 (ADR-0012): every statement that waits for a locked row waits at most 5 seconds, then the whole
 * transaction rolls back and the visitor gets the 500 of phase 7, with exactly one {@code ERROR} log line.
 *
 * <p>Deterministic by construction: the "other transaction" is a plain JDBC {@link Connection} of the test thread that
 * holds the row lock; the real MockMvc request runs on the same thread and simply blocks until H2 gives up on the lock
 * (error {@code 50200}, ADR-0013). No sleeping, no second thread, no race. After the request, the holder writes a
 * small change and commits, which proves the first transaction is not harmed by the second one's timeout.
 *
 * <p>One case per lock the application takes: {@code users} (cart writes), {@code orders} (customer cancel),
 * {@code categories} (admin delete) and the conditional {@code UPDATE products} of {@code decrementStock} (checkout),
 * which has no {@code @Lock} at all and is therefore the case a per-query JPA hint would have missed.
 *
 * <p>Same annotations as {@code LoginFlowTest}, so the Spring context (and its in-memory database) is shared. All data
 * is private to this class (emails {@code @locktimeout.test}, names starting with {@code Lock test}) and removed in
 * {@code @BeforeEach} and {@code @AfterEach}; seed accounts and products are never touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LockTimeoutIntegrationTest {

    private static final Duration AT_LEAST = Duration.ofMillis(4_500);
    private static final Duration LESS_THAN = Duration.ofSeconds(15);
    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^.*\\sERROR\\s+\\d+\\s---.*$");

    private static final String RECEIVER = "Nguoi Nhan Bi Mat";
    private static final String PHONE = "0912345678";
    private static final String ADDRESS = "12 Duong Bi Mat";

    @Autowired
    MockMvc mockMvc;

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
    void connections_haveFiveSecondLockTimeout() {
        assertThat(jdbc.queryForObject("select lock_timeout()", Integer.class)).isEqualTo(5000);
    }

    @Test
    void addToCart_whenUsersRowStaysLocked_failsAfterAboutFiveSecondsAndChangesNothing(CapturedOutput output)
            throws Exception {
        long userId = createUser();
        long productId = createProduct(createCategory(), 100_000, 10);
        Connection holder = holdRow("select id from users where id = ? for update", userId);
        int logOffset = output.getAll().length();

        Timed result = timed(() -> mockMvc.perform(post("/cart/items").with(csrf()).with(customer(userId))
                .param("productId", String.valueOf(productId)).param("quantity", "1")).andReturn());

        assertTimedOut(result, output, logOffset, "POST /cart/items");
        assertThat(count("select count(*) from carts where user_id = ?", userId)).as("no cart written").isZero();
        assertThat(count("select count(*) from cart_items where product_id = ?", productId)).isZero();

        commitChange(holder, "update users set phone = '0900000001' where id = ?", userId);
        assertThat(jdbc.queryForObject("select phone from users where id = ?", String.class, userId))
                .as("the first transaction completed normally").isEqualTo("0900000001");
    }

    @Test
    void cancelOrder_whenOrdersRowStaysLocked_failsAfterAboutFiveSecondsAndKeepsOrderAndStock(CapturedOutput output)
            throws Exception {
        long userId = createUser();
        long productId = createProduct(createCategory(), 100_000, 10);
        long orderId = createOrder(userId, productId, 2);
        Connection holder = holdRow("select id from orders where id = ? for update", orderId);
        int logOffset = output.getAll().length();

        Timed result = timed(() -> mockMvc.perform(post("/orders/" + orderId + "/cancel").with(csrf())
                .with(customer(userId))).andReturn());

        assertTimedOut(result, output, logOffset, "POST /orders/" + orderId + "/cancel");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                .isEqualTo("PENDING");
        assertThat(stock(productId)).as("stock not restored by a rolled back cancel").isEqualTo(10);

        commitChange(holder, "update orders set note = 'held' where id = ?", orderId);
        assertThat(jdbc.queryForObject("select note from orders where id = ?", String.class, orderId))
                .isEqualTo("held");
    }

    @Test
    void deleteCategory_whenCategoryRowStaysLocked_failsAfterAboutFiveSecondsAndKeepsCategory(CapturedOutput output)
            throws Exception {
        long categoryId = createCategory();
        Connection holder = holdRow("select id from categories where id = ? for update", categoryId);
        int logOffset = output.getAll().length();

        Timed result = timed(() -> mockMvc.perform(post("/admin/categories/" + categoryId + "/delete").with(csrf())
                .with(admin())).andReturn());

        assertTimedOut(result, output, logOffset, "POST /admin/categories/" + categoryId + "/delete");
        assertThat(count("select count(*) from categories where id = ?", categoryId)).as("category kept").isOne();

        commitChange(holder, "update categories set description = 'held' where id = ?", categoryId);
        assertThat(jdbc.queryForObject("select description from categories where id = ?", String.class, categoryId))
                .isEqualTo("held");
    }

    /**
     * The holder locks the product row with FOR UPDATE; H2 checks the foreign key of {@code order_items} without
     * waiting for that lock. So the order is saved, and the request waits exactly at the
     * conditional {@code UPDATE products} of {@code decrementStock}: the statement the plan calls out because it has
     * no {@code @Lock}.
     */
    @Test
    void checkout_whenProductRowStaysLocked_failsAtConditionalStockUpdateAndRollsBackEverything(CapturedOutput output)
            throws Exception {
        long userId = createUser();
        long productId = createProduct(createCategory(), 100_000, 5);
        createCart(userId, productId, 1);
        Connection holder = holdRow("select id from products where id = ? for update", productId);
        int logOffset = output.getAll().length();

        Timed result = timed(() -> mockMvc.perform(post("/checkout").with(csrf()).with(customer(userId))
                .param("receiverName", RECEIVER).param("phone", PHONE).param("shippingAddress", ADDRESS)
                .param("expectedTotal", "100000")).andReturn());

        assertTimedOut(result, output, logOffset, "POST /checkout");
        assertThat(output.getAll().substring(logOffset)).as("the wait was at the stock UPDATE")
                .contains("decrementStock");
        assertThat(count("select count(*) from orders where user_id = ?", userId)).as("no order").isZero();
        assertThat(count("select count(*) from order_items where product_id = ?", productId)).isZero();
        assertThat(stock(productId)).as("stock unchanged").isEqualTo(5);
        assertThat(cartLines(userId)).as("cart kept").isEqualTo(Map.of(productId, 1));

        commitChange(holder, "update products set description = 'held' where id = ?", productId);
        assertThat(jdbc.queryForObject("select description from products where id = ?", String.class, productId))
                .isEqualTo("held");
    }

    // ------------------------------------------------------------------ assertions

    private void assertTimedOut(Timed result, CapturedOutput output, int logOffset, String request) {
        assertThat(result.elapsed()).as("waited for the 5 second lock timeout").isGreaterThanOrEqualTo(AT_LEAST)
                .isLessThan(LESS_THAN);
        System.out.printf("LOCK-TIMEOUT-TIME %s took %d ms%n", request, result.elapsed().toMillis());
        assertThat(result.mvcResult().getResponse().getStatus()).isEqualTo(500);
        assertThat(result.mvcResult().getResolvedException()).as("resolved by UnhandledExceptionResolver")
                .isInstanceOf(PessimisticLockingFailureException.class);

        String log = output.getAll().substring(logOffset);
        Matcher errors = ERROR_LINE.matcher(log);
        List<String> errorLines = new ArrayList<>();
        while (errors.find()) {
            errorLines.add(errors.group());
        }
        assertThat(errorLines).as("exactly one ERROR line").hasSize(1);
        assertThat(errorLines.get(0)).contains("Unhandled exception: " + request);
        assertThat(log).contains("CannotAcquireLockException");
        assertThat(log).as("no personal data in the log")
                .doesNotContain("@locktimeout.test", "Lock Tester", RECEIVER, PHONE, ADDRESS);
    }

    // ------------------------------------------------------------------ timing and locking

    private record Timed(MvcResult mvcResult, Duration elapsed) {
    }

    private interface Action {
        MvcResult run() throws Exception;
    }

    private static Timed timed(Action action) throws Exception {
        long start = System.nanoTime();
        MvcResult result = action.run();
        return new Timed(result, Duration.ofNanos(System.nanoTime() - start));
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

    // ------------------------------------------------------------------ principals

    private static RequestPostProcessor customer(long userId) {
        return user(new CurrentUser(userId, "u" + userId + "@locktimeout.test", null, "Lock Tester", Role.USER));
    }

    private static RequestPostProcessor admin() {
        return user(new CurrentUser(1L, "admin@locktimeout.test", null, "Lock Admin", Role.ADMIN));
    }

    // ------------------------------------------------------------------ private test data

    private long createUser() {
        String email = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@locktimeout.test";
        return jdbc.queryForObject("select id from final table (insert into users (email, password_hash, full_name, role) "
                + "values (?, ?, 'Lock Tester', 'USER'))", Long.class, email, encoder.encode("Test-pass-1"));
    }

    private long createCategory() {
        return jdbc.queryForObject("select id from final table (insert into categories (name) values (?))", Long.class,
                "Lock test category " + UUID.randomUUID().toString().substring(0, 8));
    }

    private long createProduct(long categoryId, long price, int stock) {
        return jdbc.queryForObject("select id from final table (insert into products (category_id, name, price, stock, active) "
                        + "values (?, ?, ?, ?, true))", Long.class,
                categoryId, "Lock test product " + UUID.randomUUID().toString().substring(0, 8), price, stock);
    }

    private void createCart(long userId, long productId, int quantity) {
        Long cartId = jdbc.queryForObject("select id from final table (insert into carts (user_id) values (?))", Long.class, userId);
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
        Map<Long, Integer> lines = new java.util.TreeMap<>();
        jdbc.query("select i.product_id, i.quantity from cart_items i join carts c on c.id = i.cart_id "
                + "where c.user_id = ?", rs -> {
            lines.put(rs.getLong(1), rs.getInt(2));
        }, userId);
        return lines;
    }

    private void cleanUp() {
        String users = "select id from users where email like '%@locktimeout.test'";
        String products = "select id from products where name like 'Lock test product %'";
        jdbc.update("delete from order_items where order_id in (select id from orders where user_id in (" + users + "))");
        jdbc.update("delete from order_items where product_id in (" + products + ")");
        jdbc.update("delete from orders where user_id in (" + users + ")");
        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id in (" + users + "))");
        jdbc.update("delete from cart_items where product_id in (" + products + ")");
        jdbc.update("delete from carts where user_id in (" + users + ")");
        jdbc.update("delete from products where name like 'Lock test product %'");
        jdbc.update("delete from categories where name like 'Lock test category %'");
        jdbc.update("delete from users where email like '%@locktimeout.test'");
    }
}

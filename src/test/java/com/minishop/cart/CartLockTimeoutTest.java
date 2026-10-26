package com.minishop.cart;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-008-13, BR-008-5 (ADR-0008, ADR-0013): a cart write waits for the locked {@code users} row at most 5 seconds,
 * then the whole transaction rolls back (a {@link PessimisticLockingFailureException}) and nothing is written.
 *
 * <p>Moved from {@code LockTimeoutIntegrationTest} (ADR-0014), which called {@code POST /cart/items}; here the
 * package-private {@link CartService} is called directly. Deterministic by construction: the "other transaction" is
 * a plain JDBC {@link Connection} of the test thread that holds the row lock; the service call runs on the same
 * thread and simply blocks until H2 gives up on the lock. After the call, the holder writes a small change and
 * commits, which proves the first transaction is not harmed by the second one's timeout.
 *
 * <p>All data is private to this class (emails {@code @cartlock.test}, names starting with {@code Cart lock}) and
 * removed in {@code @BeforeEach} and {@code @AfterEach}; seed accounts and products are never touched.
 */
@SpringBootTest
class CartLockTimeoutTest {

    private static final Duration AT_LEAST = Duration.ofMillis(4_500);
    private static final Duration LESS_THAN = Duration.ofSeconds(15);

    @Autowired
    CartService cartService;

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
    void add_whenUsersRowStaysLocked_failsAfterAboutFiveSecondsAndChangesNothing() throws Exception {
        long userId = createUser();
        long productId = createProduct(createCategory(), 100_000, 10);
        Connection holder = holdRow("select id from users where id = ? for update", userId);

        long start = System.nanoTime();
        Throwable failure = failureOf(() -> cartService.add(new CartOwner.Customer(userId), productId, 1));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("waited for the 5 second lock timeout").isGreaterThanOrEqualTo(AT_LEAST)
                .isLessThan(LESS_THAN);
        System.out.printf("LOCK-TIMEOUT-TIME CartService.add took %d ms%n", elapsed.toMillis());
        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(count("select count(*) from carts where user_id = ?", userId)).as("no cart written").isZero();
        assertThat(count("select count(*) from cart_items where product_id = ?", productId)).isZero();

        commitChange(holder, "update users set phone = '0900000001' where id = ?", userId);
        assertThat(jdbc.queryForObject("select phone from users where id = ?", String.class, userId))
                .as("the first transaction completed normally").isEqualTo("0900000001");
    }

    // ------------------------------------------------------------------ timing and locking

    private interface Action {
        void run() throws Exception;
    }

    private static Throwable failureOf(Action action) {
        try {
            action.run();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("the call was expected to fail with a lock timeout, but it succeeded");
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
        String email = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@cartlock.test";
        return jdbc.queryForObject("select id from final table (insert into users (email, password_hash, full_name, role) "
                + "values (?, ?, 'Cart Lock Tester', 'USER'))", Long.class, email, encoder.encode("Test-pass-1"));
    }

    private long createCategory() {
        return jdbc.queryForObject("select id from final table (insert into categories (name) values (?))", Long.class,
                "Cart lock category " + UUID.randomUUID().toString().substring(0, 8));
    }

    private long createProduct(long categoryId, long price, int stock) {
        return jdbc.queryForObject("select id from final table (insert into products (category_id, name, price, stock, active) "
                        + "values (?, ?, ?, ?, true))", Long.class,
                categoryId, "Cart lock product " + UUID.randomUUID().toString().substring(0, 8), price, stock);
    }

    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private void cleanUp() {
        String users = "select id from users where email like '%@cartlock.test'";
        String products = "select id from products where name like 'Cart lock product %'";
        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id in (" + users + "))");
        jdbc.update("delete from cart_items where product_id in (" + products + ")");
        jdbc.update("delete from carts where user_id in (" + users + ")");
        jdbc.update("delete from products where name like 'Cart lock product %'");
        jdbc.update("delete from categories where name like 'Cart lock category %'");
        jdbc.update("delete from users where email like '%@cartlock.test'");
    }
}

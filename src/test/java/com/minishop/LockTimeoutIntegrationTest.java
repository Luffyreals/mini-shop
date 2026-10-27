package com.minishop;

import com.minishop.catalog.CategoryAdminService;
import com.minishop.support.ResetAdminAccess;
import com.minishop.support.TestAdminAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * AC-008-13, BR-008-5 (ADR-0012, ADR-0013): every statement that waits for a locked row waits at most 5 seconds, then
 * the whole transaction rolls back and the caller gets a {@link PessimisticLockingFailureException}.
 *
 * <p>Deterministic by construction: the "other transaction" is a plain JDBC {@link Connection} of the test thread that
 * holds the row lock; the real service call runs on the same thread and simply blocks until H2 gives up on the lock
 * (error {@code 50200}). No sleeping, no second thread, no race. After the call, the holder writes a small change and
 * commits, which proves the first transaction is not harmed by the second one's timeout.
 *
 * <p>This class keeps the connection setting and the case of the public admin API ({@code categories}, deleting a
 * category). The cases of package-private services live next to them: {@code cart.CartLockTimeoutTest} ({@code users})
 * and {@code order.OrderLockTimeoutTest} ({@code orders}, and the conditional {@code UPDATE products} of
 * {@code decrementStock} at checkout).
 *
 * <p>All data is private to this class (names starting with {@code Lock test category}) and removed in
 * {@code @BeforeEach} and {@code @AfterEach}; seed accounts and products are never touched.
 */
@SpringBootTest
@ExtendWith(ResetAdminAccess.class)
class LockTimeoutIntegrationTest {

    private static final Duration AT_LEAST = Duration.ofMillis(4_500);
    private static final Duration LESS_THAN = Duration.ofSeconds(15);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    CategoryAdminService categoryAdminService;

    @Autowired
    TestAdminAccess access;

    private final List<Connection> holders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        access.actAsAdmin(1L);
        cleanUp();
    }

    @AfterEach
    void tearDown() throws SQLException {
        access.reset();
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
    void deleteCategory_whenCategoryRowStaysLocked_failsAfterAboutFiveSecondsAndKeepsCategory() throws Exception {
        long categoryId = createCategory();
        Connection holder = holdRow("select id from categories where id = ? for update", categoryId);

        long start = System.nanoTime();
        Throwable failure = failureOf(() -> categoryAdminService.delete(categoryId));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("waited for the 5 second lock timeout").isGreaterThanOrEqualTo(AT_LEAST)
                .isLessThan(LESS_THAN);
        System.out.printf("LOCK-TIMEOUT-TIME CategoryAdminService.delete took %d ms%n", elapsed.toMillis());
        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(count("select count(*) from categories where id = ?", categoryId)).as("category kept").isOne();

        commitChange(holder, "update categories set description = 'held' where id = ?", categoryId);
        assertThat(jdbc.queryForObject("select description from categories where id = ?", String.class, categoryId))
                .as("the first transaction completed normally").isEqualTo("held");
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

    private long createCategory() {
        return jdbc.queryForObject("select id from final table (insert into categories (name) values (?))", Long.class,
                "Lock test category " + UUID.randomUUID().toString().substring(0, 8));
    }

    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private void cleanUp() {
        jdbc.update("delete from categories where name like 'Lock test category %'");
    }
}

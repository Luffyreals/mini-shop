package com.minishop.order;

import com.minishop.support.ResetAdminAccess;
import com.minishop.support.TestAdminAccess;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The app must keep writing after Hikari has replaced every connection it opened at startup, including the one
 * Flyway ran V1/V2 on. Hikari does that on its own after {@code maxLifetime} (30 minutes by default); here the pool
 * is told to do it at once. H2 2.4.240 compiled {@code CHECK (… in (…))} constraints with the session that created
 * them, so once that session closed, every insert or update checked by one failed with "The database has been
 * closed" until the app restarted (H2 issue #4302, fixed in 2.5.250).
 *
 * <p>{@link DirtiesContext}: if this ever fails again, the broken database must not leak into the next test class.
 */
@SpringBootTest
@ExtendWith(ResetAdminAccess.class)
@DirtiesContext
class PoolConnectionRetireIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TestAdminAccess access;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderAdminService orderAdminService;

    OrderTestData data;

    @BeforeEach
    void setUp() {
        data = new OrderTestData(jdbc, encoder);
        data.cleanUp();
    }

    @AfterEach
    void tearDown() {
        access.reset();
        data.cleanUp();
    }

    /** Asks the pool to replace every connection, then waits until none of the H2 sessions from before is left. */
    private void retireEveryPoolConnection() {
        Set<Long> before = new HashSet<>(sessionIds());
        ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (sessionIds().stream().anyMatch(before::contains)) {
            if (System.nanoTime() > deadline) {
                fail("The pool still holds a session from before the eviction");
            }
            Thread.onSpinWait();
        }
    }

    private List<Long> sessionIds() {
        return jdbc.queryForList("select session_id from information_schema.sessions", Long.class);
    }

    @Test
    void placeOrder_afterThePoolReplacedItsConnections_stillPlacesTheOrder() {
        var u = data.createUser();
        long product = data.createProduct("Retire", 100_000, 5);
        data.createCart(u.id(), new long[]{product, 2});
        retireEveryPoolConnection();

        // orders.status and orders.payment_method are checked with "in (…)"
        long orderId = orderService.placeOrder(u.id(), new CheckoutForm("Người nhận", "0912345678", "12 Lê Lợi", null),
                new BigDecimal("200000"));

        assertThat(data.orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(data.stock(product)).isEqualTo(3);
    }

    @Test
    void writes_checkedByAnInListConstraint_afterThePoolReplacedItsConnections_stillWork() {
        var u = data.createUser();
        long product = data.createProduct("Retire", 100_000, 5);
        long orderId = data.insertOrder(u.id(), "PENDING", new long[]{product, 1});
        long adminId = jdbc.queryForObject("select min(id) from users where role = 'ADMIN'", Long.class);
        retireEveryPoolConnection();

        // users.role is checked with "in (…)", like a registration
        assertThat(data.createUser().id()).isPositive();
        // An admin status change updates orders.status
        access.actAsAdmin(adminId);
        assertThat(orderAdminService.changeStatus(orderId, "CONFIRMED"))
                .isInstanceOf(StatusChangeResult.Changed.class);
        assertThat(data.orderStatus(orderId)).isEqualTo("CONFIRMED");
    }
}

package com.minishop.cart;

import com.minishop.user.CurrentUser;
import com.minishop.user.LoginHook;
import com.minishop.user.Role;
import com.minishop.user.SessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * What happens to the guest cart at login and logout (BR-004-7, ADR-0006, AC-009-5, AC-009-10), on the real context
 * and H2. {@code GuestCartLifecycle} is built by hand around the real {@link CartService} and a recording store when a
 * test must look inside {@code clear()}; the autowired bean is used to prove the real wiring. Not
 * {@code @Transactional}: the commit before {@code clear()} is the point. Data is private to {@link CartTestData}.
 */
@SpringBootTest
class GuestCartLifecycleIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    CartService cartService;

    @Autowired
    GuestCartLifecycle lifecycle;

    @Autowired
    GuestCartStore realStore;

    @Autowired
    ApplicationContext context;

    CartTestData data;
    private final List<Connection> holders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        data = new CartTestData(jdbc, encoder);
        data.cleanUp();
        realStore.clear();
    }

    @AfterEach
    void tearDown() throws SQLException {
        for (Connection holder : holders) {
            try {
                holder.rollback();
            } finally {
                holder.close();
            }
        }
        holders.clear();
        realStore.clear();
        data.cleanUp();
    }

    private static CurrentUser loggedIn(CartTestData.TestUser user) {
        return new CurrentUser(user.id(), user.email(), "Cart Tester", Role.USER);
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void context_hasExactlyOneGuestCartStoreAndOneLoginHook() {
        Map<String, GuestCartStore> stores = context.getBeansOfType(GuestCartStore.class);
        Map<String, LoginHook> hooks = context.getBeansOfType(LoginHook.class);

        assertThat(stores).hasSize(1);
        assertThat(stores.values()).singleElement().isInstanceOf(InMemoryGuestCartStore.class).isSameAs(realStore);
        assertThat(hooks).hasSize(1);
        assertThat(hooks.values()).singleElement().isSameAs(lifecycle);
        assertThat(context.getBeansOfType(SessionListener.class).values()).contains(lifecycle);
    }

    // ------------------------------------------------------------------ merge

    @Test
    void afterLogin_mergesToMinOfDbPlusGuestStockAnd99_skipsHiddenAndSoldOutLines_thenEmptiesTheGuestCart() {
        var user = data.createUser();
        long inBoth = data.createProduct("A in both", 100_000, 5);
        long guestOnly = data.createProduct("B guest only", 100_000, 5);
        long dbOnly = data.createProduct("C db only", 100_000, 5);
        long big = data.createProduct("D big stock", 100_000, 500);
        long hidden = data.createProduct("E hidden", 100_000, 5);
        long soldOut = data.createProduct("F sold out", 100_000, 5);
        data.setActive(hidden, false);
        data.setStock(soldOut, 0);
        data.createCart(user.id(), new long[]{inBoth, 3}, new long[]{dbOnly, 1}, new long[]{big, 60});
        List<String> stockBefore = data.stockSnapshot();
        realStore.getOrCreate().put(inBoth, 4);       // 3 + 4 = 7, capped to stock 5
        realStore.getOrCreate().put(guestOnly, 2);    // new line
        realStore.getOrCreate().put(big, 60);         // 60 + 60 = 120, capped to 99
        realStore.getOrCreate().put(hidden, 1);       // skipped
        realStore.getOrCreate().put(soldOut, 1);      // skipped

        lifecycle.afterLogin(loggedIn(user));

        assertThat(data.cartLines(user.id())).containsOnly(
                Map.entry(inBoth, 5), Map.entry(guestOnly, 2), Map.entry(dbOnly, 1), Map.entry(big, 99));
        assertThat(realStore.find()).as("guest cart emptied after the merge").isEmpty();
        assertThat(data.stockSnapshot()).as("a cart never touches stock").isEqualTo(stockBefore);
    }

    @Test
    void afterLogin_forAUserWithoutADbCart_createsExactlyOneCartWithTheEligibleLines() {
        var user = data.createUser();
        long ok = data.createProduct("A ok", 100_000, 10);
        long hidden = data.createProduct("B hidden", 100_000, 10);
        data.setActive(hidden, false);
        realStore.getOrCreate().put(ok, 2);
        realStore.getOrCreate().put(hidden, 1);

        lifecycle.afterLogin(loggedIn(user));

        assertThat(data.cartCount(user.id())).isEqualTo(1);
        assertThat(data.cartLines(user.id())).containsOnly(Map.entry(ok, 2));
        assertThat(realStore.find()).isEmpty();
    }

    @Test
    void afterLogin_withAnEmptyGuestCart_onlyClearsIt_andCreatesNoCartRow() {
        var user = data.createUser();
        RecordingGuestCartStore store = new RecordingGuestCartStore();
        store.getOrCreate();
        int cartsBefore = data.totalRows("carts");

        new GuestCartLifecycle(cartService, store).afterLogin(loggedIn(user));

        assertThat(store.clearCalls()).isOne();
        assertThat(store.find()).isEmpty();
        assertThat(data.totalRows("carts")).isEqualTo(cartsBefore);
        assertThat(data.cartCount(user.id())).isZero();
    }

    @Test
    void afterLogin_withNoGuestCart_doesNothing() {
        var user = data.createUser();
        RecordingGuestCartStore store = new RecordingGuestCartStore();
        int cartsBefore = data.totalRows("carts");

        new GuestCartLifecycle(cartService, store).afterLogin(loggedIn(user));

        assertThat(store.clearCalls()).isZero();
        assertThat(store.getOrCreateCalls()).as("never creates a guest cart").isZero();
        assertThat(data.totalRows("carts")).isEqualTo(cartsBefore);
    }

    // ------------------------------------------------------------------ commit before clear

    /** A store that, inside clear(), looks at the database on another connection. */
    private final class ProbingStore implements GuestCartStore {

        private final RecordingGuestCartStore inner = new RecordingGuestCartStore();
        private final long userId;
        Integer rowsVisibleAtClear;

        ProbingStore(long userId) {
            this.userId = userId;
        }

        @Override
        public Optional<SessionCart> find() {
            return inner.find();
        }

        @Override
        public SessionCart getOrCreate() {
            return inner.getOrCreate();
        }

        @Override
        public void save(SessionCart cart) {
            inner.save(cart);
        }

        @Override
        public void clear() {
            // jdbc is not in a transaction here, so it uses another connection than the merge did
            rowsVisibleAtClear = jdbc.queryForObject("select count(*) from cart_items i join carts c on c.id = i.cart_id "
                    + "where c.user_id = ?", Integer.class, userId);
            inner.clear();
        }
    }

    @Test
    void afterLogin_commitsTheMergeBeforeItClearsTheGuestCart() {
        var user = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        long b = data.createProduct("B", 100_000, 10);
        ProbingStore store = new ProbingStore(user.id());
        store.getOrCreate().put(a, 1);
        store.getOrCreate().put(b, 2);

        new GuestCartLifecycle(cartService, store).afterLogin(loggedIn(user));

        assertThat(store.rowsVisibleAtClear).as("rows visible from another connection when clear() ran").isEqualTo(2);
        assertThat(store.find()).isEmpty();
    }

    /** Proves the probe above can fail: inside a caller's transaction the merge is not committed yet at clear(). */
    @Test
    void probe_seesNothing_whenTheMergeRunsInsideAnOpenTransaction() {
        var user = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        ProbingStore store = new ProbingStore(user.id());
        store.getOrCreate().put(a, 1);
        GuestCartLifecycle inner = new GuestCartLifecycle(cartService, store);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> inner.afterLogin(loggedIn(user)));

        assertThat(store.rowsVisibleAtClear).isZero();
        assertThat(data.cartLines(user.id())).as("committed with the outer transaction").containsOnly(Map.entry(a, 1));
    }

    // ------------------------------------------------------------------ failure keeps the guest cart (BR-004-7)

    @Test
    void afterLogin_whenTheMergeThrows_letsTheErrorOutAndKeepsTheGuestCart() {
        var user = data.createUser();
        CartService failing = mock(CartService.class);
        doThrow(new IllegalStateException("boom")).when(failing).mergeGuestCart(anyLong(), any());
        RecordingGuestCartStore store = new RecordingGuestCartStore().seed(11L, 2).seed(12L, 3);

        assertThatThrownBy(() -> new GuestCartLifecycle(failing, store).afterLogin(loggedIn(user)))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(store.clearCalls()).isZero();
        assertThat(store.find()).hasValueSatisfying(cart ->
                assertThat(cart.lines()).containsExactly(Map.entry(11L, 2), Map.entry(12L, 3)));
    }

    @Test
    void afterLogin_whenTheUsersRowStaysLocked_failsByLockTimeout_keepsTheGuestCart_andChangesNoDbCart() throws Exception {
        var user = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        long b = data.createProduct("B", 100_000, 10);
        data.createCart(user.id(), new long[]{a, 1});
        RecordingGuestCartStore store = new RecordingGuestCartStore().seed(a, 4).seed(b, 2);
        Connection holder = dataSource.getConnection();
        holders.add(holder);
        holder.setAutoCommit(false);
        try (PreparedStatement lock = holder.prepareStatement("select id from users where id = ? for update")) {
            lock.setLong(1, user.id());
            try (var rs = lock.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }

        assertThatThrownBy(() -> new GuestCartLifecycle(cartService, store).afterLogin(loggedIn(user)))
                .isInstanceOf(PessimisticLockingFailureException.class);
        holder.rollback();

        assertThat(store.clearCalls()).isZero();
        assertThat(store.find()).hasValueSatisfying(cart ->
                assertThat(cart.lines()).containsExactly(Map.entry(a, 4), Map.entry(b, 2)));
        assertThat(data.cartLines(user.id())).as("the db cart did not change").containsOnly(Map.entry(a, 1));
    }

    // ------------------------------------------------------------------ logout

    @Test
    void sessionChanged_toGuest_clearsTheGuestCart() {
        var user = data.createUser();
        realStore.getOrCreate().put(data.createProduct("A", 100_000, 10), 1);

        lifecycle.sessionChanged(loggedIn(user), null);

        assertThat(realStore.find()).isEmpty();
    }

    @Test
    void sessionChanged_onSignIn_keepsTheGuestCart() {
        var user = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        realStore.getOrCreate().put(a, 1);

        lifecycle.sessionChanged(null, loggedIn(user));

        assertThat(realStore.find()).hasValueSatisfying(cart -> assertThat(cart.quantityOf(a)).isEqualTo(1));
    }

    @Test
    void sessionChanged_toGuest_leavesTheDbCartAlone() {
        var user = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        data.createCart(user.id(), new long[]{a, 2});

        lifecycle.sessionChanged(loggedIn(user), null);

        assertThat(data.cartLines(user.id())).containsOnly(Map.entry(a, 2));
    }
}

package com.minishop.user;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDraft;
import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.ProductDraft;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.common.AccessDeniedException;
import com.minishop.order.OrderAdminService;
import com.minishop.storage.ImageUpload;
import com.minishop.support.AdminTestData;
import com.minishop.support.LogCapture;
import com.minishop.support.ResetAdminAccess;
import com.minishop.support.TestAdminAccess;
import com.minishop.support.TestImages;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-009-9, BR-009-3 on the real context and a real database: the admin services refuse a guest and a customer, and a
 * refused call changes nothing (row counts of the admin tables, the stock, the order status and the upload folder).
 * Hiding a menu entry is not a check; the services do it themselves.
 *
 * <p>The guest cases use the {@link AppSession} bean of the context (through {@link TestAdminAccess} in its default,
 * delegating mode). The customer and admin cases use a private {@code AppSession} (this test is in {@code user}, so
 * it may call the package-private {@code signIn}) with no listeners: nothing touches the JavaFX thread and the
 * shared session of the context stays a guest. These are the admin tests that do not fake the role check.
 */
@SpringBootTest
@ExtendWith(ResetAdminAccess.class)
class AdminAccessIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AppSession contextSession;

    @Autowired
    TestAdminAccess access;

    @Autowired
    CategoryAdminService categoryAdminService;

    @Autowired
    ProductAdminService productAdminService;

    @Autowired
    OrderAdminService orderAdminService;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

    AdminTestData data;
    Path uploadDir;
    long category;
    long product;
    long order;

    @BeforeEach
    void setUp() {
        access.reset();
        uploadDir = Path.of(uploadDirSetting).toAbsolutePath().normalize();
        data = new AdminTestData(jdbc, encoder, uploadDir);
        data.cleanUp();
        category = data.createCategory("Access");
        product = data.createProduct(category, "Access product", 100_000, 10, true);
        AdminTestData.TestUser customer = data.createUser(Role.USER);
        order = data.insertOrder(customer.id(), "PENDING", new long[]{product, 2});
    }

    @AfterEach
    void tearDown() {
        access.reset();
        data.cleanUp();
    }

    // ------------------------------------------------------------------ what is called

    private static ImageUpload image() {
        byte[] bytes = TestImages.png(300);
        return ImageUpload.from("a.png", "image/png", bytes.length, new ByteArrayResource(bytes)).orElseThrow();
    }

    private ProductDraft productDraft() {
        return new ProductDraft(AdminTestData.NAME_PREFIX + "refused " + UUID.randomUUID(), category,
                new BigDecimal("150000"), null);
    }

    /** Every write of the three services, with arguments that would succeed for an admin. */
    private Map<String, ThrowingCallable> writes() {
        Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
        calls.put("CategoryAdminService.create", () -> categoryAdminService.create(
                new CategoryDraft(AdminTestData.NAME_PREFIX + "refused " + UUID.randomUUID(), null)));
        calls.put("CategoryAdminService.update", () -> categoryAdminService.update(category,
                new CategoryDraft(AdminTestData.NAME_PREFIX + "renamed " + UUID.randomUUID(), "mô tả")));
        calls.put("CategoryAdminService.delete", () -> categoryAdminService.delete(category));
        calls.put("ProductAdminService.create", () -> productAdminService.create(productDraft(), 5, true, image()));
        calls.put("ProductAdminService.update", () -> productAdminService.update(product, productDraft(), image()));
        calls.put("ProductAdminService.setActive", () -> productAdminService.setActive(product, false));
        calls.put("ProductAdminService.adjustStock", () -> productAdminService.adjustStock(product, 5));
        calls.put("OrderAdminService.changeStatus", () -> orderAdminService.changeStatus(order, "CANCELLED"));
        return calls;
    }

    /** Every read of the three services. */
    private Map<String, ThrowingCallable> reads() {
        Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
        calls.put("CategoryAdminService.list", () -> categoryAdminService.list());
        calls.put("CategoryAdminService.get", () -> categoryAdminService.get(category));
        calls.put("CategoryAdminService.options", () -> categoryAdminService.options());
        calls.put("ProductAdminService.list", () -> productAdminService.list(null, null));
        calls.put("ProductAdminService.getForEdit", () -> productAdminService.getForEdit(product));
        calls.put("ProductAdminService.lowStock", () -> productAdminService.lowStock());
        calls.put("OrderAdminService.listOrders", () -> orderAdminService.listOrders(null, null));
        calls.put("OrderAdminService.getOrder", () -> orderAdminService.getOrder(order));
        calls.put("OrderAdminService.dashboard", () -> orderAdminService.dashboard());
        return calls;
    }

    // ------------------------------------------------------------------ what must not change

    private record Snapshot(int categories, int products, int orders, int stock, String productAndCategory,
                            String orderStatus, long uploadFiles) {
    }

    private Snapshot snapshot() {
        try {
            long files = Files.isDirectory(uploadDir) ? countFiles(uploadDir) : 0;
            return new Snapshot(
                    jdbc.queryForObject("select count(*) from categories", Integer.class),
                    jdbc.queryForObject("select count(*) from products", Integer.class),
                    jdbc.queryForObject("select count(*) from orders", Integer.class),
                    jdbc.queryForObject("select coalesce(sum(stock), 0) from products", Integer.class),
                    data.productRow(product) + data.categoryName(category),
                    data.orderStatus(order),
                    files);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long countFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private void assertEveryWriteRefusedAndNothingChanged() {
        Snapshot before = snapshot();

        writes().forEach((name, call) -> assertThatThrownBy(call).as(name).isInstanceOf(AccessDeniedException.class));

        assertThat(snapshot()).as("no row, stock, status or upload file changed").isEqualTo(before);
    }

    private void assertEveryReadRefused() {
        reads().forEach((name, call) -> assertThatThrownBy(call).as(name).isInstanceOf(AccessDeniedException.class));
    }

    private static AppSession signedIn(Role role, long id, String email, String fullName) {
        AppSession session = new AppSession(List::of);
        session.signIn(new CurrentUser(id, email, fullName, role));
        return session;
    }

    // ------------------------------------------------------------------ a guest (the session of the context)

    @Test
    void theSessionOfTheContext_startsAsAGuest() {
        assertThat(contextSession.isLoggedIn()).isFalse();
    }

    @Test
    void guest_everyWriteIsRefusedAndNothingChanges() {
        assertEveryWriteRefusedAndNothingChanged();
    }

    @Test
    void guest_everyReadIsRefused() {
        assertEveryReadRefused();
    }

    // ------------------------------------------------------------------ a customer

    @Test
    void customer_everyWriteIsRefusedAndNothingChanges() {
        AdminTestData.TestUser row = data.createUser(Role.USER);
        access.actThrough(signedIn(Role.USER, row.id(), row.email(), row.fullName()));

        assertEveryWriteRefusedAndNothingChanged();
    }

    @Test
    void customer_everyReadIsRefused() {
        AdminTestData.TestUser row = data.createUser(Role.USER);
        access.actThrough(signedIn(Role.USER, row.id(), row.email(), row.fullName()));

        assertEveryReadRefused();
    }

    // ------------------------------------------------------------------ an admin

    @Test
    void admin_withTheRealSession_canWriteAndTheLogLineHasThatAdminId() {
        AdminTestData.TestUser row = data.createUser(Role.ADMIN);
        access.actThrough(signedIn(Role.ADMIN, row.id(), row.email(), row.fullName()));

        try (LogCapture logs = LogCapture.of(LoggerFactory.getLogger(ProductAdminService.class))) {
            assertThat(productAdminService.adjustStock(product, 5))
                    .isEqualTo(new StockAdjustResult.Adjusted(data.productName(product), 15));

            assertThat(logs.events(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement().asString()
                    .startsWith("Admin action: admin=" + row.id() + ", product=" + product);
        }
        assertThat(data.stock(product)).isEqualTo(15);
    }

    @Test
    void admin_afterSignOut_isRefusedAgainAndNothingChanges() {
        AdminTestData.TestUser row = data.createUser(Role.ADMIN);
        AppSession session = signedIn(Role.ADMIN, row.id(), row.email(), row.fullName());
        access.actThrough(session);
        assertThat(productAdminService.setActive(product, false)).isEqualTo(data.productName(product));

        session.signOut();

        assertThatThrownBy(() -> productAdminService.setActive(product, true))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(data.productRow(product)).containsEntry("active", false);
    }
}

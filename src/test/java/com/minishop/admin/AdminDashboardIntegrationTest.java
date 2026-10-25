package com.minishop.admin;

import com.minishop.catalog.LowStockRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.common.VndFormatter;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderDashboard;
import com.minishop.order.StatusCount;
import com.minishop.support.AdminTestData;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard on a real PostgreSQL (AC-006-6, 7, 9). Other test classes also create orders in the shared
 * database, so the counts are compared as before/after deltas, never as absolute numbers (those are checked in
 * the slice test with a mocked service).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminDashboardIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    OrderAdminService orderAdminService;

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
        category = data.createCategory("Dashboard");
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private RequestPostProcessor loggedInAdmin() {
        return user(new CurrentUser(admin.id(), admin.email(), null, admin.fullName(), Role.ADMIN));
    }

    private static Map<String, Long> countsOf(OrderDashboard dashboard) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (StatusCount c : dashboard.counts()) {
            counts.put(c.status(), c.count());
        }
        return counts;
    }

    // ---------------------------------------------------------- AC-006-6

    @Test
    void dashboard_afterAddingOrdersOfEveryStatus_countsAndRevenueGrowByExactlyThoseOrders() throws Exception {
        OrderDashboard before = orderAdminService.dashboard();
        var customer = data.createUser(Role.USER);
        for (int i = 0; i < 3; i++) {
            data.insertOrderWithTotal(customer.id(), "PENDING", new BigDecimal("100000"));
        }
        data.insertOrderWithTotal(customer.id(), "CONFIRMED", new BigDecimal("100000"));
        data.insertOrderWithTotal(customer.id(), "DELIVERED", new BigDecimal("300000"));
        data.insertOrderWithTotal(customer.id(), "DELIVERED", new BigDecimal("150000"));
        data.insertOrderWithTotal(customer.id(), "CANCELLED", new BigDecimal("100000"));

        OrderDashboard after = orderAdminService.dashboard();

        Map<String, Long> was = countsOf(before);
        Map<String, Long> now = countsOf(after);
        assertThat(now).containsOnlyKeys("PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED");
        assertThat(now.get("PENDING") - was.get("PENDING")).isEqualTo(3);
        assertThat(now.get("CONFIRMED") - was.get("CONFIRMED")).isEqualTo(1);
        assertThat(now.get("SHIPPING") - was.get("SHIPPING")).isEqualTo(0);
        assertThat(now.get("DELIVERED") - was.get("DELIVERED")).isEqualTo(2);
        assertThat(now.get("CANCELLED") - was.get("CANCELLED")).isEqualTo(1);
        assertThat(after.deliveredRevenue().subtract(before.deliveredRevenue())).isEqualByComparingTo("450000");

        // The page renders what the service returned: every tile's number and the revenue.
        String html = mockMvc.perform(get("/admin").with(loggedInAdmin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (StatusCount c : after.counts()) {
            Matcher m = Pattern.compile("href=\"/admin/orders\\?status=" + c.status()
                    + "\"[^>]*>\\s*<span[^>]*>[^<]*</span>\\s*<span[^>]*>(\\d+)</span>").matcher(html);
            assertThat(m.find()).as("tile %s", c.status()).isTrue();
            assertThat(Long.parseLong(m.group(1))).as("tile %s", c.status()).isEqualTo(c.count());
        }
        assertThat(html).contains("Doanh thu (đơn đã giao)")
                .contains(new VndFormatter().format(after.deliveredRevenue()));
        assertThat(after.latest()).hasSizeLessThanOrEqualTo(5);
    }

    // ---------------------------------------------------------- AC-006-7

    @Test
    void lowStock_listsOnlyActiveProductsAtOrBelowFiveLeastFirst() throws Exception {
        long zero = data.createProduct(category, "stock 0", 100_000, 0, true);
        long three = data.createProduct(category, "stock 3", 100_000, 3, true);
        long five = data.createProduct(category, "stock 5", 100_000, 5, true);
        long six = data.createProduct(category, "stock 6", 100_000, 6, true);
        long hidden = data.createProduct(category, "hidden 1", 100_000, 1, false);

        List<LowStockRow> rows = productAdminService.lowStock();

        assertThat(rows).hasSizeLessThanOrEqualTo(10);
        List<Long> mine = rows.stream().map(LowStockRow::id)
                .filter(id -> List.of(zero, three, five, six, hidden).contains(id)).toList();
        assertThat(mine).containsExactly(zero, three, five);
        assertThat(rows).allMatch(r -> r.stock() <= 5);
        assertThat(rows).extracting(LowStockRow::stock).isSorted();

        String html = mockMvc.perform(get("/admin").with(loggedInAdmin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (long id : List.of(zero, three, five)) {
            assertThat(html).contains("href=\"/admin/products/" + id + "/edit\"");
        }
        assertThat(html).doesNotContain("/admin/products/" + six + "/edit")
                .doesNotContain("/admin/products/" + hidden + "/edit");
    }

    // ---------------------------------------------------------- AC-006-9

    @Test
    void dashboardAndLowStock_issueFourStatementsWhetherThereIsOneOrderOrThirty() {
        var customers = List.of(data.createUser(Role.USER), data.createUser(Role.USER), data.createUser(Role.USER));
        long product = data.createProduct(category, "Few", 100_000, 2, true);
        data.insertOrderWithTotal(customers.get(0).id(), "PENDING", new BigDecimal("100000"));

        long withOne = AdminTestData.statementsOf(entityManagerFactory, this::loadDashboard);

        for (int i = 0; i < 29; i++) {
            data.insertOrder(customers.get(i % 3).id(), i % 2 == 0 ? "PENDING" : "DELIVERED", new long[]{product, 1});
            data.createProduct(category, "Low " + i, 100_000, i % 6, true);
        }
        long withThirty = AdminTestData.statementsOf(entityManagerFactory, this::loadDashboard);

        // counts by status, delivered revenue, latest 5 with the customer joined, low stock.
        assertThat(withOne).isEqualTo(4);
        assertThat(withThirty).isEqualTo(withOne);
    }

    private void loadDashboard() {
        OrderDashboard dashboard = orderAdminService.dashboard();
        assertThat(dashboard.latest()).isNotEmpty().allMatch(o -> o.customerEmail() != null);
        productAdminService.lowStock();
    }
}

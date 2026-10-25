package com.minishop.admin;

import com.minishop.common.VndFormatter;
import com.minishop.order.OrderAdminService;
import com.minishop.support.AdminTestData;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-006-52, the main flow of the brief on PostgreSQL: a customer orders, the admin opens the dashboard, clicks the
 * "Chờ xử lý" tile, opens the order and walks it to "Đã giao"; the customer then sees "Đã giao" and the revenue
 * on the dashboard grows by exactly the order total. Everything goes through the real controllers and filters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    OrderAdminService orderAdminService;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

    AdminTestData data;

    @BeforeEach
    void setUp() {
        data = new AdminTestData(jdbc, encoder, Path.of(uploadDirSetting).toAbsolutePath().normalize());
        data.cleanUp();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private static RequestPostProcessor principal(AdminTestData.TestUser u, Role role) {
        return user(new CurrentUser(u.id(), u.email(), null, u.fullName(), role));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void customerOrders_adminWalksOrderToDelivered_customerSeesDeliveredAndRevenueGrows() throws Exception {
        var adminUser = data.createUser(Role.ADMIN);
        var customerUser = data.createUser(Role.USER);
        var admin = principal(adminUser, Role.ADMIN);
        var customer = principal(customerUser, Role.USER);
        long category = data.createCategory("Flow");
        long product = data.createProduct(category, "Flow", 100_000, 10, true);
        BigDecimal revenueBefore = orderAdminService.dashboard().deliveredRevenue();

        // 1. The customer puts 2 in the cart and checks out.
        mockMvc.perform(post("/cart/items").with(customer).with(csrf())
                        .param("productId", String.valueOf(product)).param("quantity", "2"))
                .andExpect(status().is3xxRedirection());
        String location = mockMvc.perform(post("/checkout").with(customer).with(csrf())
                        .param("receiverName", "Nguyễn Văn A").param("phone", "0912 345 678")
                        .param("shippingAddress", "12 Lê Lợi").param("expectedTotal", "200000"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        long orderId = Long.parseLong(location.substring("/orders/".length()));
        assertThat(data.stock(product)).isEqualTo(8);

        // 2. The admin opens the dashboard: the "Chờ xử lý" tile links to the filtered list.
        String dashboard = body(mockMvc.perform(get("/admin").with(admin)).andExpect(status().isOk()).andReturn());
        assertThat(dashboard).contains("href=\"/admin/orders?status=PENDING\"").contains("Chờ xử lý");

        // 3. The list filtered by PENDING has the order; open it.
        String list = body(mockMvc.perform(get("/admin/orders?status=PENDING").with(admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).contains("href=\"/admin/orders/" + orderId + "\"").contains(customerUser.email());
        String detail = body(mockMvc.perform(get("/admin/orders/" + orderId).with(admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail).contains("Xác nhận đơn").contains("Hủy đơn").doesNotContain("Bắt đầu giao hàng");

        // 4. Confirm, ship, deliver; each step shows only the next buttons.
        MvcResult confirmed = mockMvc.perform(post("/admin/orders/" + orderId + "/status").with(admin).with(csrf())
                        .param("status", "CONFIRMED"))
                .andExpect(redirectedUrl("/admin/orders/" + orderId))
                .andExpect(flash().attribute("notice",
                        "Đã chuyển đơn hàng #" + orderId + " sang trạng thái Đã xác nhận."))
                .andReturn();
        detail = body(mockMvc.perform(get("/admin/orders/" + orderId).with(admin)
                        .flashAttrs(confirmed.getFlashMap())).andExpect(status().isOk()).andReturn());
        assertThat(detail).contains("Đã chuyển đơn hàng #" + orderId + " sang trạng thái Đã xác nhận.")
                .contains("Bắt đầu giao hàng").doesNotContain("Xác nhận đã giao");

        mockMvc.perform(post("/admin/orders/" + orderId + "/status").with(admin).with(csrf())
                        .param("status", "SHIPPING"))
                .andExpect(flash().attribute("notice",
                        "Đã chuyển đơn hàng #" + orderId + " sang trạng thái Đang giao."));
        detail = body(mockMvc.perform(get("/admin/orders/" + orderId).with(admin)).andExpect(status().isOk()).andReturn());
        assertThat(detail).contains("Xác nhận đã giao").doesNotContain("Hủy đơn</button>");

        mockMvc.perform(post("/admin/orders/" + orderId + "/status").with(admin).with(csrf())
                        .param("status", "DELIVERED"))
                .andExpect(flash().attribute("notice",
                        "Đã chuyển đơn hàng #" + orderId + " sang trạng thái Đã giao."));
        detail = body(mockMvc.perform(get("/admin/orders/" + orderId).with(admin)).andExpect(status().isOk()).andReturn());
        assertThat(detail).contains("Đơn hàng đã ở trạng thái cuối.");
        assertThat(data.orderStatus(orderId)).isEqualTo("DELIVERED");
        assertThat(data.stock(product)).isEqualTo(8);

        // 5. The customer sees "Đã giao"; the dashboard revenue grew by exactly the order total.
        String orders = body(mockMvc.perform(get("/orders").with(customer)).andExpect(status().isOk()).andReturn());
        assertThat(orders).contains("href=\"/orders/" + orderId + "\"").contains("Đã giao");
        BigDecimal revenueAfter = orderAdminService.dashboard().deliveredRevenue();
        assertThat(revenueAfter.subtract(revenueBefore)).isEqualByComparingTo("200000");
        String dashboardAfter = body(mockMvc.perform(get("/admin").with(admin)).andExpect(status().isOk()).andReturn());
        assertThat(dashboardAfter).contains(new VndFormatter().format(revenueAfter));
    }
}

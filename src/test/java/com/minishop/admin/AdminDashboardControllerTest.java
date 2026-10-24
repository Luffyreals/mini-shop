package com.minishop.admin;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.catalog.LowStockRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.config.SecurityConfig;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderDashboard;
import com.minishop.order.StatusCount;
import com.minishop.user.Role;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.minishop.admin.AdminPageAssertions.assertLayout;
import static com.minishop.admin.AdminPageAssertions.main;
import static com.minishop.admin.AdminPageAssertions.matches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The dashboard page with mocked services (AC-006-4, 6, 7, 8): absolute numbers here, because the integration test
 * can only compare deltas on the shared database.
 */
@WebMvcTest(AdminDashboardController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AdminDashboardControllerTest {

    private static final String EVIL = "<script>alert(1)</script>";
    private static final String EVIL_ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;";

    /** 2026-10-24T17:30:00Z is 00:30 on 25/10 in Vietnam. */
    private static final Instant CREATED = Instant.parse("2026-10-24T17:30:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderAdminService orderAdminService;

    @MockitoBean
    ProductAdminService productAdminService;

    @BeforeEach
    void stubDefaults() {
        when(orderAdminService.dashboard()).thenReturn(dashboard(List.of(row(7, "Nguyễn Văn A", "a@example.com",
                "Chờ xử lý", "pending"))));
        when(productAdminService.lowStock()).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- helpers

    private static AdminOrderRow row(long id, String customer, String email, String statusLabel, String statusKey) {
        return new AdminOrderRow(id, CREATED, customer, email, "Người nhận " + id, new BigDecimal("450000"),
                statusLabel, statusKey);
    }

    private static OrderDashboard dashboard(List<AdminOrderRow> latest) {
        return new OrderDashboard(List.of(
                new StatusCount("PENDING", "Chờ xử lý", 3), new StatusCount("CONFIRMED", "Đã xác nhận", 1),
                new StatusCount("SHIPPING", "Đang giao", 0), new StatusCount("DELIVERED", "Đã giao", 2),
                new StatusCount("CANCELLED", "Đã hủy", 1)), new BigDecimal("450000"), latest);
    }

    private String page() throws Exception {
        return mockMvc.perform(get("/admin").with(user(TestUsers.admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"))
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------ AC-006-4

    @Test
    void index_usesTheAdminLayoutWithTitleH1AndSidebar() throws Exception {
        assertLayout(page(), "Tổng quan | Quản trị Mini Shop", "Tổng quan", "dashboard");
    }

    @Test
    void index_adminNameWithScript_isEscapedInGreeting() throws Exception {
        String html = mockMvc.perform(get("/admin").with(user(TestUsers.withName(EVIL, Role.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Xin chào, " + EVIL_ESCAPED).doesNotContain(EVIL);
    }

    // ------------------------------------------------------------ AC-006-6

    @Test
    void index_rendersFiveTilesInOrderWithLabelNumberAndFilterLink() throws Exception {
        when(orderAdminService.dashboard()).thenReturn(dashboard(List.of()));
        String html = page();

        Matcher m = Pattern.compile("<a[^>]*href=\"/admin/orders\\?status=([A-Z]+)\"[^>]*>\\s*"
                + "<span[^>]*>([^<]*)</span>\\s*<span[^>]*>(\\d+)</span>").matcher(html);
        List<String> tiles = new java.util.ArrayList<>();
        while (m.find()) {
            tiles.add(m.group(1) + "|" + m.group(2) + "|" + m.group(3));
        }
        assertThat(tiles).containsExactly("PENDING|Chờ xử lý|3", "CONFIRMED|Đã xác nhận|1", "SHIPPING|Đang giao|0",
                "DELIVERED|Đã giao|2", "CANCELLED|Đã hủy|1");
    }

    @Test
    void index_showsDeliveredRevenueAsOneFigure() throws Exception {
        String html = page();

        assertThat(matches("Doanh thu \\(đơn đã giao\\).*?450\\.000 ₫", html)).isTrue();
        assertThat(html).containsOnlyOnce("Doanh thu (đơn đã giao)");
    }

    @Test
    void index_hasNoChart() throws Exception {
        assertThat(page()).doesNotContain("<canvas").doesNotContain("<svg");
    }

    @Test
    void index_hasLinkToAllOrders() throws Exception {
        assertThat(matches("<a[^>]*href=\"/admin/orders\"[^>]*>\\s*Xem tất cả đơn hàng\\s*</a>", page())).isTrue();
    }

    @Test
    void index_callsEachServiceOnce_twoReadOnlyTransactions() throws Exception {
        page();

        verify(orderAdminService).dashboard();
        verify(productAdminService).lowStock();
    }

    // ------------------------------------------------------------ AC-006-7

    @Test
    void index_withLowStock_listsThreeRowsLeastFirstLinkedToEditPage() throws Exception {
        when(productAdminService.lowStock()).thenReturn(List.of(
                new LowStockRow(11, "Loa mini", 0), new LowStockRow(12, "Bàn phím", 3), new LowStockRow(13, "Chuột", 5)));
        String html = page();

        Matcher m = Pattern.compile("<a[^>]*href=\"/admin/products/(\\d+)/edit\"[^>]*>([^<]*)</a>\\s*"
                + "<span[^>]*>([^<]*)</span>").matcher(html);
        List<String> rows = new java.util.ArrayList<>();
        while (m.find()) {
            rows.add(m.group(1) + "|" + m.group(2) + "|" + m.group(3));
        }
        assertThat(rows).containsExactly("11|Loa mini|Tồn kho: 0", "12|Bàn phím|Tồn kho: 3", "13|Chuột|Tồn kho: 5");
        assertThat(html).doesNotContain("Không có sản phẩm nào sắp hết hàng.");
        assertThat(matches("<h2[^>]*>\\s*Sắp hết hàng\\s*</h2>", html)).isTrue();
    }

    @Test
    void index_withoutLowStock_showsEmptyMessageAndNoList() throws Exception {
        String html = page();

        assertThat(html).contains("Không có sản phẩm nào sắp hết hàng.").doesNotContain("admin-stocklist");
    }

    // ------------------------------------------------------------ AC-006-8

    @Test
    void index_withFiveLatestOrders_rendersTableWithLinksDateCustomerTotalAndStatus() throws Exception {
        when(orderAdminService.dashboard()).thenReturn(dashboard(List.of(
                row(21, "An", "an@example.com", "Chờ xử lý", "pending"),
                row(20, "Bình", "binh@example.com", "Đã xác nhận", "confirmed"),
                row(19, "Chi", "chi@example.com", "Đang giao", "shipping"),
                row(18, "Dung", "dung@example.com", "Đã giao", "delivered"),
                row(17, "Em", "em@example.com", "Đã hủy", "cancelled"))));
        String html = page();

        assertThat(matches("<h2[^>]*>\\s*Đơn hàng mới nhất\\s*</h2>", html)).isTrue();
        for (String column : List.of("Mã đơn", "Ngày đặt", "Khách hàng", "Tổng tiền", "Trạng thái")) {
            assertThat(matches("<th scope=\"col\"[^>]*>\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        for (int id = 17; id <= 21; id++) {
            assertThat(matches("<a[^>]*href=\"/admin/orders/" + id + "\"[^>]*>\\s*#" + id + "\\s*</a>", html))
                    .as("#%d", id).isTrue();
        }
        assertThat(html.indexOf("#21")).isLessThan(html.indexOf("#17"));
        assertThat(html).contains("25/10/2026 00:30").contains("450.000 ₫").contains("an@example.com");
        assertThat(matches("<span[^>]*class=\"order-status order-status--pending\"[^>]*>\\s*Chờ xử lý\\s*</span>",
                html)).isTrue();
        assertThat(matches("<span[^>]*class=\"order-status order-status--cancelled\"[^>]*>\\s*Đã hủy\\s*</span>",
                html)).isTrue();
        assertThat(html).doesNotContain("Chưa có đơn hàng nào.");
    }

    @Test
    void index_withoutOrders_showsEmptyMessageAndNoTable() throws Exception {
        when(orderAdminService.dashboard()).thenReturn(dashboard(List.of()));
        String html = page();

        assertThat(html).contains("Chưa có đơn hàng nào.");
        assertThat(main(html)).doesNotContain("<table");
    }

    // ---------------------------------------------------------- escaping

    @Test
    void index_customerNameAndProductNameWithScript_areEscaped() throws Exception {
        when(orderAdminService.dashboard()).thenReturn(dashboard(List.of(row(5, EVIL, "x@example.com", "Chờ xử lý",
                "pending"))));
        when(productAdminService.lowStock()).thenReturn(List.of(new LowStockRow(9, EVIL, 1)));
        String html = page();

        assertThat(html).doesNotContain(EVIL);
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).isEqualTo(2);
    }

    @Test
    void index_withFlashNotice_showsItInsideMain() throws Exception {
        mockMvc.perform(get("/admin").with(user(TestUsers.admin()))
                        .flashAttr("notice", "Xin chào").flashAttr("noticeKind", "info"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"status\"")))
                .andExpect(model().attributeExists("orderStats", "lowStock"));
    }
}

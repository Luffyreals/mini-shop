package com.minishop.order;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.common.NotFoundException;
import com.minishop.config.SecurityConfig;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tầng web của lịch sử đơn, chi tiết đơn và hủy đơn, {@code OrderService} được mock
 * (AC-005-19..24, 27, 28, 32, 33).
 */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class OrderControllerTest {

    private static final String NOT_FOUND_TITLE = "<title>Không tìm thấy đơn hàng | Mini Shop</title>";
    private static final String EVIL = "<script>alert(1)</script>";
    private static final String EVIL_ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;";

    /** 2026-10-24T17:30:00Z là 00:30 ngày 25/10 giờ Việt Nam. */
    private static final Instant CREATED = Instant.parse("2026-10-24T17:30:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    // ---------- helpers ----------

    private static RequestPostProcessor loggedInUser() {
        return user(TestUsers.user());
    }

    private static OrderSummaryView summary(long id, String total, OrderStatus status) {
        return new OrderSummaryView(id, CREATED, new BigDecimal(total), status.label(),
                status.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static OrderDetailView detail(long id, OrderStatus status, String note, boolean cancellable,
                                          String address, String productName) {
        return new OrderDetailView(id, CREATED, status.label(), status.name().toLowerCase(java.util.Locale.ROOT),
                PaymentMethod.COD.label(), "Nguyễn Văn A", "0912345678", address, note,
                List.of(new OrderLineView(productName, new BigDecimal("100000"), 2, new BigDecimal("200000")),
                        new OrderLineView("Quần jean", new BigDecimal("250000"), 1, new BigDecimal("250000"))),
                new BigDecimal("450000"), cancellable);
    }

    private static OrderDetailView pendingDetail(long id) {
        return detail(id, OrderStatus.PENDING, "Giao giờ hành chính", true, "12 Lê Lợi\nQuận 1", "Áo thun");
    }

    private static boolean matches(String regex, String html) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(html).find();
    }

    private static String main(String html) {
        int start = html.indexOf("<main");
        return html.substring(start, html.indexOf("</main>", start));
    }

    private static String header(String html) {
        int start = html.indexOf("<header");
        return html.substring(start, html.indexOf("</header>", start));
    }

    private String getHtml(String url, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get(url).with(who)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- AC-005-20: danh sách ----------

    @Test
    void list_withOrders_rendersTableWithLinksVietnamTimeMoneyAndStatus() throws Exception {
        when(orderService.listOrders(eq(2L), isNull())).thenReturn(new OrderListView(
                List.of(summary(12, "450000", OrderStatus.PENDING), summary(11, "1250000", OrderStatus.CANCELLED),
                        summary(10, "100000", OrderStatus.DELIVERED)), 1, 1, 3));

        String html = mockMvc.perform(get("/orders").with(loggedInUser()))
                .andExpect(status().isOk())
                .andExpect(view().name("order/list"))
                .andExpect(model().attributeExists("orders", "pageNav"))
                .andExpect(content().string(containsString("<title>Đơn hàng của tôi | Mini Shop</title>")))
                .andReturn().getResponse().getContentAsString();

        assertThat(matches("<h1[^>]*>\\s*Đơn hàng của tôi\\s*</h1>", html)).isTrue();
        for (String column : List.of("Mã đơn", "Ngày đặt", "Tổng tiền", "Trạng thái")) {
            assertThat(matches("<th scope=\"col\">\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        assertThat(matches("<a[^>]*href=\"/orders/12\"[^>]*>\\s*#12\\s*</a>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/orders/11\"[^>]*>\\s*#11\\s*</a>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/orders/10\"[^>]*>\\s*#10\\s*</a>", html)).isTrue();
        assertThat(html).contains("25/10/2026 00:30").contains("450.000 ₫").contains("1.250.000 ₫")
                .contains("100.000 ₫");
        assertThat(matches("<span[^>]*class=\"order-status order-status--pending\"[^>]*>\\s*Chờ xử lý\\s*</span>",
                html)).isTrue();
        assertThat(html).contains("Đã hủy").contains("Đã giao");
        assertThat(html).doesNotContain("Bạn chưa có đơn hàng nào.");
        // Mới nhất trước: thứ tự của service được giữ nguyên.
        assertThat(html.indexOf("#12")).isLessThan(html.indexOf("#11"));
        assertThat(html.indexOf("#11")).isLessThan(html.indexOf("#10"));
    }

    // ---------- AC-005-21: phân trang ----------

    @Test
    void list_onMiddlePage_rendersPaginationLinksAndPassesRawPageToService() throws Exception {
        when(orderService.listOrders(eq(2L), eq("2"))).thenReturn(new OrderListView(
                List.of(summary(5, "100000", OrderStatus.PENDING)), 2, 3, 25));

        String html = mockMvc.perform(get("/orders?page=2").with(loggedInUser()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("href=\"/orders?page=1\"").contains("href=\"/orders?page=3\"");
        verify(orderService).listOrders(2L, "2");
    }

    @Test
    void list_withBadPageValue_isPassedAsRawStringToService() throws Exception {
        when(orderService.listOrders(anyLong(), anyString())).thenReturn(new OrderListView(
                List.of(summary(5, "100000", OrderStatus.PENDING)), 1, 1, 1));

        mockMvc.perform(get("/orders?page=abc").with(loggedInUser())).andExpect(status().isOk());

        verify(orderService).listOrders(2L, "abc");
    }

    // ---------- AC-005-22: chưa có đơn ----------

    @Test
    void list_whenNoOrders_showsEmptyStateWithoutTable() throws Exception {
        when(orderService.listOrders(eq(2L), isNull())).thenReturn(new OrderListView(List.of(), 1, 0, 0));

        String html = getHtml("/orders", loggedInUser());

        assertThat(html).contains("Bạn chưa có đơn hàng nào.");
        assertThat(matches("<a[^>]*href=\"/products\"[^>]*>\\s*Bắt đầu mua sắm\\s*</a>", main(html))).isTrue();
        assertThat(main(html)).doesNotContain("<table");
    }

    // ---------- AC-005-23: chi tiết ----------

    @Test
    void detail_rendersAllSnapshotInformationAndConfirmScript() throws Exception {
        when(orderService.getOrder(2L, 12L)).thenReturn(pendingDetail(12));

        String html = mockMvc.perform(get("/orders/12").with(loggedInUser()))
                .andExpect(status().isOk())
                .andExpect(view().name("order/detail"))
                .andExpect(model().attributeExists("order"))
                .andExpect(content().string(containsString("<title>Đơn hàng #12 | Mini Shop</title>")))
                .andReturn().getResponse().getContentAsString();

        assertThat(matches("<h1[^>]*>\\s*Đơn hàng #12\\s*</h1>", html)).isTrue();
        assertThat(html).contains("Ngày đặt").contains("25/10/2026 00:30").contains("Trạng thái").contains("Chờ xử lý")
                .contains("Phương thức thanh toán").contains("Thanh toán khi nhận hàng (COD)")
                .contains("Thông tin nhận hàng").contains("Họ tên người nhận").contains("Nguyễn Văn A")
                .contains("Số điện thoại").contains("0912345678")
                .contains("Địa chỉ giao hàng").contains("12 Lê Lợi\nQuận 1")
                .contains("Ghi chú").contains("Giao giờ hành chính");
        for (String column : List.of("Sản phẩm", "Đơn giá", "Số lượng", "Thành tiền")) {
            assertThat(matches("<th scope=\"col\">\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        assertThat(matches("<caption class=\"visually-hidden\">", html)).isTrue();
        assertThat(html).contains("Áo thun").contains("Quần jean").contains("100.000 ₫").contains("200.000 ₫")
                .contains("250.000 ₫").contains("Tổng cộng").contains("450.000 ₫");
        assertThat(matches("<a[^>]*href=\"/orders\"[^>]*>\\s*Quay lại danh sách đơn\\s*</a>", main(html))).isTrue();
        assertThat(html).contains("<script src=\"/js/confirm.js\"");
    }

    @Test
    void detail_whenNoteIsNull_hasNoNoteSection() throws Exception {
        when(orderService.getOrder(2L, 12L)).thenReturn(
                detail(12, OrderStatus.PENDING, null, true, "12 Lê Lợi", "Áo thun"));

        String html = getHtml("/orders/12", loggedInUser());

        assertThat(main(html)).doesNotContain("<dt>Ghi chú</dt>");
        assertThat(main(html)).contains("<dt>Địa chỉ giao hàng</dt>");
    }

    // ---------- AC-005-27, 28: nút hủy ----------

    @Test
    void detail_whenCancellable_hasCancelFormWithCsrfAndConfirmText() throws Exception {
        when(orderService.getOrder(2L, 12L)).thenReturn(pendingDetail(12));

        String html = getHtml("/orders/12", loggedInUser());

        String form = Pattern.compile("<form[^>]*action=\"/orders/12/cancel\"[^>]*>.*?</form>", Pattern.DOTALL)
                .matcher(html).results().map(java.util.regex.MatchResult::group).findFirst().orElseThrow();
        assertThat(form).contains("method=\"post\"").contains("name=\"_csrf\"")
                .contains("data-confirm=\"Bạn có chắc muốn hủy đơn hàng này?\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Hủy đơn hàng\\s*</button>", form)).isTrue();
    }

    @Test
    void detail_whenNotCancellable_hasNoCancelFormOrText() throws Exception {
        when(orderService.getOrder(2L, 12L)).thenReturn(
                detail(12, OrderStatus.CONFIRMED, null, false, "12 Lê Lợi", "Áo thun"));

        String html = getHtml("/orders/12", loggedInUser());

        assertThat(html).doesNotContain("Hủy đơn hàng").doesNotContain("/orders/12/cancel")
                .doesNotContain("data-confirm");
        assertThat(main(html)).doesNotContain("<form");
        assertThat(html).contains("Đã xác nhận");
    }

    // ---------- AC-005-24: 404 ----------

    @ParameterizedTest(name = "USER and ADMIN: service NotFound -> 404 ({0})")
    @ValueSource(strings = {"user", "admin"})
    void detail_whenServiceThrowsNotFound_rendersNotFoundPage(String who) throws Exception {
        RequestPostProcessor principal = who.equals("user") ? loggedInUser() : user(TestUsers.admin());
        long id = who.equals("user") ? 2L : 1L;
        when(orderService.getOrder(id, 77L)).thenThrow(new NotFoundException("Order not found: 77"));

        String html = mockMvc.perform(get("/orders/77").with(principal))
                .andExpect(status().isNotFound())
                .andExpect(view().name("order/not-found"))
                .andExpect(content().string(containsString(NOT_FOUND_TITLE)))
                .andReturn().getResponse().getContentAsString();

        assertThat(matches("<h1[^>]*>\\s*Không tìm thấy đơn hàng\\s*</h1>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/orders\"", main(html))).isTrue();
    }

    @ParameterizedTest(name = "GET /orders/{0} -> 404 without calling the service")
    @ValueSource(strings = {"abc", "99999999999999999999", "-1", "1.5"})
    void detail_withBadId_is404AndServiceNotCalled(String id) throws Exception {
        mockMvc.perform(get("/orders/" + id).with(loggedInUser()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("order/not-found"))
                .andExpect(content().string(containsString(NOT_FOUND_TITLE)));

        verifyNoInteractions(orderService);
    }

    @Test
    void cancel_withBadId_is404AndServiceNotCalled() throws Exception {
        mockMvc.perform(post("/orders/abc/cancel").with(loggedInUser()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("order/not-found"));
        mockMvc.perform(post("/orders/99999999999999999999/cancel").with(loggedInUser()).with(csrf()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(orderService);
    }

    // ---------- AC-005-27, 28, 24: hủy ----------

    @Test
    void cancel_whenCancelled_redirectsToDetailWithSuccess() throws Exception {
        when(orderService.cancelByCustomer(2L, 12L)).thenReturn(CancelResult.CANCELLED);

        mockMvc.perform(post("/orders/12/cancel").with(loggedInUser()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/12"))
                .andExpect(flash().attribute("notice", "Đã hủy đơn hàng #12."))
                .andExpect(flash().attribute("noticeKind", "success"));
    }

    @Test
    void cancel_whenNotCancellable_redirectsToDetailWithError() throws Exception {
        when(orderService.cancelByCustomer(2L, 12L)).thenReturn(CancelResult.NOT_CANCELLABLE);

        mockMvc.perform(post("/orders/12/cancel").with(loggedInUser()).with(csrf()))
                .andExpect(redirectedUrl("/orders/12"))
                .andExpect(flash().attribute("notice", "Đơn hàng này không thể hủy nữa."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @Test
    void cancel_whenNotFound_is404() throws Exception {
        when(orderService.cancelByCustomer(2L, 12L)).thenThrow(new NotFoundException("Order not found: 12"));

        mockMvc.perform(post("/orders/12/cancel").with(loggedInUser()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("order/not-found"));
    }

    @Test
    void cancel_ignoresRefererWhenRedirecting() throws Exception {
        when(orderService.cancelByCustomer(2L, 12L)).thenReturn(CancelResult.CANCELLED);

        mockMvc.perform(post("/orders/12/cancel").with(loggedInUser()).with(csrf())
                        .header("Referer", "https://evil.example/"))
                .andExpect(redirectedUrl("/orders/12"));
    }

    @Test
    void cancel_withoutCsrf_isForbiddenAndServiceNotCalled() throws Exception {
        mockMvc.perform(post("/orders/12/cancel").with(loggedInUser())).andExpect(status().isForbidden());

        verify(orderService, never()).cancelByCustomer(anyLong(), anyLong());
    }

    // ---------- flash notice hiển thị ----------

    @Test
    void detail_withFlashNotice_showsItWithRoleForItsKind() throws Exception {
        when(orderService.getOrder(2L, 12L)).thenReturn(pendingDetail(12));

        String html = mockMvc.perform(get("/orders/12").with(loggedInUser())
                        .flashAttr("notice", "Đơn hàng này không thể hủy nữa.").flashAttr("noticeKind", "error"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Đơn hàng này không thể hủy nữa.").contains("role=\"alert\"");
    }

    // ---------- AC-005-32: header ----------

    @ParameterizedTest(name = "header link for {0}")
    @ValueSource(strings = {"user", "admin"})
    void header_forLoggedInUsers_hasOrdersLink(String who) throws Exception {
        RequestPostProcessor principal = who.equals("user") ? loggedInUser() : user(TestUsers.admin());
        long id = who.equals("user") ? 2L : 1L;
        when(orderService.listOrders(eq(id), isNull())).thenReturn(new OrderListView(List.of(), 1, 0, 0));

        String html = getHtml("/orders", principal);

        assertThat(matches("<a[^>]*href=\"/orders\"[^>]*>\\s*Đơn hàng của tôi\\s*</a>", header(html))).isTrue();
    }

    // ---------- AC-005-33: escape ----------

    @Test
    void detail_addressNoteAndProductNameWithScript_areEscaped() throws Exception {
        when(orderService.getOrder(2L, 12L)).thenReturn(detail(12, OrderStatus.PENDING, EVIL, true, EVIL, EVIL));

        String html = getHtml("/orders/12", loggedInUser());

        assertThat(html).doesNotContain(EVIL);
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).isEqualTo(3);
    }
}

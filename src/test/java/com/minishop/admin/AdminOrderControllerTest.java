package com.minishop.admin;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.common.NotFoundException;
import com.minishop.config.SecurityConfig;
import com.minishop.order.AdminOrderDetail;
import com.minishop.order.AdminOrderPage;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderLineView;
import com.minishop.order.StatusAction;
import com.minishop.order.StatusChangeResult;
import com.minishop.order.StatusFilterOption;
import com.minishop.user.Role;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.minishop.admin.AdminPageAssertions.assertLayout;
import static com.minishop.admin.AdminPageAssertions.formsWithAction;
import static com.minishop.admin.AdminPageAssertions.main;
import static com.minishop.admin.AdminPageAssertions.matches;
import static com.minishop.admin.AdminPageAssertions.unescape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Order pages with a mocked {@code OrderAdminService} (AC-006-4, 5, 38..43, 45, 49): list with filter links and
 * pagination, the detail page, the buttons exactly as the service lists them, the four outcomes of a status change
 * as flash messages, and the admin 404. The page never decides which step is allowed: it only loops over
 * {@code actions}.
 */
@WebMvcTest(AdminOrderController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AdminOrderControllerTest {

    private static final String EVIL = "<script>alert(1)</script>";
    private static final String EVIL_ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;";
    private static final String NOT_FOUND_TITLE = "Không tìm thấy đơn hàng";

    /** 2026-10-24T17:30:00Z is 00:30 on 25/10 in Vietnam. */
    private static final Instant CREATED = Instant.parse("2026-10-24T17:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-25T03:05:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderAdminService orderService;

    @BeforeEach
    void stubList() {
        when(orderService.listOrders(any(), any())).thenReturn(listPage(List.of(), null, "Tất cả", 1, 0, 0));
    }

    // ---------------------------------------------------------------- helpers

    private static RequestPostProcessor admin() {
        return user(TestUsers.admin());
    }

    private static AdminOrderRow row(long id, String customer, String email, String receiver, String label, String key) {
        return new AdminOrderRow(id, CREATED, customer, email, receiver, new BigDecimal("450000"), label, key);
    }

    private static List<StatusFilterOption> filters(String selected) {
        List<StatusFilterOption> options = new ArrayList<>();
        options.add(new StatusFilterOption(null, "Tất cả", selected == null));
        String[][] all = {{"PENDING", "Chờ xử lý"}, {"CONFIRMED", "Đã xác nhận"}, {"SHIPPING", "Đang giao"},
                {"DELIVERED", "Đã giao"}, {"CANCELLED", "Đã hủy"}};
        for (String[] s : all) {
            options.add(new StatusFilterOption(s[0], s[1], s[0].equals(selected)));
        }
        return options;
    }

    private static AdminOrderPage listPage(List<AdminOrderRow> rows, String status, String statusLabel, int page,
                                           int totalPages, long total) {
        return new AdminOrderPage(rows, status, statusLabel, filters(status), page, totalPages, total);
    }

    private static StatusAction action(long orderId, String target, String label) {
        String confirm = switch (target) {
            case "CANCELLED" -> "Hủy đơn hàng #" + orderId + " và hoàn lại tồn kho?";
            case "DELIVERED" -> "Đánh dấu đơn hàng #" + orderId + " đã giao? Không thể hoàn tác.";
            default -> null;
        };
        return new StatusAction(target, label, confirm, target.equals("CANCELLED"));
    }

    private static AdminOrderDetail detail(long id, String statusLabel, String statusKey, String note,
                                           List<StatusAction> actions) {
        return new AdminOrderDetail(id, CREATED, UPDATED, statusLabel, statusKey, "Thanh toán khi nhận hàng (COD)",
                "Trần Thị B", "khach@example.com", "Nguyễn Văn A", "0912345678", "12 Lê Lợi\nQuận 1", note,
                List.of(new OrderLineView("Áo thun", new BigDecimal("100000"), 2, new BigDecimal("200000")),
                        new OrderLineView("Quần jean", new BigDecimal("250000"), 1, new BigDecimal("250000"))),
                new BigDecimal("450000"), actions);
    }

    private static AdminOrderDetail pending(long id) {
        return detail(id, "Chờ xử lý", "pending", "Giao giờ hành chính", List.of(
                action(id, "CONFIRMED", "Xác nhận đơn"), action(id, "CANCELLED", "Hủy đơn")));
    }

    private String html(String url) throws Exception {
        return mockMvc.perform(get(url).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ============================================================= LIST (AC-006-38, 39)

    @Test
    void list_rendersLayoutColumnsAndEveryField() throws Exception {
        when(orderService.listOrders(any(), any())).thenReturn(listPage(List.of(
                row(12, "Trần Thị B", "khach@example.com", "Nguyễn Văn A", "Chờ xử lý", "pending"),
                row(11, "Lê Văn C", "c@example.com", "Lê Văn D", "Đã hủy", "cancelled")), null, "Tất cả", 1, 1, 2));

        String html = mockMvc.perform(get("/admin/orders").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/order-list"))
                .andExpect(model().attributeExists("orders", "pageNav"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Đơn hàng | Quản trị Mini Shop", "Đơn hàng", "orders");
        for (String column : List.of("Mã đơn", "Ngày đặt", "Khách hàng", "Người nhận", "Tổng tiền", "Trạng thái")) {
            assertThat(matches("<th scope=\"col\"[^>]*>\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        assertThat(matches("<a[^>]*href=\"/admin/orders/12\"[^>]*>\\s*#12\\s*</a>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/orders/11\"[^>]*>\\s*#11\\s*</a>", html)).isTrue();
        String text = unescape(html);
        assertThat(text).contains("25/10/2026 00:30").contains("450.000 ₫").contains("Trần Thị B")
                .contains("khach@example.com").contains("Nguyễn Văn A");
        assertThat(matches("<span[^>]*class=\"order-status order-status--pending\"[^>]*>\\s*Chờ xử lý\\s*</span>", html))
                .isTrue();
        assertThat(matches("<span[^>]*class=\"order-status order-status--cancelled\"[^>]*>\\s*Đã hủy\\s*</span>", html))
                .isTrue();
        assertThat(text.indexOf("#12")).isLessThan(text.indexOf("#11"));
        assertThat(html).doesNotContain("Chưa có đơn hàng nào.");
    }

    @Test
    void list_filterIsAllPlusFiveLabelsAsLinksWithTheSelectedOneMarked() throws Exception {
        when(orderService.listOrders("PENDING", null)).thenReturn(listPage(List.of(
                row(12, "A", "a@example.com", "B", "Chờ xử lý", "pending")), "PENDING", "Chờ xử lý", 1, 1, 1));

        String html = html("/admin/orders?status=PENDING");

        Matcher nav = Pattern.compile("<nav[^>]*aria-label=\"Lọc theo trạng thái\"[^>]*>(.*?)</nav>", Pattern.DOTALL)
                .matcher(unescape(html));
        assertThat(nav.find()).isTrue();
        Matcher link = Pattern.compile("<a\\s([^>]*)>([^<]*)</a>").matcher(nav.group(1));
        List<String> links = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        while (link.find()) {
            String href = Pattern.compile("href=\"([^\"]*)\"").matcher(link.group(1)).results()
                    .map(r -> r.group(1)).findFirst().orElseThrow();
            links.add(link.group(2).strip() + "|" + href);
            if (link.group(1).contains("aria-current")) {
                marked.add(link.group(2).strip());
            }
        }
        assertThat(links).containsExactly("Tất cả|/admin/orders", "Chờ xử lý|/admin/orders?status=PENDING",
                "Đã xác nhận|/admin/orders?status=CONFIRMED", "Đang giao|/admin/orders?status=SHIPPING",
                "Đã giao|/admin/orders?status=DELIVERED", "Đã hủy|/admin/orders?status=CANCELLED");
        assertThat(marked).containsExactly("Chờ xử lý");
    }

    @Test
    void list_withoutFilter_marksOnlyAll() throws Exception {
        String html = unescape(html("/admin/orders"));

        Matcher marked = Pattern.compile("<a[^>]*aria-current[^>]*>([^<]*)</a>").matcher(html);
        List<String> texts = new ArrayList<>();
        while (marked.find()) {
            texts.add(marked.group(1).strip());
        }
        // The sidebar marks "Đơn hàng" with aria-current="page"; the filter marks "Tất cả".
        assertThat(texts).containsExactlyInAnyOrder("Đơn hàng", "Tất cả");
    }

    @Test
    void list_passesStatusAndPageAsRawStringsToTheService() throws Exception {
        mockMvc.perform(get("/admin/orders").with(admin()).param("status", "PENDING").param("page", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/orders?status=abc&page=abc").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/orders").with(admin())).andExpect(status().isOk());

        verify(orderService).listOrders("PENDING", "2");
        verify(orderService).listOrders("abc", "abc");
        verify(orderService).listOrders(null, null);
    }

    @Test
    void list_paginationLinksKeepTheStatusFilter() throws Exception {
        when(orderService.listOrders(any(), any())).thenReturn(listPage(List.of(
                row(12, "A", "a@example.com", "B", "Chờ xử lý", "pending")), "PENDING", "Chờ xử lý", 2, 3, 45));

        String html = html("/admin/orders?status=PENDING&page=2");

        assertThat(html).contains("href=\"/admin/orders?status=PENDING&amp;page=1\"")
                .contains("href=\"/admin/orders?status=PENDING&amp;page=3\"");
    }

    @Test
    void list_withoutFilter_paginationLinksHaveOnlyThePage() throws Exception {
        when(orderService.listOrders(any(), any())).thenReturn(listPage(List.of(
                row(12, "A", "a@example.com", "B", "Chờ xử lý", "pending")), null, "Tất cả", 1, 2, 25));

        assertThat(html("/admin/orders")).contains("href=\"/admin/orders?page=2\"").doesNotContain("status=PENDING&");
    }

    @Test
    void list_emptyWithoutFilter_saysThereAreNoOrdersAndHasNoTable() throws Exception {
        String html = html("/admin/orders");

        assertThat(html).contains("Chưa có đơn hàng nào.");
        assertThat(main(html)).doesNotContain("<table");
    }

    @Test
    void list_emptyWithFilter_namesTheStatus() throws Exception {
        when(orderService.listOrders(any(), any())).thenReturn(listPage(List.of(), "DELIVERED", "Đã giao", 1, 0, 0));

        String html = unescape(html("/admin/orders?status=DELIVERED"));

        assertThat(html).contains("Không có đơn hàng nào ở trạng thái Đã giao.").doesNotContain("Chưa có đơn hàng nào.");
        assertThat(main(html)).doesNotContain("<table");
    }

    @Test
    void list_customerAndReceiverNamesWithScript_areEscaped() throws Exception {
        when(orderService.listOrders(any(), any())).thenReturn(listPage(List.of(
                row(12, EVIL, "a@example.com", EVIL, "Chờ xử lý", "pending")), null, "Tất cả", 1, 1, 1));

        String html = html("/admin/orders");

        assertThat(html).doesNotContain(EVIL);
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).isEqualTo(2);
    }

    @Test
    void list_adminNameWithScript_isEscaped() throws Exception {
        String html = mockMvc.perform(get("/admin/orders").with(user(TestUsers.withName(EVIL, Role.ADMIN))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Xin chào, " + EVIL_ESCAPED).doesNotContain(EVIL);
    }

    // ============================================================= DETAIL (AC-006-40)

    @Test
    void detail_rendersTitleAndEveryInformationOfTheOrder() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(pending(12));

        String html = mockMvc.perform(get("/admin/orders/12").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/order-detail"))
                .andExpect(model().attributeExists("order"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Đơn hàng #12 | Quản trị Mini Shop", "Đơn hàng #12", "orders");
        String text = unescape(html);
        assertThat(text).contains("Ngày đặt").contains("25/10/2026 00:30")
                .contains("Cập nhật lần cuối").contains("25/10/2026 10:05")
                .contains("Chờ xử lý")
                .contains("Phương thức thanh toán").contains("Thanh toán khi nhận hàng (COD)")
                .contains("Thông tin nhận hàng").contains("Họ tên người nhận").contains("Nguyễn Văn A")
                .contains("Số điện thoại").contains("0912345678")
                .contains("Địa chỉ giao hàng").contains("12 Lê Lợi\nQuận 1")
                .contains("Ghi chú").contains("Giao giờ hành chính");
        // "Tài khoản đặt hàng: {họ tên} ({email})": the label and the value are separate elements (dt and dd).
        assertThat(matches("<dt>\\s*Tài khoản đặt hàng\\s*</dt>\\s*<dd[^>]*>\\s*Trần Thị B \\(khach@example.com\\)\\s*</dd>",
                text)).isTrue();
        for (String column : List.of("Sản phẩm", "Đơn giá", "Số lượng", "Thành tiền")) {
            assertThat(matches("<th scope=\"col\"[^>]*>\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        assertThat(text).contains("Áo thun").contains("Quần jean").contains("100.000 ₫").contains("200.000 ₫")
                .contains("250.000 ₫").contains("Tổng cộng").contains("450.000 ₫");
        assertThat(matches("<a[^>]*href=\"/admin/orders\"[^>]*>\\s*Quay lại danh sách đơn\\s*</a>", main(html))).isTrue();
        assertThat(html).contains("<script src=\"/js/confirm.js\" defer></script>");
    }

    @Test
    void detail_withoutNote_hasNoNoteEntry() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(detail(12, "Chờ xử lý", "pending", null, List.of()));

        String html = unescape(html("/admin/orders/12"));

        assertThat(html).doesNotContain("<dt>Ghi chú</dt>").contains("<dt>Địa chỉ giao hàng</dt>");
    }

    @Test
    void detail_addressNoteReceiverAccountAndProductNameWithScript_areEscaped() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(new AdminOrderDetail(12, CREATED, UPDATED, "Chờ xử lý", "pending",
                "Thanh toán khi nhận hàng (COD)", EVIL, "e@example.com", EVIL, "0912345678", EVIL, EVIL,
                List.of(new OrderLineView(EVIL, new BigDecimal("1000"), 1, new BigDecimal("1000"))),
                new BigDecimal("1000"), List.of()));

        String html = html("/admin/orders/12");

        assertThat(html).doesNotContain(EVIL);
        // receiver, address, note, product name, and the account name (shown once).
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).isEqualTo(5);
    }

    // ============================================================= BUTTONS (AC-006-41, 49)

    @Test
    void detail_pending_showsConfirmAndCancelWithConfirmTextOnlyOnCancel() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(pending(12));

        String html = html("/admin/orders/12");

        List<String> forms = formsWithAction(html, "/admin/orders/12/status");
        assertThat(forms).hasSize(2);
        assertThat(forms.get(0)).contains("method=\"post\"").contains("name=\"_csrf\"")
                .contains("name=\"status\" value=\"CONFIRMED\"").doesNotContain("data-confirm");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Xác nhận đơn\\s*</button>", forms.get(0))).isTrue();
        assertThat(forms.get(1)).contains("name=\"_csrf\"").contains("name=\"status\" value=\"CANCELLED\"")
                .contains("data-confirm=\"Hủy đơn hàng #12 và hoàn lại tồn kho?\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Hủy đơn\\s*</button>", forms.get(1))).isTrue();
        assertThat(html).doesNotContain("Đơn hàng đã ở trạng thái cuối.");
    }

    @Test
    void detail_confirmed_showsStartShippingAndCancel() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(detail(12, "Đã xác nhận", "confirmed", null, List.of(
                action(12, "SHIPPING", "Bắt đầu giao hàng"), action(12, "CANCELLED", "Hủy đơn"))));

        List<String> forms = formsWithAction(html("/admin/orders/12"), "/admin/orders/12/status");

        assertThat(forms).hasSize(2);
        assertThat(forms.get(0)).contains("value=\"SHIPPING\"").contains("Bắt đầu giao hàng").doesNotContain("data-confirm");
        assertThat(forms.get(1)).contains("value=\"CANCELLED\"").contains("Hủy đơn");
    }

    @Test
    void detail_shipping_showsOnlyMarkDeliveredWithItsConfirmText() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(detail(12, "Đang giao", "shipping", null, List.of(
                action(12, "DELIVERED", "Xác nhận đã giao"))));

        String html = html("/admin/orders/12");

        List<String> forms = formsWithAction(html, "/admin/orders/12/status");
        assertThat(forms).hasSize(1);
        assertThat(forms.get(0)).contains("value=\"DELIVERED\"").contains("Xác nhận đã giao")
                .contains("data-confirm=\"Đánh dấu đơn hàng #12 đã giao? Không thể hoàn tác.\"");
        assertThat(html).doesNotContain("Hủy đơn</button>");
    }

    @ParameterizedTest(name = "final status {0}: no buttons, only the text")
    @ValueSource(strings = {"delivered", "cancelled"})
    void detail_finalStatus_hasNoStatusFormAndShowsFinalText(String key) throws Exception {
        when(orderService.getOrder(12L)).thenReturn(detail(12, "Đã giao", key, null, List.of()));

        String html = html("/admin/orders/12");

        assertThat(html).contains("Đơn hàng đã ở trạng thái cuối.");
        assertThat(formsWithAction(html, "/admin/orders/12/status")).isEmpty();
        assertThat(html).doesNotContain("name=\"status\"");
    }

    @Test
    void detail_buttonsComeOnlyFromTheServiceList_templateHasNoTransitionTable() throws Exception {
        // A deliberately odd list: the page must render exactly this, whatever the real table says.
        when(orderService.getOrder(12L)).thenReturn(detail(12, "Chờ xử lý", "pending", null, List.of(
                action(12, "SHIPPING", "Chỉ nút này"))));

        String html = html("/admin/orders/12");

        List<String> forms = formsWithAction(html, "/admin/orders/12/status");
        assertThat(forms).hasSize(1);
        assertThat(forms.get(0)).contains("value=\"SHIPPING\"").contains("Chỉ nút này");
        assertThat(html).doesNotContain("Xác nhận đơn").doesNotContain("Hủy đơn");
    }

    @Test
    void detail_dangerButtonDiffersByWordsNotOnlyByColor() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(pending(12));

        String html = html("/admin/orders/12");

        assertThat(matches("<button[^>]*btn-outline-danger[^>]*>\\s*Hủy đơn\\s*</button>", html)).isTrue();
        assertThat(matches("<button[^>]*btn-primary[^>]*>\\s*Xác nhận đơn\\s*</button>", html)).isTrue();
    }

    @Test
    void detail_withFlashNotice_showsIt() throws Exception {
        when(orderService.getOrder(12L)).thenReturn(pending(12));

        String html = mockMvc.perform(get("/admin/orders/12").with(admin())
                        .flashAttr("notice", "Không thể chuyển đơn hàng #12 từ Đang giao sang Đã hủy.")
                        .flashAttr("noticeKind", "error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Không thể chuyển đơn hàng #12 từ Đang giao sang Đã hủy.");
        assertThat(html).contains("role=\"alert\"");
    }

    // ============================================================= POST status (AC-006-42, 43, 45)

    @Test
    void postStatus_whenChanged_redirectsToDetailWithTheNewLabel() throws Exception {
        when(orderService.changeStatus(1L, 12L, "CONFIRMED")).thenReturn(new StatusChangeResult.Changed("Đã xác nhận"));

        mockMvc.perform(post("/admin/orders/12/status").with(admin()).with(csrf()).param("status", "CONFIRMED")
                        .header("Referer", "https://evil.example/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders/12"))
                .andExpect(flash().attribute("notice", "Đã chuyển đơn hàng #12 sang trạng thái Đã xác nhận."))
                .andExpect(flash().attribute("noticeKind", "success"));
    }

    @Test
    void postStatus_whenCancelled_saysStockWasGivenBack() throws Exception {
        when(orderService.changeStatus(1L, 12L, "CANCELLED")).thenReturn(new StatusChangeResult.Cancelled());

        mockMvc.perform(post("/admin/orders/12/status").with(admin()).with(csrf()).param("status", "CANCELLED"))
                .andExpect(redirectedUrl("/admin/orders/12"))
                .andExpect(flash().attribute("notice", "Đã hủy đơn hàng #12 và hoàn lại tồn kho."))
                .andExpect(flash().attribute("noticeKind", "success"));
    }

    @Test
    void postStatus_whenNotAllowed_flashesFromAndToLabelsAsError() throws Exception {
        when(orderService.changeStatus(1L, 12L, "CANCELLED"))
                .thenReturn(new StatusChangeResult.NotAllowed("Đang giao", "Đã hủy"));

        mockMvc.perform(post("/admin/orders/12/status").with(admin()).with(csrf()).param("status", "CANCELLED"))
                .andExpect(redirectedUrl("/admin/orders/12"))
                .andExpect(flash().attribute("notice", "Không thể chuyển đơn hàng #12 từ Đang giao sang Đã hủy."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @Test
    void postStatus_whenInvalidStatus_flashesInvalidMessage() throws Exception {
        when(orderService.changeStatus(1L, 12L, "abc")).thenReturn(new StatusChangeResult.InvalidStatus());

        mockMvc.perform(post("/admin/orders/12/status").with(admin()).with(csrf()).param("status", "abc"))
                .andExpect(redirectedUrl("/admin/orders/12"))
                .andExpect(flash().attribute("notice", "Trạng thái không hợp lệ."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @Test
    void postStatus_passesTheRawStatusToTheServiceEvenWhenMissingOrBlank() throws Exception {
        when(orderService.changeStatus(anyLong(), anyLong(), any())).thenReturn(new StatusChangeResult.InvalidStatus());

        mockMvc.perform(post("/admin/orders/12/status").with(admin()).with(csrf())).andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/orders/12/status").with(admin()).with(csrf()).param("status", " pending "))
                .andExpect(status().is3xxRedirection());

        verify(orderService).changeStatus(1L, 12L, null);
        verify(orderService).changeStatus(1L, 12L, " pending ");
    }

    // ============================================================= 404 (AC-006-5)

    @ParameterizedTest(name = "GET /admin/orders/{0} -> admin 404 without calling the service")
    @ValueSource(strings = {"abc", "-1", "1.5", "99999999999999999999"})
    void detail_withBadId_is404InAdminLayout(String id) throws Exception {
        String html = mockMvc.perform(get("/admin/orders/" + id).with(admin()))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString();

        assertNotFoundPage(html);
        verifyNoInteractions(orderService);
    }

    @Test
    void detail_whenServiceThrowsNotFound_is404InAdminLayout() throws Exception {
        when(orderService.getOrder(999999L)).thenThrow(new NotFoundException("Order not found: 999999"));

        assertNotFoundPage(mockMvc.perform(get("/admin/orders/999999").with(admin()))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void postStatus_withBadOrUnknownId_is404AndChangesNothing() throws Exception {
        when(orderService.changeStatus(anyLong(), eq(999L), anyString()))
                .thenThrow(new NotFoundException("Order not found: 999"));

        assertNotFoundPage(mockMvc.perform(post("/admin/orders/abc/status").with(admin()).with(csrf())
                        .param("status", "CANCELLED"))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString());
        verifyNoInteractions(orderService);

        assertNotFoundPage(mockMvc.perform(post("/admin/orders/999/status").with(admin()).with(csrf())
                        .param("status", "CANCELLED"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        verify(orderService, never()).changeStatus(1L, 12L, "CANCELLED");
        verify(orderService, never()).getOrder(anyLong());
        verify(orderService, never()).dashboard();
        verify(orderService, never()).listOrders(isNull(), isNull());
    }

    private static void assertNotFoundPage(String html) {
        assertLayout(html, NOT_FOUND_TITLE + " | Quản trị Mini Shop", NOT_FOUND_TITLE, "orders");
        assertThat(matches("<a[^>]*href=\"/admin/orders\"[^>]*>\\s*Quay lại danh sách đơn\\s*</a>", main(html))).isTrue();
    }
}

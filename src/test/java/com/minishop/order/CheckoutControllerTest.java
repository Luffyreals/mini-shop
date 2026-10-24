package com.minishop.order;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * Tầng web của thanh toán, {@code OrderService} được mock (AC-005-3..5, 7, 9, 11..14, 18, 19, 33; BR-005-5, 7).
 * Người đăng nhập dùng {@code user(TestUsers...)}: {@code @WithMockUser} sẽ cho {@code @AuthenticationPrincipal}
 * là null.
 */
@WebMvcTest(CheckoutController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class CheckoutControllerTest {

    private static final String MSG_NOT_ORDERABLE =
            "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng.";
    private static final String MSG_PRICE_CHANGED =
            "Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận.";
    private static final String MSG_EMPTY_CART = "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo.";

    private static final String PHONE_ERROR = "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    // ---------- helpers ----------

    private static CheckoutView checkoutOf(String total, CheckoutLineView... lines) {
        return new CheckoutView(List.of(lines), new BigDecimal(total), true);
    }

    private static CheckoutLineView lineView(String name, String price, int qty) {
        BigDecimal unit = new BigDecimal(price);
        return new CheckoutLineView(name, unit, qty, unit.multiply(BigDecimal.valueOf(qty)));
    }

    /** Hai dòng, tổng 450 000 (khớp ví dụ của plan). */
    private static CheckoutView standardView() {
        return checkoutOf("450000", lineView("Áo thun", "100000", 2), lineView("Quần jean", "250000", 1));
    }

    private static CheckoutView emptyView() {
        return new CheckoutView(List.of(), BigDecimal.ZERO, false);
    }

    private static CheckoutView notOrderableView() {
        return new CheckoutView(List.of(lineView("Áo thun", "100000", 2)), new BigDecimal("200000"), false);
    }

    private static RequestPostProcessor loggedInUser() {
        return user(TestUsers.user());
    }

    private static MockHttpServletRequestBuilder validPost(String expectedTotal) {
        return post("/checkout").with(loggedInUser()).with(csrf())
                .param("receiverName", "Nguyễn Văn A")
                .param("phone", "0912 345 678")
                .param("shippingAddress", "12 Lê Lợi\nQuận 1")
                .param("note", "Giao giờ hành chính")
                .param("expectedTotal", expectedTotal);
    }

    /** Like {@link #validPost} with total 450000 but with the given receiver name and phone (one value each). */
    private static MockHttpServletRequestBuilder validPostWith(String receiverName, String phone) {
        return post("/checkout").with(loggedInUser()).with(csrf())
                .param("receiverName", receiverName)
                .param("phone", phone)
                .param("shippingAddress", "12 Lê Lợi\nQuận 1")
                .param("note", "Giao giờ hành chính")
                .param("expectedTotal", "450000");
    }

    private static String firstGroup(String regex, String html) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(html);
        assertThat(m.find()).as("pattern %s in html", regex).isTrue();
        return m.group();
    }

    /** The whole start tag of the input/textarea with this name, attributes in any order. */
    private static String field(String html, String name) {
        return HtmlUtils.htmlUnescape(firstGroup("<(input|textarea)[^>]*\\bname=\"" + name + "\"[^>]*>", html));
    }

    private static String textareaContent(String html, String name) {
        Matcher m = Pattern.compile("<textarea[^>]*\\bname=\"" + name + "\"[^>]*>(.*?)</textarea>", Pattern.DOTALL)
                .matcher(html);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static boolean matches(String regex, String html) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(html).find();
    }

    private static String main(String html) {
        int start = html.indexOf("<main");
        return html.substring(start, html.indexOf("</main>", start));
    }

    private static String checkoutForm(String html) {
        return firstGroup("<form[^>]*action=\"/checkout\"[^>]*>.*?</form>", html);
    }

    private String getCheckout(RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get("/checkout").with(who)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- AC-005-3: trang thanh toán ----------

    @Test
    void show_forUser_rendersFormSummaryAndPaymentText() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());
        when(orderService.prefilledForm(2L)).thenReturn(CheckoutForm.prefill("Khách hàng mẫu", "0987654321"));

        MvcResult result = mockMvc.perform(get("/checkout").with(loggedInUser()))
                .andExpect(status().isOk())
                .andExpect(view().name("order/checkout"))
                .andExpect(model().attributeExists("form", "checkout"))
                .andExpect(content().string(containsString("<title>Thanh toán | Mini Shop</title>")))
                .andReturn();
        String html = result.getResponse().getContentAsString();

        assertThat(matches("<h1[^>]*>\\s*Thanh toán\\s*</h1>", html)).isTrue();
        // Mỗi label for khớp id của ô.
        for (String[] pair : new String[][]{{"receiverName", "Họ tên người nhận"}, {"phone", "Số điện thoại"},
                {"shippingAddress", "Địa chỉ giao hàng"}, {"note", "Ghi chú \\(không bắt buộc\\)"}}) {
            assertThat(matches("<label[^>]*\\bfor=\"" + pair[0] + "\"[^>]*>\\s*" + pair[1] + "\\s*</label>", html))
                    .as("label for %s", pair[0]).isTrue();
            assertThat(field(html, pair[0])).contains("id=\"" + pair[0] + "\"");
        }
        assertThat(field(html, "receiverName")).contains("value=\"Khách hàng mẫu\"").contains("maxlength=\"100\"")
                .contains("autocomplete=\"name\"");
        assertThat(field(html, "phone")).contains("value=\"0987654321\"").contains("type=\"tel\"")
                .contains("inputmode=\"tel\"").contains("autocomplete=\"tel\"");
        assertThat(field(html, "shippingAddress")).contains("maxlength=\"500\"")
                .contains("autocomplete=\"street-address\"");
        assertThat(field(html, "note")).contains("maxlength=\"500\"");

        // BR-005-7: phương thức thanh toán là chữ, không có lựa chọn.
        assertThat(html).contains("Phương thức thanh toán").contains("Thanh toán khi nhận hàng (COD)")
                .doesNotContain("type=\"radio\"").doesNotContain("<select");

        // Bảng tóm tắt.
        assertThat(html).contains("Đơn hàng của bạn").contains("Áo thun").contains("Quần jean")
                .contains("100.000 ₫").contains("200.000 ₫").contains("250.000 ₫").contains("450.000 ₫")
                .contains("Tổng cộng");
        for (String header : List.of("Sản phẩm", "Số lượng", "Đơn giá", "Thành tiền")) {
            assertThat(matches("<th scope=\"col\">\\s*" + header + "\\s*</th>", html)).as(header).isTrue();
        }
        assertThat(matches("<caption class=\"visually-hidden\">", html)).isTrue();

        // Ô ẩn expectedTotal và nút nằm trong form action=/checkout có _csrf.
        assertThat(field(html, "expectedTotal")).contains("type=\"hidden\"").contains("value=\"450000\"");
        String form = checkoutForm(html);
        assertThat(form).contains("method=\"post\"").contains("name=\"_csrf\"")
                .contains("name=\"expectedTotal\"")
                .contains("Xác nhận đặt hàng");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Xác nhận đặt hàng\\s*</button>", form)).isTrue();

        // Link quay lại giỏ.
        assertThat(matches("<a[^>]*href=\"/cart\"[^>]*>\\s*Quay lại giỏ hàng\\s*</a>", main(html))).isTrue();
        verify(orderService, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void show_forAdmin_isAllowedAndUsesAdminId() throws Exception {
        when(orderService.prepareCheckout(1L)).thenReturn(standardView());
        when(orderService.prefilledForm(1L)).thenReturn(CheckoutForm.prefill("Quản trị viên", ""));

        String html = getCheckout(user(TestUsers.admin()));

        assertThat(html).contains("Xác nhận đặt hàng");
        assertThat(field(html, "receiverName")).contains("value=\"Quản trị viên\"");
        verify(orderService).prepareCheckout(1L);
    }

    @Test
    void show_priceInHiddenField_isPlainStringNeverScientific() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(checkoutOf("4500000", lineView("Áo thun", "4500000", 1)));
        when(orderService.prefilledForm(2L)).thenReturn(CheckoutForm.prefill("A", ""));

        assertThat(field(getCheckout(loggedInUser()), "expectedTotal")).contains("value=\"4500000\"");
    }

    /** AC-007-18: every text box of the checkout form carries the server's limit as maxlength. */
    @ParameterizedTest(name = "{0} has maxlength {1}")
    @CsvSource({"receiverName,100", "phone,20", "shippingAddress,500", "note,500"})
    void show_inputs_carryMaxlengthEqualToServerLimit(String name, String maxlength) throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());
        when(orderService.prefilledForm(2L)).thenReturn(CheckoutForm.prefill("Khách hàng mẫu", ""));

        assertThat(field(getCheckout(loggedInUser()), name)).contains("maxlength=\"" + maxlength + "\"");
    }

    // ---------- AC-005-4, 5: giỏ trống / không đặt được ----------

    @Test
    void show_whenCartEmpty_redirectsToCartWithoutNotice() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(emptyView());

        mockMvc.perform(get("/checkout").with(loggedInUser()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attributeCount(0));
        verify(orderService, never()).prefilledForm(anyLong());
    }

    @Test
    void show_whenNotOrderable_redirectsToCartWithErrorNotice() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(notOrderableView());

        mockMvc.perform(get("/checkout").with(loggedInUser()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", MSG_NOT_ORDERABLE))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    // ---------- AC-005-7: đặt thành công ----------

    @Test
    void place_whenValid_redirectsToOrderAndPassesUserFormAndTotal() throws Exception {
        when(orderService.placeOrder(eq(2L), any(), any())).thenReturn(42L);

        mockMvc.perform(validPost("450000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/42"))
                .andExpect(flash().attribute("notice", "Đặt hàng thành công. Mã đơn của bạn là #42."))
                .andExpect(flash().attribute("noticeKind", "success"));

        ArgumentCaptor<CheckoutForm> form = ArgumentCaptor.forClass(CheckoutForm.class);
        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orderService).placeOrder(eq(2L), form.capture(), total.capture());
        assertThat(total.getValue()).isEqualByComparingTo("450000");
        assertThat(form.getValue().receiverName()).isEqualTo("Nguyễn Văn A");
        assertThat(form.getValue().phone()).isEqualTo("0912 345 678");
        assertThat(form.getValue().normalizedPhone()).isEqualTo("0912345678");
        assertThat(form.getValue().shippingAddress()).isEqualTo("12 Lê Lợi\nQuận 1");
        assertThat(form.getValue().note()).isEqualTo("Giao giờ hành chính");
    }

    /** AC-007-16: +84 / 84 forms are accepted and reach the service as the one stored form, 0912345678. */
    @ParameterizedTest(name = "[{0}] is stored as 0912345678")
    @ValueSource(strings = {"+84912345678", "+84 912 345 678", "+84-912-345-678", "84912345678"})
    void place_withInternationalPhone_passesNormalizedZeroPhoneToService(String typed) throws Exception {
        when(orderService.placeOrder(eq(2L), any(), any())).thenReturn(42L);

        mockMvc.perform(validPostWith("Nguyễn Văn A", typed))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/42"));

        ArgumentCaptor<CheckoutForm> form = ArgumentCaptor.forClass(CheckoutForm.class);
        verify(orderService).placeOrder(eq(2L), form.capture(), any());
        assertThat(form.getValue().phone()).isEqualTo(typed);
        assertThat(form.getValue().normalizedPhone()).isEqualTo("0912345678");
    }

    /** AC-007-16: wrong country code, a 0 after 84, or a missing digit keep the old phone error. */
    @ParameterizedTest(name = "[{0}] is rejected")
    @ValueSource(strings = {"+840912345678", "+1 212 555 0100", "+8491234567"})
    void place_withInvalidInternationalPhone_showsPhoneErrorAndKeepsTypedText(String typed) throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());

        String html = mockMvc.perform(validPostWith("Nguyễn Văn A", typed))
                .andExpect(status().isOk())
                .andExpect(view().name("order/checkout"))
                .andExpect(model().attributeHasFieldErrors("form", "phone"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(PHONE_ERROR);
        assertThat(field(html, "phone")).contains("value=\"" + typed + "\"");
        verify(orderService, never()).placeOrder(anyLong(), any(), any());
    }

    /** AC-007-16: when the page is shown again for another error, the +84 text is what the customer typed. */
    @Test
    void place_withPlus84PhoneAndAnotherFieldInvalid_keepsPlus84TextAsTyped() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());

        String html = mockMvc.perform(validPostWith("", "+84 912 345 678"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "receiverName"))
                .andReturn().getResponse().getContentAsString();

        assertThat(field(html, "phone")).contains("value=\"+84 912 345 678\"");
        assertThat(html).doesNotContain(PHONE_ERROR);
        verify(orderService, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void place_asAdmin_isAllowedAndUsesAdminId() throws Exception {
        when(orderService.placeOrder(eq(1L), any(), any())).thenReturn(7L);

        mockMvc.perform(validPost("450000").with(user(TestUsers.admin())))
                .andExpect(redirectedUrl("/orders/7"));

        verify(orderService).placeOrder(eq(1L), any(), any());
    }

    @Test
    void place_whenExpectedTotalMissingOrMalformed_passesNullToService() throws Exception {
        when(orderService.placeOrder(anyLong(), any(), any())).thenReturn(5L);

        mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                .param("receiverName", "A").param("phone", "0912345678").param("shippingAddress", "B"))
                .andExpect(redirectedUrl("/orders/5"));
        mockMvc.perform(validPost("4.5E+5")).andExpect(redirectedUrl("/orders/5"));

        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orderService, org.mockito.Mockito.times(2)).placeOrder(eq(2L), any(), total.capture());
        assertThat(total.getAllValues()).containsOnlyNulls();
    }

    // ---------- AC-005-9: lỗi form ----------

    private static Stream<Arguments> invalidForms() {
        String ok = "x";
        return Stream.of(
                // name, receiverName, phone, address, note, field, message
                Arguments.of("họ tên trống", "   ", "0912345678", ok, "", "receiverName",
                        "Vui lòng nhập họ tên người nhận."),
                Arguments.of("họ tên 101 ký tự", "a".repeat(101), "0912345678", ok, "", "receiverName",
                        "Họ tên người nhận tối đa 100 ký tự."),
                Arguments.of("phone quá ngắn", "A", "12345", ok, "", "phone", PHONE_ERROR),
                Arguments.of("phone có chữ", "A", "0abc456789", ok, "", "phone", PHONE_ERROR),
                Arguments.of("phone 11 số", "A", "09123456789", ok, "", "phone", PHONE_ERROR),
                Arguments.of("phone trống", "A", "", ok, "", "phone", "Vui lòng nhập số điện thoại."),
                Arguments.of("địa chỉ trống", "A", "0912345678", "  ", "", "shippingAddress",
                        "Vui lòng nhập địa chỉ giao hàng."),
                Arguments.of("địa chỉ 501 ký tự", "A", "0912345678", "a".repeat(501), "", "shippingAddress",
                        "Địa chỉ giao hàng tối đa 500 ký tự."),
                Arguments.of("ghi chú 501 ký tự", "A", "0912345678", ok, "n".repeat(501), "note",
                        "Ghi chú tối đa 500 ký tự."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidForms")
    void place_whenFieldInvalid_rendersFormWithErrorKeepsTypedValuesAndDoesNotPlaceOrder(
            String name, String receiverName, String phone, String address, String note, String field,
            String message) throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());

        String html = mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", receiverName).param("phone", phone)
                        .param("shippingAddress", address).param("note", note)
                        .param("expectedTotal", "450000"))
                .andExpect(status().isOk())
                .andExpect(view().name("order/checkout"))
                .andExpect(model().attributeHasFieldErrors("form", field))
                .andReturn().getResponse().getContentAsString();

        assertThat(matches("<p[^>]*class=\"field-error\"[^>]*id=\"" + field + "-error\"[^>]*>\\s*"
                + Pattern.quote(message) + "\\s*</p>", html)
                || matches("<p[^>]*id=\"" + field + "-error\"[^>]*class=\"field-error\"[^>]*>\\s*"
                + Pattern.quote(message) + "\\s*</p>", html))
                .as("error text for %s", field).isTrue();
        assertThat(field(html, field)).contains("aria-invalid=\"true\"")
                .contains("aria-describedby=\"" + field + "-error\"");
        // Giá trị đã gõ còn nguyên (kể cả phone sai và chuỗi dài).
        if (!phone.isEmpty()) {
            assertThat(field(html, "phone")).contains("value=\"" + phone + "\"");
        }
        if (field.equals("note")) {
            assertThat(HtmlUtils.htmlUnescape(textareaContent(html, "note"))).isEqualTo(note);
        }
        if (field.equals("receiverName")) {
            assertThat(field(html, "receiverName")).contains("value=\"" + receiverName.strip() + "\"");
        }
        // Trang vẫn đủ bảng tóm tắt và ô expectedTotal với giá hiện tại; không có thông báo giá đổi.
        assertThat(html).contains("Quần jean").doesNotContain(MSG_PRICE_CHANGED);
        assertThat(field(html, "expectedTotal")).contains("value=\"450000\"");
        verify(orderService, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void place_whenPhoneInvalid_keepsExactlyWhatWasTyped() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());

        String html = mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", "A").param("phone", "0912.345.67")
                        .param("shippingAddress", "x").param("expectedTotal", "450000"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(field(html, "phone")).contains("value=\"0912.345.67\"");
    }

    @Test
    void place_whenFormInvalidAndPriceChanged_alsoShowsPriceChangedNotice() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(checkoutOf("460000", lineView("Áo thun", "460000", 1)));

        String html = mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", "").param("phone", "0912345678")
                        .param("shippingAddress", "x").param("expectedTotal", "450000"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("noticeKind", "error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MSG_PRICE_CHANGED).contains("Vui lòng nhập họ tên người nhận.");
        assertThat(field(html, "expectedTotal")).contains("value=\"460000\"");
        verify(orderService, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void place_whenFormInvalidAndTotalMissing_showsPriceChangedNotice() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());

        String html = mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", "").param("phone", "0912345678").param("shippingAddress", "x"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MSG_PRICE_CHANGED);
    }

    @Test
    void place_whenFormInvalidAndCartEmpty_redirectsToCart() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(emptyView());

        mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", "").param("phone", "").param("shippingAddress", ""))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attributeCount(0));
        verify(orderService, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void place_whenFormInvalidAndCartNotOrderable_redirectsToCartWithError() throws Exception {
        when(orderService.prepareCheckout(2L)).thenReturn(notOrderableView());

        mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", "").param("phone", "").param("shippingAddress", ""))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", MSG_NOT_ORDERABLE))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    // ---------- AC-005-11..14: kết quả nghiệp vụ ----------

    @Test
    void place_whenEmptyCart_redirectsToOrdersWithInfo() throws Exception {
        when(orderService.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.EmptyCart());

        mockMvc.perform(validPost("450000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders"))
                .andExpect(flash().attribute("notice", MSG_EMPTY_CART))
                .andExpect(flash().attribute("noticeKind", "info"));
    }

    @Test
    void place_whenNotOrderable_redirectsToCartWithGenericText() throws Exception {
        when(orderService.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.NotOrderable());

        mockMvc.perform(validPost("450000"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", MSG_NOT_ORDERABLE))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @Test
    void place_whenInsufficientStock_redirectsToCartNamingTheProduct() throws Exception {
        when(orderService.placeOrder(anyLong(), any(), any()))
                .thenThrow(new PlaceOrderException.InsufficientStock("Áo thun"));

        mockMvc.perform(validPost("450000"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", "Sản phẩm Áo thun không đủ hàng. Vui lòng cập nhật giỏ hàng."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    // ---------- AC-005-13: giá đổi ----------

    @Test
    void place_whenPriceChanged_rendersCheckoutAgainWithNewPriceNoticeAndTypedValues() throws Exception {
        CheckoutView fresh = checkoutOf("460000", lineView("Áo thun", "105000", 2), lineView("Quần jean", "250000", 1));
        when(orderService.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.PriceChanged(fresh));

        String html = mockMvc.perform(validPost("450000"))
                .andExpect(status().isOk())
                .andExpect(view().name("order/checkout"))
                .andExpect(model().attribute("checkout", fresh))
                .andExpect(model().attribute("noticeKind", "error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(MSG_PRICE_CHANGED).contains("105.000 ₫").contains("460.000 ₫");
        assertThat(matches("class=\"notice[^\"]*\"[^>]*role=\"alert\"|role=\"alert\"[^>]*class=\"notice", html))
                .isTrue();
        assertThat(field(html, "expectedTotal")).contains("value=\"460000\"");
        // Giá trị khách đã gõ còn nguyên.
        assertThat(field(html, "receiverName")).contains("value=\"Nguyễn Văn A\"");
        assertThat(field(html, "phone")).contains("value=\"0912 345 678\"");
        assertThat(HtmlUtils.htmlUnescape(textareaContent(html, "shippingAddress")))
                .contains("12 Lê Lợi").contains("Quận 1");
        assertThat(HtmlUtils.htmlUnescape(textareaContent(html, "note"))).isEqualTo("Giao giờ hành chính");
        // Không có lỗi ô nào.
        assertThat(html).doesNotContain("class=\"field-error\"").doesNotContain("aria-invalid=\"true\"");
    }

    // ---------- AC-005-18: tham số thừa ----------

    @Test
    void place_withExtraParameters_ignoresThemAndUsesPrincipalId() throws Exception {
        when(orderService.placeOrder(anyLong(), any(), any())).thenReturn(9L);

        mockMvc.perform(validPost("450000")
                        .param("userId", "1").param("status", "DELIVERED").param("totalAmount", "1")
                        .param("unitPrice", "1").param("paymentMethod", "X").param("id", "5"))
                .andExpect(redirectedUrl("/orders/9"));

        ArgumentCaptor<CheckoutForm> form = ArgumentCaptor.forClass(CheckoutForm.class);
        verify(orderService).placeOrder(eq(2L), form.capture(), any());
        assertThat(form.getValue()).isEqualTo(new CheckoutForm("Nguyễn Văn A", "0912 345 678",
                "12 Lê Lợi\nQuận 1", "Giao giờ hành chính"));
        assertThat(CheckoutForm.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("receiverName", "phone", "shippingAddress", "note");
    }

    // ---------- spec §6: không dùng Referer ----------

    @Test
    void place_ignoresRefererAndReturnUrl() throws Exception {
        when(orderService.placeOrder(anyLong(), any(), any())).thenReturn(42L);

        mockMvc.perform(validPost("450000").header("Referer", "https://evil.example/")
                        .param("returnUrl", "https://evil.example/"))
                .andExpect(redirectedUrl("/orders/42"));
    }

    // ---------- AC-005-19: CSRF ----------

    @Test
    void place_withoutCsrf_isForbiddenAndDoesNotCallService() throws Exception {
        mockMvc.perform(post("/checkout").with(loggedInUser())
                        .param("receiverName", "A").param("phone", "0912345678").param("shippingAddress", "x")
                        .param("expectedTotal", "450000"))
                .andExpect(status().isForbidden());

        verify(orderService, never()).placeOrder(anyLong(), any(), any());
        verify(orderService, never()).prepareCheckout(anyLong());
    }

    // ---------- AC-005-33: escape ----------

    @Test
    void show_productNameWithScript_isEscaped() throws Exception {
        String evil = "<script>alert(1)</script>";
        when(orderService.prepareCheckout(2L)).thenReturn(checkoutOf("100000", lineView(evil, "100000", 1)));
        when(orderService.prefilledForm(2L)).thenReturn(CheckoutForm.prefill("A", ""));

        String html = getCheckout(loggedInUser());

        assertThat(html).doesNotContain(evil).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void place_typedScriptInAddress_isEscapedWhenFormIsShownAgain() throws Exception {
        String evil = "<script>alert(1)</script>";
        when(orderService.prepareCheckout(2L)).thenReturn(standardView());

        String html = mockMvc.perform(post("/checkout").with(loggedInUser()).with(csrf())
                        .param("receiverName", "").param("phone", "0912345678").param("shippingAddress", evil)
                        .param("expectedTotal", "450000"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain(evil);
        assertThat(textareaContent(html, "shippingAddress")).isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }
}

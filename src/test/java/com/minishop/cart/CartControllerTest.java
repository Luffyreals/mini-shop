package com.minishop.cart;

import com.minishop.common.NotFoundException;
import com.minishop.config.SecurityConfig;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tầng web của giỏ hàng, {@code CartService} được mock (AC-004-1..2, 4..7, 9..12, 14, 18, 19, 22).
 * Người đăng nhập dùng {@code user(TestUsers...)}: {@code @WithMockUser} sẽ được coi là khách.
 */
@WebMvcTest(CartController.class)
@Import(SecurityConfig.class)
class CartControllerTest {

    private static final String NOT_FOUND_TITLE = "<title>Không tìm thấy sản phẩm | Mini Shop</title>";

    private static final RequestPostProcessor GUEST = request -> request;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CartService cartService;

    // ---------- helpers ----------

    private static CartLineView line(long id, String name, String price, int qty, CartLineStatus status,
                                     int available) {
        BigDecimal unit = new BigDecimal(price);
        boolean purchasable = status == CartLineStatus.AVAILABLE || status == CartLineStatus.LOW_STOCK;
        return new CartLineView(id, name, null, unit, qty,
                purchasable ? unit.multiply(BigDecimal.valueOf(qty)) : null, status, available);
    }

    private static CartLineView available(long id, String name, String price, int qty) {
        return line(id, name, price, qty, CartLineStatus.AVAILABLE, 0);
    }

    private static CartView cartOf(String total, CartLineView... lines) {
        return new CartView(List.of(lines), new BigDecimal(total));
    }

    private static CartView emptyCart() {
        return new CartView(List.of(), BigDecimal.ZERO);
    }

    private static boolean matches(String regex, String html) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(html).find();
    }

    private static String main(String html) {
        int start = html.indexOf("<main");
        return html.substring(start, html.indexOf("</main>", start));
    }

    private String getCart() throws Exception {
        return mockMvc.perform(get("/cart")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private MockHttpServletRequestBuilder add(String productId, String quantity) {
        MockHttpServletRequestBuilder b = post("/cart/items").with(csrf());
        if (productId != null) {
            b.param("productId", productId);
        }
        if (quantity != null) {
            b.param("quantity", quantity);
        }
        return b;
    }

    private void assertNotFoundPage(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(view().name("product/not-found"))
                .andExpect(content().string(containsString(NOT_FOUND_TITLE)));
    }

    // ---------- AC-004-10: giỏ trống ----------

    @Test
    void view_whenEmpty_showsEmptyStateWithoutTableOrFormAndCreatesNoSession() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());

        MvcResult result = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/view"))
                .andExpect(content().string(containsString("<title>Giỏ hàng | Mini Shop</title>")))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Giỏ hàng của bạn đang trống.");
        assertThat(matches("<h1[^>]*>\\s*Giỏ hàng\\s*</h1>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/products\"[^>]*>\\s*Tiếp tục mua sắm", html)).isTrue();
        assertThat(main(html)).doesNotContain("<table").doesNotContain("<form");
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void view_whenEmptyForLoggedInUser_showsEmptyStateForThatCustomer() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());

        mockMvc.perform(get("/cart").with(user(TestUsers.user())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giỏ hàng của bạn đang trống.")));

        verify(cartService).view(new CartOwner.Customer(2L));
    }

    // ---------- AC-004-9: giỏ có hàng ----------

    @Test
    void view_withLines_rendersColumnsLinksPricesFormsAndTotal() throws Exception {
        when(cartService.view(any())).thenReturn(cartOf("3750000",
                available(7, "Tai nghe không dây", "1250000", 2),
                available(8, "Chuột", "1250000", 1)));

        String html = getCart();

        assertThat(html).contains("<title>Giỏ hàng | Mini Shop</title>")
                .contains("Sản phẩm trong giỏ hàng");
        for (String column : List.of("Sản phẩm", "Đơn giá", "Số lượng", "Thành tiền")) {
            assertThat(matches("<th scope=\"col\">" + column + "</th>", html)).as(column).isTrue();
        }
        assertThat(matches("<a[^>]*href=\"/products/7\"[^>]*>Tai nghe không dây</a>", html)).isTrue();
        assertThat(html).contains("1.250.000 ₫")      // đơn giá
                .contains("2.500.000 ₫")                // thành tiền dòng 1
                .contains("Tổng cộng")
                .contains("3.750.000 ₫");               // tổng
        assertThat(matches("<img[^>]*src=\"/images/placeholder-product.svg\"", html)).isTrue();
        assertThat(matches("<label[^>]*for=\"qty-7\"[^>]*>Số lượng</label>", html)).isTrue();
        assertThat(matches("<input(?=[^>]*id=\"qty-7\")(?=[^>]*name=\"quantity\")(?=[^>]*value=\"2\")"
                + "(?=[^>]*max=\"99\")[^>]*>", html)).isTrue();
        assertThat(html).contains("action=\"/cart/items/7\"").contains("action=\"/cart/items/7/delete\"")
                .contains("Cập nhật").contains("Xóa");
    }

    @Test
    void view_formsCarryCsrfTokenAndNoCartOrUserIdAppearsInUrls() throws Exception {
        when(cartService.view(any())).thenReturn(cartOf("1000", available(7, "Sách", "1000", 1)));

        String html = mockMvc.perform(get("/cart").with(user(TestUsers.user())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(matches("<form[^>]*action=\"/cart/items/7\"[^>]*>.*?name=\"_csrf\"", html)).isTrue();
        assertThat(html).doesNotContain("cartId").doesNotContain("userId");
        assertThat(matches("(action|href)=\"[^\"]*(cart|user)[-_]?id", main(html))).isFalse();
    }

    @Test
    void view_escapesProductName() throws Exception {
        when(cartService.view(any())).thenReturn(cartOf("1000",
                available(7, "<script>alert(1)</script>", "1000", 1)));

        String html = getCart();

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    // ---------- AC-004-14: nhãn ----------

    @Test
    void view_showsLabelsAndOnlyDeleteForNonPurchasableLines() throws Exception {
        when(cartService.view(any())).thenReturn(cartOf("2000",
                line(1, "Hàng ngừng bán", "500000", 1, CartLineStatus.DISCONTINUED, 0),
                line(2, "Hàng hết", "600000", 3, CartLineStatus.OUT_OF_STOCK, 0),
                line(3, "Hàng thiếu", "1000", 2, CartLineStatus.LOW_STOCK, 2)));

        String html = getCart();

        assertThat(html).contains("Sản phẩm đã ngừng bán").contains("Chỉ còn 2 sản phẩm");
        assertThat(matches("badge[^>]*>Hết hàng<", html)).isTrue();
        // ngừng bán: tên không là link, không có form sửa; vẫn có Xóa
        assertThat(html).doesNotContain("href=\"/products/1\"").doesNotContain("action=\"/cart/items/1\"")
                .contains("action=\"/cart/items/1/delete\"");
        // hết hàng: không có form sửa, vẫn có Xóa
        assertThat(html).doesNotContain("action=\"/cart/items/2\"").contains("action=\"/cart/items/2/delete\"");
        // thiếu hàng vẫn mua được
        assertThat(html).contains("action=\"/cart/items/3\"").contains("href=\"/products/3\"");
        // "—" cho hai dòng không mua được; số tiền của chúng không được hiện
        assertThat(html.split("<span>—</span>", -1)).hasSize(3);
        assertThat(html).doesNotContain("500.000 ₫ ").doesNotContain("1.800.000");
    }

    // ---------- AC-004-2 ----------

    @Test
    void add_asGuest_redirectsToProductWithSuccessFlashAndCartLink() throws Exception {
        when(cartService.add(any(), eq(7L), eq(2))).thenReturn(new CartChange(CartChange.Outcome.OK, 2));

        mockMvc.perform(add("7", "2"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/products/7"))
                .andExpect(flash().attribute("notice", "Đã thêm vào giỏ hàng."))
                .andExpect(flash().attribute("noticeKind", "success"))
                .andExpect(flash().attribute("showCartLink", true));

        ArgumentCaptor<CartOwner> owner = ArgumentCaptor.forClass(CartOwner.class);
        verify(cartService).add(owner.capture(), eq(7L), eq(2));
        assertThat(owner.getValue()).isInstanceOf(CartOwner.Guest.class);
    }

    // ---------- AC-004-18 / BR-004-8 ----------

    @Test
    void add_resolvesOwnerFromPrincipalAndIgnoresUserIdAndCartIdParams() throws Exception {
        when(cartService.add(any(), eq(7L), eq(2))).thenReturn(new CartChange(CartChange.Outcome.OK, 2));

        mockMvc.perform(add("7", "2").param("userId", "1").param("cartId", "9"))
                .andExpect(redirectedUrl("/products/7"));
        ArgumentCaptor<CartOwner> guest = ArgumentCaptor.forClass(CartOwner.class);
        verify(cartService).add(guest.capture(), eq(7L), eq(2));
        assertThat(guest.getValue()).isInstanceOf(CartOwner.Guest.class);

        mockMvc.perform(add("7", "2").param("userId", "1").param("cartId", "9").with(user(TestUsers.user())))
                .andExpect(redirectedUrl("/products/7"));
        verify(cartService).add(new CartOwner.Customer(2L), 7L, 2);
    }

    @Test
    void updateAndRemove_asAdmin_useAdminPrincipalNotParams() throws Exception {
        when(cartService.update(any(), eq(7L), eq(3))).thenReturn(new CartChange(CartChange.Outcome.OK, 3));

        mockMvc.perform(post("/cart/items/7").with(csrf()).with(user(TestUsers.admin()))
                        .param("quantity", "3").param("userId", "2"))
                .andExpect(redirectedUrl("/cart"));
        mockMvc.perform(post("/cart/items/7/delete").with(csrf()).with(user(TestUsers.admin()))
                        .param("userId", "2").param("cartId", "5"))
                .andExpect(redirectedUrl("/cart"));

        verify(cartService).update(new CartOwner.Customer(1L), 7L, 3);
        verify(cartService).remove(new CartOwner.Customer(1L), 7L);
    }

    // ---------- AC-004-4, 5, CAPPED_AT_MAX ----------

    @Test
    void add_whenCappedAtStock_flashesStockInfoWithCartLink() throws Exception {
        when(cartService.add(any(), eq(7L), eq(5)))
                .thenReturn(new CartChange(CartChange.Outcome.CAPPED_AT_STOCK, 4));

        mockMvc.perform(add("7", "5"))
                .andExpect(redirectedUrl("/products/7"))
                .andExpect(flash().attribute("notice", "Chỉ còn 4 sản phẩm trong kho."))
                .andExpect(flash().attribute("noticeKind", "info"))
                .andExpect(flash().attribute("showCartLink", true));
    }

    @Test
    void add_whenCappedAtMax_flashesMaxMessage() throws Exception {
        when(cartService.add(any(), eq(7L), eq(50)))
                .thenReturn(new CartChange(CartChange.Outcome.CAPPED_AT_MAX, 99));

        mockMvc.perform(add("7", "50"))
                .andExpect(redirectedUrl("/products/7"))
                .andExpect(flash().attribute("notice", "Số lượng tối đa cho mỗi sản phẩm là 99."))
                .andExpect(flash().attribute("noticeKind", "info"))
                .andExpect(flash().attribute("showCartLink", true));
    }

    @Test
    void add_whenOutOfStock_flashesErrorWithoutCartLink() throws Exception {
        when(cartService.add(any(), eq(7L), eq(1)))
                .thenReturn(new CartChange(CartChange.Outcome.OUT_OF_STOCK, 0));

        MvcResult result = mockMvc.perform(add("7", "1"))
                .andExpect(redirectedUrl("/products/7"))
                .andExpect(flash().attribute("notice", "Sản phẩm này đã hết hàng."))
                .andExpect(flash().attribute("noticeKind", "error"))
                .andReturn();

        assertThat((java.util.Map<String, Object>) result.getFlashMap()).doesNotContainKey("showCartLink");
    }

    // ---------- AC-004-6: productId sai / không có ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"abc", "", "99999999999999999999", "-1"})
    void add_withUnparseableProductId_returns404AndDoesNotCallService(String productId) throws Exception {
        assertNotFoundPage(add(productId, "1"));

        verify(cartService, never()).add(any(), anyLong(), anyInt());
    }

    @Test
    void add_whenServiceThrowsNotFound_returns404() throws Exception {
        when(cartService.add(any(), eq(404L), eq(1))).thenThrow(new NotFoundException("Product not found: 404"));

        assertNotFoundPage(add("404", "1"));
    }

    // ---------- AC-004-7: số lượng sai ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "100", "abc", "", "00", "1e2", "2.5"})
    void add_withInvalidQuantity_redirectsWithErrorAndDoesNotCallService(String quantity) throws Exception {
        mockMvc.perform(add("7", quantity))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/products/7"))
                .andExpect(flash().attribute("notice", "Số lượng không hợp lệ."))
                .andExpect(flash().attribute("noticeKind", "error"));

        verify(cartService, never()).add(any(), anyLong(), anyInt());
    }

    @Test
    void add_redirectNeverComesFromRefererOrReturnUrlParam() throws Exception {
        when(cartService.add(any(), eq(7L), eq(1))).thenReturn(new CartChange(CartChange.Outcome.OK, 1));

        mockMvc.perform(add("7", "1").header("Referer", "https://evil.example/")
                        .param("returnUrl", "https://evil.example/").param("redirect", "//evil.example"))
                .andExpect(redirectedUrl("/products/7"));
        mockMvc.perform(add("7", "0").header("Referer", "https://evil.example/")
                        .param("returnUrl", "https://evil.example/"))
                .andExpect(redirectedUrl("/products/7"));
    }

    // ---------- AC-004-11: cập nhật ----------

    @Test
    void update_whenOk_redirectsToCartWithSuccess() throws Exception {
        when(cartService.update(any(), eq(7L), eq(3))).thenReturn(new CartChange(CartChange.Outcome.OK, 3));

        MvcResult result = mockMvc.perform(post("/cart/items/7").with(csrf()).param("quantity", "3"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", "Đã cập nhật số lượng."))
                .andExpect(flash().attribute("noticeKind", "success"))
                .andReturn();

        assertThat((java.util.Map<String, Object>) result.getFlashMap()).doesNotContainKey("showCartLink");
    }

    @Test
    void update_whenCappedAtStock_flashesStockInfo() throws Exception {
        when(cartService.update(any(), eq(7L), eq(9)))
                .thenReturn(new CartChange(CartChange.Outcome.CAPPED_AT_STOCK, 2));

        mockMvc.perform(post("/cart/items/7").with(csrf()).param("quantity", "9"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", "Chỉ còn 2 sản phẩm trong kho."))
                .andExpect(flash().attribute("noticeKind", "info"));
    }

    @Test
    void update_whenOutOfStock_flashesError() throws Exception {
        when(cartService.update(any(), eq(7L), eq(1)))
                .thenReturn(new CartChange(CartChange.Outcome.OUT_OF_STOCK, 0));

        mockMvc.perform(post("/cart/items/7").with(csrf()).param("quantity", "1"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", "Sản phẩm này đã hết hàng."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @Test
    void update_whenNotInCart_redirectsWithoutNotice() throws Exception {
        when(cartService.update(any(), eq(7L), eq(1)))
                .thenReturn(new CartChange(CartChange.Outcome.NOT_IN_CART, 0));

        MvcResult result = mockMvc.perform(post("/cart/items/7").with(csrf()).param("quantity", "1"))
                .andExpect(redirectedUrl("/cart"))
                .andReturn();

        assertThat((java.util.Map<String, Object>) result.getFlashMap()).doesNotContainKey("notice");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "100", "abc", "", "00", "1e2"})
    void update_withInvalidQuantity_redirectsWithErrorAndDoesNotCallService(String quantity) throws Exception {
        MockHttpServletRequestBuilder request = post("/cart/items/7").with(csrf());
        if (quantity != null) {
            request.param("quantity", quantity);
        }

        mockMvc.perform(request)
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", "Số lượng không hợp lệ."))
                .andExpect(flash().attribute("noticeKind", "error"));

        verify(cartService, never()).update(any(), anyLong(), anyInt());
    }

    @Test
    void update_withNonNumericProductId_returns404AndDoesNotCallService() throws Exception {
        assertNotFoundPage(post("/cart/items/abc").with(csrf()).param("quantity", "1"));

        verify(cartService, never()).update(any(), anyLong(), anyInt());
    }

    @Test
    void update_whenServiceThrowsNotFound_returns404() throws Exception {
        when(cartService.update(any(), eq(7L), eq(1))).thenThrow(new NotFoundException("hidden"));

        assertNotFoundPage(post("/cart/items/7").with(csrf()).param("quantity", "1"));
    }

    // ---------- AC-004-12: xóa ----------

    @Test
    void remove_redirectsToCartWithSuccess_evenWhenLineDidNotExist() throws Exception {
        // service.remove là void và không phân biệt có dòng hay không: xóa là lũy đẳng
        mockMvc.perform(post("/cart/items/7/delete").with(csrf()))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("notice", "Đã xóa sản phẩm khỏi giỏ hàng."))
                .andExpect(flash().attribute("noticeKind", "success"));

        verify(cartService).remove(any(CartOwner.Guest.class), eq(7L));
    }

    @Test
    void remove_withNonNumericProductId_returns404AndDoesNotCallService() throws Exception {
        assertNotFoundPage(post("/cart/items/abc/delete").with(csrf()));

        verify(cartService, never()).remove(any(), anyLong());
    }

    // ---------- AC-004-19 ----------

    @Test
    void cartPosts_withoutCsrfToken_areForbiddenAndDoNotCallService() throws Exception {
        mockMvc.perform(post("/cart/items").param("productId", "7").param("quantity", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/items/7").param("quantity", "1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/items/7/delete")).andExpect(status().isForbidden());

        verify(cartService, never()).add(any(), anyLong(), anyInt());
        verify(cartService, never()).update(any(), anyLong(), anyInt());
        verify(cartService, never()).remove(any(), anyLong());
    }

    // ---------- AC-004-22: header ----------

    private static String headerLink(String html) {
        var m = Pattern.compile("<a[^>]*href=\"/cart\"[^>]*>([^<]*)</a>").matcher(html);
        assertThat(m.find()).as("header cart link").isTrue();
        return m.group(1).strip();
    }

    private String cartPageFor(RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get("/cart").with(who)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void header_showsQuantityForGuestUserAndAdmin() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());
        when(cartService.totalQuantity(any())).thenReturn(5);

        for (RequestPostProcessor who : List.of(GUEST, user(TestUsers.user()), user(TestUsers.admin()))) {
            assertThat(headerLink(cartPageFor(who))).isEqualTo("Giỏ hàng (5)");
        }
        verify(cartService).totalQuantity(any(CartOwner.Guest.class));
        verify(cartService).totalQuantity(new CartOwner.Customer(2L));
        verify(cartService).totalQuantity(new CartOwner.Customer(1L));
    }

    @Test
    void header_whenCartEmpty_showsPlainLabelWithoutCount() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());
        when(cartService.totalQuantity(any())).thenReturn(0);

        for (RequestPostProcessor who : List.of(GUEST, user(TestUsers.user()), user(TestUsers.admin()))) {
            String html = cartPageFor(who);
            assertThat(headerLink(html)).isEqualTo("Giỏ hàng");
            assertThat(html).doesNotContain("Giỏ hàng (");
        }
    }

    @Test
    void header_onNotFoundPage_alsoShowsQuantity() throws Exception {
        when(cartService.totalQuantity(any())).thenReturn(3);

        String html = mockMvc.perform(add("abc", "1")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(headerLink(html)).isEqualTo("Giỏ hàng (3)");
    }

    /** Số trên header chỉ để trang trí: truy vấn đếm lỗi thì header hiện "Giỏ hàng", trang vẫn render. */
    @Test
    void header_whenCountQueryFails_pageStillRendersWithPlainLabel() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());
        when(cartService.totalQuantity(any())).thenThrow(new IllegalStateException("DB down"));

        String html = cartPageFor(user(TestUsers.user()));

        assertThat(headerLink(html)).isEqualTo("Giỏ hàng");
    }

    @Test
    void header_isComputedOncePerRender() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());
        when(cartService.totalQuantity(any())).thenReturn(2);

        getCart();

        verify(cartService, times(1)).totalQuantity(any());
    }

    // ---------- GĐ5: nút "Đặt hàng" và link header (AC-005-1, 32) ----------

    private static final String BLOCKED_TEXT =
            "Vui lòng xóa hoặc giảm số lượng các sản phẩm không đủ hàng trước khi đặt hàng.";

    private static boolean hasCheckoutButton(String html) {
        return matches("<a[^>]*href=\"/checkout\"[^>]*>\s*Đặt hàng\s*</a>", main(html));
    }

    @Test
    void view_whenEveryLineAvailable_showsCheckoutLinkForGuestAndUser() throws Exception {
        when(cartService.view(any())).thenReturn(cartOf("300000", available(1, "Áo thun", "100000", 3)));

        for (RequestPostProcessor who : List.of(GUEST, user(TestUsers.user()))) {
            String html = cartPageFor(who);
            assertThat(hasCheckoutButton(html)).isTrue();
            assertThat(main(html)).doesNotContain(BLOCKED_TEXT);
        }
    }

    @ParameterizedTest(name = "a {0} line blocks checkout")
    @EnumSource(value = CartLineStatus.class, names = {"LOW_STOCK", "OUT_OF_STOCK", "DISCONTINUED"})
    void view_whenAnyLineNotAvailable_hidesCheckoutLinkAndShowsBlockedText(CartLineStatus status) throws Exception {
        when(cartService.view(any())).thenReturn(cartOf("100000",
                available(1, "Áo thun", "100000", 1), line(2, "Quần jean", "250000", 3, status, 1)));

        for (RequestPostProcessor who : List.of(GUEST, user(TestUsers.user()))) {
            String html = cartPageFor(who);
            assertThat(html).doesNotContain("href=\"/checkout\"");
            assertThat(main(html)).contains(BLOCKED_TEXT);
        }
    }

    @Test
    void view_whenEmpty_hasNeitherCheckoutLinkNorBlockedText() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());

        String html = cartPageFor(user(TestUsers.user()));

        assertThat(html).doesNotContain("href=\"/checkout\"");
        assertThat(main(html)).doesNotContain(BLOCKED_TEXT);
    }

    @Test
    void header_ordersLink_isShownOnlyToLoggedInUsers() throws Exception {
        when(cartService.view(any())).thenReturn(emptyCart());

        assertThat(cartPageFor(GUEST)).doesNotContain("Đơn hàng của tôi");
        for (RequestPostProcessor who : List.of(user(TestUsers.user()), user(TestUsers.admin()))) {
            String html = cartPageFor(who);
            assertThat(matches("<a[^>]*href=\"/orders\"[^>]*>\s*Đơn hàng của tôi\s*</a>", html)).isTrue();
        }
    }
}

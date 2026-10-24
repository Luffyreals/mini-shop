package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chỉ kiểm chuỗi filter bảo mật (QĐ13): {@code useDefaultFilters = false} nên không nạp controller nào,
 * test không phụ thuộc vào handler của tính năng cụ thể.
 */
@WebMvcTest(useDefaultFilters = false)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    /** AC-001-6: đường dẫn không công khai bị chặn mặc định và chuyển sang trang đăng nhập. */
    @Test
    void nonPublicPath_redirectsAnonymousUserToLogin() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** AC-001-4: ở profile mặc định, /h2-console không công khai và không có filter chain riêng. */
    @Test
    void h2Console_inDefaultProfile_redirectsToLoginAndHasNoDedicatedFilterChain() throws Exception {
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(context.containsBean("h2ConsoleSecurityFilterChain")).isFalse();
    }

    /** AC-001-13: actuator health là công khai, không bị chuyển sang /login (slice không có actuator nên không assert 200). */
    @Test
    void actuatorHealth_isNotRedirectedToLoginForAnonymous() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

        assertThat(HttpStatus.valueOf(result.getResponse().getStatus()).is3xxRedirection()).isFalse();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    /**
     * AC-001-16: trang công khai có CSP chặt, chỉ cho phép tài nguyên cùng origin.
     * Chỉ assert header: không có controller nên "/" có thể trả 404, nhưng header vẫn được thêm.
     */
    @Test
    void homePage_carriesContentSecurityPolicy() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("img-src 'self' data:")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    /** AC-001-16 "mọi trang": cả response chuyển hướng của trang cần đăng nhập cũng mang CSP. */
    @Test
    void protectedPageRedirect_alsoCarriesContentSecurityPolicy() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    /** Đường dẫn công khai chỉ áp dụng cho GET: POST vào /products/** (ví dụ thêm giỏ ở GĐ4) vẫn phải đăng nhập. */
    @Test
    void publicPaths_areGetOnly_postToProductsRequiresLogin() throws Exception {
        mockMvc.perform(post("/products/1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void staticResources_arePublic() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    /** AC-003-15: khách chưa đăng nhập vào khu vực cần quyền thì được chuyển sang /login. */
    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/products", "/orders/1", "/checkout", "/checkout/x"})
    void protectedAreas_redirectAnonymousToLogin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** AC-003-14 */
    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/x"})
    @WithMockUser(roles = "USER")
    void admin_asUser_isForbiddenAndForwardedToAccessDenied(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-003-14 */
    @Test
    @WithMockUser(roles = "USER")
    void adminPost_asUserWithCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/x").with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** AC-003-16: ADMIN qua được bộ lọc (slice không có controller nên là 404, không phải 403 hay redirect). */
    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/x"})
    @WithMockUser(roles = "ADMIN")
    void admin_asAdmin_passesSecurityFilter(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        assertThat(HttpStatus.valueOf(result.getResponse().getStatus()).is3xxRedirection()).isFalse();
        assertThat(result.getResponse().getForwardedUrl()).isNull();
    }

    /**
     * Lỗi của một request POST được container dispatch lại thành {@code POST /error}: trang lỗi phải hiện được
     * cho khách, không bị chuyển sang /login (ví dụ POST /register gặp lỗi 500).
     */
    @Test
    void errorPage_forPostDispatch_isNotRedirectedToLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/error").with(csrf())).andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    /**
     * Spec 003 §6: các trang đăng nhập/đăng ký/403 mở cho khách, kể cả khi có query string
     * ({@code /login?error} sau khi đăng nhập sai, AC-003-11).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/login", "/login?error", "/login?foo", "/register", "/access-denied"})
    void authPages_getIsNotRedirectedForAnonymous(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    @Test
    void registerPost_withCsrf_isNotRedirectedToLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/register").with(csrf())).andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    /** AC-003-18 */
    @Test
    void registerPost_withoutCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/register"))
                .andExpect(status().isForbidden());
    }

    /** AC-003-18 */
    @Test
    void loginPost_withoutCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/login").param("email", "a@b.vn").param("password", "x"))
                .andExpect(status().isForbidden());
    }

    /** AC-003-18 */
    @Test
    @WithMockUser
    void logoutPost_withoutCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/logout"))
                .andExpect(status().isForbidden());
    }

    /** AC-004-23: giỏ mở cho khách, kể cả khi có query string thật (bài học GĐ3). */
    @ParameterizedTest
    @ValueSource(strings = {"/cart", "/cart?x", "/cart?x=1"})
    void cartGet_isNotRedirectedForAnonymous(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    /** AC-004-23 */
    @ParameterizedTest
    @ValueSource(strings = {"/cart/items", "/cart/items/1", "/cart/items/1/delete"})
    void cartPost_withCsrf_isNotRedirectedToLoginForAnonymous(String path) throws Exception {
        MvcResult result = mockMvc.perform(post(path).with(csrf())).andReturn();

        // Slice không có controller nên kỳ vọng 404; 403 hay 5xx đều là lỗi của chuỗi filter.
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403).isLessThan(500);
    }

    /** AC-004-19 */
    @ParameterizedTest
    @ValueSource(strings = {"/cart/items", "/cart/items/1", "/cart/items/1/delete"})
    void cartPost_withoutCsrf_asAnonymous_isForbidden(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-004-19 */
    @ParameterizedTest
    @ValueSource(strings = {"/cart/items", "/cart/items/1", "/cart/items/1/delete"})
    @WithMockUser
    void cartPost_withoutCsrf_asUser_isForbidden(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-005-25, AC-005-6: đơn hàng và thanh toán cần đăng nhập, kể cả URL có query string thật (bài học GĐ3). */
    @ParameterizedTest
    @ValueSource(strings = {"/orders", "/orders?page=2", "/orders/abc", "/orders/1", "/checkout", "/checkout?continue"})
    void orderAndCheckoutPages_redirectAnonymousToLogin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** AC-005-6: POST có CSRF nhưng chưa đăng nhập thì được chuyển sang /login, không chạm tới handler. */
    @ParameterizedTest
    @ValueSource(strings = {"/checkout", "/orders/1/cancel"})
    void orderAndCheckoutPost_withCsrf_redirectsAnonymousToLogin(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** AC-005-19: người đã đăng nhập gửi POST thiếu token CSRF bị chặn (403, trang access-denied). */
    @ParameterizedTest
    @ValueSource(strings = {"/checkout", "/orders/1/cancel"})
    @WithMockUser
    void orderAndCheckoutPost_withoutCsrf_asUser_isForbidden(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-005-19: người đã đăng nhập, có CSRF thì qua bộ lọc (slice không có controller nên là 404, không phải 403). */
    @ParameterizedTest
    @ValueSource(strings = {"/checkout", "/orders/1/cancel"})
    @WithMockUser
    void orderAndCheckoutPost_withCsrf_asUser_passesSecurityFilter(String path) throws Exception {
        MvcResult result = mockMvc.perform(post(path).with(csrf())).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getForwardedUrl()).isNull();
    }

    /** QĐ12: file JS xác nhận hủy là tài nguyên tĩnh công khai, khách không bị chuyển sang /login. */
    @Test
    void confirmScript_isPublicForAnonymous() throws Exception {
        mockMvc.perform(get("/js/confirm.js"))
                .andExpect(status().isOk());
    }

    /** AC-004-23: /cart/** mở không làm lộ POST vào /products và /products/{id}. */
    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/1"})
    void productsPost_withCsrf_asAnonymous_stillRedirectsToLogin(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---------- GĐ6: khu quản trị và /uploads (spec 006 §6) ----------

    /** Mọi URL GET của spec 006 §6, kể cả có query string thật (bài học GĐ3). */
    private static final String[] ADMIN_GET_URLS = {
            "/admin", "/admin/categories", "/admin/categories/new", "/admin/categories/1/edit",
            "/admin/products", "/admin/products?q=a&page=2", "/admin/products/new", "/admin/products/1/edit",
            "/admin/orders", "/admin/orders?status=PENDING", "/admin/orders/1"};

    /** Mọi URL POST của spec 006 §6; hai URL sản phẩm đầu là multipart. */
    private static final String[] ADMIN_POST_URLS = {
            "/admin/categories", "/admin/categories/1", "/admin/categories/1/delete",
            "/admin/products", "/admin/products/1",
            "/admin/products/1/hide", "/admin/products/1/show", "/admin/products/1/stock",
            "/admin/orders/1/status"};

    private static boolean isMultipartUrl(String path) {
        return path.equals("/admin/products") || path.equals("/admin/products/1");
    }

    private static AbstractMockHttpServletRequestBuilder<?> adminPost(String path) {
        if (isMultipartUrl(path)) {
            return multipart(path).file(new MockMultipartFile("image", "a.png", "image/png", new byte[]{1, 2, 3}));
        }
        return post(path);
    }

    static Stream<String> adminGetUrls() {
        return Stream.of(ADMIN_GET_URLS);
    }

    static Stream<String> adminPostUrls() {
        return Stream.of(ADMIN_POST_URLS);
    }

    /** AC-006-1 */
    @ParameterizedTest
    @MethodSource("adminGetUrls")
    void adminGet_asAnonymous_redirectsToLogin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** AC-006-1: a POST with a valid CSRF token still goes to /login, never reaches a handler (multipart too). */
    @ParameterizedTest
    @MethodSource("adminPostUrls")
    void adminPost_asAnonymousWithCsrf_redirectsToLogin(String path) throws Exception {
        mockMvc.perform(adminPost(path).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** AC-006-2 */
    @ParameterizedTest
    @MethodSource("adminGetUrls")
    @WithMockUser(roles = "USER")
    void adminGet_asUser_isForbiddenWithTheAccessDeniedPage(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-006-2: a USER with a valid token is still refused. */
    @ParameterizedTest
    @MethodSource("adminPostUrls")
    @WithMockUser(roles = "USER")
    void adminPost_asUserWithCsrf_isForbiddenWithTheAccessDeniedPage(String path) throws Exception {
        mockMvc.perform(adminPost(path).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-006-3: no token, no change, including the multipart upload forms. */
    @ParameterizedTest
    @MethodSource("adminPostUrls")
    @WithMockUser(roles = "ADMIN")
    void adminPost_asAdminWithoutCsrf_isForbidden(String path) throws Exception {
        mockMvc.perform(adminPost(path))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }

    /** AC-006-3: a wrong token is the same as no token. */
    @ParameterizedTest
    @MethodSource("adminPostUrls")
    @WithMockUser(roles = "ADMIN")
    void adminPost_asAdminWithInvalidCsrf_isForbidden(String path) throws Exception {
        mockMvc.perform(adminPost(path).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    /** AC-006-3: an upload whose token is only in the body is read after multipart parsing and still counts. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMultipartPost_withoutCsrf_isForbiddenEvenWithAFileAndFields() throws Exception {
        mockMvc.perform(multipart("/admin/products")
                        .file(new MockMultipartFile("image", "a.png", "image/png", new byte[]{1}))
                        .param("name", "X"))
                .andExpect(status().isForbidden());
    }

    /** Sanity: the same requests as ADMIN with a token pass the filter chain (slice has no handler: 404). */
    @ParameterizedTest
    @MethodSource("adminPostUrls")
    @WithMockUser(roles = "ADMIN")
    void adminPost_asAdminWithCsrf_passesTheSecurityFilter(String path) throws Exception {
        MvcResult result = mockMvc.perform(adminPost(path).with(csrf())).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getForwardedUrl()).isNull();
    }

    /** Sanity: every GET URL passes the filter for ADMIN. */
    @ParameterizedTest
    @MethodSource("adminGetUrls")
    @WithMockUser(roles = "ADMIN")
    void adminGet_asAdmin_passesTheSecurityFilter(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getForwardedUrl()).isNull();
    }

    /** BR-006-1, AC-006-27: product images are public, even with a query string. */
    @ParameterizedTest
    @ValueSource(strings = {"/uploads/x.png", "/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.webp", "/uploads/x.png?v=1"})
    void uploadsGet_asAnonymous_isNotRedirectedToLogin(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        // Slice has no resource handler, so 404 is expected; a redirect or 403 would be a filter-chain bug.
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    /** BR-006-1: only GET is public under /uploads. */
    @ParameterizedTest
    @ValueSource(strings = {"/uploads/x.png", "/uploads/", "/uploads/sub/x.png"})
    void uploadsPost_withCsrf_asAnonymous_redirectsToLogin(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void uploadsPut_andDelete_asAnonymous_areNotPublic() throws Exception {
        mockMvc.perform(put("/uploads/x.png").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(delete("/uploads/x.png").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** BR-006-1: opening /uploads/** must not open anything else: the sidebar of the shop stays closed too. */
    @ParameterizedTest
    @ValueSource(strings = {"/uploadsx", "/upload", "/uploads-private/x.png"})
    void pathsThatOnlyLookLikeUploads_stayProtected(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}

package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}

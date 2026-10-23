package com.minishop.config;

import org.junit.jupiter.api.Test;
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
}

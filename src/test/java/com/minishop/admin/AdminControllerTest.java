package com.minishop.admin;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import com.minishop.user.Role;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Trang quản trị tạm và header đã đăng nhập (AC-003-9 phần header, 14, 16, 21 bản slice). */
@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void adminPage_asAdmin_rendersPlaceholderAndHeaderWithAdminLinkAndLogoutForm() throws Exception {
        String html = mockMvc.perform(get("/admin").with(user(TestUsers.admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"))
                .andExpect(content().string(containsString("<title>Quản trị | Mini Shop</title>")))
                .andExpect(content().string(containsString("Trang quản trị")))
                .andExpect(content().string(containsString("Khu quản trị sẽ được xây dựng ở giai đoạn sau.")))
                .andExpect(content().string(containsString("Xin chào, Quản trị viên")))
                .andExpect(content().string(containsString("href=\"/admin\">Quản trị</a>")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("(?s)<form[^>]*action=\"/logout\"[^>]*method=\"post\".*?"
                + "name=\"_csrf\".*?Đăng xuất</button>.*?</form>");
    }

    @Test
    void adminPage_withMaliciousFullName_escapesItInHeader() throws Exception {
        String html = mockMvc.perform(get("/admin")
                        .with(user(TestUsers.withName("<script>alert(1)</script>", Role.ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Xin chào, &lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void adminPage_asUser_isForbiddenAndForwardedToAccessDenied() throws Exception {
        mockMvc.perform(get("/admin").with(user(TestUsers.user())))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/access-denied"));
    }
}

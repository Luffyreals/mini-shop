package com.minishop.common;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Trang 403 (AC-003-14, 16; QĐ6). */
@WebMvcTest(AccessDeniedController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AccessDeniedTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void accessDenied_asUser_returns403PageWithHomeLinkAndNoAdminLink() throws Exception {
        mockMvc.perform(get("/access-denied").with(user(TestUsers.user())))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"))
                .andExpect(content().string(containsString("<title>Không có quyền truy cập | Mini Shop</title>")))
                .andExpect(content().string(containsString("Bạn không có quyền truy cập trang này.")))
                .andExpect(content().string(containsString("href=\"/\">Về trang chủ</a>")))
                .andExpect(content().string(containsString("Xin chào, Khách hàng mẫu")))
                .andExpect(content().string(not(containsString("Quản trị"))))
                .andExpect(content().string(not(containsString("href=\"/admin\""))));
    }

    @Test
    void accessDenied_postWithCsrf_stillReturns403() throws Exception {
        mockMvc.perform(post("/access-denied").with(user(TestUsers.user())).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));
    }

    @Test
    void accessDenied_asAnonymous_returns403InsteadOfRedirect() throws Exception {
        mockMvc.perform(get("/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Bạn không có quyền truy cập trang này.")));
    }
}

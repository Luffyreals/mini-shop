package com.minishop.common;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Trang 403 (AC-003-14, 16; QĐ6) và hai nghĩa của 403 (AC-007-8, 9; BR-007-4): lỗi CSRF hiện trang "Phiên làm việc
 * đã hết hạn", thiếu quyền giữ trang cũ. Controller phân biệt bằng exception mà Spring Security lưu trong attribute
 * {@code WebAttributes.ACCESS_DENIED_403} trước khi forward.
 */
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

    // ----------------------------------------------------------- CSRF: session expired page

    private static final String EXPIRED_TITLE = "Phiên làm việc đã hết hạn";
    private static final String EXPIRED_TEXT =
            "Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại.";

    private static void assertSessionExpiredPage(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(view().name("error/page"))
                .andExpect(model().attribute("errorHeading", EXPIRED_TITLE))
                .andExpect(content().string(containsString("<title>" + EXPIRED_TITLE + " | Mini Shop</title>")))
                .andExpect(content().string(containsString(">" + EXPIRED_TITLE + "</h1>")))
                .andExpect(content().string(containsString(">" + EXPIRED_TEXT + "<")))
                .andExpect(content().string(containsString("href=\"/\">Về trang chủ</a>")))
                .andExpect(content().string(not(containsString("Bạn không có quyền"))))
                .andExpect(content().string(not(containsString("admin-body"))));
    }

    @Test
    void accessDenied_withMissingCsrfToken_showsSessionExpiredPage() throws Exception {
        assertSessionExpiredPage(mockMvc.perform(get("/access-denied")
                .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))));
    }

    @Test
    void accessDenied_withInvalidCsrfToken_showsSessionExpiredPageWithoutTheToken() throws Exception {
        InvalidCsrfTokenException cause = new InvalidCsrfTokenException(
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "expected-secret-token"), "actual-secret-token");

        assertSessionExpiredPage(mockMvc.perform(get("/access-denied")
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, cause))
                .andExpect(content().string(not(containsString("expected-secret-token"))))
                .andExpect(content().string(not(containsString("actual-secret-token")))));
    }

    @Test
    void accessDenied_withCsrfFailureOnPost_showsSessionExpiredPage() throws Exception {
        assertSessionExpiredPage(mockMvc.perform(post("/access-denied").with(csrf())
                .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException("old-token"))));
    }

    @Test
    void accessDenied_withCsrfFailureForLoggedInUser_showsSessionExpiredPageInStorefrontLayout() throws Exception {
        assertSessionExpiredPage(mockMvc.perform(get("/access-denied").with(user(TestUsers.user()))
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, new InvalidCsrfTokenException(
                                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t"), "x")))
                .andExpect(content().string(containsString("Xin chào, Khách hàng mẫu"))));
    }

    @Test
    void accessDenied_withCsrfFailureForAdmin_stillShowsStorefrontLayoutBecauseTheSessionMayBeGone() throws Exception {
        assertSessionExpiredPage(mockMvc.perform(get("/access-denied").with(user(TestUsers.admin()))
                .requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))));
    }

    @Test
    void accessDenied_withMissingPermission_keepsTheOldForbiddenPage() throws Exception {
        mockMvc.perform(get("/access-denied").with(user(TestUsers.user()))
                        .requestAttr(WebAttributes.ACCESS_DENIED_403, new AccessDeniedException("x")))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"))
                .andExpect(content().string(containsString("Bạn không có quyền truy cập trang này.")))
                .andExpect(content().string(not(containsString(EXPIRED_TITLE))))
                .andExpect(content().string(not(containsString(EXPIRED_TEXT))));
    }

    @Test
    void accessDenied_withNonExceptionAttribute_keepsTheOldForbiddenPage() throws Exception {
        mockMvc.perform(get("/access-denied").requestAttr(WebAttributes.ACCESS_DENIED_403, "not an exception"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));
    }
}

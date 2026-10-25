package com.minishop.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-005-2: khách có giỏ bấm "Đặt hàng", được đưa qua {@code /login}, đăng nhập thật rồi quay lại
 * {@code /checkout} với giỏ đã gộp. Đăng nhập thật nên phát {@code InteractiveAuthenticationSuccessEvent} và
 * request đã lưu được dùng lại. Helper đăng nhập chép từ CartMergeIntegrationTest (không phụ thuộc chéo).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    OrderTestData data;

    @BeforeEach
    void setUp() {
        data = new OrderTestData(jdbc, encoder);
        data.cleanUp();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    /** FormLoginRequestBuilder has no session(...), so wrap it and attach the session to the built request. */
    private static RequestBuilder formLoginIn(String email, String password, MockHttpSession session) {
        RequestBuilder login = formLogin("/login").userParameter("email").user(email).password(password);
        return context -> {
            MockHttpServletRequest request = login.buildRequest(context);
            request.setSession(session);
            return request;
        };
    }

    @Test
    void guestWithCart_clickingCheckout_logsInAndLandsOnCheckoutWithMergedCart() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("Flow", 100_000, 10);
        String name = data.productName(a);
        MockHttpSession guest = new MockHttpSession();
        mockMvc.perform(post("/cart/items").with(csrf()).session(guest)
                        .param("productId", String.valueOf(a)).param("quantity", "2"))
                .andExpect(status().is3xxRedirection());
        assertThat(data.cartLines(u.id())).isEmpty();

        // The guest is sent to the login page.
        MvcResult blocked = mockMvc.perform(get("/checkout").session(guest))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(URI.create(blocked.getResponse().getRedirectedUrl()).getPath()).isEqualTo("/login");

        // Real login with the same session: the saved request sends the user back to /checkout.
        MvcResult login = mockMvc.perform(formLoginIn(u.email(), OrderTestData.PASSWORD, guest))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI back = URI.create(login.getResponse().getRedirectedUrl());
        assertThat(back.getPath()).startsWith("/checkout");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 2));

        String url = back.getPath() + (back.getRawQuery() == null ? "" : "?" + back.getRawQuery());
        mockMvc.perform(get(url).session(session))
                .andExpect(authenticated())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)))
                .andExpect(content().string(containsString("200.000 ₫")))
                .andExpect(content().string(containsString("Xác nhận đặt hàng")))
                .andExpect(content().string(containsString("Order Tester")));
    }
}

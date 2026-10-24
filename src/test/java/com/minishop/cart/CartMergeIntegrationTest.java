package com.minishop.cart;

import com.minishop.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gộp giỏ khách vào giỏ DB bằng đăng nhập thật (QĐ5): chứng minh {@code InteractiveAuthenticationSuccessEvent}
 * được phát, listener thấy attribute đã chuyển sang session mới, và redirect của success handler không đổi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CartMergeIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    CartTestData data;

    @BeforeEach
    void setUp() {
        data = new CartTestData(jdbc, encoder);
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

    private MvcResult login(CartTestData.TestUser u, MockHttpSession session) throws Exception {
        return mockMvc.perform(formLoginIn(u.email(), CartTestData.PASSWORD, session)).andReturn();
    }

    private static MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void guestAdd(MockHttpSession session, long productId, int quantity) throws Exception {
        mockMvc.perform(post("/cart/items").with(csrf()).session(session)
                        .param("productId", String.valueOf(productId)).param("quantity", String.valueOf(quantity)))
                .andExpect(status().is3xxRedirection());
    }

    /** AC-004-15 */
    @Test
    void login_withGuestCart_mergesCappedAtStockClearsSessionCartAndShowsHeader() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 4);
        long b = data.createProduct("B", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 3});
        MockHttpSession guest = new MockHttpSession();
        guestAdd(guest, a, 2);
        guestAdd(guest, b, 1);
        assertThat(guest.getAttribute(SessionCart.SESSION_ATTRIBUTE)).isNotNull();

        MvcResult result = login(u, guest);

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(Math.min(a, b), Math.min(a, b) == a ? 4 : 1),
                Map.entry(Math.max(a, b), Math.max(a, b) == a ? 4 : 1));
        MockHttpSession session = sessionOf(result);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(SessionCart.SESSION_ATTRIBUTE)).isNull();
        mockMvc.perform(get("/").session(session))
                .andExpect(authenticated())
                .andExpect(content().string(containsString("Giỏ hàng (5)")));
    }

    /** AC-004-15 + AC-003-10: redirect về trang đã bị chặn vẫn hoạt động, giỏ vẫn được gộp. */
    @Test
    void login_afterVisitingOrders_redirectsToOrdersAndStillMerges() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        MockHttpSession guest = new MockHttpSession();
        guestAdd(guest, a, 2);
        mockMvc.perform(get("/orders").session(guest)).andExpect(status().is3xxRedirection());

        MvcResult result = login(u, guest);

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(URI.create(result.getResponse().getRedirectedUrl()).getPath()).startsWith("/orders");
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 2));
    }

    /** AC-004-16 */
    @Test
    void login_withHiddenAndOutOfStockGuestLines_skipsThemAndStillLogsIn() throws Exception {
        var u = data.createUser();
        long ok = data.createProduct("OK", 100_000, 10);
        long hidden = data.createProduct("Hidden", 100_000, 10);
        long soldOut = data.createProduct("Sold out", 100_000, 10);
        MockHttpSession guest = new MockHttpSession();
        guestAdd(guest, ok, 1);
        guestAdd(guest, hidden, 1);
        guestAdd(guest, soldOut, 1);
        data.setActive(hidden, false);
        data.setStock(soldOut, 0);

        MvcResult result = mockMvc.perform(formLoginIn(u.email(), CartTestData.PASSWORD, guest))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated())
                .andReturn();

        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(ok, 1));
        assertThat(sessionOf(result).getAttribute(SessionCart.SESSION_ATTRIBUTE)).isNull();
    }

    /** AC-004-17 */
    @Test
    void logoutThenLogin_keepsDbCartAndGuestSeesEmptyCartAfterLogout() throws Exception {
        var u = data.createUser();
        long a = data.createProduct("A", 100_000, 10);
        data.createCart(u.id(), new long[]{a, 3});
        MockHttpSession session = sessionOf(login(u, new MockHttpSession()));
        assertThat(session).isNotNull();

        mockMvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(redirectedUrl("/?logout"));

        mockMvc.perform(get("/cart").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giỏ hàng của bạn đang trống.")));
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 3));

        MockHttpSession again = sessionOf(login(u, new MockHttpSession()));
        assertThat(again).isNotNull();
        mockMvc.perform(get("/cart").session(again))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giỏ hàng (3)")));
        assertThat(data.cartLines(u.id())).containsExactly(Map.entry(a, 3));
    }

    /** BR-004-6 */
    @Test
    void login_withoutGuestCart_createsNoCartRow() throws Exception {
        var u = data.createUser();
        int before = data.totalRows("carts");

        mockMvc.perform(formLoginIn(u.email(), CartTestData.PASSWORD, new MockHttpSession()))
                .andExpect(authenticated());

        assertThat(data.cartCount(u.id())).isZero();
        assertThat(data.totalRows("carts")).isEqualTo(before);
    }
}

package com.minishop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chạy ở mọi nơi, không cần Docker: V1 chạy được trên H2 (PostgreSQL mode), Hibernate validate đạt,
 * seed chạy được (AC-001-13, AC-001-4 phía profile h2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class H2ProfileSmokeTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.seed.user-password}")
    String userPassword;

    @Test
    void contextLoads_withFlywayAndHibernateValidateOnH2() {
        assertThat(context).isNotNull();
    }

    @Test
    void healthEndpoint_isPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void h2ConsoleSecurityFilterChain_existsInH2Profile() {
        assertThat(context.containsBean("h2ConsoleSecurityFilterChain")).isTrue();
    }

    /**
     * AC-001-4 phía profile h2: lớp bảo mật cho qua {@code /h2-console}, không chuyển sang trang đăng nhập.
     * MockMvc chỉ chạy qua DispatcherServlet chứ không qua servlet riêng của H2 console, nên ở đây không
     * assert 200; việc console thật sự mở được nằm trong checklist của phase-verifier.
     */
    @Test
    void h2Console_isNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/h2-console/"))
                .andExpect(result -> assertThat(result.getResponse().getStatus() / 100).isNotEqualTo(3))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isNull());
    }

    /** V2 có hiệu lực trên H2. Lệnh insert bị từ chối nên không để lại dòng nào (seed vẫn đúng 2 người dùng). */
    @Test
    void v2_uppercaseEmail_isRejectedOnH2() {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where \"version\" = '2' and \"success\"", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "insert into users (email, password_hash, full_name, role) values ('A@x.local', 'h', 'A', 'USER')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loginPage_isPublicOnH2() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void seed_populatesProductsAndUsers() {
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isGreaterThanOrEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
    }

    /** AC-002-1, 2 trên H2: truy vấn Specification thật; 22 sản phẩm seed trừ 1 sản phẩm bị ẩn. */
    @Test
    void productList_isPublicAndCountsOnlyActiveSeedProducts() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tìm thấy 21 sản phẩm")));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tìm thấy 21 sản phẩm")));
    }

    /** AC-002-16: mọi image_path của dữ liệu mẫu trỏ tới file có thật trong static/. */
    @Test
    void seededImagePaths_pointToExistingStaticResources() {
        List<String> paths = jdbc.queryForList(
                "select image_path from products where image_path is not null", String.class);

        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path -> assertThat(new ClassPathResource("static" + path).exists())
                .as("static resource for %s", path).isTrue());
    }

    /**
     * GĐ4 trên H2: thêm giỏ của người dùng đăng nhập chạy {@code lockOwner} (select ... for update) và ghi
     * giỏ DB; khách thêm giỏ chỉ ghi session. Dọn giỏ của tài khoản seed ở cuối, không thêm người dùng.
     */
    @Test
    void cart_guestAddAndLoggedInAdd_workOnH2() throws Exception {
        Long productId = jdbc.queryForObject(
                "select id from products where active = true and stock > 0 order by id limit 1", Long.class);
        String name = jdbc.queryForObject("select name from products where id = ?", String.class, productId);
        Long userId = jdbc.queryForObject("select id from users where email = 'user@minishop.local'", Long.class);
        try {
            MockHttpSession guest = new MockHttpSession();
            mockMvc.perform(post("/cart/items").with(csrf()).session(guest)
                            .param("productId", String.valueOf(productId)).param("quantity", "1"))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(get("/cart").session(guest))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(name)));
            // Chỉ xét giỏ của tài khoản seed, để test không phụ thuộc dữ liệu do test khác để lại.
            assertThat(jdbc.queryForObject("select count(*) from carts where user_id = ?", Integer.class, userId))
                    .isZero();

            // Real login carries the guest cart; merge runs lockOwner on H2.
            RequestBuilder login = formLogin("/login").userParameter("email")
                    .user("user@minishop.local").password(userPassword);
            MvcResult result = mockMvc.perform(context -> {
                MockHttpServletRequest request = login.buildRequest(context);
                request.setSession(guest);
                return request;
            }).andExpect(status().is3xxRedirection()).andReturn();
            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            assertThat(jdbc.queryForObject("select count(*) from cart_items i join carts c on c.id = i.cart_id "
                    + "where c.user_id = ? and i.product_id = ?", Integer.class, userId, productId)).isEqualTo(1);

            mockMvc.perform(post("/cart/items").with(csrf()).session(session)
                            .param("productId", String.valueOf(productId)).param("quantity", "1"))
                    .andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("select quantity from cart_items i join carts c on c.id = i.cart_id "
                    + "where c.user_id = ?", Integer.class, userId)).isEqualTo(Math.min(2,
                    jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)));
            mockMvc.perform(get("/cart").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(name)));
        } finally {
            jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id = ?)", userId);
            jdbc.update("delete from carts where user_id = ?", userId);
        }
    }

    /**
     * GĐ5 trên H2 (AC-005-34): đặt rồi hủy một đơn bằng tài khoản seed chạy được {@code lockOwner},
     * {@code decrementStock}, câu xóa giỏ, {@code findDetailByIdAndUserId} và {@code lockByIdAndUserId}
     * ({@code for update}) cùng {@code incrementStock}. Khôi phục kho và xóa đơn, giỏ ở finally; không thêm người dùng.
     */
    @Test
    void order_placeThenCancel_worksOnH2AndRestoresStock() throws Exception {
        Map<String, Object> product = jdbc.queryForMap(
                "select id, price, stock from products where active = true and stock > 1 order by id limit 1");
        Long productId = ((Number) product.get("ID")).longValue();
        int stockBefore = ((Number) product.get("STOCK")).intValue();
        String price = ((java.math.BigDecimal) product.get("PRICE")).toPlainString();
        Long userId = jdbc.queryForObject("select id from users where email = 'user@minishop.local'", Long.class);
        try {
            RequestBuilder login = formLogin("/login").userParameter("email")
                    .user("user@minishop.local").password(userPassword);
            MockHttpSession session = (MockHttpSession) mockMvc.perform(login)
                    .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);

            mockMvc.perform(post("/cart/items").with(csrf()).session(session)
                            .param("productId", String.valueOf(productId)).param("quantity", "1"))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(get("/checkout").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Xác nhận đặt hàng")));

            String location = mockMvc.perform(post("/checkout").with(csrf()).session(session)
                            .param("receiverName", "Khách hàng mẫu").param("phone", "0912 345 678")
                            .param("shippingAddress", "12 Lê Lợi").param("expectedTotal", price))
                    .andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            assertThat(location).startsWith("/orders/");
            long orderId = Long.parseLong(location.substring("/orders/".length()));
            assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId))
                    .isEqualTo(stockBefore - 1);
            assertThat(jdbc.queryForObject("select count(*) from cart_items i join carts c on c.id = i.cart_id "
                    + "where c.user_id = ?", Integer.class, userId)).isZero();
            assertThat(jdbc.queryForObject("select phone from orders where id = ?", String.class, orderId))
                    .isEqualTo("0912345678");
            mockMvc.perform(get(location).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Hủy đơn hàng")));

            mockMvc.perform(post(location + "/cancel").with(csrf()).session(session))
                    .andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                    .isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId))
                    .isEqualTo(stockBefore);
        } finally {
            jdbc.update("delete from order_items where order_id in (select id from orders where user_id = ?)", userId);
            jdbc.update("delete from orders where user_id = ?", userId);
            jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id = ?)", userId);
            jdbc.update("delete from carts where user_id = ?", userId);
            jdbc.update("update products set stock = ? where id = ?", stockBefore, productId);
        }
    }
}

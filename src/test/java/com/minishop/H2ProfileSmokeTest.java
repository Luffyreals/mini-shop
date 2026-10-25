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
import static org.assertj.core.api.Assertions.tuple;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDeleteResult;
import com.minishop.catalog.LowStockRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderDashboard;
import com.minishop.order.StatusCount;
import com.minishop.support.TestImages;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @Autowired
    CategoryAdminService categoryAdminService;

    @Autowired
    ProductAdminService productAdminService;

    @Autowired
    OrderAdminService orderAdminService;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

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

    /**
     * AC-008-6, 13 (ADR-0012): the H2 profile replaces the PostgreSQL {@code SET lock_timeout} with H2's own syntax
     * ({@code SET LOCK_TIMEOUT 5000}, milliseconds). If it were wrong, Hikari could not create a single connection and
     * the context would not start; this pins the value too. H2's {@code LOCK_TIMEOUT()} function reports the setting of
     * the current session, and the pool runs the init SQL on every physical connection.
     */
    @Test
    void connections_haveFiveSecondLockTimeoutOnH2() {
        assertThat(jdbc.queryForObject("select lock_timeout()", Integer.class)).isEqualTo(5000);
    }

    /** AC-008-5, 6: only the h2 profile turns the H2 console on (application.yml turns it off for every other profile). */
    @Test
    void h2ConsoleProperty_isEnabledInH2Profile() {
        assertThat(context.getEnvironment().getProperty("spring.h2.console.enabled", Boolean.class)).isTrue();
        assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET LOCK_TIMEOUT 5000");
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

    private MockHttpSession login(String email, String password) throws Exception {
        RequestBuilder login = formLogin("/login").userParameter("email").user(email).password(password);
        return (MockHttpSession) mockMvc.perform(login).andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession(false);
    }

    /**
     * GĐ6 trên H2 (AC-006-51): admin thêm danh mục, thêm sản phẩm có ảnh PNG, điều chỉnh kho; khách seed đặt hai
     * đơn; admin đưa đơn một CONFIRMED, SHIPPING, DELIVERED và hủy đơn hai (kho về đúng). Chạy cả các truy vấn mới
     * ({@code countByStatus}, {@code sumTotalByStatus}, {@code findAllWithProductCount}, {@code adjustStock} ở hai
     * đầu 0 và 100000, {@code lockById} của đơn và danh mục) trên H2. Dọn đơn, sản phẩm, danh mục và file ở finally.
     */
    @Test
    void admin_fullFlow_worksOnH2AndRestoresStockOnCancel() throws Exception {
        String categoryName = "Admin test H2 category";
        String productName = "Admin test H2 product";
        Path uploadDir = Path.of(uploadDirSetting).toAbsolutePath().normalize();
        Long userId = jdbc.queryForObject("select id from users where email = 'user@minishop.local'", Long.class);
        try {
            MockHttpSession admin = login("admin@minishop.local", adminPassword);
            MockHttpSession customer = login("user@minishop.local", userPassword);
            for (String url : List.of("/admin", "/admin/orders", "/admin/products", "/admin/categories",
                    "/admin/categories/new", "/admin/products/new")) {
                mockMvc.perform(get(url).session(admin)).andExpect(status().isOk());
            }
            // No order at all: no row from countByStatus, and 0 (not null) from sumTotalByStatus.
            OrderDashboard empty = orderAdminService.dashboard();
            assertThat(empty.counts()).extracting(StatusCount::count).containsOnly(0L);
            assertThat(empty.deliveredRevenue()).isEqualByComparingTo(BigDecimal.ZERO);

            // Category and product (with a PNG) through the real controllers.
            mockMvc.perform(post("/admin/categories").with(csrf()).session(admin).param("name", categoryName))
                    .andExpect(status().is3xxRedirection());
            Long categoryId = jdbc.queryForObject("select id from categories where name = ?", Long.class, categoryName);
            mockMvc.perform(post("/admin/categories").with(csrf()).session(admin)
                            .param("name", categoryName.toUpperCase(java.util.Locale.ROOT)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Tên danh mục đã tồn tại.")));
            byte[] png = TestImages.png(10 * 1024);
            String location = mockMvc.perform(multipart("/admin/products")
                            .file(new MockMultipartFile("image", "a.png", "image/png", png))
                            .with(csrf()).session(admin)
                            .param("name", productName).param("categoryId", String.valueOf(categoryId))
                            .param("price", "100.000").param("initialStock", "10").param("active", "true"))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            assertThat(location).matches("/admin/products/\\d+/edit");
            long productId = Long.parseLong(location.split("/")[3]);
            String imagePath = jdbc.queryForObject("select image_path from products where id = ?", String.class, productId);
            assertThat(imagePath).matches("/uploads/[0-9a-f-]{36}\\.png");
            assertThat(uploadDir.resolve(imagePath.substring("/uploads/".length()))).exists();
            mockMvc.perform(get(imagePath)).andExpect(status().isOk());
            assertThat(categoryAdminService.list()).filteredOn(r -> r.id() == categoryId).singleElement()
                    .satisfies(r -> assertThat(r.productCount()).isEqualTo(1));
            assertThat(categoryAdminService.delete(1L, categoryId)).isInstanceOf(CategoryDeleteResult.InUse.class);

            // Stock +5, then two orders by the seed user (1 each).
            mockMvc.perform(post("/admin/products/" + productId + "/stock").with(csrf()).session(admin)
                            .param("delta", "5"))
                    .andExpect(status().is3xxRedirection());
            assertThat(stockOf(productId)).isEqualTo(15);
            long first = placeOrder(customer, productId);
            long second = placeOrder(customer, productId);
            assertThat(stockOf(productId)).isEqualTo(13);

            for (String step : List.of("CONFIRMED", "SHIPPING", "DELIVERED")) {
                mockMvc.perform(post("/admin/orders/" + first + "/status").with(csrf()).session(admin)
                                .param("status", step))
                        .andExpect(status().is3xxRedirection());
                assertThat(statusOf(first)).isEqualTo(step);
            }
            assertThat(stockOf(productId)).isEqualTo(13);
            mockMvc.perform(get("/admin/orders/" + first).session(admin)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Đơn hàng đã ở trạng thái cuối.")));
            mockMvc.perform(get("/admin/orders?status=DELIVERED").session(admin)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("href=\"/admin/orders/" + first + "\"")));
            OrderDashboard after = orderAdminService.dashboard();
            assertThat(after.counts()).extracting(StatusCount::status, StatusCount::count)
                    .containsExactly(tuple("PENDING", 1L), tuple("CONFIRMED", 0L), tuple("SHIPPING", 0L),
                            tuple("DELIVERED", 1L), tuple("CANCELLED", 0L));
            assertThat(after.deliveredRevenue()).isEqualByComparingTo("100000");
            assertThat(after.latest()).extracting(AdminOrderRow::id).containsExactly(second, first);

            mockMvc.perform(post("/admin/orders/" + second + "/status").with(csrf()).session(admin)
                            .param("status", "CANCELLED"))
                    .andExpect(status().is3xxRedirection());
            assertThat(statusOf(second)).isEqualTo("CANCELLED");
            assertThat(stockOf(productId)).isEqualTo(14);
            // A refused step changes nothing.
            mockMvc.perform(post("/admin/orders/" + second + "/status").with(csrf()).session(admin)
                            .param("status", "CANCELLED"))
                    .andExpect(status().is3xxRedirection());
            assertThat(stockOf(productId)).isEqualTo(14);

            // adjustStock at both ends of 0..100000 on H2.
            String name = jdbc.queryForObject("select name from products where id = ?", String.class, productId);
            assertThat(productAdminService.adjustStock(1L, productId, -14))
                    .isEqualTo(new StockAdjustResult.Adjusted(name, 0));
            assertThat(productAdminService.adjustStock(1L, productId, -1))
                    .isEqualTo(new StockAdjustResult.NotEnough(name, 1, 0));
            assertThat(productAdminService.lowStock()).extracting(LowStockRow::id).contains(productId);
            assertThat(productAdminService.adjustStock(1L, productId, 100_000))
                    .isEqualTo(new StockAdjustResult.Adjusted(name, 100_000));
            assertThat(productAdminService.adjustStock(1L, productId, 1))
                    .isEqualTo(new StockAdjustResult.WouldExceed(name));
            assertThat(productAdminService.adjustStock(1L, productId, -100_000))
                    .isEqualTo(new StockAdjustResult.Adjusted(name, 0));
        } finally {
            Long product = jdbc.query("select id from products where name = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, productName);
            String imagePath = jdbc.query("select image_path from products where name = ?",
                    rs -> rs.next() ? rs.getString(1) : null, productName);
            jdbc.update("delete from order_items where order_id in (select id from orders where user_id = ?)", userId);
            jdbc.update("delete from orders where user_id = ?", userId);
            jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id = ?)", userId);
            jdbc.update("delete from carts where user_id = ?", userId);
            if (product != null) {
                jdbc.update("delete from products where id = ?", product);
            }
            jdbc.update("delete from categories where name = ?", categoryName);
            if (imagePath != null && imagePath.startsWith("/uploads/")) {
                Files.deleteIfExists(uploadDir.resolve(imagePath.substring("/uploads/".length())));
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
    }

    private long placeOrder(MockHttpSession customer, long productId) throws Exception {
        mockMvc.perform(post("/cart/items").with(csrf()).session(customer)
                        .param("productId", String.valueOf(productId)).param("quantity", "1"))
                .andExpect(status().is3xxRedirection());
        String location = mockMvc.perform(post("/checkout").with(csrf()).session(customer)
                        .param("receiverName", "Khách hàng mẫu").param("phone", "0912 345 678")
                        .param("shippingAddress", "12 Lê Lợi").param("expectedTotal", "100000"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/orders/");
        return Long.parseLong(location.substring("/orders/".length()));
    }

    private int stockOf(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }
}

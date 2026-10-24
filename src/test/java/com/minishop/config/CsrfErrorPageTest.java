package com.minishop.config;

import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import com.minishop.support.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 403 page for a CSRF failure, on a real server (AC-007-8, 9; BR-007-4): a stale or missing token shows
 * "Phiên làm việc đã hết hạn" and changes nothing; a missing permission still shows the old "không có quyền" page.
 */
@LiveServerTest
class CsrfErrorPageTest {

    private static final String TITLE = "Phiên làm việc đã hết hạn";
    private static final String MESSAGE =
            "Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại.";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.seed.user-password}")
    String userPassword;

    private LiveClient user() throws Exception {
        return new LiveClient(port).login("user@minishop.local", userPassword);
    }

    private LiveClient admin() throws Exception {
        return new LiveClient(port).login("admin@minishop.local", adminPassword);
    }

    private long someActiveProductId() {
        return jdbc.queryForObject("select min(id) from products where active = true and stock > 0", Long.class);
    }

    private static void assertSessionExpiredPage(HttpResponse<String> response, String what) {
        String body = response.body();
        assertThat(response.statusCode()).as(what).isEqualTo(403);
        assertThat(response.headers().firstValue("Location")).as(what + ": never a redirect").isEmpty();
        assertThat(body).as(what)
                .contains("<title>" + TITLE + " | Mini Shop</title>")
                .contains(">" + TITLE + "</h1>")
                .contains(">" + MESSAGE + "<")
                .contains("href=\"/\">Về trang chủ</a>")
                .contains("<body class=\"site-body\">")
                .doesNotContain("Bạn không có quyền", "admin-body", "Whitelabel");
        assertThat(body.split("<h1[\\s>]", -1).length - 1).as(what + ": exactly one h1").isEqualTo(1);
    }

    @Test
    void postCartItems_asGuestWithoutToken_showsSessionExpiredPageAndCartStaysEmpty() throws Exception {
        LiveClient guest = new LiveClient(port);

        HttpResponse<String> response = guest.postForm("/cart/items",
                Map.of("productId", String.valueOf(someActiveProductId()), "quantity", "1"), false);

        assertSessionExpiredPage(response, "guest without token");
        assertThat(guest.get("/cart").body()).contains("Giỏ hàng của bạn đang trống.");
    }

    @Test
    void postCancelOrder_asUserWithWrongToken_showsSessionExpiredPageAndOrderStaysPending() throws Exception {
        LiveClient user = user();
        long orderId = insertPendingOrderForUser();
        try {
            HttpResponse<String> response = user.postForm("/orders/" + orderId + "/cancel",
                    Map.of("_csrf", "not-a-valid-token"), false);

            assertSessionExpiredPage(response, "wrong token");
            assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        } finally {
            jdbc.update("delete from orders where id = ?", orderId);
        }
    }

    @Test
    void postLogout_withoutToken_showsSessionExpiredPageAndUserStaysLoggedIn() throws Exception {
        LiveClient user = user();

        HttpResponse<String> response = user.postForm("/logout", Map.of(), false);

        assertSessionExpiredPage(response, "logout without token");
        assertThat(user.get("/orders").statusCode()).as("still logged in").isEqualTo(200);
    }

    @Test
    void postCartItems_withTokenOfAnotherSession_showsSessionExpiredPage() throws Exception {
        String oldToken = new LiveClient(port).freshCsrf();
        LiveClient freshVisitor = new LiveClient(port);

        HttpResponse<String> response = freshVisitor.postForm("/cart/items", Map.of(
                "_csrf", oldToken, "productId", String.valueOf(someActiveProductId()), "quantity", "1"), false);

        assertSessionExpiredPage(response, "token of an old session");
        assertThat(freshVisitor.get("/cart").body()).contains("Giỏ hàng của bạn đang trống.");
    }

    @Test
    void postAdminCategory_asAdminWithoutToken_showsStorefrontSessionExpiredPageAndCreatesNothing() throws Exception {
        LiveClient admin = admin();
        int before = count("categories");

        HttpResponse<String> response = admin.postForm("/admin/categories",
                Map.of("name", "Danh mục không token", "description", "x"), false);

        assertSessionExpiredPage(response, "admin form without token");
        assertThat(count("categories")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from categories where name = ?", Integer.class,
                "Danh mục không token")).isZero();
    }

    @Test
    void postAdminProductMultipart_asAdminWithoutToken_showsSessionExpiredPageAndCreatesNothing() throws Exception {
        LiveClient admin = admin();
        int before = count("products");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "Sản phẩm không token");
        fields.put("categoryId", String.valueOf(jdbc.queryForObject("select min(id) from categories", Long.class)));
        fields.put("price", "1.250.000");
        fields.put("initialStock", "5");
        fields.put("active", "true");

        HttpResponse<String> response = admin.postMultipart("/admin/products", fields, false,
                "image", "small.png", "image/png", TestImages.png(2048));

        assertSessionExpiredPage(response, "admin multipart without token");
        assertThat(count("products")).isEqualTo(before);
    }

    @Test
    void getAdmin_asUser_isStillTheNoPermissionPageNotTheSessionExpiredPage() throws Exception {
        HttpResponse<String> response = user().get("/admin");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Bạn không có quyền truy cập trang này.")
                .doesNotContain("Phiên làm việc", "Biểu mẫu đã hết hạn");
    }

    @Test
    void postAdminCategory_asUserWithValidToken_isTheNoPermissionPageNotTheSessionExpiredPage() throws Exception {
        LiveClient user = user();
        int before = count("categories");

        HttpResponse<String> response = user.postForm("/admin/categories",
                Map.of("name", "Danh mục của user", "description", "x"), true);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Bạn không có quyền truy cập trang này.")
                .doesNotContain("Phiên làm việc");
        assertThat(count("categories")).isEqualTo(before);
    }

    // ---------------------------------------------------------------- helpers

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private long insertPendingOrderForUser() {
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, "user@minishop.local");
        jdbc.update("insert into orders (user_id, status, receiver_name, phone, shipping_address, payment_method,"
                + " total_amount) values (?, 'PENDING', 'Khách hàng mẫu', '0900000000', '12 Đường Mẫu', 'COD', 1000)",
                userId);
        return jdbc.queryForObject("select max(id) from orders where user_id = ?", Long.class, userId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }
}

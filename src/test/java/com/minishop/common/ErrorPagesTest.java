package com.minishop.common;

import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content of the error pages on a real Tomcat (AC-007-1, 2, 4, 7, 11, 12, 13; BR-007-1, 2). MockMvc cannot
 * show these: it does not do an error dispatch.
 */
@LiveServerTest
class ErrorPagesTest {

    private static final String SITE = " | Mini Shop";
    private static final String ADMIN_SITE = " | Quản trị Mini Shop";

    private static final String NOT_FOUND = "Không tìm thấy trang";
    private static final String NOT_FOUND_TEXT = "Trang bạn tìm không tồn tại hoặc đã bị di chuyển.";
    private static final String BAD_REQUEST = "Yêu cầu không hợp lệ";
    private static final String BAD_REQUEST_TEXT = "Đường dẫn hoặc dữ liệu gửi lên không hợp lệ.";
    private static final String SERVER_ERROR = "Đã có lỗi xảy ra";
    private static final String SERVER_ERROR_TEXT = "Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.";

    @LocalServerPort
    int port;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.seed.user-password}")
    String userPassword;

    private LiveClient guest() {
        return new LiveClient(port);
    }

    private LiveClient user() throws Exception {
        return new LiveClient(port).login("user@minishop.local", userPassword);
    }

    private LiveClient admin() throws Exception {
        return new LiveClient(port).login("admin@minishop.local", adminPassword);
    }

    // ------------------------------------------------------------------ 404

    @Test
    void notFound_forGuestOnPublicPaths_showsStorefrontNotFoundPage() throws Exception {
        LiveClient guest = guest();
        for (String path : new String[]{"/products/", "/products/abc/xyz", "/images/khong-co.jpg", "/css/khong-co.css"}) {
            HttpResponse<String> response = guest.get(path);

            assertStorePage(response, 404, NOT_FOUND, NOT_FOUND_TEXT, path);
            assertThat(response.body()).as(path).contains("Đăng nhập");
        }
    }

    @Test
    void notFound_forUserAndAdminOnUnknownUrl_showsStorefrontNotFoundPageWithTheirHeader() throws Exception {
        HttpResponse<String> asUser = user().get("/khong-co-trang-nay");
        HttpResponse<String> asAdmin = admin().get("/khong-co-trang-nay");

        assertStorePage(asUser, 404, NOT_FOUND, NOT_FOUND_TEXT, "user");
        assertThat(asUser.body()).contains("Xin chào, Khách hàng mẫu");
        assertStorePage(asAdmin, 404, NOT_FOUND, NOT_FOUND_TEXT, "admin");
        assertThat(asAdmin.body()).contains("Xin chào, Quản trị viên");
        assertThat(asAdmin.body()).as("an admin outside /admin gets the storefront layout")
                .doesNotContain("admin-body");
    }

    @Test
    void notFound_forGuestOnProtectedUnknownUrl_redirectsToLoginAndShowsNotFoundAfterLogin() throws Exception {
        LiveClient client = guest();

        HttpResponse<String> first = client.get("/khong-co-trang-nay");

        assertThat(first.statusCode()).isEqualTo(302);
        assertThat(first.headers().firstValue("Location").orElse("")).endsWith("/login");

        HttpResponse<String> login = client.submitLogin("user@minishop.local", userPassword);
        String location = login.headers().firstValue("Location").orElse("");
        assertThat(location).contains("/khong-co-trang-nay");
        HttpResponse<String> afterLogin = client.get(location.substring(location.indexOf("/khong-co")));

        assertStorePage(afterLogin, 404, NOT_FOUND, NOT_FOUND_TEXT, "after login");
    }

    // ------------------------------------------------------------------ 500

    @Test
    void serverError_whenControllerThrows_showsGenericPageAndLeaksNothing() throws Exception {
        HttpResponse<String> response = user().get("/test/boom");

        assertStorePage(response, 500, SERVER_ERROR, SERVER_ERROR_TEXT, "/test/boom");
        assertThat(response.body())
                .doesNotContain("Boom", "IllegalStateException", "select", "secret", "C:\\", "java.", "at com.",
                        "Exception", "Whitelabel", "/test/boom");
    }

    @Test
    void serverError_forPostAlsoShowsGenericPage() throws Exception {
        HttpResponse<String> response = user().postForm("/test/boom", Map.of("phone", "0987654321"), true);

        assertStorePage(response, 500, SERVER_ERROR, SERVER_ERROR_TEXT, "POST /test/boom");
        assertThat(response.body()).doesNotContain("0987654321", "Boom");
    }

    @Test
    void serverError_forGuestOnProtectedPath_isStillALoginRedirectNotAnErrorPage() throws Exception {
        HttpResponse<String> response = guest().get("/test/boom");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse("")).endsWith("/login");
    }

    // -------------------------------------------------------------- 405, 400

    @Test
    void methodNotAllowed_forGetOnPostOnlyUrls_showsBadRequestPageWith405() throws Exception {
        LiveClient user = user();
        for (String path : new String[]{"/cart/items", "/orders/1/cancel"}) {
            HttpResponse<String> response = user.get(path);

            assertStorePage(response, 405, BAD_REQUEST, BAD_REQUEST_TEXT, path);
        }
    }

    @Test
    void badRequest_forPathParameterOnFirewall_showsBadRequestPageWith400() throws Exception {
        HttpResponse<String> response = user().get("/products;x=1");

        assertStorePage(response, 400, BAD_REQUEST, BAD_REQUEST_TEXT, "/products;x=1");
    }

    // ------------------------------------------------------- admin layout

    @Test
    void errorsUnderAdmin_forAdmin_useAdminLayoutWithBackLinkToAdmin() throws Exception {
        LiveClient admin = admin();
        Map<String, Object[]> cases = new java.util.LinkedHashMap<>();
        cases.put("/admin/khong-co", new Object[]{404, NOT_FOUND, NOT_FOUND_TEXT});
        cases.put("/admin/orders/1/status", new Object[]{405, BAD_REQUEST, BAD_REQUEST_TEXT});
        cases.put("/admin/test/boom", new Object[]{500, SERVER_ERROR, SERVER_ERROR_TEXT});

        for (Map.Entry<String, Object[]> c : cases.entrySet()) {
            HttpResponse<String> response = admin.get(c.getKey());
            String body = response.body();

            assertThat(response.statusCode()).as(c.getKey()).isEqualTo((int) c.getValue()[0]);
            assertThat(response.headers().firstValue("Location")).as(c.getKey()).isEmpty();
            assertThat(body).as(c.getKey()).contains("<body class=\"admin-body\">")
                    .contains("<title>" + c.getValue()[1] + ADMIN_SITE + "</title>")
                    .contains(">" + c.getValue()[2] + "<")
                    .contains("href=\"/admin\">Về trang quản trị</a>")
                    .contains("Xin chào, Quản trị viên")
                    .doesNotContain("Whitelabel", "site-header", "Boom", "IllegalStateException");
            assertThat(h1Count(body)).as(c.getKey()).isEqualTo(1);
            assertThat(h1Text(body)).as(c.getKey()).isEqualTo(c.getValue()[1]);
            // the logout form on the error page carries a usable token (CsrfFilter ran on the first dispatch)
            assertThat(body).as(c.getKey()).contains("Đăng xuất");
            assertThat(LiveClient.csrfFrom(body)).as(c.getKey()).isNotBlank();
            assertThat(body).as(c.getKey()).doesNotContain("aria-current=\"");
        }
    }

    @Test
    void errorsUnderAdmin_forUser_getForbiddenPageNotAnErrorPage() throws Exception {
        HttpResponse<String> response = user().get("/admin/khong-co");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Bạn không có quyền truy cập trang này.")
                .doesNotContain("admin-body");
    }

    @Test
    void errorsLookingLikeAdminButNotUnderIt_forAdmin_useStorefrontLayout() throws Exception {
        // "/administrator" is not "/admin/..." and is not matched by the admin rule, so it is an unknown URL
        HttpResponse<String> response = admin().get("/administrator");

        assertStorePage(response, 404, NOT_FOUND, NOT_FOUND_TEXT, "/administrator");
        assertThat(response.body()).doesNotContain("admin-body");
    }

    // ------------------------------------------------------------------ JSON

    @Test
    void jsonClients_getDefaultJsonWithoutDetails() throws Exception {
        Map<String, HttpResponse<String>> responses = new java.util.LinkedHashMap<>();
        responses.put("guest /products/abc/xyz 404", guest().get("/products/abc/xyz", LiveClient.JSON));
        LiveClient user = user();
        responses.put("user /test/boom 500", user.get("/test/boom", LiveClient.JSON));
        responses.put("user /actuator/khong-co 404", user.get("/actuator/khong-co", LiveClient.JSON));
        int[] expected = {404, 500, 404};
        String[] requestedPaths = {"/products/abc/xyz", "/test/boom", "/actuator/khong-co"};

        int i = 0;
        for (Map.Entry<String, HttpResponse<String>> entry : responses.entrySet()) {
            HttpResponse<String> response = entry.getValue();
            String body = response.body();
            String requestedPath = requestedPaths[i];

            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(expected[i++]);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).as(entry.getKey())
                    .contains("application/json");
            assertThat(body).as(entry.getKey()).contains("\"status\":" + response.statusCode());
            assertThat(body).as("BR-007-2: the path is not echoed, " + entry.getKey())
                    .doesNotContain("\"path\"")
                    .doesNotContain(requestedPath);
            assertThat(body).as(entry.getKey())
                    .doesNotContain("\"trace\"", "\"message\"", "\"exception\"", "\"errors\"")
                    .doesNotContain("Boom", "IllegalStateException", "select", "secret", "<html");
        }
    }

    // ------------------------------------------------------------ all pages

    @Test
    void everyErrorPage_hasNon200StatusAndNoRedirect() throws Exception {
        LiveClient user = user();
        for (String path : new String[]{"/khong-co-trang-nay", "/test/boom", "/cart/items", "/products/abc/xyz"}) {
            HttpResponse<String> response = user.get(path);

            assertThat(response.statusCode()).as(path).isGreaterThanOrEqualTo(400);
            assertThat(response.headers().firstValue("Location")).as(path).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertStorePage(HttpResponse<String> response, int status, String heading, String message,
                                        String what) {
        String body = response.body();
        assertThat(response.statusCode()).as(what).isEqualTo(status);
        assertThat(response.headers().firstValue("Location")).as(what + ": never a redirect").isEmpty();
        assertThat(body).as(what).contains("<title>" + heading + SITE + "</title>")
                .contains(">" + message + "<")
                .contains("href=\"/\">Về trang chủ</a>")
                .contains("site-header").contains("site-footer")
                .contains("<body class=\"site-body\">")
                .doesNotContain("Whitelabel");
        assertThat(h1Count(body)).as(what + ": exactly one h1").isEqualTo(1);
        assertThat(h1Text(body)).as(what).isEqualTo(heading);
    }

    private static int h1Count(String html) {
        Matcher m = Pattern.compile("<h1[\\s>]").matcher(html);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static String h1Text(String html) {
        Matcher m = Pattern.compile("<h1[^>]*>([^<]*)</h1>").matcher(html);
        assertThat(m.find()).as("an h1").isTrue();
        return m.group(1).strip();
    }
}

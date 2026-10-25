package com.minishop.admin;

import com.minishop.support.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-006-29 on a real Tomcat. MockMvc skips Tomcat, but the multipart limit is enforced by Tomcat while Spring
 * Security's CsrfFilter reads the {@code _csrf} parameter (before any controller). With a 2 MB Tomcat limit an
 * oversized image would have become a 403 or a reset connection; with the 10 MB ceiling of ADR-0010 the request
 * is parsed whole and the application answers with the form and "Ảnh tối đa 2 MB.".
 *
 * <p>Uses the {@code h2} profile with its own in-memory database (so it does not share one with
 * {@code H2ProfileSmokeTest}), a real form login through {@link HttpClient} with a cookie jar, and the
 * {@code _csrf} token taken from the rendered form. Runs without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:upload-limit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class AdminUploadLimitTest {

    private static final String NAME = "Admin test upload limit";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final Pattern FIRST_CATEGORY = Pattern.compile("<option value=\"(\\d+)\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

    private HttpClient client() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build());
    }

    private static String csrfOf(String html) {
        Matcher m = CSRF.matcher(html);
        assertThat(m.find()).as("a _csrf hidden input in the page").isTrue();
        return m.group(1);
    }

    private HttpClient loggedInAdmin() throws Exception {
        HttpClient client = client();
        String token = csrfOf(get(client, "/login").body());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("email", "admin@minishop.local");
        form.put("password", adminPassword);
        form.put("_csrf", token);
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpResponse<String> login = send(client, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElse("")).doesNotContain("error");
        return client;
    }

    private HttpResponse<String> postProduct(HttpClient client, String csrf, String categoryId, byte[] image)
            throws Exception {
        String boundary = "----minishop" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream(image.length + 2048);
        field(out, boundary, "_csrf", csrf);
        field(out, boundary, "name", NAME);
        field(out, boundary, "categoryId", categoryId);
        field(out, boundary, "price", "1.250.000");
        field(out, boundary, "initialStock", "30");
        field(out, boundary, "description", "Ảnh thử");
        field(out, boundary, "active", "true");
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(image);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(client, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/products"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build());
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private Set<Path> uploads() throws IOException {
        Path dir = Path.of(uploadDirSetting).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return Set.copyOf(files.toList());
        }
    }

    private int productsNamed(String name) {
        return jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, name);
    }

    @Test
    void postProduct_withImageOverTwoMegabytes_returnsFormWithImageErrorAndStoresNothing() throws Exception {
        HttpClient client = loggedInAdmin();
        HttpResponse<String> form = get(client, "/admin/products/new");
        assertThat(form.statusCode()).isEqualTo(200);
        String csrf = csrfOf(form.body());
        Matcher category = FIRST_CATEGORY.matcher(form.body());
        assertThat(category.find()).isTrue();
        Set<Path> uploadsBefore = uploads();

        for (int bytes : new int[]{2_621_440 /* 2.5 MB */, 9 * 1024 * 1024 /* 9 MB */}) {
            HttpResponse<String> response = postProduct(client, csrf, category.group(1), TestImages.png(bytes));

            assertThat(response.statusCode()).as("%d bytes", bytes).isEqualTo(200);
            assertThat(response.body()).as("%d bytes", bytes).contains("Ảnh tối đa 2 MB.")
                    .contains("value=\"" + NAME + "\"");
            assertThat(productsNamed(NAME)).as("%d bytes: no product created", bytes).isZero();
            assertThat(uploads()).as("%d bytes: no file written", bytes).isEqualTo(uploadsBefore);
        }
    }

    /** Control: the same request with a small valid image goes all the way through, so the failures above are real. */
    @Test
    void postProduct_withSmallValidImage_isCreatedThroughTheSameRealRequestPath() throws Exception {
        HttpClient client = loggedInAdmin();
        HttpResponse<String> form = get(client, "/admin/products/new");
        String csrf = csrfOf(form.body());
        Matcher category = FIRST_CATEGORY.matcher(form.body());
        assertThat(category.find()).isTrue();
        Set<Path> uploadsBefore = uploads();
        try {
            HttpResponse<String> response = postProduct(client, csrf, category.group(1), TestImages.png(500 * 1024));

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location").orElse("")).matches(".*/admin/products/\\d+/edit");
            assertThat(productsNamed(NAME)).isEqualTo(1);
            assertThat(uploads()).hasSize(uploadsBefore.size() + 1);
        } finally {
            String imagePath = jdbc.query("select image_path from products where name = ?",
                    rs -> rs.next() ? rs.getString(1) : null, NAME);
            jdbc.update("delete from products where name = ?", NAME);
            if (imagePath != null) {
                Files.deleteIfExists(Path.of(uploadDirSetting).toAbsolutePath().normalize()
                        .resolve(imagePath.substring("/uploads/".length())));
            }
        }
    }
}

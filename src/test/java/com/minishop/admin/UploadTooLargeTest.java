package com.minishop.admin;

import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import com.minishop.support.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-007-10, 11 on a real Tomcat: an image above the 10 MB ceiling (ADR-0010) used to make Tomcat close the
 * connection ("connection reset" in the browser). With {@code server.tomcat.max-swallow-size: 50MB} (ADR-0011)
 * Tomcat reads the body away and the admin sees the 413 page in the admin layout. Nothing is created or written.
 *
 * <p>Complements {@link AdminUploadLimitTest} (2 to 9 MB, the form error "Ảnh tối đa 2 MB."). Above 50 MB the
 * connection may still be dropped by design (accepted in the spec, question 3), so it is not tested.
 */
@LiveServerTest
@ExtendWith(OutputCaptureExtension.class)
class UploadTooLargeTest {

    private static final String NAME = "Admin test upload too large";
    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^.*\\sERROR\\s+\\d+\\s---.*$");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.storage.local.upload-dir}")
    String uploadDirSetting;

    @Test
    void postProduct_withImageAboveTheCeiling_showsAdminPayloadTooLargePageAndStoresNothing(CapturedOutput output)
            throws Exception {
        LiveClient admin = new LiveClient(port).login("admin@minishop.local", adminPassword);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", NAME);
        fields.put("categoryId", String.valueOf(jdbc.queryForObject("select min(id) from categories", Long.class)));
        fields.put("price", "1.250.000");
        fields.put("initialStock", "30");
        fields.put("description", "Ảnh thử");
        fields.put("active", "true");
        Set<Path> uploadsBefore = uploads();
        int productsBefore = jdbc.queryForObject("select count(*) from products", Integer.class);

        for (int megabytes : new int[]{12, 30}) {
            int offset = output.getAll().length();

            HttpResponse<String> response = admin.postMultipart("/admin/products", fields, true,
                    "image", "big.png", "image/png", TestImages.png(megabytes * 1024 * 1024));

            String body = response.body();
            String what = megabytes + " MB";
            assertThat(response.statusCode()).as(what).isEqualTo(413);
            assertThat(response.headers().firstValue("Location")).as(what + ": never a redirect").isEmpty();
            assertThat(body).as(what)
                    .contains("<title>Tệp tải lên quá lớn | Quản trị Mini Shop</title>")
                    .contains("<body class=\"admin-body\">")
                    .contains(">Tệp tải lên quá lớn</h1>")
                    .contains(">Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.<")
                    .contains("href=\"/admin/products\">Về danh sách sản phẩm</a>")
                    .contains("admin-sidebar").contains("Xin chào, Quản trị viên")
                    .doesNotContain("Whitelabel", "site-header");
            assertThat(body.split("<h1[\\s>]", -1).length - 1).as(what + ": exactly one h1").isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from products", Integer.class))
                    .as(what + ": no product created").isEqualTo(productsBefore);
            assertThat(jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, NAME))
                    .as(what).isZero();
            assertThat(uploads()).as(what + ": no file written").isEqualTo(uploadsBefore);
            assertThat(ERROR_LINE.matcher(output.getAll().substring(offset)).find())
                    .as(what + ": no ERROR line").isFalse();
        }
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
}

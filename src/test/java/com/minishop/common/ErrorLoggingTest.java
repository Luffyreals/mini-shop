package com.minishop.common;

import com.minishop.support.ErrorTestEndpoints;
import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logging of errors on a real server (AC-007-5, BR-007-5): each 500 writes exactly one {@code ERROR} line with the
 * method and the path (no query string) and no personal data; a 4xx never writes {@code ERROR}.
 *
 * <p>The log is captured with Spring Boot's {@link OutputCaptureExtension}; an {@code ERROR} line is recognised by
 * the default console pattern ({@code ... ERROR 1234 --- [...]}). Only the output written after a request
 * started is looked at, so the login steps and other tests do not count.
 */
@LiveServerTest
@ExtendWith(OutputCaptureExtension.class)
class ErrorLoggingTest {

    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^.*\\sERROR\\s+\\d+\\s---.*$");

    @LocalServerPort
    int port;

    @Value("${app.seed.user-password}")
    String userPassword;

    private LiveClient user() throws Exception {
        return new LiveClient(port).login("user@minishop.local", userPassword);
    }

    private static List<String> errorLines(String log) {
        Matcher m = ERROR_LINE.matcher(log);
        List<String> lines = new java.util.ArrayList<>();
        while (m.find()) {
            lines.add(m.group());
        }
        return lines;
    }

    private static String since(CapturedOutput output, int offset) {
        return output.getAll().substring(offset);
    }

    @Test
    void get_whenControllerThrows_logsExactlyOneErrorLineWithMethodAndPathWithoutQuery(CapturedOutput output)
            throws Exception {
        LiveClient user = user();
        int offset = output.getAll().length();

        HttpResponse<String> response = user.get("/test/boom?q=0987654321");

        assertThat(response.statusCode()).isEqualTo(500);
        String log = since(output, offset);
        List<String> errors = errorLines(log);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Unhandled exception: GET /test/boom")
                .doesNotContain("?").doesNotContain("0987654321");
        assertThat(log).as("the stack trace follows the line").contains("java.lang.IllegalStateException")
                .contains(ErrorTestEndpoints.SECRET_MESSAGE);
        assertThat(log).doesNotContain("Servlet.service()").doesNotContain("0987654321");
    }

    @Test
    void post_whenControllerThrows_logsExactlyOneErrorLineAndNoFormValues(CapturedOutput output) throws Exception {
        LiveClient user = user();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("phone", "0987654321");
        form.put("shippingAddress", "12 Đường Bí Mật");
        form.put("note", "ghi-chu-bi-mat");
        form.put("email", "bi-mat@example.com");
        form.put("password", "MatKhauBiMat1");
        int offset = output.getAll().length();

        HttpResponse<String> response = user.postForm("/test/boom", form, true);

        assertThat(response.statusCode()).isEqualTo(500);
        String log = since(output, offset);
        List<String> errors = errorLines(log);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Unhandled exception: POST /test/boom");
        assertThat(log).doesNotContain("0987654321", "Đường Bí Mật", "ghi-chu-bi-mat", "bi-mat@example.com",
                "MatKhauBiMat1", "Servlet.service()");
    }

    @Test
    void errorsOfTheClient_neverWriteAnErrorLine(CapturedOutput output) throws Exception {
        LiveClient user = user();
        LiveClient guest = new LiveClient(port);
        int offset = output.getAll().length();

        assertThat(user.get("/khong-co-trang-nay").statusCode()).as("404").isEqualTo(404);
        assertThat(user.get("/products/abc/xyz").statusCode()).as("404 no handler").isEqualTo(404);
        assertThat(user.get("/cart/items").statusCode()).as("405").isEqualTo(405);
        assertThat(user.get("/products;x=1").statusCode()).as("400 firewall").isEqualTo(400);
        assertThat(guest.postForm("/cart/items", Map.of("productId", "1"), false).statusCode())
                .as("403 CSRF").isEqualTo(403);
        assertThat(user.get("/admin").statusCode()).as("403 permission").isEqualTo(403);
        assertThat(user.postForm("/orders/1/cancel", Map.of("_csrf", "wrong"), false).statusCode())
                .as("403 wrong token").isEqualTo(403);

        String log = since(output, offset);
        assertThat(errorLines(log)).as("no ERROR line for any 4xx").isEmpty();
        assertThat(log).doesNotContain("Servlet.service()");
    }

    /**
     * A query string that is not valid percent-encoding is reported by Tomcat when a parameter is read, as an
     * {@code InvalidParameterException}; it is a mistake of the client, so 400 and no {@code ERROR}. The Java
     * HttpClient refuses to send such a URI, hence the raw socket.
     */
    @Test
    void malformedQueryEncoding_isAClientErrorNotAnErrorLine(CapturedOutput output) throws Exception {
        int offset = output.getAll().length();

        String statusLine = rawGet("/products?q=%zz");

        assertThat(statusLine).startsWith("HTTP/1.1 400");
        String log = since(output, offset);
        assertThat(errorLines(log)).isEmpty();
        assertThat(log).doesNotContain("Servlet.service()");
    }

    private String rawGet(String target) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout((int) Duration.ofSeconds(20).toMillis());
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Accept: text/html\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return all.lines().findFirst().orElse("");
        }
    }
}

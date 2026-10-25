package com.minishop.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A small browser stand-in for tests that talk to a real server ({@link LiveServerTest}): its own cookie jar (so
 * its own session), no automatic redirects (a test must see a 302 as a 302), and the {@code _csrf} token taken
 * from a rendered page like a browser form does.
 *
 * <p>Pages are requested with {@code Accept: text/html,...} unless a test passes another value. One instance is
 * one visitor; create another instance for a second visitor.
 */
public final class LiveClient {

    public static final String HTML = "text/html,application/xhtml+xml";
    public static final String JSON = "application/json";

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private final int port;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client;
    private boolean loggedIn;

    public LiveClient(int port) {
        this.port = port;
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** The current {@code JSESSIONID} in this visitor's cookie jar, or {@code null} when it has no session. */
    public String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .map(HttpCookie::getValue)
                .findFirst().orElse(null);
    }

    /** Puts a given {@code JSESSIONID} into this visitor's cookie jar, like an attacker who planted it. */
    public LiveClient withSessionId(String sessionId) {
        HttpCookie cookie = new HttpCookie("JSESSIONID", sessionId);
        cookie.setPath("/");
        cookie.setVersion(0);
        cookies.getCookieStore().add(URI.create(url("/")), cookie);
        return this;
    }

    /** The {@code _csrf} value of the first form in the page; fails the test when there is none. */
    public static String csrfFrom(String html) {
        Matcher m = CSRF.matcher(html);
        assertThat(m.find()).as("a _csrf hidden input in the page").isTrue();
        return m.group(1);
    }

    /** Real form login; asserts the 302 that is not the failure redirect. Returns this for chaining. */
    public LiveClient login(String email, String password) throws Exception {
        submitLogin(email, password);
        return this;
    }

    /** Like {@link #login} but returns the 302 response, to look at where it sends the visitor next. */
    public HttpResponse<String> submitLogin(String email, String password) throws Exception {
        String token = csrfFrom(get("/login").body());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("email", email);
        form.put("password", password);
        form.put("_csrf", token);
        HttpResponse<String> response = postForm("/login", form, false);
        assertThat(response.statusCode()).as("login status").isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse("")).as("login redirect").doesNotContain("error");
        loggedIn = true;
        return response;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return get(path, HTML);
    }

    public HttpResponse<String> get(String path, String accept) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path)))
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(30)).GET().build());
    }

    /** Raw bytes of a resource (favicon). */
    public HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Posts a urlencoded form. With {@code withCsrf} a token valid for this client's session is added first (taken
     * from the home page for a logged-in visitor, from the login page for a guest; both create the session).
     * Pass {@code false} to send the form as given, for example with a wrong or missing token.
     */
    public HttpResponse<String> postForm(String path, Map<String, String> form, boolean withCsrf) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>(form);
        if (withCsrf) {
            fields.put("_csrf", freshCsrf());
        }
        StringBuilder body = new StringBuilder();
        fields.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return send(HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", HTML)
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    /**
     * Posts {@code multipart/form-data}: the text {@code fields} (in order) and, when {@code fileBytes} is not null,
     * one file part. A token is added first when {@code withCsrf} (see {@link #postForm}); the {@code _csrf} field
     * goes before the other fields, like in the real form.
     */
    public HttpResponse<String> postMultipart(String path, Map<String, String> fields, boolean withCsrf,
                                              String fileField, String fileName, String contentType,
                                              byte[] fileBytes) throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        if (withCsrf) {
            all.put("_csrf", freshCsrf());
        }
        all.putAll(fields);
        String boundary = "----minishop" + System.nanoTime();
        int size = fileBytes == null ? 0 : fileBytes.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(size + 2048);
        for (Map.Entry<String, String> field : all.entrySet()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (fileBytes != null) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", HTML)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build());
    }

    /** A token valid for the current session of this client (creates the session when the client is a guest). */
    public String freshCsrf() throws Exception {
        return csrfFrom(get(loggedIn ? "/" : "/login").body());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

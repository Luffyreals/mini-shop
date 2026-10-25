package com.minishop.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser stand-in that speaks HTTP/1.1 over a plain socket, for the tests that must control the {@code Host} header
 * (phase 8, tunnel behaviour). The JDK {@code HttpClient} refuses to send a {@code Host} header of its own choosing, and
 * the property that would allow it ({@code jdk.httpclient.allowRestrictedHeaders}) is read once per JVM, so a test that
 * depended on it would pass or fail with the order of the test classes. A real tunnel (cloudflared) forwards the public
 * domain in {@code Host}, which is exactly what these tests need to send.
 *
 * <p><b>Isolation:</b> always connects to {@code 127.0.0.1} on the port the test got from the server (never a fixed
 * port), so the peer address the server sees is {@code 127.0.0.1} whatever {@code localhost} resolves to.
 * Cookies are kept by hand (only {@code JSESSIONID}) because a {@code Secure} cookie must still be sent back over
 * {@code http://} here: the public profile sets {@code Secure} explicitly and the test talks plain HTTP.
 * One instance is one visitor. Redirects are never followed.
 */
public final class RawHttpClient {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final Pattern SESSION_COOKIE = Pattern.compile("^JSESSIONID=([^;]+)");

    /** One response: status, headers (names lower-cased, in order) and the decoded body. */
    public record Response(int status, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        public List<String> allHeaders(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        public String location() {
            String location = header("Location");
            return location == null ? "" : location;
        }

        /** The {@code Set-Cookie} line of the session cookie, or an empty string. */
        public String sessionSetCookie() {
            return allHeaders("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElse("");
        }
    }

    private final int port;
    private String sessionCookie;

    public RawHttpClient(int port) {
        this.port = port;
    }

    /** The {@code Host} header a plain request to this server carries. */
    public String ownHost() {
        return "127.0.0.1:" + port;
    }

    public Response get(String path, Map<String, String> headers) throws IOException {
        return send("GET", path, headers, null);
    }

    public Response get(String path) throws IOException {
        return get(path, Map.of());
    }

    public Response postForm(String path, Map<String, String> form, Map<String, String> headers) throws IOException {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", "application/x-www-form-urlencoded");
        return send("POST", path, all, body.toString());
    }

    /** The {@code _csrf} value of the first form in a page; fails the test when there is none. */
    public static String csrfFrom(String html) {
        Matcher m = CSRF.matcher(html);
        assertThat(m.find()).as("a _csrf hidden input in the page").isTrue();
        return m.group(1);
    }

    /**
     * Form login like a browser: {@code GET /login} (token and session cookie), then {@code POST /login}. The same
     * extra headers go with both requests, as they would through a tunnel.
     */
    public Response login(String email, String password, Map<String, String> headers) throws IOException {
        Response page = get("/login", headers);
        assertThat(page.status()).as("GET /login").isEqualTo(200);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("email", email);
        form.put("password", password);
        form.put("_csrf", csrfFrom(page.body()));
        return postForm("/login", form, headers);
    }

    public Response login(String email, String password) throws IOException {
        return login(email, password, Map.of());
    }

    /** Sends one request on a new connection ({@code Connection: close}). */
    public Response send(String method, String path, Map<String, String> headers, String body) throws IOException {
        Map<String, String> lowerCaseNames = new LinkedHashMap<>();
        headers.forEach((k, v) -> lowerCaseNames.put(k.toLowerCase(Locale.ROOT), v));
        StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(lowerCaseNames.getOrDefault("host", ownHost())).append("\r\n");
        request.append("Accept: ").append(lowerCaseNames.getOrDefault("accept", "text/html")).append("\r\n");
        request.append("Connection: close\r\n");
        if (sessionCookie != null) {
            request.append("Cookie: JSESSIONID=").append(sessionCookie).append("\r\n");
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!name.equals("host") && !name.equals("accept")) {
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        }
        request.append("\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(bodyBytes);
            socket.getOutputStream().flush();
            Response response = parse(socket.getInputStream().readAllBytes());
            remember(response);
            return response;
        }
    }

    private void remember(Response response) {
        for (String setCookie : response.allHeaders("Set-Cookie")) {
            Matcher m = SESSION_COOKIE.matcher(setCookie);
            if (m.find()) {
                sessionCookie = m.group(1);
            }
        }
    }

    private static Response parse(byte[] raw) throws IOException {
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        assertThat(split).as("a complete HTTP response head").isPositive();
        String[] lines = new String(raw, 0, split, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.computeIfAbsent(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).strip());
        }
        byte[] bodyBytes = new byte[raw.length - split - 4];
        System.arraycopy(raw, split + 4, bodyBytes, 0, bodyBytes.length);
        List<String> encoding = headers.getOrDefault("transfer-encoding", List.of());
        if (!encoding.isEmpty() && encoding.get(0).equalsIgnoreCase("chunked")) {
            bodyBytes = dechunk(bodyBytes);
        }
        return new Response(status, headers, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] chunked) throws IOException {
        InputStream in = new ByteArrayInputStream(chunked);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            int size = Integer.parseInt((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).strip(), 16);
            if (size == 0) {
                break;
            }
            out.write(in.readNBytes(size));
            readLine(in); // the CRLF after the chunk
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return line.toString(StandardCharsets.ISO_8859_1).stripTrailing();
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

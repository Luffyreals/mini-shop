package com.minishop.support;

import com.minishop.MiniShopApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application the way {@code spring-boot:run} does (a plain {@link SpringApplicationBuilder}, no test
 * context), for tests of startup rules and profiles (phase 8, QĐ10): {@code PublicProfileStartupTest},
 * {@code LocalProfileTest}.
 *
 * <p><b>Isolation rules built in</b>
 * <ul>
 *   <li>Every value goes in as a command-line argument ({@code --NAME=value}), the highest property source. So a real
 *   environment variable of the developer ({@code SEED_ADMIN_PASSWORD}, {@code DB_URL}...) can never leak into a test,
 *   and "missing" is simulated with an empty value ({@code --NAME=}), which beats the real variable too.</li>
 *   <li>Never a duplicate key: two {@code --a=1 --a=2} arguments would be joined into {@code "1,2"}. Callers add only
 *   keys that {@link #start} does not set.</li>
 *   <li>Tomcat on a random port, Docker Compose support off, banner off.</li>
 *   <li>Classes under {@code target/test-classes} are kept out of component scanning with a {@link TypeExcludeFilter}
 *   bean (what Spring Boot's own test support does for a test context). Without it the scan would pick up
 *   {@code TestcontainersConfiguration} (a second container and datasource) and the nested rogue {@code @Component}
 *   classes of the architecture tests.</li>
 *   <li>Each scenario gets its own database inside one PostgreSQL container ({@link #newDatabase}), so contexts that
 *   are open at the same time never see each other's rows.</li>
 * </ul>
 */
public final class StandaloneApp {

    private StandaloneApp() {
    }

    /** What the dev defaults of application.yml are, passed explicitly so a real SEED_* variable is ignored. */
    public static final String DEV_ADMIN_PASSWORD = "Admin@123";
    public static final String DEV_USER_PASSWORD = "User@123";

    /** Starts the application; the caller must close the returned context. */
    public static ConfigurableApplicationContext start(String... args) {
        List<String> all = new ArrayList<>(List.of(
                "--server.port=0", "--spring.docker.compose.enabled=false", "--spring.main.banner-mode=off"));
        all.addAll(List.of(args));
        return new SpringApplicationBuilder(MiniShopApplication.class)
                .initializers(context -> context.getBeanFactory().registerSingleton(
                        "excludeTestClassesFromScanning", new ExcludeTestClasses()))
                .run(all.toArray(String[]::new));
    }

    /**
     * Starts the application and returns what made it fail. If it starts, the context is closed and the test fails,
     * so a missing check can never leave a server running.
     */
    public static Throwable startExpectingFailure(String... args) {
        ConfigurableApplicationContext context;
        try {
            context = start(args);
        } catch (Throwable failure) {
            return failure;
        }
        context.close();
        throw new AssertionError("the application was expected to refuse to start, but it started");
    }

    /** The first throwable in the cause chain (the given one included) whose class has this simple name, or null. */
    public static Throwable causeNamed(Throwable failure, String simpleName) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 30; depth++) {
            if (current.getClass().getSimpleName().equals(simpleName)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    public static int portOf(ConfigurableApplicationContext context) {
        return context.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    // ------------------------------------------------------------------ databases

    /** Creates an empty database in the container and returns its name. */
    public static String newDatabase(PostgreSQLContainer postgres) throws SQLException {
        String name = "t" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        }
        return name;
    }

    public static String jdbcUrl(PostgreSQLContainer postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }

    public static Connection connect(PostgreSQLContainer postgres, String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(postgres, database), postgres.getUsername(), postgres.getPassword());
    }

    /** Single value of a query on a scenario database. */
    public static String scalar(PostgreSQLContainer postgres, String database, String sql) throws SQLException {
        try (Connection connection = connect(postgres, database); Statement statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    public static void execute(PostgreSQLContainer postgres, String database, String sql) throws SQLException {
        try (Connection connection = connect(postgres, database); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ------------------------------------------------------------------ a minimal browser

    /**
     * One visitor of the running server, over plain HTTP to 127.0.0.1, with cookies handled by hand: Java's
     * {@code CookieManager} would not send a {@code Secure} cookie back over {@code http://}, which is exactly what the
     * public profile sets (plan QĐ10).
     */
    public static final class Visitor {

        private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
        private static final Pattern SESSION_COOKIE = Pattern.compile("^JSESSIONID=([^;]+)");

        private final int port;
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        private String sessionCookie;

        public Visitor(int port) {
            this.port = port;
        }

        public String url(String path) {
            return "http://127.0.0.1:" + port + path;
        }

        public HttpResponse<String> get(String path, String accept, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                    .header("Accept", accept).timeout(Duration.ofSeconds(30)).GET();
            applyHeaders(request, headers);
            return remember(client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }

        public HttpResponse<String> get(String path) throws Exception {
            return get(path, "text/html", Map.of());
        }

        public HttpResponse<String> postForm(String path, Map<String, String> form, Map<String, String> headers)
                throws Exception {
            StringBuilder body = new StringBuilder();
            form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&")
                    .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            applyHeaders(request, headers);
            return remember(client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }

        /** Form login like a browser: GET /login for the token and the session cookie, then POST /login. */
        public HttpResponse<String> login(String email, String password, Map<String, String> headers)
                throws Exception {
            HttpResponse<String> page = get("/login", "text/html", headers);
            assertThat(page.statusCode()).as("GET /login").isEqualTo(200);
            Matcher token = CSRF.matcher(page.body());
            assertThat(token.find()).as("a _csrf input on the login page").isTrue();
            Map<String, String> form = new LinkedHashMap<>();
            form.put("email", email);
            form.put("password", password);
            form.put("_csrf", token.group(1));
            return postForm("/login", form, headers);
        }

        public HttpResponse<String> login(String email, String password) throws Exception {
            return login(email, password, Map.of());
        }

        /** Where a response sends the visitor. */
        public static String location(HttpResponse<?> response) {
            return response.headers().firstValue("Location").orElse("");
        }

        private void applyHeaders(HttpRequest.Builder request, Map<String, String> headers) {
            headers.forEach(request::header);
            if (sessionCookie != null) {
                request.header("Cookie", "JSESSIONID=" + sessionCookie);
            }
        }

        private HttpResponse<String> remember(HttpResponse<String> response) {
            for (String setCookie : response.headers().allValues("Set-Cookie")) {
                Matcher m = SESSION_COOKIE.matcher(setCookie);
                if (m.find()) {
                    sessionCookie = m.group(1);
                }
            }
            return response;
        }
    }

    /** Keeps everything compiled under {@code target/test-classes} out of component scanning. */
    private static final class ExcludeTestClasses extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            return metadataReader.getResource().getURL().toString().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExcludeTestClasses;
        }

        @Override
        public int hashCode() {
            return ExcludeTestClasses.class.hashCode();
        }
    }
}

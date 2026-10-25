package com.minishop;

import com.minishop.support.RawHttpClient;
import com.minishop.support.StandaloneApp;
import com.minishop.support.StandaloneApp.Visitor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Public mode (ADR-0012) on the real application, started like {@code spring-boot:run} (see {@link StandaloneApp}):
 * AC-008-1..5 (startup rules, reset of the demo passwords, seed, no H2 console, health only, headers) and the cookie and
 * redirect part of AC-008-11 and AC-008-12.
 *
 * <p>The refusals (AC-008-1, 2) point the datasource at {@code 127.0.0.1:1}, where nothing listens: if the check ran
 * after the DB connection, the failure would be "Connection refused" instead of our message. Every scenario that needs a
 * database gets its own, inside one PostgreSQL container, so contexts that are open together never share rows. All
 * values are passed as command-line arguments, so a real {@code SEED_*} variable on the developer's machine cannot
 * change the result. Passwords used here are test values and never the real ones.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class PublicProfileStartupTest {

    private static final String ADMIN_EMAIL = "admin@minishop.local";
    private static final String USER_EMAIL = "user@minishop.local";
    private static final String NEW_ADMIN = "Verify-Admin-2026";
    private static final String NEW_USER = "Verify-User-2026";
    private static final String TUNNEL_HOST = "demo-test.trycloudflare.com";

    private static final String EXPECTED_CSP = "default-src 'self'; img-src 'self' data:; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private static ConfigurableApplicationContext shared;
    private static int sharedPort;

    @BeforeAll
    static void startSharedPublicApp() throws Exception {
        String database = StandaloneApp.newDatabase(postgres);
        shared = StandaloneApp.start(publicRun(database, NEW_ADMIN, NEW_USER));
        sharedPort = StandaloneApp.portOf(shared);
    }

    @AfterAll
    static void stopSharedPublicApp() {
        if (shared != null) {
            shared.close();
        }
    }

    // ------------------------------------------------------------------ argument builders

    private static String[] databaseArgs(String database) {
        return new String[]{"--spring.datasource.url=" + StandaloneApp.jdbcUrl(postgres, database),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()};
    }

    private static String[] join(String[] first, String... more) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    private static String[] publicRun(String database, String adminPassword, String userPassword) {
        return join(databaseArgs(database), "--spring.profiles.active=public",
                "--SEED_ADMIN_PASSWORD=" + adminPassword, "--SEED_USER_PASSWORD=" + userPassword);
    }

    private static String[] devRun(String database) {
        return join(databaseArgs(database), "--SEED_ADMIN_PASSWORD=" + StandaloneApp.DEV_ADMIN_PASSWORD,
                "--SEED_USER_PASSWORD=" + StandaloneApp.DEV_USER_PASSWORD);
    }

    /** A run that must be refused before any database connection (the datasource points at a closed port). */
    private static String[] refusedRun(String profiles, String adminPassword, String userPassword) {
        return new String[]{"--spring.profiles.active=" + profiles,
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none", "--spring.datasource.username=nobody",
                "--spring.datasource.password=nobody",
                "--SEED_ADMIN_PASSWORD=" + adminPassword, "--SEED_USER_PASSWORD=" + userPassword};
    }

    // ------------------------------------------------------------------ AC-008-1, 2: refusals

    private void assertRefused(Throwable failure, CapturedOutput output, int offset, String message,
                               String... forbidden) {
        Throwable cause = StandaloneApp.causeNamed(failure, "StartupRequirementException");
        assertThat(cause).as("a StartupRequirementException in the cause chain of %s", failure).isNotNull();
        assertThat(cause.getMessage()).isEqualTo(message);

        String log = output.getAll().substring(offset);
        assertThat(log).contains(message);
        assertThat(log).as("Boot's failure report").contains("APPLICATION FAILED TO START");
        assertThat(log).as("the app stopped before it tried the database")
                .doesNotContain("Connection refused").doesNotContain("HikariPool");
        for (String value : forbidden) {
            assertThat(log).as("the log must not contain a secret value").doesNotContain(value);
            assertThat(failure.toString() + " " + cause.getMessage()).doesNotContain(value);
        }
    }

    @Test
    void start_whenBothDemoPasswordsAreMissing_refusesAndNamesBothVariables(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("public", "", ""));

        assertRefused(failure, output, offset,
                "Public mode requires environment variables: SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
    }

    @Test
    void start_whenOneDemoPasswordIsMissing_namesOnlyThatVariable(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("public", NEW_ADMIN, "  "));

        assertRefused(failure, output, offset, "Public mode requires environment variables: SEED_USER_PASSWORD",
                NEW_ADMIN);
    }

    @Test
    void start_whenPasswordsAreTheDevDefaults_refusesWithoutPrintingThem(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("public", "Admin@123", "User@123"));

        assertRefused(failure, output, offset,
                "Public mode requires non-default demo passwords: SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD",
                "Admin@123", "User@123");
    }

    @Test
    void start_whenAPasswordIsShorterThanTwelveCharacters_refusesAndNamesThatVariable(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("public", NEW_ADMIN, "Abcdefghi12"));

        assertRefused(failure, output, offset, "Public mode requires non-default demo passwords: SEED_USER_PASSWORD",
                "Abcdefghi12", NEW_ADMIN);
    }

    @Test
    void start_whenAdminAndUserPasswordsAreTheSame_refuses(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("public", "Same-Verify-Pass-1",
                "Same-Verify-Pass-1"));

        assertRefused(failure, output, offset,
                "Public mode requires different passwords for SEED_ADMIN_PASSWORD and SEED_USER_PASSWORD",
                "Same-Verify-Pass-1");
    }

    @Test
    void start_withH2AndPublicProfiles_isRefused(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("h2,public", NEW_ADMIN, NEW_USER));

        assertRefused(failure, output, offset, "Profiles 'h2' and 'public' cannot be active together", NEW_ADMIN,
                NEW_USER);
        assertThat(output.getAll().substring(offset)).contains("Run public mode with the default (Docker) database "
                + "or with 'local,public'.");
    }

    // ------------------------------------------------------------------ AC-008-3: reset of the demo passwords

    @Test
    void start_whenDemoAccountsHaveDevPasswords_resetsExactlyThoseTwoBeforeTomcatStarts(CapturedOutput output)
            throws Exception {
        String database = StandaloneApp.newDatabase(postgres);
        // 1st run: plain dev mode seeds the demo accounts with Admin@123 / User@123
        try (ConfigurableApplicationContext dev = StandaloneApp.start(devRun(database))) {
            assertThat(dev).isNotNull();
        }
        String otherHash = ENCODER.encode("Other-pass-1");
        StandaloneApp.execute(postgres, database, "insert into users (email, password_hash, full_name, role) values "
                + "('khac@minishop.test', '" + otherHash + "', 'Người khác', 'USER')");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from users")).isEqualTo("3");
        int offset = output.getAll().length();

        // 2nd run: public mode with new passwords
        try (ConfigurableApplicationContext publicApp = StandaloneApp.start(publicRun(database, NEW_ADMIN, NEW_USER))) {
            String log = output.getAll().substring(offset);

            Matcher updated = Pattern.compile("Public mode: demo passwords updated for 2 account\\(s\\)").matcher(log);
            assertThat(updated.find()).as("the INFO line").isTrue();
            int updatedAt = updated.start();
            assertThat(updated.find()).as("exactly one such line").isFalse();
            assertThat(log).doesNotContain("no demo accounts found");
            int tomcatAt = log.indexOf("Tomcat started on port");
            assertThat(tomcatAt).as("Tomcat started").isPositive();
            assertThat(updatedAt).as("the reset is logged before Tomcat accepts requests").isLessThan(tomcatAt);
            assertThat(log).doesNotContain(NEW_ADMIN, NEW_USER, "Admin@123", "User@123");

            assertThat(ENCODER.matches(NEW_ADMIN, hashOf(database, ADMIN_EMAIL))).isTrue();
            assertThat(ENCODER.matches(NEW_USER, hashOf(database, USER_EMAIL))).isTrue();
            assertThat(ENCODER.matches("Admin@123", hashOf(database, ADMIN_EMAIL))).isFalse();
            assertThat(ENCODER.matches("User@123", hashOf(database, USER_EMAIL))).isFalse();
            assertThat(hashOf(database, "khac@minishop.test")).as("another account is untouched").isEqualTo(otherHash);
            assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from users")).isEqualTo("3");

            int port = StandaloneApp.portOf(publicApp);
            // the dev password no longer logs in, the new one does
            HttpResponse<String> oldPassword = new Visitor(port).login(ADMIN_EMAIL, "Admin@123");
            assertThat(oldPassword.statusCode()).isEqualTo(302);
            assertThat(Visitor.location(oldPassword)).contains("/login?error");
            HttpResponse<String> oldUserPassword = new Visitor(port).login(USER_EMAIL, "User@123");
            assertThat(Visitor.location(oldUserPassword)).contains("/login?error");

            HttpResponse<String> newPassword = new Visitor(port).login(ADMIN_EMAIL, NEW_ADMIN);
            assertThat(newPassword.statusCode()).isEqualTo(302);
            assertThat(Visitor.location(newPassword)).doesNotContain("error");
            assertThat(Visitor.location(new Visitor(port).login(USER_EMAIL, NEW_USER))).doesNotContain("error");
        }
    }

    private static String hashOf(String database, String email) throws Exception {
        return StandaloneApp.scalar(postgres, database,
                "select password_hash from users where email = '" + email + "'");
    }

    // ------------------------------------------------------------------ AC-008-4: empty database

    @Test
    void start_onEmptyDatabase_seedsCatalogAndDemoAccountsWithTheConfiguredPasswords(CapturedOutput output)
            throws Exception {
        String database = StandaloneApp.newDatabase(postgres);
        int offset = output.getAll().length();

        try (ConfigurableApplicationContext app = StandaloneApp.start(publicRun(database, NEW_ADMIN, NEW_USER))) {
            String log = output.getAll().substring(offset);
            String notFound = "Public mode: no demo accounts found, so no password was reset";
            assertThat(log).contains(notFound);
            assertThat(log.indexOf(notFound)).isEqualTo(log.lastIndexOf(notFound));
            assertThat(log).doesNotContain("demo passwords updated for");
            assertThat(log.indexOf(notFound)).as("before Tomcat starts").isLessThan(log.indexOf("Tomcat started"));
            assertThat(log).contains("Seeded 2 users").contains("Seeded 5 categories and 22 products");
            assertThat(log).doesNotContain(NEW_ADMIN, NEW_USER);

            assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from users")).isEqualTo("2");
            assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from categories")).isEqualTo("5");
            assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from products")).isEqualTo("22");
            assertThat(ENCODER.matches(NEW_ADMIN, hashOf(database, ADMIN_EMAIL))).isTrue();
            assertThat(ENCODER.matches(NEW_USER, hashOf(database, USER_EMAIL))).isTrue();
            assertThat(ENCODER.matches("Admin@123", hashOf(database, ADMIN_EMAIL))).as("never the dev password")
                    .isFalse();

            int port = StandaloneApp.portOf(app);
            assertThat(Visitor.location(new Visitor(port).login(ADMIN_EMAIL, "Admin@123"))).contains("/login?error");
            assertThat(Visitor.location(new Visitor(port).login(ADMIN_EMAIL, NEW_ADMIN))).doesNotContain("error");
            assertThat(Visitor.location(new Visitor(port).login(USER_EMAIL, NEW_USER))).doesNotContain("error");
        }
    }

    // ------------------------------------------------------------------ AC-008-5: what is not exposed

    @Test
    void publicApp_hasNoH2ConsoleBeanAndTheConsolePathIsNotFound() throws Exception {
        assertThat(shared.getEnvironment().getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
        assertThat(shared.containsBean("h2ConsoleSecurityFilterChain")).isFalse();
        assertThat(shared.getBeansOfType(ServletRegistrationBean.class).keySet())
                .noneMatch(name -> name.toLowerCase().contains("h2"));

        Visitor admin = new Visitor(sharedPort);
        assertThat(Visitor.location(admin.login(ADMIN_EMAIL, NEW_ADMIN))).doesNotContain("error");
        assertThat(admin.get("/h2-console").statusCode()).as("logged in as admin: nothing there").isEqualTo(404);
        assertThat(admin.get("/h2-console/").statusCode()).isEqualTo(404);
        assertThat(admin.get("/h2-console/login.do").statusCode()).isEqualTo(404);

        HttpResponse<String> anonymous = new Visitor(sharedPort).get("/h2-console/");
        assertThat(anonymous.statusCode()).as("never served to anyone").isNotEqualTo(200);
        assertThat(URI.create(Visitor.location(anonymous)).getPath()).as("anonymous goes to the login page")
                .isEqualTo("/login");
    }

    @Test
    void publicApp_hasNoLiveReloadRestartOrPersistentSessions() {
        assertThat(shared.getEnvironment().getProperty("spring.devtools.livereload.enabled", Boolean.class)).isFalse();
        assertThat(shared.getEnvironment().getProperty("spring.devtools.restart.enabled", Boolean.class)).isFalse();
        assertThat(shared.getEnvironment().getProperty("server.servlet.session.persistent", Boolean.class)).isFalse();
    }

    @Test
    void publicApp_actuatorHealthReturnsOnlyTheStatus() throws Exception {
        HttpResponse<String> health = new Visitor(sharedPort).get("/actuator/health", "application/json", Map.of());

        assertThat(health.statusCode()).isEqualTo(200);
        // AC-008-5: exactly the status. Probes are switched off (management.endpoint.health.probes.enabled=false), so
        // there is no "groups" list either.
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(shared.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(shared.getEnvironment().getProperty("management.endpoint.health.probes.enabled", Boolean.class))
                .isFalse();
        assertThat(shared.getEnvironment().getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(shared.getEnvironment().getProperty("management.endpoint.health.show-components"))
                .isEqualTo("never");
    }

    @Test
    void publicApp_livenessAndReadinessProbesDoNotExist() throws Exception {
        Visitor admin = new Visitor(sharedPort);
        assertThat(Visitor.location(admin.login(ADMIN_EMAIL, NEW_ADMIN))).doesNotContain("error");

        for (String probe : List.of("liveness", "readiness")) {
            assertThat(admin.get("/actuator/health/" + probe, "application/json", Map.of()).statusCode())
                    .as("/actuator/health/%s as admin", probe).isEqualTo(404);

            HttpResponse<String> anonymous = new Visitor(sharedPort)
                    .get("/actuator/health/" + probe, "application/json", Map.of());
            assertThat(anonymous.statusCode()).as("/actuator/health/%s as a guest", probe).isNotEqualTo(200);
            assertThat(anonymous.body()).doesNotContain("\"status\"");
        }
    }

    @Test
    void publicApp_noOtherActuatorEndpointExistsEvenForAnAdmin() throws Exception {
        Visitor admin = new Visitor(sharedPort);
        assertThat(Visitor.location(admin.login(ADMIN_EMAIL, NEW_ADMIN))).doesNotContain("error");

        for (String endpoint : List.of("env", "beans", "configprops", "mappings", "metrics", "info", "loggers",
                "heapdump", "threaddump", "shutdown", "httpexchanges", "conditions")) {
            assertThat(admin.get("/actuator/" + endpoint, "application/json", Map.of()).statusCode())
                    .as("/actuator/%s", endpoint).isEqualTo(404);
        }
    }

    @Test
    void publicApp_keepsTheSecurityHeadersOfEarlierPhases() throws Exception {
        HttpResponse<String> home = new Visitor(sharedPort).get("/");

        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.headers().firstValue("Content-Security-Policy")).hasValue(EXPECTED_CSP);
        assertThat(home.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(home.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(home.headers().firstValue("Strict-Transport-Security")).as("plain HTTP: no HSTS").isEmpty();
    }

    @Test
    void publicApp_listensOnLoopbackOnly() throws Exception {
        assertThat(shared.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
        try (Socket loopback = new Socket()) {
            loopback.connect(new InetSocketAddress("127.0.0.1", sharedPort), 2_000);
            assertThat(loopback.isConnected()).isTrue();
        }
        // Another machine on the same network reaches this machine through one of its own non-loopback addresses.
        List<InetAddress> others = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nic.isUp() && !nic.isLoopback()) {
                Collections.list(nic.getInetAddresses()).stream()
                        .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress())
                        .forEach(others::add);
            }
        }
        for (InetAddress address : others) {
            assertThatThrownBy(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, sharedPort), 2_000);
                }
            }).as("port %d must not answer on %s", sharedPort, address.getHostAddress())
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    // ------------------------------------------------------------------ AC-008-11, 12: cookie and redirect

    /**
     * What cloudflared sends: the public domain in {@code Host} (the test client really sends it, see
     * {@link RawHttpClient}), {@code X-Forwarded-Proto: https} and the visitor's address. {@code ip} makes the throttle
     * key of each test its own.
     */
    private static Map<String, String> tunnelHeaders(String ip) {
        return Map.of("Host", TUNNEL_HOST, "X-Forwarded-Proto", "https", "X-Forwarded-For", ip);
    }

    @Test
    void publicApp_trustsNoForwardedHostOrPortHeader() {
        // Boot would read X-Forwarded-Host/-Port by default; a client can send them, so they are switched off
        assertThat(shared.getEnvironment().getProperty("server.tomcat.remoteip.host-header")).isEmpty();
        assertThat(shared.getEnvironment().getProperty("server.tomcat.remoteip.port-header")).isEmpty();
        assertThat(shared.getEnvironment().getProperty("server.tomcat.remoteip.internal-proxies"))
                .isEqualTo("127.0.0.0/8, ::1/128");
    }

    @Test
    void login_throughTheTunnel_redirectsToHttpsDomainAndSetsSecureHttpOnlyLaxCookieAndHsts() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(sharedPort)
                .login(USER_EMAIL, NEW_USER, tunnelHeaders("203.0.113.5"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("https://" + TUNNEL_HOST + "/");
        String cookie = response.sessionSetCookie();
        assertThat(cookie).as("the new session cookie after login").startsWith("JSESSIONID=");
        assertThat(cookie).contains("Secure").contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(response.header("Strict-Transport-Security")).isNotNull();
    }

    @Test
    void login_throughTheTunnel_withAWrongPassword_staysOnTheHttpsDomain() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(sharedPort)
                .login(USER_EMAIL, "Wrong-pass-1", tunnelHeaders("203.0.113.6"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("https://" + TUNNEL_HOST + "/login?error");
    }

    @Test
    void login_throughTheTunnel_ignoresASpoofedForwardedHostAndPort() throws Exception {
        Map<String, String> headers = new java.util.LinkedHashMap<>(tunnelHeaders("203.0.113.7"));
        headers.put("X-Forwarded-Host", "evil.example.com");
        headers.put("X-Forwarded-Port", "8443");

        RawHttpClient.Response response = new RawHttpClient(sharedPort).login(USER_EMAIL, NEW_USER, headers);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).as("the real Host header decides, not X-Forwarded-Host/-Port")
                .isEqualTo("https://" + TUNNEL_HOST + "/");
    }

    @Test
    void login_withoutForwardedProto_ignoresASpoofedForwardedHostAndPort() throws Exception {
        RawHttpClient client = new RawHttpClient(sharedPort);
        Map<String, String> headers = Map.of("X-Forwarded-Host", "evil.example.com", "X-Forwarded-Port", "8443",
                "X-Forwarded-For", "203.0.113.8");

        RawHttpClient.Response response = client.login(USER_EMAIL, NEW_USER, headers);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("http://" + client.ownHost() + "/");
        assertThat(response.header("Strict-Transport-Security")).as("not https: no HSTS").isNull();
    }

    @Test
    void login_directlyOnLocalhost_stillWorksOverPlainHttp() throws Exception {
        HttpResponse<String> response = new Visitor(sharedPort).login(USER_EMAIL, NEW_USER);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(Visitor.location(response)).isEqualTo("http://127.0.0.1:" + sharedPort + "/");
        String cookie = response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID="))
                .findFirst().orElse("");
        assertThat(cookie).startsWith("JSESSIONID=");
        assertThat(cookie).as("Secure is explicit in public mode, even over http").contains("Secure")
                .contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(response.headers().firstValue("Strict-Transport-Security")).as("no HSTS over plain http").isEmpty();
    }
}

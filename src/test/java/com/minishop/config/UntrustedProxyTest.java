package com.minishop.config;

import com.minishop.support.LiveServerTest;
import com.minishop.support.RawHttpClient;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other side of {@link ForwardedHeadersTest} (AC-008-12, BR-008-4): when the connection does <em>not</em> come from
 * a trusted proxy, every {@code X-Forwarded-*} header is ignored. The test cannot really connect from another machine,
 * so it makes the loopback peer untrusted instead: {@code internal-proxies} is set to one address that is not this
 * machine, so the same connection that was a trusted tunnel in {@link ForwardedHeadersTest} is now "anyone on the
 * network".
 *
 * <p>This is a deliberate second Spring context (the property differs) with its own in-memory database, so nothing here
 * can touch the login counters of the shared {@link LiveServerTest} context. Inside this context every request comes
 * from {@code 127.0.0.1}, and the last test blocks that address, so it runs last ({@code @Order}); the others make at
 * most one login each.
 */
@LiveServerTest
@TestPropertySource(properties = {
        "server.tomcat.remoteip.internal-proxies=192.0.2.1/32",
        "spring.datasource.url=jdbc:h2:mem:untrusted-proxy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UntrustedProxyTest {

    private static final String SEED_USER = "user@minishop.local";

    @LocalServerPort
    int port;

    @Autowired
    Environment environment;

    @Value("${app.seed.user-password}")
    String seedUserPassword;

    private static String uniqueEmail() {
        return "throttle-" + UUID.randomUUID() + "@minishop.test";
    }

    @Test
    @Order(1)
    void thisContext_trustsOnlyAnAddressThatIsNotThisMachine() {
        assertThat(environment.getProperty("server.tomcat.remoteip.internal-proxies")).isEqualTo("192.0.2.1/32");
        assertThat(environment.getProperty("spring.datasource.url")).contains("untrusted-proxy");
    }

    @Test
    @Order(2)
    void login_withForwardedHeadersFromAnUntrustedPeer_staysOnPlainHttpAndTheRealHost() throws Exception {
        RawHttpClient client = new RawHttpClient(port);
        Map<String, String> headers = Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example.com",
                "X-Forwarded-Port", "8443", "X-Forwarded-For", "203.0.113.1");

        RawHttpClient.Response response = client.login(SEED_USER, seedUserPassword, headers);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("http://" + client.ownHost() + "/");
        assertThat(response.header("Strict-Transport-Security")).as("not treated as https: no HSTS").isNull();
    }

    @Test
    @Order(3)
    void protectedPage_withForwardedHeadersFromAnUntrustedPeer_redirectsToThePlainHttpLoginPage() throws Exception {
        RawHttpClient client = new RawHttpClient(port);
        Map<String, String> headers = Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example.com",
                "X-Forwarded-Port", "8443");

        RawHttpClient.Response response = client.get("/orders", headers);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("http://" + client.ownHost() + "/login");
        assertThat(response.header("Strict-Transport-Security")).isNull();
    }

    @Test
    @Order(4)
    void forwardedProtoHttps_fromAnUntrustedPeer_doesNotMakeTheRequestSecure() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(port).get("/",
                Map.of("X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.2"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Strict-Transport-Security")).isNull();
    }

    /**
     * Twenty wrong logins, each with another email <em>and</em> another {@code X-Forwarded-For}: if the header were
     * believed, every attempt would count for a different address and nothing would be blocked. All of them count for
     * the real peer address, so the 21st is refused. Last, because it blocks {@code 127.0.0.1} in this context.
     */
    @Test
    @Order(99)
    void ipLimit_ignoresXForwardedFor_everyAttemptCountsAgainstThePeerAddress() throws Exception {
        for (int i = 1; i <= 20; i++) {
            RawHttpClient.Response response = new RawHttpClient(port).login(uniqueEmail(), "Wrong-pass-1",
                    Map.of("X-Forwarded-For", "203.0.113." + (100 + i), "X-Forwarded-Proto", "https"));
            assertThat(response.location()).as("attempt %d", i).endsWith("/login?error");
        }

        RawHttpClient client = new RawHttpClient(port);
        RawHttpClient.Response blocked = client.login(uniqueEmail(), "Wrong-pass-1",
                Map.of("X-Forwarded-For", "203.0.113.200"));

        assertThat(blocked.location()).isEqualTo("http://" + client.ownHost() + "/login?blocked");

        // a new forwarded address does not help, and not even the right password gets through
        RawHttpClient.Response rightPassword = new RawHttpClient(port).login(SEED_USER, seedUserPassword,
                Map.of("X-Forwarded-For", "203.0.113.201"));
        assertThat(rightPassword.location()).endsWith("/login?blocked");
    }
}

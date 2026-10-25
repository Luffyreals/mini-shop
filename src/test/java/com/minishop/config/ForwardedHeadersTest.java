package com.minishop.config;

import com.minishop.support.LiveServerTest;
import com.minishop.support.RawHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the app believes about a request that arrives through a local proxy (cloudflared), on a real Tomcat with the
 * {@code h2} profile (AC-008-10: the behaviour is the same in every profile, only the {@code Secure} cookie flag is
 * public-mode only): AC-008-8, 10, 11, 12; BR-008-3, 4.
 *
 * <p>The tunnel is simulated the way it really talks: the public domain in {@code Host} (sent for real by
 * {@link RawHttpClient}), {@code X-Forwarded-Proto: https} and {@code X-Forwarded-For}. The connection comes from the
 * loopback address, so it is a trusted proxy. {@code X-Forwarded-Host}/{@code -Port} are <em>not</em> trusted (a client
 * can send them), which the spoofing tests pin down.
 *
 * <p><b>Isolation (QĐ10):</b> this class shares its Spring context, and with it the in-memory login counters, with every
 * other {@link LiveServerTest}. So wrong passwords are only ever tried with a private
 * {@code throttle-<uuid>@minishop.test} email and a private {@code 203.0.113.x} address from {@code X-Forwarded-For}
 * (203.0.113.70-99 belong to this class), and never more than 19 times from the real loopback address. Logins that must
 * succeed use the seed user, which is safe: a success clears the email count and gives its IP entry back.
 */
@LiveServerTest
class ForwardedHeadersTest {

    private static final String SEED_USER = "user@minishop.local";
    private static final String TUNNEL_HOST = "demo-abc.trycloudflare.com";

    @LocalServerPort
    int port;

    @Autowired
    Environment environment;

    @Value("${app.seed.user-password}")
    String seedUserPassword;

    private static String uniqueEmail() {
        return "throttle-" + UUID.randomUUID() + "@minishop.test";
    }

    private static Map<String, String> tunnel(String ip) {
        return Map.of("Host", TUNNEL_HOST, "X-Forwarded-Proto", "https", "X-Forwarded-For", ip);
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void trustedProxies_areTheLoopbackRangesOnly() {
        assertThat(environment.getProperty("server.tomcat.remoteip.internal-proxies"))
                .isEqualTo("127.0.0.0/8, ::1/128");
        assertThat(environment.getProperty("server.tomcat.remoteip.remote-ip-header")).isEqualTo("X-Forwarded-For");
        assertThat(environment.getProperty("server.tomcat.remoteip.protocol-header")).isEqualTo("X-Forwarded-Proto");
    }

    @Test
    void forwardedHostAndPortHeaders_areNotRead() {
        assertThat(environment.getProperty("server.tomcat.remoteip.host-header")).isEmpty();
        assertThat(environment.getProperty("server.tomcat.remoteip.port-header")).isEmpty();
    }

    // ------------------------------------------------------------------ AC-008-11: through the tunnel

    @Test
    void login_throughTheTunnel_redirectsToTheHttpsDomainWithHsts() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(port).login(SEED_USER, seedUserPassword,
                tunnel("203.0.113.70"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("https://" + TUNNEL_HOST + "/");
        assertThat(response.header("Strict-Transport-Security")).as("https: HSTS").isNotNull();
    }

    @Test
    void failedLogin_throughTheTunnel_staysOnTheHttpsDomain() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(port).login(uniqueEmail(), "Wrong-pass-1",
                tunnel("203.0.113.71"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("https://" + TUNNEL_HOST + "/login?error");
    }

    @Test
    void anonymousProtectedPage_throughTheTunnel_redirectsToTheLoginPageOnTheHttpsDomain() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(port).get("/orders", tunnel("203.0.113.72"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("https://" + TUNNEL_HOST + "/login");
    }

    @Test
    void forwardedHostAndPort_sentByAClient_areIgnored() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(tunnel("203.0.113.73"));
        headers.put("X-Forwarded-Host", "evil.example.com");
        headers.put("X-Forwarded-Port", "8443");

        RawHttpClient.Response response = new RawHttpClient(port).login(SEED_USER, seedUserPassword, headers);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).as("the real Host header decides").isEqualTo("https://" + TUNNEL_HOST + "/");
        assertThat(response.location()).doesNotContain("evil").doesNotContain("8443");
    }

    @Test
    void forwardedHostAndPort_withoutForwardedProto_areIgnoredToo() throws Exception {
        RawHttpClient client = new RawHttpClient(port);
        Map<String, String> headers = Map.of("X-Forwarded-Host", "evil.example.com", "X-Forwarded-Port", "8443",
                "X-Forwarded-For", "203.0.113.74");

        RawHttpClient.Response response = client.login(SEED_USER, seedUserPassword, headers);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("http://" + client.ownHost() + "/");
    }

    // ------------------------------------------------------------------ AC-008-12: straight to localhost

    @Test
    void login_withoutForwardedHeaders_redirectsToThePlainHttpHostAndSetsNoHsts() throws Exception {
        RawHttpClient client = new RawHttpClient(port);

        RawHttpClient.Response response = client.login(SEED_USER, seedUserPassword);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo("http://" + client.ownHost() + "/");
        assertThat(response.header("Strict-Transport-Security")).as("plain http: no HSTS").isNull();
    }

    @Test
    void sessionCookie_inTheH2Profile_isHttpOnlyAndLaxButNotSecure() throws Exception {
        RawHttpClient.Response response = new RawHttpClient(port).login(SEED_USER, seedUserPassword);

        String cookie = response.sessionSetCookie();
        assertThat(cookie).startsWith("JSESSIONID=");
        assertThat(cookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(cookie).as("Secure is public-mode only, so plain http dev keeps its session").doesNotContain("Secure");
    }

    // ------------------------------------------------------------------ AC-008-8, 10: the IP comes from the proxy

    @Test
    void ipLimit_countsTheForwardedAddress_twentyWrongLoginsBlockThatAddressOnly() throws Exception {
        String blockedIp = "203.0.113.80";
        for (int i = 1; i <= 20; i++) {
            RawHttpClient.Response response = new RawHttpClient(port).login(uniqueEmail(), "Wrong-pass-1",
                    tunnel(blockedIp));
            assertThat(response.location()).as("attempt %d", i).isEqualTo("https://" + TUNNEL_HOST + "/login?error");
        }

        // the 21st attempt, with a brand-new email, from the same forwarded address
        RawHttpClient.Response blocked = new RawHttpClient(port).login(uniqueEmail(), "Wrong-pass-1", tunnel(blockedIp));
        assertThat(blocked.location()).isEqualTo("https://" + TUNNEL_HOST + "/login?blocked");

        // the right password of the seed user is refused from that address too: it is not even checked
        RawHttpClient.Response rightPassword = new RawHttpClient(port).login(SEED_USER, seedUserPassword,
                tunnel(blockedIp));
        assertThat(rightPassword.location()).isEqualTo("https://" + TUNNEL_HOST + "/login?blocked");

        // another forwarded address is a different visitor
        RawHttpClient.Response otherAddress = new RawHttpClient(port).login(uniqueEmail(), "Wrong-pass-1",
                tunnel("203.0.113.81"));
        assertThat(otherAddress.location()).isEqualTo("https://" + TUNNEL_HOST + "/login?error");

        // and so is the real loopback address, which carried the tunnel's connections but was never counted
        RawHttpClient.Response noHeader = new RawHttpClient(port).login(SEED_USER, seedUserPassword);
        assertThat(noHeader.location()).as("a login straight to localhost is not affected").doesNotContain("?");
    }

    @Test
    void emailLimit_blocksTheSixthAttemptForTheSameEmailFromAnyForwardedAddress() throws Exception {
        String email = uniqueEmail();
        for (int i = 1; i <= 5; i++) {
            RawHttpClient.Response response = new RawHttpClient(port).login(email, "Wrong-pass-1",
                    tunnel("203.0.113." + (90 + i)));
            assertThat(response.location()).as("attempt %d", i).endsWith("/login?error");
        }

        RawHttpClient.Response sixth = new RawHttpClient(port).login(email, "Wrong-pass-1", tunnel("203.0.113.97"));

        assertThat(sixth.location()).isEqualTo("https://" + TUNNEL_HOST + "/login?blocked");
    }

    @Test
    void blockedPage_showsTheBlockedMessageAndNotTheWrongPasswordMessage() throws Exception {
        RawHttpClient.Response page = new RawHttpClient(port).get("/login?blocked", tunnel("203.0.113.98"));

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.")
                .doesNotContain("Email hoặc mật khẩu không đúng.");
    }
}

package com.minishop.config;

import com.minishop.support.LiveServerTest;
import com.minishop.support.RawHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login gate under a real burst of parallel requests on a real Tomcat (AC-008-7, 8; BR-008-3). If the gate only read
 * the state and the failure were counted after the (slow) password check, all parallel requests would pass the gate and
 * every one would get its password checked. The atomic gate lets exactly the first 5 attempts of an email (or the first
 * 20 of an IP) through and refuses the rest with {@code /login?blocked}.
 *
 * <p>Every visitor first loads the login page (token and session), then all of them post at the same instant. Each
 * visitor has its own session. {@code X-Forwarded-For} comes from the loopback connection, so it is trusted (QĐ10
 * isolation): the email burst uses one address per request so the IP limit cannot decide, the IP burst uses one
 * address and one email per request so the email limit cannot decide. The addresses are {@code 198.51.100.x} (this
 * class owns {@code .1} to {@code .120}) and the emails are private, so the shared live context is not disturbed.
 */
@LiveServerTest
class LoginThrottleBurstTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private record Burst(int errors, int blocked, List<String> other) {
    }

    private static String uniqueEmail() {
        return "burst-" + UUID.randomUUID() + "@minishop.test";
    }

    /**
     * {@code requests} parallel visitors; visitor {@code i} posts the form {@code form.apply(i)} with the headers
     * {@code headers.apply(i)}. Returns how the responses were distributed.
     */
    private Burst burst(int requests, IntFunction<Map<String, String>> form,
                        IntFunction<Map<String, String>> headers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<RawHttpClient.Response>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int visitor = i;
                Callable<RawHttpClient.Response> task = () -> {
                    RawHttpClient client = new RawHttpClient(port);
                    Map<String, String> visitorHeaders = headers.apply(visitor);
                    String token = RawHttpClient.csrfFrom(client.get("/login", visitorHeaders).body());
                    Map<String, String> fields = new LinkedHashMap<>(form.apply(visitor));
                    fields.put("_csrf", token);
                    ready.countDown();
                    assertThat(go.await(30, TimeUnit.SECONDS)).isTrue();
                    return client.postForm("/login", fields, visitorHeaders);
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(60, TimeUnit.SECONDS)).as("every visitor loaded the login page").isTrue();
            go.countDown();

            int errors = 0;
            int blocked = 0;
            List<String> other = new ArrayList<>();
            for (Future<RawHttpClient.Response> future : futures) {
                RawHttpClient.Response response = future.get(60, TimeUnit.SECONDS);
                assertThat(response.status()).isEqualTo(302);
                String location = response.location();
                if (location.endsWith("/login?error")) {
                    errors++;
                } else if (location.endsWith("/login?blocked")) {
                    blocked++;
                } else {
                    other.add(location);
                }
            }
            return new Burst(errors, blocked, other);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, String> login(String email, String password) {
        return Map.of("email", email, "password", password);
    }

    @Test
    void parallelWrongLoginsForOneEmail_letExactlyFiveBeCheckedAndRefuseTheRest() throws Exception {
        String email = uniqueEmail();
        int requests = 20;

        Burst result = burst(requests, i -> login(email, "Wrong-pass-1"),
                i -> Map.of("X-Forwarded-For", "198.51.100." + (i + 1)));

        assertThat(result.other()).as("nothing but error or blocked").isEmpty();
        assertThat(result.errors()).as("only 5 passwords may be checked").isEqualTo(5);
        assertThat(result.blocked()).isEqualTo(requests - 5);
    }

    @Test
    void parallelWrongLoginsForOneEmail_leaveTheEmailBlockedForTheNextRequest() throws Exception {
        String email = uniqueEmail();
        burst(8, i -> login(email, "Wrong-pass-1"), i -> Map.of("X-Forwarded-For", "198.51.100." + (30 + i)));

        RawHttpClient.Response next = new RawHttpClient(port).login(email, "Wrong-pass-1",
                Map.of("X-Forwarded-For", "198.51.100.40"));

        assertThat(next.location()).endsWith("/login?blocked");
    }

    @Test
    void parallelWrongLoginsFromOneIp_letExactlyTwentyBeCheckedAndRefuseTheRest() throws Exception {
        int requests = 30;

        Burst result = burst(requests, i -> login(uniqueEmail(), "Wrong-pass-1"),
                i -> Map.of("X-Forwarded-For", "198.51.100.50"));

        assertThat(result.other()).isEmpty();
        assertThat(result.errors()).as("only 20 passwords may be checked").isEqualTo(20);
        assertThat(result.blocked()).isEqualTo(requests - 20);
    }

    @Test
    void parallelLoginsWithTheRightPasswordOfABlockedAccount_areAllRefused() throws Exception {
        String email = "burst-" + UUID.randomUUID() + "@minishop.test";
        String password = "Right-pass-1";
        jdbc.update("insert into users (email, password_hash, full_name, role) values (?, ?, 'Burst Tester', 'USER')",
                email, encoder.encode(password));
        try {
            for (int i = 0; i < 5; i++) {
                RawHttpClient.Response wrong = new RawHttpClient(port).login(email, "Wrong-pass-1",
                        Map.of("X-Forwarded-For", "198.51.100." + (60 + i)));
                assertThat(wrong.location()).as("wrong attempt %d", i + 1).endsWith("/login?error");
            }

            Burst result = burst(10, i -> login(email, password),
                    i -> Map.of("X-Forwarded-For", "198.51.100." + (70 + i)));

            assertThat(result.blocked()).as("the right password is not even checked").isEqualTo(10);
            assertThat(result.errors()).isZero();
            assertThat(result.other()).as("nobody got logged in").isEmpty();
        } finally {
            jdbc.update("delete from users where email = ?", email);
        }
    }

    @Test
    void parallelWrongLoginsForDifferentEmails_areNotBlockedByEachOther() throws Exception {
        int requests = 12;

        Burst result = burst(requests, i -> login(uniqueEmail(), "Wrong-pass-1"),
                i -> Map.of("X-Forwarded-For", "198.51.100." + (90 + i)));

        assertThat(result.errors()).as("different emails and different addresses: all of them are checked")
                .isEqualTo(requests);
        assertThat(result.blocked()).isZero();
    }
}

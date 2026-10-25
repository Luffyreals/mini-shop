package com.minishop.config;

import com.minishop.support.MutableClock;
import com.minishop.user.LoginThrottle;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The gate in front of the password check (AC-008-7, QĐ4): {@code POST /login} calls {@code tryAcquire} once; a refusal
 * is handed to the failure handler and the chain, which holds {@code UsernamePasswordAuthenticationFilter}, is never
 * called, so a correct password is not even looked at. Otherwise the {@link LoginThrottle.Attempt} is stored in
 * {@link LoginThrottleFilter#ATTEMPT_ATTRIBUTE}. A real {@link LoginThrottle} (mutable clock) is used, so the test also
 * proves that the filter reads the parameter {@code email} and {@code getRemoteAddr()}.
 */
@ExtendWith(MockitoExtension.class)
class LoginThrottleFilterTest {

    private static final String IP = "198.51.100.7";

    @Mock
    FilterChain chain;

    @Mock
    AuthenticationFailureHandler blockedHandler;

    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(clock);
    private LoginThrottleFilter filter;

    @BeforeEach
    void createFilter() {
        // after Mockito has injected the mocks (field initialisers run before that)
        filter = new LoginThrottleFilter(throttle, blockedHandler);
    }

    private static MockHttpServletRequest request(String method, String uri, String email, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        if (email != null) {
            request.setParameter("email", email);
        }
        request.setParameter("password", "Correct-pass-1");
        return request;
    }

    private void blockEmail(String email) {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES_PER_EMAIL; i++) {
            assertThat(throttle.tryAcquire(email, "203.0.113." + (i + 1))).isPresent();
        }
    }

    private void blockIp(String ip) {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES_PER_IP; i++) {
            assertThat(throttle.tryAcquire("many" + i + "@x.vn", ip)).isPresent();
        }
    }

    // ------------------------------------------------------------------ refusal

    @Test
    void postLogin_whenEmailBlocked_handsLoginBlockedExceptionToHandlerAndStopsTheChain() throws Exception {
        blockEmail("user@x.vn");
        MockHttpServletRequest request = request("POST", "/login", "user@x.vn", "192.0.2.50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        ArgumentCaptor<AuthenticationException> captured = ArgumentCaptor.forClass(AuthenticationException.class);
        verify(blockedHandler).onAuthenticationFailure(any(), any(), captured.capture());
        assertThat(captured.getValue()).isInstanceOf(LoginBlockedException.class)
                .hasMessage("Login temporarily blocked");
        verifyNoInteractions(chain);
    }

    @Test
    void postLogin_whenRefused_storesNoAttemptInTheRequest() throws Exception {
        blockEmail("user@x.vn");
        MockHttpServletRequest request = request("POST", "/login", "user@x.vn", "192.0.2.50");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE)).isNull();
    }

    @Test
    void postLogin_whenEmailBlocked_matchesTheNormalisedEmail() throws Exception {
        blockEmail("user@x.vn");

        filter.doFilter(request("POST", "/login", "  USER@X.VN ", "192.0.2.50"), new MockHttpServletResponse(), chain);

        verify(blockedHandler).onAuthenticationFailure(any(), any(), any(LoginBlockedException.class));
        verifyNoInteractions(chain);
    }

    @Test
    void postLogin_whenIpBlocked_blocksAnyEmailFromThatIp() throws Exception {
        blockIp(IP);

        filter.doFilter(request("POST", "/login", "never-seen@x.vn", IP), new MockHttpServletResponse(), chain);

        verify(blockedHandler).onAuthenticationFailure(any(), any(), any(LoginBlockedException.class));
        verifyNoInteractions(chain);
    }

    @Test
    void postLogin_whenIpBlockedAndEmailParameterMissing_isStillBlocked() throws Exception {
        blockIp(IP);

        filter.doFilter(request("POST", "/login", null, IP), new MockHttpServletResponse(), chain);

        verify(blockedHandler).onAuthenticationFailure(any(), any(), any(LoginBlockedException.class));
        verifyNoInteractions(chain);
    }

    // ------------------------------------------------------------------ let through

    @Test
    void postLogin_whenNeitherIsBlocked_callsTheChainExactlyOnceAndStoresTheAttempt() throws Exception {
        blockEmail("someone-else@x.vn");
        blockIp("198.51.100.99");
        MockHttpServletRequest request = request("POST", "/login", "user@x.vn", IP);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(blockedHandler);
        assertThat(request.getAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE))
                .isInstanceOf(LoginThrottle.Attempt.class);
    }

    @Test
    void postLogin_withNoAttemptsYet_callsTheChain() throws Exception {
        MockHttpServletRequest request = request("POST", "/login", "user@x.vn", IP);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(blockedHandler, never()).onAuthenticationFailure(any(), any(), any());
    }

    @Test
    void postLogin_countsEveryAttemptAtTheGate_soTheFifthIsCheckedAndTheSixthIsRefused() throws Exception {
        for (int i = 1; i <= 5; i++) {
            filter.doFilter(request("POST", "/login", "user@x.vn", "203.0.113." + i),
                    new MockHttpServletResponse(), chain);
        }
        verify(chain, times(5)).doFilter(any(), any());
        verifyNoInteractions(blockedHandler);

        MockHttpServletRequest sixth = request("POST", "/login", "user@x.vn", "203.0.113.6");
        filter.doFilter(sixth, new MockHttpServletResponse(), chain);

        verify(chain, times(5)).doFilter(any(), any());
        verify(blockedHandler).onAuthenticationFailure(any(), any(), any(LoginBlockedException.class));
    }

    @Test
    void postLogin_usesTheRemoteAddressAsTheClientIp() throws Exception {
        for (int i = 0; i < 20; i++) {
            filter.doFilter(request("POST", "/login", "u" + i + "@x.vn", IP), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(20)).doFilter(any(), any());

        filter.doFilter(request("POST", "/login", "u20@x.vn", IP), new MockHttpServletResponse(), chain);

        verify(blockedHandler).onAuthenticationFailure(any(), any(), any(LoginBlockedException.class));
        verify(chain, times(20)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ refusals count nothing

    @Test
    void refusedRequests_neverCountAndNeverExtendTheBlock() throws Exception {
        for (int i = 1; i <= 5; i++) {
            filter.doFilter(request("POST", "/login", "user@x.vn", "203.0.113." + i),
                    new MockHttpServletResponse(), chain);
        }
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 20; i++) {
            filter.doFilter(request("POST", "/login", "user@x.vn", "192.0.2." + i),
                    new MockHttpServletResponse(), chain);
        }
        verify(blockedHandler, times(20)).onAuthenticationFailure(any(), any(), any(LoginBlockedException.class));

        clock.advance(Duration.ofMinutes(5));
        MockHttpServletRequest again = request("POST", "/login", "user@x.vn", "192.0.2.200");
        filter.doFilter(again, new MockHttpServletResponse(), chain);

        assertThat(again.getAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE))
                .as("15 minutes after the block began, refused attempts did not extend it")
                .isInstanceOf(LoginThrottle.Attempt.class);
        verify(chain, times(6)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ everything else

    @Test
    void otherRequests_alwaysPassEvenWhenEmailAndIpAreBlocked() throws Exception {
        blockEmail("user@x.vn");
        blockIp(IP);
        MockHttpServletRequest getLogin = request("GET", "/login", "user@x.vn", IP);
        MockHttpServletRequest postRegister = request("POST", "/register", "user@x.vn", IP);
        MockHttpServletRequest postLogout = request("POST", "/logout", "user@x.vn", IP);
        MockHttpServletRequest postOtherLogin = request("POST", "/login/other", "user@x.vn", IP);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(getLogin, response, chain);
        filter.doFilter(postRegister, response, chain);
        filter.doFilter(postLogout, response, chain);
        filter.doFilter(postOtherLogin, response, chain);

        verify(chain).doFilter(getLogin, response);
        verify(chain).doFilter(postRegister, response);
        verify(chain).doFilter(postLogout, response);
        verify(chain).doFilter(postOtherLogin, response);
        verifyNoInteractions(blockedHandler);
        assertThat(getLogin.getAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE)).isNull();
        assertThat(postRegister.getAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE)).isNull();
    }

    @Test
    void otherRequests_areNeverCounted() throws Exception {
        for (int i = 0; i < 50; i++) {
            filter.doFilter(request("GET", "/login", "user@x.vn", IP), new MockHttpServletResponse(), chain);
            filter.doFilter(request("POST", "/register", "user@x.vn", IP), new MockHttpServletResponse(), chain);
        }

        assertThat(throttle.isBlocked("user@x.vn", IP)).isFalse();
        assertThat(throttle.tryAcquire("user@x.vn", IP)).as("nothing was counted before").isPresent();
    }
}

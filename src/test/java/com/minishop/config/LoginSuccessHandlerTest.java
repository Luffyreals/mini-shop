package com.minishop.config;

import com.minishop.support.MutableClock;
import com.minishop.user.CurrentUser;
import com.minishop.user.LoginThrottle;
import com.minishop.user.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AC-008-9: a successful login gives back what the gate counted for that attempt (the {@code Attempt} that
 * {@link LoginThrottleFilter} left in the request), with the authenticated name as the fallback when the filter did not
 * run. The redirect stays exactly what {@code formLogin().defaultSuccessUrl("/")} did before phase 8: the saved request
 * if there is one, otherwise "/".
 */
@ExtendWith(MockitoExtension.class)
class LoginSuccessHandlerTest {

    private static final String IP = "198.51.100.7";

    @Mock
    LoginThrottle mockThrottle;

    private final LoginThrottle realThrottle = new LoginThrottle(new MutableClock());

    private static Authentication authenticated(String email) {
        CurrentUser principal = new CurrentUser(7L, email, null, "Khách hàng mẫu", Role.USER);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("email", "USER@minishop.local");
        request.setRemoteAddr(IP);
        return request;
    }

    private LoginThrottle.Attempt acquire(String email, String ip) {
        return realThrottle.tryAcquire(email, ip).orElseThrow();
    }

    // ------------------------------------------------------------------ what is given back

    @Test
    void onSuccess_withTheAttemptOfTheFilter_recordsSuccessForThatAttemptOnly() throws Exception {
        LoginThrottle.Attempt attempt = acquire("user@minishop.local", IP);
        MockHttpServletRequest request = loginRequest();
        request.setAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE, attempt);

        new LoginSuccessHandler(mockThrottle).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                authenticated("user@minishop.local"));

        verify(mockThrottle).recordSuccess(attempt);
        verify(mockThrottle, never()).recordSuccess(anyString());
    }

    @Test
    void onSuccess_withTheAttemptOfTheFilter_clearsTheEmailAndRemovesOneIpEntry() throws Exception {
        for (int i = 0; i < 2; i++) {
            acquire("user@minishop.local", "203.0.113." + (i + 1)); // two earlier wrong passwords
        }
        for (int i = 0; i < 18; i++) {
            acquire("other" + i + "@x.vn", IP); // 18 earlier failures from the same IP
        }
        LoginThrottle.Attempt attempt = acquire("user@minishop.local", IP); // this login: the 19th entry of the IP
        MockHttpServletRequest request = loginRequest();
        request.setAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE, attempt);

        new LoginSuccessHandler(realThrottle).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                authenticated("user@minishop.local"));

        for (int i = 0; i < 4; i++) {
            assertThat(realThrottle.tryAcquire("user@minishop.local", "192.0.2." + (i + 1)))
                    .as("the email count restarted: attempt %d", i + 1).isPresent();
        }
        assertThat(realThrottle.isBlocked("user@minishop.local", null)).as("4 attempts: not blocked").isFalse();
        // the IP kept its 18 failures and lost only this login: 1 more is 19, 2 more is 20
        assertThat(realThrottle.tryAcquire("late1@x.vn", IP)).isPresent();
        assertThat(realThrottle.isBlocked("fresh@x.vn", IP)).isFalse();
        assertThat(realThrottle.tryAcquire("late2@x.vn", IP)).isPresent();
        assertThat(realThrottle.isBlocked("fresh@x.vn", IP)).isTrue();
    }

    @Test
    void onSuccess_withoutTheAttempt_fallsBackToTheAuthenticatedName() throws Exception {
        new LoginSuccessHandler(mockThrottle).onAuthenticationSuccess(loginRequest(), new MockHttpServletResponse(),
                authenticated("user@minishop.local"));

        verify(mockThrottle).recordSuccess("user@minishop.local");
        verify(mockThrottle, never()).recordSuccess(any(LoginThrottle.Attempt.class));
    }

    @Test
    void onSuccess_withPlainStringPrincipal_fallbackUsesAuthenticationName() throws Exception {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("name@x.vn", null, null);

        new LoginSuccessHandler(mockThrottle).onAuthenticationSuccess(loginRequest(), new MockHttpServletResponse(),
                authentication);

        verify(mockThrottle).recordSuccess("name@x.vn");
    }

    @Test
    void onSuccess_whenTheAttributeIsNotAnAttempt_fallsBackInsteadOfFailing() throws Exception {
        MockHttpServletRequest request = loginRequest();
        request.setAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE, "not an attempt");

        new LoginSuccessHandler(mockThrottle).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                authenticated("user@minishop.local"));

        verify(mockThrottle).recordSuccess("user@minishop.local");
        verify(mockThrottle, never()).recordSuccess(any(LoginThrottle.Attempt.class));
    }

    @Test
    void onSuccess_withoutTheAttempt_clearsTheEmailButLeavesTheIpCount() throws Exception {
        for (int i = 0; i < 3; i++) {
            acquire("user@minishop.local", "203.0.113." + (i + 1));
        }
        for (int i = 0; i < 19; i++) {
            acquire("other" + i + "@x.vn", IP);
        }

        new LoginSuccessHandler(realThrottle).onAuthenticationSuccess(loginRequest(), new MockHttpServletResponse(),
                authenticated("user@minishop.local"));

        for (int i = 0; i < 4; i++) {
            assertThat(realThrottle.tryAcquire("user@minishop.local", "192.0.2." + (i + 1))).isPresent();
        }
        assertThat(realThrottle.isBlocked("user@minishop.local", null)).isFalse();
        assertThat(realThrottle.tryAcquire("late@x.vn", IP)).as("the 20th entry of the IP").isPresent();
        assertThat(realThrottle.isBlocked("fresh@x.vn", IP)).as("the fallback does not touch the IP").isTrue();
    }

    // ------------------------------------------------------------------ redirect (unchanged by phase 8)

    @Test
    void onSuccess_withoutSavedRequest_redirectsToHome() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginSuccessHandler(mockThrottle).onAuthenticationSuccess(loginRequest(), response,
                authenticated("user@minishop.local"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void onSuccess_withSavedRequest_redirectsBackToIt() throws Exception {
        MockHttpServletRequest visit = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse visitResponse = new MockHttpServletResponse();
        new HttpSessionRequestCache().saveRequest(visit, visitResponse);
        MockHttpServletRequest login = loginRequest();
        login.setSession(visit.getSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginSuccessHandler(mockThrottle).onAuthenticationSuccess(login, response,
                authenticated("user@minishop.local"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(URI.create(response.getRedirectedUrl()).getPath()).isEqualTo("/orders");
        verify(mockThrottle).recordSuccess("user@minishop.local");
    }
}

package com.minishop.config;

import com.minishop.user.LoginThrottle;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * The gate in front of the password check (BR-008-3, AC-008-7). It looks only at {@code POST /login} and calls
 * {@link LoginThrottle#tryAcquire} once: that atomically refuses a blocked email or IP and otherwise counts the attempt
 * at once, so parallel requests cannot all get through before the first failure is recorded. When refused, the filter
 * hands a {@link LoginBlockedException} to the failure handler and stops, so
 * {@code UsernamePasswordAuthenticationFilter} never runs and the password is not checked, even when it is correct.
 * <p><b>Handing the attempt to the success handler:</b> the {@link LoginThrottle.Attempt} returned by the gate is stored
 * as the request attribute {@link #ATTEMPT_ATTRIBUTE}. {@link LoginSuccessHandler} reads it (same request, no forward
 * in between) and gives the provisional count back. The failure path needs nothing: the attempt is already counted.
 * <p><b>Position:</b> added before {@code UsernamePasswordAuthenticationFilter}, so it runs after
 * {@code CsrfFilter} (a request without a CSRF token is rejected first, and is not counted) and before the password check.
 * <p><b>Not a bean:</b> {@code SecurityConfig} creates it with {@code new}. A filter that is a Spring bean is also
 * registered by Boot as an ordinary servlet filter, a second time and outside Spring Security.
 * <p><b>Refused attempts are not counted:</b> they never reach the count, so a refused attempt never extends a block.
 * <p>The client IP is {@code request.getRemoteAddr()}: Tomcat's {@code RemoteIpValve} has already replaced it with the
 * real client address when (and only when) the connection comes from a trusted local proxy (QĐ5).
 */
final class LoginThrottleFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@link LoginThrottle.Attempt} of this {@code POST /login}. */
    static final String ATTEMPT_ATTRIBUTE = LoginThrottleFilter.class.getName() + ".ATTEMPT";

    private static final RequestMatcher LOGIN_SUBMIT = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/login");

    private final LoginThrottle throttle;
    private final AuthenticationFailureHandler blockedHandler;

    LoginThrottleFilter(LoginThrottle throttle, AuthenticationFailureHandler blockedHandler) {
        this.throttle = throttle;
        this.blockedHandler = blockedHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN_SUBMIT.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<LoginThrottle.Attempt> attempt = throttle.tryAcquire(request.getParameter("email"),
                request.getRemoteAddr());
        if (attempt.isEmpty()) {
            blockedHandler.onAuthenticationFailure(request, response, new LoginBlockedException());
            return;
        }
        request.setAttribute(ATTEMPT_ATTRIBUTE, attempt.get());
        chain.doFilter(request, response);
    }
}

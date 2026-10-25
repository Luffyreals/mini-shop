package com.minishop.config;

import com.minishop.user.LoginThrottle;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * Gives back what the throttle counted for this login (AC-008-9), then redirects exactly like
 * {@code formLogin().defaultSuccessUrl("/")} did: back to the saved request if there is one, otherwise to {@code /}.
 * <p>{@link LoginThrottleFilter} counted the attempt provisionally and left it in the request attribute
 * {@link LoginThrottleFilter#ATTEMPT_ATTRIBUTE}. On success this clears the email's failure count and removes this
 * attempt's entry from the IP count. A wrong password needs no handler: its count simply stays. If the attribute is
 * missing (the filter did not run), only the email's count is cleared, using the authenticated name.
 */
final class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final LoginThrottle throttle;

    LoginSuccessHandler(LoginThrottle throttle) {
        this.throttle = throttle;
        setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        if (request.getAttribute(LoginThrottleFilter.ATTEMPT_ATTRIBUTE) instanceof LoginThrottle.Attempt attempt) {
            throttle.recordSuccess(attempt);
        } else {
            throttle.recordSuccess(authentication.getName());
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}

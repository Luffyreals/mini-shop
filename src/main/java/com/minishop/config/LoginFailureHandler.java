package com.minishop.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

import java.io.IOException;

/**
 * Redirects to {@code /login?error} (or {@code /login?blocked} for a {@link LoginBlockedException}) and carries the typed email to the next GET /login as a flash attribute,
 * so the user does not retype it. Runs inside the security filter chain (outside Spring MVC), hence the direct
 * {@link FlashMapManager} call. It never reads the password and never exposes the exception message,
 * so the page cannot reveal whether an email exists (BR-003-5).
 */
final class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final int MAX_EMAIL_LENGTH = 255;
    private static final String BLOCKED_URL = "/login?blocked";

    private final FlashMapManager flashMapManager;

    LoginFailureHandler(FlashMapManager flashMapManager) {
        super("/login?error");
        this.flashMapManager = flashMapManager;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String email = request.getParameter("email");
        if (email != null) {
            email = email.strip();
            if (email.codePointCount(0, email.length()) > MAX_EMAIL_LENGTH) {
                // Cắt theo code point để không tách đôi một emoji (surrogate pair), như ProductFilter ở GĐ2.
                email = email.substring(0, email.offsetByCodePoints(0, MAX_EMAIL_LENGTH));
            }
            if (!email.isEmpty()) {
                FlashMap flashMap = new FlashMap();
                flashMap.put("email", email);
                flashMap.setTargetRequestPath("/login");
                flashMapManager.saveOutputFlashMap(flashMap, request, response);
            }
        }
        if (exception instanceof LoginBlockedException) {
            // The throttle refused the attempt before the password was checked: no exception is stored in the
            // session, and the page differs from /login?error only by this query parameter.
            getRedirectStrategy().sendRedirect(request, response, BLOCKED_URL);
            return;
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}

package com.minishop.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * Chooses the view for every error that reaches Spring Boot's {@code /error} endpoint (ADR-0011). Declaring this
 * bean replaces Boot's {@code DefaultErrorViewResolver}; {@code BasicErrorController} still negotiates the
 * content, so browsers get the pages below and other clients get Boot's plain JSON.
 *
 * <p>Status to page:
 * <ul>
 *   <li>404 {@link ErrorPage#NOT_FOUND}; 413 {@link ErrorPage#PAYLOAD_TOO_LARGE};</li>
 *   <li>403 the old {@code error/403} view, as a safety net (a CSRF failure goes through
 *       {@link AccessDeniedController} instead);</li>
 *   <li>500 and above, or an unexpected status below 400 {@link ErrorPage#SERVER_ERROR};</li>
 *   <li>any other 4xx (400, 401, 405, 415, 429...) {@link ErrorPage#BAD_REQUEST}.</li>
 * </ul>
 *
 * <p>Layout: the admin layout is used only when the original URI is {@code /admin} or under {@code /admin/}
 * <b>and</b> the viewer has {@code ROLE_ADMIN} (the sidebar prints the signed-in user's name, which an anonymous
 * viewer does not have). Everything else gets the storefront layout. The resolver never redirects, so the page is
 * shown at the URL that failed, with the real status.
 *
 * <p>The security context is available here because Boot registers the security filter chain for error
 * dispatches too. Request parameters are never read: for a 413 the body cannot be parsed.
 */
@Component
class ErrorPageResolver implements ErrorViewResolver, Ordered {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        if (status == HttpStatus.FORBIDDEN) {
            return new ModelAndView("error/403", HttpStatus.FORBIDDEN);
        }
        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        boolean adminLayout = isAdminPath(uri) && currentUserIsAdmin();
        return ErrorPage.forStatus(status.value()).view(adminLayout, status);
    }

    /** True for {@code /admin} and {@code /admin/...}; {@code /administrator} is not the admin area. */
    static boolean isAdminPath(String uri) {
        return uri != null && (uri.equals("/admin") || uri.startsWith("/admin/"));
    }

    private static boolean currentUserIsAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ADMIN_ROLE.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

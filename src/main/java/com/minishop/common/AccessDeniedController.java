package com.minishop.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * Target of {@code accessDeniedPage("/access-denied")}. Security forwards here keeping the original HTTP method,
 * so it accepts every method.
 *
 * <p>403 has two meanings and this is where they are told apart (ADR-0011): the handler stores the exception
 * that caused the denial in {@link WebAttributes#ACCESS_DENIED_403} before forwarding. A
 * {@link CsrfException} (token missing, wrong or expired) is a stale form, shown as "session expired" in the
 * storefront layout (the viewer may no longer be an admin, or logged in at all); anything else is a real lack of
 * permission and keeps the old page. Neither page shows the token or the detailed cause.
 */
@Controller
class AccessDeniedController {

    private static final Logger log = LoggerFactory.getLogger(AccessDeniedController.class);

    @RequestMapping("/access-denied")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ModelAndView accessDenied(HttpServletRequest request) {
        Object cause = request.getAttribute(WebAttributes.ACCESS_DENIED_403);
        if (cause instanceof CsrfException) {
            log.debug("Access denied: CSRF failure ({})", cause.getClass().getSimpleName());
            return ErrorPage.SESSION_EXPIRED.view(false, HttpStatus.FORBIDDEN);
        }
        return new ModelAndView("error/403", HttpStatus.FORBIDDEN);
    }
}

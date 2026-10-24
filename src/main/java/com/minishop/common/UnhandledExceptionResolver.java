package com.minishop.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;

/**
 * Logs every unexpected exception exactly once and turns it into a 500 (ADR-0011).
 *
 * <p>Why a resolver at the end of the chain and not a {@code @ControllerAdvice} with
 * {@code @ExceptionHandler(Exception.class)}: {@code DispatcherServlet} asks the resolvers in order, first
 * {@code @ExceptionHandler} methods, then {@code @ResponseStatus}, then {@code DefaultHandlerExceptionResolver}
 * (405, 400, a missing static resource 404, an upload over the limit 413...). A catch-all advice runs in the
 * first step and would swallow all of those, so it would need a list of exceptions to rethrow, which is easy to
 * leave incomplete. An exception that still reaches this resolver ({@link Ordered#LOWEST_PRECEDENCE}) is by
 * definition one nobody planned for.
 *
 * <p>Because the exception counts as handled here, it never reaches Tomcat, so Tomcat does not write a second
 * {@code Servlet.service() ... threw exception} line. The log line holds the method and the path without the
 * query string, plus the stack trace; never parameters, body or principal, which may be personal data.
 *
 * <p>Three cases are not errors of ours: a client that disconnected and a malformed parameter that Tomcat
 * reports lazily (for example {@code ?q=%zz}) are logged at DEBUG only, and security exceptions are left to
 * Spring Security's filter. The rendered page comes from {@link ErrorPageResolver} after the error dispatch.
 *
 * <p>Known limit: an exception thrown while rendering a template, or inside a filter, does not pass through any
 * resolver; Tomcat logs it once.
 */
@Component
class UnhandledExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UnhandledExceptionResolver.class);

    private static final int MAX_CAUSE_DEPTH = 20;

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (isSecurityException(ex)) {
            return null; // not ours: ExceptionTranslationFilter turns these into 403 or the login redirect
        }
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("Client disconnected: {} {}", request.getMethod(), request.getRequestURI());
            return new ModelAndView();
        }
        InvalidParameterException invalid = findInvalidParameter(ex);
        if (invalid != null) {
            log.debug("Invalid request parameters: {} {} -> {}", request.getMethod(), request.getRequestURI(),
                    invalid.getErrorCode());
            sendError(response, invalid.getErrorCode());
            return new ModelAndView();
        }
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), ex);
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return new ModelAndView();
    }

    private static boolean isSecurityException(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AccessDeniedException || current instanceof AuthenticationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static InvalidParameterException findInvalidParameter(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof InvalidParameterException invalid) {
                return invalid;
            }
            current = current.getCause();
        }
        return null;
    }

    private static void sendError(HttpServletResponse response, int status) {
        if (response.isCommitted()) {
            return; // too late to change the status; the exception is already logged or deliberately ignored
        }
        try {
            response.sendError(status);
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not send error status {}: {}", status, e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

package com.minishop.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resolver that logs every unexpected exception once and turns it into a 500 (AC-007-5; BR-007-5; QĐ3).
 * Plain unit test. The log is read through a Logback {@link ListAppender} on the resolver's own logger, so the
 * level and the message are checked as data and no log format matters; the real-server counterpart (exactly one
 * line, nothing from Tomcat) is {@code ErrorLoggingTest}.
 */
class UnhandledExceptionResolverTest {

    private final UnhandledExceptionResolver resolver = new UnhandledExceptionResolver();
    private final Logger logger = (Logger) LoggerFactory.getLogger(UnhandledExceptionResolver.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(originalLevel);
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString(query);
        if (query != null) {
            request.addParameter("q", "0987654321");
        }
        return request;
    }

    private List<ILoggingEvent> logged(Level level) {
        return events.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    void resolveException_forUnexpectedException_logsOneErrorAndSendsFiveHundred() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException("boom: select * from users");

        ModelAndView mav = resolver.resolveException(request("GET", "/x", "q=0987654321"), response, null, failure);

        assertThat(mav).isNotNull();
        assertThat(mav.isEmpty()).as("empty ModelAndView: handled, the error page is drawn after the error dispatch")
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(logged(Level.ERROR)).hasSize(1);
        ILoggingEvent event = logged(Level.ERROR).get(0);
        assertThat(event.getFormattedMessage()).isEqualTo("Unhandled exception: GET /x");
        assertThat(event.getFormattedMessage()).doesNotContain("?").doesNotContain("0987654321");
        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom: select * from users");
    }

    @Test
    void resolveException_forPost_logsTheMethodAndThePathOnly() {
        MockHttpServletRequest request = request("POST", "/checkout", null);
        request.addParameter("phone", "0987654321");
        request.addParameter("shippingAddress", "12 Đường Bí Mật");

        resolver.resolveException(request, new MockHttpServletResponse(), null, new RuntimeException("x"));

        assertThat(logged(Level.ERROR)).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).isEqualTo("Unhandled exception: POST /checkout"));
        assertThat(events.list).allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("0987654321").doesNotContain("Bí Mật"));
    }

    @Test
    void resolveException_forInvalidParameterWrappedInAnotherException_sendsItsCodeAndDoesNotLogAnError() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Exception wrapped = new IllegalArgumentException("binding failed",
                new InvalidParameterException("bad encoding", 400));

        ModelAndView mav = resolver.resolveException(request("GET", "/products", "q=%zz"), response, null, wrapped);

        assertThat(mav).isNotNull();
        assertThat(mav.isEmpty()).isTrue();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(logged(Level.ERROR)).isEmpty();
        assertThat(logged(Level.WARN)).isEmpty();
        assertThat(logged(Level.DEBUG)).hasSize(1);
    }

    @Test
    void resolveException_forInvalidParameterWithItsOwnCode_sendsThatCode() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.resolveException(request("POST", "/admin/products", null), response, null,
                new InvalidParameterException("too large", 413));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(logged(Level.ERROR)).isEmpty();
    }

    @Test
    void resolveException_forDisconnectedClient_logsAtDebugAndSendsNothing() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView mav = resolver.resolveException(request("GET", "/x", null), response, null,
                new IOException("Broken pipe"));

        assertThat(mav).isNotNull();
        assertThat(mav.isEmpty()).isTrue();
        assertThat(response.getStatus()).as("no sendError").isEqualTo(200);
        assertThat(response.isCommitted()).as("no sendError").isFalse();
        assertThat(logged(Level.ERROR)).isEmpty();
        assertThat(logged(Level.DEBUG)).hasSize(1);
    }

    @Test
    void resolveException_whenResponseIsAlreadyCommitted_stillLogsButDoesNotSendError() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ModelAndView mav = resolver.resolveException(request("GET", "/x", null), response, null,
                new IllegalStateException("late failure"));

        assertThat(mav).isNotNull();
        assertThat(response.getStatus()).as("status unchanged").isEqualTo(200);
        assertThat(logged(Level.ERROR)).hasSize(1);
        assertThat(logged(Level.DEBUG)).as("sendError was not even tried (it would log a DEBUG line on failure)")
                .isEmpty();
    }

    /**
     * Deviation from the plan, by backend: Spring Security's {@code ExceptionTranslationFilter} owns these (403 or
     * the login redirect). Turning them into 500 would break the access rules, so the resolver must leave them.
     */
    @Test
    void resolveException_forSecurityExceptions_returnsNullAndLeavesThemToSpringSecurity() {
        List<Exception> securityExceptions = List.of(
                new AccessDeniedException("denied"),
                new BadCredentialsException("bad"),
                new InsufficientAuthenticationException("full authentication required"));

        for (Exception ex : securityExceptions) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ModelAndView mav = resolver.resolveException(request("GET", "/admin", null), response, null, ex);

            assertThat(mav).as(ex.getClass().getSimpleName()).isNull();
            assertThat(response.getStatus()).as(ex.getClass().getSimpleName()).isEqualTo(200);
        }
        assertThat(events.list).as("nothing is logged for them").isEmpty();
    }

    @Test
    void resolveException_forSecurityExceptionWrappedInAnotherException_returnsNullAndLogsNothing() {
        List<Exception> wrapped = List.of(
                new RuntimeException(new AccessDeniedException("x")),
                new RuntimeException(new BadCredentialsException("bad")),
                new IllegalStateException("outer", new RuntimeException("middle", new AccessDeniedException("deep"))));

        for (Exception ex : wrapped) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ModelAndView mav = resolver.resolveException(request("GET", "/admin", null), response, null, ex);

            assertThat(mav).as(ex.toString()).isNull();
            assertThat(response.getStatus()).as("no sendError: " + ex).isEqualTo(200);
            assertThat(response.getErrorMessage()).as("no sendError: " + ex).isNull();
        }
        assertThat(events.list).as("nothing is logged for them").isEmpty();
    }

    @Test
    void resolveException_forSecurityExceptionAtTheEndOfADeepButBoundedChain_returnsNull() {
        Throwable chain = new AccessDeniedException("x");
        for (int i = 0; i < 15; i++) {
            chain = new RuntimeException("wrapper " + i, chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView mav = resolver.resolveException(request("GET", "/admin", null), response, null,
                (Exception) chain);

        assertThat(mav).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(events.list).isEmpty();
    }

    @Test
    void getOrder_isLowestPrecedenceSoItRunsAfterEveryOtherResolver() {
        assertThat(resolver.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}

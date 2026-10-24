package com.minishop.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A real Tomcat on a random port, the {@code h2} profile (no Docker needed) and the test-only error endpoints
 * ({@link ErrorTestEndpoints}). Needed because MockMvc does not do an error dispatch, so it can never show the
 * content of an error page, and does not run Tomcat's own limits (phase 7 plan, QĐ12).
 *
 * <p>Every class annotated with this uses the exact same configuration, so they all share one Spring context
 * (and one in-memory database). Tests therefore must not leave data behind that another test could see: clean up
 * what you create. Never use port 8080 here: the port is random on purpose.
 *
 * <p>Use {@code @LocalServerPort int port} and {@link LiveClient} in the test class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:live-server;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles("h2")
@Import(ErrorTestEndpoints.class)
public @interface LiveServerTest {
}

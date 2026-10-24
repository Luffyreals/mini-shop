package com.minishop.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Endpoints that fail on purpose, to look at the 500 page and the error log on a real server. They exist only in
 * contexts that import this class (see {@link LiveServerTest}): a class nested in a {@code @TestConfiguration} is
 * excluded from component scanning by Spring Boot's test type filter, so no other {@code @SpringBootTest} has
 * {@code /test/boom}.
 *
 * <p>The exception message looks like what a real failure could leak (SQL text and a file path), to prove that
 * none of it reaches the page.
 */
@TestConfiguration
public class ErrorTestEndpoints {

    /** What the exception message holds; tests assert none of these fragments appears in a response body. */
    public static final String SECRET_MESSAGE =
            "Boom for test: select 1 from secret_table; C:\secret\boom.txt";

    @Controller
    public static class BoomController {

        @RequestMapping(path = "/test/boom", method = {RequestMethod.GET, RequestMethod.POST})
        public String boom() {
            throw new IllegalStateException(SECRET_MESSAGE);
        }

        @RequestMapping(path = "/admin/test/boom", method = {RequestMethod.GET, RequestMethod.POST})
        public String adminBoom() {
            throw new IllegalStateException(SECRET_MESSAGE);
        }
    }
}

package com.minishop.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * JUnit extension for {@code @SpringBootTest} classes that use {@link TestAdminAccess}: after every test (also when
 * {@code @BeforeEach} or the test failed) it puts the shared context back to the real {@code AppSession}, so a test
 * that forgets {@code access.reset()} cannot leak an admin into the next class that shares the cached context.
 *
 * <p>Use it as {@code @ExtendWith(ResetAdminAccess.class)} on the class. The explicit {@code access.reset()} in a
 * test's own {@code @AfterEach} is still fine; this only makes forgetting it harmless.
 */
public final class ResetAdminAccess implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context).getBean(TestAdminAccess.class).reset();
    }
}

package com.minishop.support;

import com.minishop.common.AdminAccess;
import com.minishop.user.AppSession;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link AdminAccess} that the admin services get in {@code @SpringBootTest} contexts (D5, QĐ15).
 *
 * <p><b>Which contexts have it.</b> Only the {@code @SpringBootTest} contexts: component scanning finds it there
 * because Spring Boot's own test type filter drops only test classes, not helpers under {@code test-classes}, so
 * every such context has it without a different context cache key. Contexts built by {@link StandaloneApp}
 * (the UI smoke tests through {@link TestShellApp}, and the startup tests) <b>never</b> have it: that builder
 * excludes everything under {@code test-classes} from scanning. There the services get the real {@link AppSession}
 * (the smoke tests log in as a real admin), and a double needed there must be listed through
 * {@code TestShellApp(Class...)}.
 *
 * <p><b>Default behaviour is production behaviour:</b> it delegates to the real {@link AppSession} bean, so a test
 * that does not care gets a guest and every admin call is refused. A test that needs an admin calls
 * {@link #actAsAdmin(long)} in {@code @BeforeEach}. The context is shared, so a leftover override would leak into
 * the next test class: put {@code @ExtendWith(}{@link ResetAdminAccess}{@code .class)} on the class, which calls
 * {@link #reset()} after every test even when the class forgot its own. Nobody signs in through
 * {@code AppSession}, so no {@code SessionListener} runs and the shared session stays a guest.
 *
 * <p>The override is a {@code volatile} field, not a thread local, because the race tests call the services from
 * executor threads.
 */
@Component
@Primary
public class TestAdminAccess implements AdminAccess {

    private final AppSession session;
    private volatile AdminAccess override;

    TestAdminAccess(AppSession session) {
        this.session = session;
    }

    @Override
    public long requireAdmin() {
        AdminAccess chosen = override;
        return (chosen != null ? chosen : session).requireAdmin();
    }

    /** Every call from now on is made by the admin with this id (a row of {@code users} for the log line only). */
    public void actAsAdmin(long adminId) {
        override = () -> adminId;
    }

    /** Every call from now on asks this access instead of the shared session (for example a private session). */
    public void actThrough(AdminAccess other) {
        override = other;
    }

    /** Back to the real {@link AppSession} of the context (a guest). */
    public void reset() {
        override = null;
    }
}

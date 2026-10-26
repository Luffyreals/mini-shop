package com.minishop.support;

import com.minishop.desktop.MiniShopApp;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The real desktop app as the launcher would build it, for the {@code @Tag("ui")} smoke tests: {@link MiniShopApp}
 * on the real JavaFX toolkit, an in-memory H2 database (test {@code config/application.yml}), and the test classes
 * kept out of component scanning ({@link StandaloneApp#builder()}).
 *
 * <p>The demo passwords are passed explicitly as command-line arguments, so a real {@code SEED_*} variable of the
 * developer can never change what a test logs in with. Start it with
 * {@code FxToolkit.setupApplication(TestShellApp::new)} and stop it with {@code FxToolkit.cleanupApplication(app)},
 * which closes the Spring context (the H2 database and the background executor).
 */
public final class TestShellApp extends MiniShopApp {

    private final AtomicReference<ConfigurableApplicationContext> builtContext = new AtomicReference<>();

    @Override
    protected ConfigurableApplicationContext createContext(String[] args) {
        ConfigurableApplicationContext context = StandaloneApp.builder()
                .headless(false)
                .run("--spring.main.banner-mode=off",
                        "--app.seed.admin-password=" + StandaloneApp.DEV_ADMIN_PASSWORD,
                        "--app.seed.user-password=" + StandaloneApp.DEV_USER_PASSWORD);
        builtContext.set(context);
        return context;
    }

    /** The Spring context built in {@code init()}; {@code null} until the application has been started. */
    public ConfigurableApplicationContext context() {
        return builtContext.get();
    }
}

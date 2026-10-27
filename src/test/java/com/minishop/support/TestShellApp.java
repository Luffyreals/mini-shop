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
    private final Class<?>[] extraSources;

    public TestShellApp() {
        this(new Class<?>[0]);
    }

    /**
     * Starts the app with extra annotated classes registered next to it (test doubles that replace a real bean, for
     * example a {@code @Primary} stub of the OS file chooser). This context does not scan {@code test-classes} at
     * all (see {@link StandaloneApp#builder()}), so a double has to be listed here. That is also why
     * {@link TestAdminAccess}, which {@code @SpringBootTest} contexts pick up by scanning, is absent here: the admin
     * services use the real {@code AppSession}.
     */
    public TestShellApp(Class<?>... extraSources) {
        this.extraSources = extraSources.clone();
    }

    @Override
    protected ConfigurableApplicationContext createContext(String[] args) {
        ConfigurableApplicationContext context = StandaloneApp.builder()
                .sources(extraSources)
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

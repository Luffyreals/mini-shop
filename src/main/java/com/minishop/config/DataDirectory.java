package com.minishop.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chooses the data directory ({@code app.data-dir}, fed by {@code DATA_DIR}) and makes it safe to put into the H2 file
 * URL (ADR-0013, BR-009-6).
 *
 * <p>Priority: a non-blank {@code DATA_DIR}, then {@code %LOCALAPPDATA%\MiniShop} when the app runs packaged
 * ({@code -Dapp.packaged=true}, set by the {@code jpackage} launcher; falls back to
 * {@code <user.home>\AppData\Local\MiniShop} when the variable is missing), then {@code ./data}.
 *
 * <p>H2 refuses a path that is "implicitly relative to the current working directory": {@code data2/minishop} fails at
 * startup, while {@code ./data2/minishop}, {@code /d/x/minishop}, {@code C:\x\minishop} and {@code ~/x/minishop} work.
 * So a plain relative value gets {@code ./} in front; anything H2 already accepts is left as it is. Both the datasource
 * URL and the upload directory read the result.
 *
 * <p>A packaged app has no console, so when neither {@code logging.file.name} nor {@code logging.file.path} is set it
 * also logs to {@code <data dir>/logs/mini-shop.log}. Running from source still logs to the console only.
 *
 * <p>An {@link EnvironmentPostProcessor}, because the value must be fixed before the DataSource exists and before the
 * logging system starts. Registered in {@code META-INF/spring.factories}.
 */
public final class DataDirectory implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "app.data-dir";
    /** System property set by the packaged launcher; it only changes the default data directory and adds the log file. */
    static final String PACKAGED_PROPERTY = "app.packaged";
    static final String DEFAULT = "./data";

    private static final String LOG_FILE_NAME_PROPERTY = "logging.file.name";
    private static final String LOG_FILE_PATH_PROPERTY = "logging.file.path";
    private static final String LOG_FILE_NAME = "${" + PROPERTY + "}/logs/mini-shop.log";
    private static final String APP_FOLDER = "MiniShop";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Binder binder = Binder.get(environment);
        String configured = binder.bind(PROPERTY, String.class).orElse(null);
        boolean packaged = binder.bind(PACKAGED_PROPERTY, Boolean.class).orElse(false);
        String resolved = resolve(configured, packaged, System.getenv(), System.getProperty("user.home"));

        Map<String, Object> overrides = new LinkedHashMap<>();
        if (!resolved.equals(configured)) {
            overrides.put(PROPERTY, resolved);
        }
        if (packaged && !environment.containsProperty(LOG_FILE_NAME_PROPERTY)
                && !environment.containsProperty(LOG_FILE_PATH_PROPERTY)) {
            overrides.put(LOG_FILE_NAME_PROPERTY, LOG_FILE_NAME);
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("normalizedDataDirectory", overrides));
        }
    }

    /**
     * The data directory as H2 accepts it. A configured value wins and is normalized; a blank one becomes
     * {@code %LOCALAPPDATA%\MiniShop} when packaged (or {@code userHome\AppData\Local\MiniShop} without that
     * variable), otherwise {@code ./data}. Pure: the caller passes the environment and the home directory.
     */
    static String resolve(String configured, boolean packaged, Map<String, String> env, String userHome) {
        if (StringUtils.hasText(configured)) {
            return normalize(configured);
        }
        if (!packaged) {
            return DEFAULT;
        }
        String localAppData = env == null ? null : env.get("LOCALAPPDATA");
        String base = StringUtils.hasText(localAppData)
                ? localAppData.strip()
                : Path.of(userHome, "AppData", "Local").toString();
        return Path.of(base, APP_FOLDER).toString();
    }

    /** The value as H2 accepts it: explicit relative ({@code ./...}) or absolute, without a trailing separator. */
    static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT;
        }
        String path = value.strip();
        while (path.length() > 1 && (path.endsWith("/") || path.endsWith("\\"))) {
            path = path.substring(0, path.length() - 1);
        }
        if (isAcceptedByH2(path)) {
            return path;
        }
        return "./" + path;
    }

    private static boolean isAcceptedByH2(String path) {
        return path.equals(".") || path.equals("..")
                || path.startsWith("./") || path.startsWith(".\\")
                || path.startsWith("../") || path.startsWith("..\\")
                || path.startsWith("/") || path.startsWith("\\") || path.startsWith("~")
                || (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':');
    }

    /** After the config files are read, before any bean. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

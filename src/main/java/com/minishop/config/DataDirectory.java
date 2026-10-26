package com.minishop.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Makes {@code app.data-dir} (fed by {@code DATA_DIR}) safe to put into the H2 file URL (ADR-0013).
 *
 * <p>H2 refuses a path that is "implicitly relative to the current working directory": {@code data2/minishop} fails at
 * startup, while {@code ./data2/minishop}, {@code /d/x/minishop}, {@code C:\x\minishop} and {@code ~/x/minishop} work.
 * So a plain relative value gets {@code ./} in front; anything H2 already accepts is left as it is. A blank value
 * falls back to the default {@code ./data}. Both the datasource URL and the upload directory read the result.
 *
 * <p>An {@link EnvironmentPostProcessor}, because the value must be fixed before the
 * DataSource exists. Registered in {@code META-INF/spring.factories}.
 */
public final class DataDirectory implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "app.data-dir";
    static final String DEFAULT = "./data";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = Binder.get(environment).bind(PROPERTY, String.class).orElse(null);
        String normalized = normalize(configured);
        if (!normalized.equals(configured)) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("normalizedDataDirectory", Map.of(PROPERTY, normalized)));
        }
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

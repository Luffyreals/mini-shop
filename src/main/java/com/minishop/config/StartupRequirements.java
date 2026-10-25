package com.minishop.config;

import com.minishop.user.PasswordPolicy;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start when the {@code public} profile is active without safe demo passwords (see ADR-0012).
 *
 * <p>This is an {@link EnvironmentPostProcessor} on purpose: it runs after Boot has read the config files
 * and knows the active profiles, but before the DataSource, Flyway and every bean. A configuration mistake
 * therefore shows up within milliseconds and never touches the database.
 *
 * <p>It is not {@code @Validated} on {@code SeedProperties} because Boot's failure report prints the
 * rejected value, which here is a password. Messages name variables only, and no password is kept in a
 * field or logged. It checks the <em>bound</em> properties ({@code app.seed.*-password}) through a {@link Binder},
 * so an override by any other name or source cannot bypass the check, and messages still name the
 * {@code SEED_*} variables.
 *
 * <p>Registered in {@code META-INF/spring.factories}; it cannot be a {@code @Component} because it runs
 * before any context exists.
 */
public final class StartupRequirements implements EnvironmentPostProcessor, Ordered {

    static final Set<String> DEV_DEFAULT_PASSWORDS = Set.of("Admin@123", "User@123");
    static final int PUBLIC_MIN_PASSWORD_LENGTH = 12;

    private static final String ADMIN_PASSWORD_VARIABLE = "SEED_ADMIN_PASSWORD";
    private static final String USER_PASSWORD_VARIABLE = "SEED_USER_PASSWORD";

    // The properties Boot binds (what the variables above feed in application-public.yml).
    private static final String ADMIN_PASSWORD_PROPERTY = "app.seed.admin-password";
    private static final String USER_PASSWORD_PROPERTY = "app.seed.user-password";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("public")) {
            checkPublic(environment);
        }
    }

    private static void checkPublic(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        String admin = read(binder, ADMIN_PASSWORD_PROPERTY, ADMIN_PASSWORD_VARIABLE);
        String user = read(binder, USER_PASSWORD_PROPERTY, USER_PASSWORD_VARIABLE);
        List<String> missing = new ArrayList<>();
        if (admin == null) {
            missing.add(ADMIN_PASSWORD_VARIABLE);
        }
        if (user == null) {
            missing.add(USER_PASSWORD_VARIABLE);
        }
        if (!missing.isEmpty()) {
            throw new StartupRequirementException(
                    "Public mode requires environment variables: " + String.join(", ", missing),
                    "Set both demo passwords as described in docs/deploy-cloudflare.md.");
        }
        List<String> weak = new ArrayList<>();
        if (!isStrong(admin)) {
            weak.add(ADMIN_PASSWORD_VARIABLE);
        }
        if (!isStrong(user)) {
            weak.add(USER_PASSWORD_VARIABLE);
        }
        if (!weak.isEmpty()) {
            throw new StartupRequirementException(
                    "Public mode requires non-default demo passwords: " + String.join(", ", weak),
                    "Use at least 12 characters with letters and digits, different from the dev defaults.");
        }
        if (admin.equals(user)) {
            throw new StartupRequirementException(
                    "Public mode requires different passwords for SEED_ADMIN_PASSWORD and SEED_USER_PASSWORD",
                    "The USER password is shared with testers; the ADMIN password must stay private.");
        }
    }

    /**
     * Reads the value Boot will bind for {@code property}, or {@code null} when it is not usable ("missing").
     * <p>Why a {@link Binder} and not {@code environment.getProperty}: the binder is what binds
     * {@code SeedProperties}, so the value checked here is the value used later, whichever way it was
     * set (the variable, {@code APP_SEED_ADMIN_PASSWORD}, {@code -Dapp.seed.admin-password}, a {@code --} argument).
     * And {@code getProperty} resolves nested placeholders strictly: a secret that contains {@code ${x}} would make it
     * throw an {@code IllegalArgumentException} whose message contains the whole secret, which Boot then prints. The
     * binder leaves such text as it is. A failure to read is reported by naming the variable only, without the cause.
     * <p>A placeholder with no value ({@code ${SEED_ADMIN_PASSWORD}} in {@code application-public.yml} when the
     * variable is unset) is left as the literal text by the binder, so a value that is exactly one placeholder counts
     * as missing.
     */
    private static String read(Binder binder, String property, String variable) {
        String value;
        try {
            value = binder.bind(property, String.class).orElse(null);
        } catch (RuntimeException e) {
            // Deliberately without the cause: its message can contain the value.
            throw new StartupRequirementException("Cannot read the value of " + variable,
                    "Check that " + variable + " is a plain text value.");
        }
        if (!StringUtils.hasText(value) || isSinglePlaceholder(value)) {
            return null;
        }
        return value;
    }

    private static boolean isSinglePlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}")
                && value.indexOf("${", 2) < 0 && value.indexOf('}') == value.length() - 1;
    }

    private static boolean isStrong(String password) {
        return password.codePointCount(0, password.length()) >= PUBLIC_MIN_PASSWORD_LENGTH
                && !DEV_DEFAULT_PASSWORDS.contains(password)
                && PasswordPolicy.isValid(password);
    }

    /** After {@code ConfigDataEnvironmentPostProcessor}, so the profiles from config files are known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

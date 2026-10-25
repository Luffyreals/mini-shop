package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-008-1, 2, 15 and BR-008-1, 2: the checks that stop the app before any bean exists. Runs against a
 * {@link MockEnvironment}, so no Spring context and no real environment variable is involved. The checks read the
 * <em>bound</em> properties ({@code app.seed.*-password}, {@code spring.datasource.*}) through a {@code Binder}, so
 * this test sets those, not the bare {@code SEED_*} / {@code DB_*} names; the last section maps the variables onto them
 * the way the {@code application-public.yml} / {@code application-local.yml} files do.
 * Messages are pinned word for word; none may ever contain a value (a password or a database URL).
 */
class StartupRequirementsTest {

    private static final String GOOD_ADMIN = "Admin-Verify-2026";
    private static final String GOOD_USER = "User-Verify-2026";
    private static final String GOOD_URL = "jdbc:postgresql://localhost:5432/minishop";

    private static final String H2_PUBLIC = "Profiles 'h2' and 'public' cannot be active together";
    private static final String H2_LOCAL = "Profiles 'h2' and 'local' cannot be active together";
    private static final String PUBLIC_MISSING = "Public mode requires environment variables: ";
    private static final String PUBLIC_WEAK = "Public mode requires non-default demo passwords: ";
    private static final String PUBLIC_SAME =
            "Public mode requires different passwords for SEED_ADMIN_PASSWORD and SEED_USER_PASSWORD";
    private static final String LOCAL_MISSING = "Profile 'local' requires environment variables: ";
    private static final String LOCAL_NOT_POSTGRES = "Profile 'local' requires DB_URL to start with jdbc:postgresql:";

    private static final String LOCAL_ACTION =
            "Set them as described in README section 'Chạy với PostgreSQL cài trên máy (không Docker)'.";
    private static final String PUBLIC_MISSING_ACTION =
            "Set both demo passwords as described in README section 'Chia sẻ app đang chạy trên máy'.";
    private static final String PUBLIC_WEAK_ACTION =
            "Use at least 12 characters with letters and digits, different from the dev defaults.";

    private final StartupRequirements requirements = new StartupRequirements();

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static MockEnvironment publicEnv(String admin, String user) {
        MockEnvironment environment = env("public");
        if (admin != null) {
            environment.setProperty("app.seed.admin-password", admin);
        }
        if (user != null) {
            environment.setProperty("app.seed.user-password", user);
        }
        return environment;
    }

    private static MockEnvironment localEnv(String url, String username, String password) {
        MockEnvironment environment = env("local");
        if (url != null) {
            environment.setProperty("spring.datasource.url", url);
        }
        if (username != null) {
            environment.setProperty("spring.datasource.username", username);
        }
        if (password != null) {
            environment.setProperty("spring.datasource.password", password);
        }
        return environment;
    }

    private StartupRequirementException failure(MockEnvironment environment) {
        assertThatThrownBy(() -> requirements.postProcessEnvironment(environment, null))
                .isInstanceOf(StartupRequirementException.class);
        try {
            requirements.postProcessEnvironment(environment, null);
        } catch (StartupRequirementException e) {
            return e;
        }
        throw new AssertionError("unreachable");
    }

    private void assertOk(MockEnvironment environment) {
        assertThatCode(() -> requirements.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ accepted configurations

    @Test
    void noProfile_isAccepted_evenWithWeakPasswordsAndNoDatabaseVariables() {
        MockEnvironment environment = env();
        environment.setProperty("app.seed.admin-password", "Admin@123");
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:x");

        assertOk(environment);
    }

    @Test
    void h2Profile_isAccepted() {
        assertOk(env("h2"));
    }

    @Test
    void validLocalProfile_isAccepted() {
        assertOk(localEnv(GOOD_URL, "minishop", "db-secret-1"));
    }

    @Test
    void validPublicProfile_isAccepted() {
        assertOk(publicEnv(GOOD_ADMIN, GOOD_USER));
    }

    @Test
    void validLocalAndPublicProfiles_areAccepted() {
        MockEnvironment environment = env("local", "public");
        environment.setProperty("spring.datasource.url", GOOD_URL);
        environment.setProperty("spring.datasource.username", "minishop");
        environment.setProperty("spring.datasource.password", "db-secret-1");
        environment.setProperty("app.seed.admin-password", GOOD_ADMIN);
        environment.setProperty("app.seed.user-password", GOOD_USER);

        assertOk(environment);
    }

    @Test
    void order_isLowestPrecedence_soProfilesFromConfigFilesAreKnown() {
        assertThat(requirements.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    // ------------------------------------------------------------------ profile combinations

    @Test
    void h2WithPublic_isRefused() {
        StartupRequirementException e = failure(env("h2", "public"));

        assertThat(e.getMessage()).isEqualTo(H2_PUBLIC);
        assertThat(e.action()).isEqualTo("Run public mode with the default (Docker) database or with 'local,public'.");
    }

    @Test
    void h2WithLocal_isRefused() {
        StartupRequirementException e = failure(env("h2", "local"));

        assertThat(e.getMessage()).isEqualTo(H2_LOCAL);
        assertThat(e.action()).isEqualTo(
                "Choose one database: 'h2' (in memory) or 'local' (PostgreSQL installed on this machine).");
    }

    @Test
    void h2WithPublic_isRefusedBeforeAnythingElseIsChecked() {
        // no password variables at all, and 'local' without DB_*: the first applicable rule still wins
        assertThat(failure(env("h2", "public")).getMessage()).isEqualTo(H2_PUBLIC);
        assertThat(failure(env("h2", "local", "public")).getMessage()).isEqualTo(H2_PUBLIC);
    }

    @Test
    void localVariablesAreCheckedBeforePublicOnes() {
        MockEnvironment environment = env("local", "public"); // both groups of variables missing

        assertThat(failure(environment).getMessage()).startsWith(LOCAL_MISSING);
    }

    @Test
    void publicIsCheckedWhenLocalIsFine() {
        MockEnvironment environment = env("local", "public");
        environment.setProperty("spring.datasource.url", GOOD_URL);
        environment.setProperty("spring.datasource.username", "minishop");
        environment.setProperty("spring.datasource.password", "db-secret-1");

        assertThat(failure(environment).getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
    }

    // ------------------------------------------------------------------ local profile (AC-008-15)

    @Test
    void local_withNoVariables_namesAllThree() {
        StartupRequirementException e = failure(localEnv(null, null, null));

        assertThat(e.getMessage()).isEqualTo(LOCAL_MISSING + "DB_URL, DB_USERNAME, DB_PASSWORD");
        assertThat(e.action()).isEqualTo(LOCAL_ACTION);
    }

    @Test
    void local_listsOnlyTheMissingVariables() {
        assertThat(failure(localEnv(GOOD_URL, "minishop", null)).getMessage()).isEqualTo(LOCAL_MISSING + "DB_PASSWORD");
        assertThat(failure(localEnv(GOOD_URL, null, "pw-1")).getMessage()).isEqualTo(LOCAL_MISSING + "DB_USERNAME");
        assertThat(failure(localEnv(null, "minishop", "pw-1")).getMessage()).isEqualTo(LOCAL_MISSING + "DB_URL");
        assertThat(failure(localEnv(GOOD_URL, null, null)).getMessage())
                .isEqualTo(LOCAL_MISSING + "DB_USERNAME, DB_PASSWORD");
        assertThat(failure(localEnv(null, null, "pw-1")).getMessage()).isEqualTo(LOCAL_MISSING + "DB_URL, DB_USERNAME");
    }

    @Test
    void local_emptyOrWhitespaceVariable_countsAsMissing() {
        assertThat(failure(localEnv("", "minishop", "pw-1")).getMessage()).isEqualTo(LOCAL_MISSING + "DB_URL");
        assertThat(failure(localEnv(GOOD_URL, "   ", "pw-1")).getMessage()).isEqualTo(LOCAL_MISSING + "DB_USERNAME");
        assertThat(failure(localEnv(GOOD_URL, "minishop", "\t ")).getMessage()).isEqualTo(LOCAL_MISSING + "DB_PASSWORD");
    }

    @Test
    void local_urlOfAnotherDatabase_isRefused() {
        for (String url : new String[]{"jdbc:h2:mem:x", "postgresql://localhost/minishop",
                "jdbc:mysql://localhost/minishop", "http://localhost", "JDBC:POSTGRESQL://localhost/x",
                " jdbc:postgresql://localhost/x"}) {
            StartupRequirementException e = failure(localEnv(url, "minishop", "pw-1"));

            assertThat(e.getMessage()).as(url).isEqualTo(LOCAL_NOT_POSTGRES);
            assertThat(e.action()).isEqualTo(LOCAL_ACTION);
        }
    }

    @Test
    void local_postgresUrlsAreAccepted() {
        for (String url : new String[]{GOOD_URL, "jdbc:postgresql:minishop", "jdbc:postgresql://127.0.0.1:55432/x?a=b",
                "jdbc:postgresql://[::1]:5432/minishop"}) {
            assertOk(localEnv(url, "minishop", "pw-1"));
        }
    }

    // ------------------------------------------------------------------ public profile (AC-008-1, 2)

    @Test
    void public_withNoPasswords_namesBothVariables() {
        StartupRequirementException e = failure(publicEnv(null, null));

        assertThat(e.getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
        assertThat(e.action()).isEqualTo(PUBLIC_MISSING_ACTION);
    }

    @Test
    void public_listsOnlyTheMissingVariable() {
        assertThat(failure(publicEnv(null, GOOD_USER)).getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD");
        assertThat(failure(publicEnv(GOOD_ADMIN, null)).getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_USER_PASSWORD");
    }

    @Test
    void public_emptyOrWhitespaceOnlyPassword_countsAsMissing() {
        assertThat(failure(publicEnv("", GOOD_USER)).getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD");
        assertThat(failure(publicEnv(GOOD_ADMIN, "   ")).getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_USER_PASSWORD");
        assertThat(failure(publicEnv("\t\t", "\n")).getMessage())
                .isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
    }

    @Test
    void public_missingIsReportedBeforeWeak() {
        // the admin one is missing, the user one is weak: "missing" comes first
        assertThat(failure(publicEnv(null, "Short1abc")).getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD");
    }

    @Test
    void public_devDefaultPasswords_areRefused() {
        StartupRequirementException e = failure(publicEnv("Admin@123", "User@123"));

        assertThat(e.getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
        assertThat(e.action()).isEqualTo(PUBLIC_WEAK_ACTION);
    }

    @Test
    void public_listsOnlyTheOffendingVariable() {
        assertThat(failure(publicEnv("Admin@123", GOOD_USER)).getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
        assertThat(failure(publicEnv(GOOD_ADMIN, "Short1abc")).getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_USER_PASSWORD");
    }

    @Test
    void public_elevenCharacters_isRefusedAndTwelveIsAccepted() {
        assertThat(failure(publicEnv("Abcdefghi12", GOOD_USER)).getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");

        assertOk(publicEnv("Abcdefghij12", GOOD_USER));
    }

    @Test
    void public_twelveLettersWithoutDigit_isRefusedByPasswordPolicy() {
        assertThat(failure(publicEnv("Abcdefghijkl", GOOD_USER)).getMessage())
                .isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
        assertThat(failure(publicEnv(GOOD_ADMIN, "123456789012")).getMessage())
                .isEqualTo(PUBLIC_WEAK + "SEED_USER_PASSWORD");
    }

    @Test
    void public_passwordOverBcryptLimit_isRefusedByPasswordPolicy() {
        // 65 characters: over the 64 character rule (and the 72 byte BCrypt limit is checked by the same policy)
        assertThat(failure(publicEnv("a1".repeat(32) + "b", GOOD_USER)).getMessage())
                .isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
    }

    @Test
    void public_aCodePointCountsAsOneCharacter() {
        // 10 ASCII + 1 emoji = 11 code points (but 12 UTF-16 chars): refused
        assertThat(failure(publicEnv("Abcdefghi1" + "😀", GOOD_USER)).getMessage())
                .isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");

        // 11 ASCII + 1 emoji = 12 code points: accepted
        assertOk(publicEnv("Abcdefghij1" + "😀", GOOD_USER));
    }

    @Test
    void public_valuesAreNeverTrimmed() {
        // "Admin@123" padded to 12+ characters with spaces is a different (long enough) password: not the dev default
        assertOk(publicEnv("  Admin@123  ", GOOD_USER));
        // spaces count as characters: 8 + 4 = 12 code points, so this one is long enough
        assertOk(publicEnv("Abcdefg1" + "    ", GOOD_USER));
    }

    @Test
    void public_sameAdminAndUserPassword_isRefused() {
        StartupRequirementException e = failure(publicEnv(GOOD_ADMIN, GOOD_ADMIN));

        assertThat(e.getMessage()).isEqualTo(PUBLIC_SAME);
        assertThat(e.action()).isEqualTo("The USER password is shared with testers; the ADMIN password must stay private.");
    }

    @Test
    void public_passwordsThatDifferOnlyInCase_areDifferent() {
        assertOk(publicEnv("Verify-Password-2026", "verify-password-2026"));
    }

    @Test
    void public_weakIsReportedBeforeSame() {
        // both weak and identical: the strength message wins
        assertThat(failure(publicEnv("Short1abc", "Short1abc")).getMessage())
                .isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
    }

    // ------------------------------------------------------------------ no value ever leaks (BR-008-1)

    @Test
    void messagesAndActions_neverContainAnyConfiguredValue() {
        String weakAdmin = "Sh0rt-adm";
        String weakUser = "Sh0rt-usr";
        String secretPassword = "Db-Secret-Pw-777";
        String secretUrl = "jdbc:mysql://secret-host.example/secretdb";
        String secretUser = "secret-db-user";

        MockEnvironment weakPublic = publicEnv(weakAdmin, weakUser);
        MockEnvironment samePublic = publicEnv("Same-Verify-Pass-1", "Same-Verify-Pass-1");
        MockEnvironment badUrl = localEnv(secretUrl, secretUser, secretPassword);
        MockEnvironment missingLocal = localEnv(secretUrl, secretUser, null);
        MockEnvironment missingPublic = publicEnv(weakAdmin, null);

        for (MockEnvironment environment : new MockEnvironment[]{weakPublic, samePublic, badUrl, missingLocal,
                missingPublic}) {
            StartupRequirementException e = failure(environment);
            assertThat(e.getMessage() + " | " + e.action())
                    .doesNotContain(weakAdmin, weakUser, "Same-Verify-Pass-1", secretPassword, secretUrl, secretUser,
                            "secret-host", "secretdb");
        }
    }

    @Test
    void messagesAndActions_neverContainTheDevDefaultsEither() {
        StartupRequirementException e = failure(publicEnv("Admin@123", "User@123"));

        assertThat(e.getMessage() + " | " + e.action()).doesNotContain("Admin@123", "User@123");
    }

    // ------------------------------------------------------------------ the bound value is what is checked

    /**
     * What {@code application-public.yml} and {@code application-local.yml} do: they bind the variables onto the
     * properties that {@code SeedProperties} and the datasource read. The test environment gets the same mapping.
     */
    private static MockEnvironment yamlEnv(String... profiles) {
        MockEnvironment environment = env(profiles);
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig", Map.of(
                "app.seed.admin-password", "${SEED_ADMIN_PASSWORD}",
                "app.seed.user-password", "${SEED_USER_PASSWORD}",
                "spring.datasource.url", "${DB_URL}",
                "spring.datasource.username", "${DB_USERNAME}",
                "spring.datasource.password", "${DB_PASSWORD}")));
        return environment;
    }

    /** A source named like Boot's real one, so relaxed names ({@code APP_SEED_ADMIN_PASSWORD}) bind. */
    private static void withEnvironmentVariables(MockEnvironment environment, Map<String, Object> variables) {
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", variables));
    }

    @Test
    void yamlMapping_withStrongVariables_isAccepted() {
        MockEnvironment environment = yamlEnv("public");
        withEnvironmentVariables(environment, Map.of("SEED_ADMIN_PASSWORD", GOOD_ADMIN,
                "SEED_USER_PASSWORD", GOOD_USER));

        assertOk(environment);
    }

    @Test
    void yamlMapping_withWeakVariable_isRefusedByVariableName() {
        MockEnvironment environment = yamlEnv("public");
        withEnvironmentVariables(environment, Map.of("SEED_ADMIN_PASSWORD", "Admin@123",
                "SEED_USER_PASSWORD", GOOD_USER));

        assertThat(failure(environment).getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
    }

    @Test
    void unresolvedPlaceholder_countsAsMissing_forThePublicPasswords() {
        MockEnvironment environment = yamlEnv("public"); // the variables are not set: the value is "${SEED_...}"

        StartupRequirementException e = failure(environment);

        assertThat(e.getMessage()).isEqualTo(PUBLIC_MISSING + "SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD");
        assertThat(e.getMessage() + e.action()).doesNotContain("${");
    }

    @Test
    void unresolvedPlaceholder_countsAsMissing_forTheDatabaseVariables() {
        MockEnvironment environment = yamlEnv("local");

        assertThat(failure(environment).getMessage())
                .isEqualTo(LOCAL_MISSING + "DB_URL, DB_USERNAME, DB_PASSWORD");
    }

    @Test
    void unresolvedDbUrl_isMissingEvenWhenTheOtherTwoAreSet() {
        MockEnvironment environment = yamlEnv("local");
        withEnvironmentVariables(environment, Map.of("DB_USERNAME", "minishop", "DB_PASSWORD", "db-secret-1"));

        assertThat(failure(environment).getMessage()).isEqualTo(LOCAL_MISSING + "DB_URL");
    }

    @Test
    void yamlMapping_withAllDatabaseVariables_isAccepted() {
        MockEnvironment environment = yamlEnv("local");
        withEnvironmentVariables(environment,
                Map.of("DB_URL", GOOD_URL, "DB_USERNAME", "minishop", "DB_PASSWORD", "db-secret-1"));

        assertOk(environment);
    }

    @Test
    void yamlMapping_withANonPostgresUrlVariable_isRefused() {
        MockEnvironment environment = yamlEnv("local");
        withEnvironmentVariables(environment,
                Map.of("DB_URL", "jdbc:h2:mem:x", "DB_USERNAME", "minishop", "DB_PASSWORD", "db-secret-1"));

        assertThat(failure(environment).getMessage()).isEqualTo(LOCAL_NOT_POSTGRES);
    }

    @Test
    void systemPropertyOverride_withAWeakPassword_isRefusedEvenWhenTheVariableIsStrong() {
        // java -Dapp.seed.admin-password=Admin@123 beats the SEED_ADMIN_PASSWORD variable in the binder
        MockEnvironment environment = yamlEnv("public");
        withEnvironmentVariables(environment, Map.of("SEED_ADMIN_PASSWORD", GOOD_ADMIN,
                "SEED_USER_PASSWORD", GOOD_USER));
        environment.getPropertySources().addFirst(new MapPropertySource("systemProperties",
                Map.of("app.seed.admin-password", "Admin@123")));

        StartupRequirementException e = failure(environment);

        assertThat(e.getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
        assertThat(e.getMessage() + e.action()).doesNotContain("Admin@123");
    }

    @Test
    void relaxedEnvironmentVariableName_withAWeakPassword_isRefusedEvenWhenTheVariableIsStrong() {
        // APP_SEED_USER_PASSWORD binds to app.seed.user-password and wins over the yaml mapping
        MockEnvironment environment = yamlEnv("public");
        withEnvironmentVariables(environment, Map.of("SEED_ADMIN_PASSWORD", GOOD_ADMIN,
                "SEED_USER_PASSWORD", GOOD_USER, "APP_SEED_USER_PASSWORD", "short1"));

        assertThat(failure(environment).getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_USER_PASSWORD");
    }

    @Test
    void overrideThatMakesBothPasswordsEqual_isRefused() {
        MockEnvironment environment = yamlEnv("public");
        withEnvironmentVariables(environment, Map.of("SEED_ADMIN_PASSWORD", GOOD_ADMIN,
                "SEED_USER_PASSWORD", GOOD_USER));
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("app.seed.admin-password", GOOD_USER)));

        assertThat(failure(environment).getMessage()).isEqualTo(PUBLIC_SAME);
    }

    @Test
    void placeholderThatResolvesToTheDevDefault_isCaughtBecauseTheBoundValueIsChecked() {
        MockEnvironment environment = env("public");
        environment.setProperty("app.seed.admin-password", "${smuggled.default}");
        environment.setProperty("smuggled.default", "Admin@123");
        environment.setProperty("app.seed.user-password", GOOD_USER);

        assertThat(failure(environment).getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
    }

    // ------------------------------------------------------------------ a secret that looks like a placeholder

    @Test
    void secretWithPlaceholderSyntax_isReadLiterally_whenTheNameIsNotDefined() {
        // 20 characters, letters and digits: strong. "${nope}" is not a property, so it stays text.
        assertOk(publicEnv("Pa55${nope}Word-1234", GOOD_USER));
        // read literally, "Pa5${nope}" is only 10 characters: refused
        assertThat(failure(publicEnv("Pa5${nope}", GOOD_USER)).getMessage())
                .isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
    }

    @Test
    void weakSecretWithPlaceholderSyntax_isRefusedWithoutLeakingAnyPartOfIt() {
        MockEnvironment environment = publicEnv("ab${nope}", GOOD_USER);

        StartupRequirementException e = failure(environment);

        assertThat(e.getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_ADMIN_PASSWORD");
        assertThat(e.getMessage() + " | " + e.action()).doesNotContain("nope").doesNotContain("${")
                .doesNotContain("ab$");
        assertThat(e).hasNoCause();
    }

    @Test
    void secretWithPlaceholderSyntaxFromAVariable_isReadLiterally() {
        MockEnvironment environment = yamlEnv("public");
        withEnvironmentVariables(environment, Map.of("SEED_ADMIN_PASSWORD", "Pa55${nope}Word-1234",
                "SEED_USER_PASSWORD", "x${y}z"));

        StartupRequirementException e = failure(environment);

        // the admin one is fine; the user one is 6 characters literally
        assertThat(e.getMessage()).isEqualTo(PUBLIC_WEAK + "SEED_USER_PASSWORD");
        assertThat(e.getMessage() + " | " + e.action()).doesNotContain("x${y}z").doesNotContain("nope");
    }

    @Test
    void databaseValuesWithPlaceholderSyntax_areReadLiterallyAndNeverPrinted() {
        assertOk(localEnv(GOOD_URL, "minishop", "pa${ss}word"));

        StartupRequirementException e = failure(localEnv("jdbc:h2:mem:${x}", "minishop", "pa${ss}word"));
        assertThat(e.getMessage() + " | " + e.action()).doesNotContain("pa${ss}word").doesNotContain("${");
    }

    @Test
    void valueThatCannotBeRead_isReportedByVariableNameWithoutTheCause() {
        MockEnvironment environment = env("public");
        // a circular reference makes the placeholder resolution throw; its message would contain the text
        environment.setProperty("app.seed.admin-password", "${loop.a}");
        environment.setProperty("loop.a", "${loop.b}");
        environment.setProperty("loop.b", "${loop.a}");
        environment.setProperty("app.seed.user-password", GOOD_USER);

        StartupRequirementException e = failure(environment);

        assertThat(e.getMessage()).isEqualTo("Cannot read the value of SEED_ADMIN_PASSWORD");
        assertThat(e.action()).isEqualTo("Check that SEED_ADMIN_PASSWORD is a plain text value.");
        assertThat(e).hasNoCause();
        assertThat(e.getMessage() + e.action()).doesNotContain("loop");
    }

    @Test
    void databaseValueThatCannotBeRead_isReportedByVariableName() {
        MockEnvironment environment = env("local");
        environment.setProperty("spring.datasource.url", GOOD_URL);
        environment.setProperty("spring.datasource.username", "minishop");
        environment.setProperty("spring.datasource.password", "${loop.a}");
        environment.setProperty("loop.a", "${loop.a}");

        StartupRequirementException e = failure(environment);

        assertThat(e.getMessage()).isEqualTo("Cannot read the value of DB_PASSWORD");
        assertThat(e).hasNoCause();
    }
}

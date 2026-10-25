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
 * AC-008-1, 2 and BR-008-1, 2: the checks that stop the app before any bean exists. Runs against a
 * {@link MockEnvironment}, so no Spring context and no real environment variable is involved. The checks read the
 * <em>bound</em> properties ({@code app.seed.*-password}) through a {@code Binder}, so this test sets those, not the
 * bare {@code SEED_*} names; the last section maps the variables onto them the way {@code application-public.yml} does.
 * Messages are pinned word for word; none may ever contain a value (a password).
 */
class StartupRequirementsTest {

    private static final String GOOD_ADMIN = "Admin-Verify-2026";
    private static final String GOOD_USER = "User-Verify-2026";

    private static final String PUBLIC_MISSING = "Public mode requires environment variables: ";
    private static final String PUBLIC_WEAK = "Public mode requires non-default demo passwords: ";
    private static final String PUBLIC_SAME =
            "Public mode requires different passwords for SEED_ADMIN_PASSWORD and SEED_USER_PASSWORD";

    private static final String PUBLIC_MISSING_ACTION =
            "Set both demo passwords as described in docs/deploy-cloudflare.md.";
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
    void noProfile_isAccepted_evenWithWeakPasswords() {
        MockEnvironment environment = env();
        environment.setProperty("app.seed.admin-password", "Admin@123");

        assertOk(environment);
    }

    @Test
    void validPublicProfile_isAccepted() {
        assertOk(publicEnv(GOOD_ADMIN, GOOD_USER));
    }

    @Test
    void order_isLowestPrecedence_soProfilesFromConfigFilesAreKnown() {
        assertThat(requirements.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
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

        MockEnvironment weakPublic = publicEnv(weakAdmin, weakUser);
        MockEnvironment samePublic = publicEnv("Same-Verify-Pass-1", "Same-Verify-Pass-1");
        MockEnvironment missingPublic = publicEnv(weakAdmin, null);

        for (MockEnvironment environment : new MockEnvironment[]{weakPublic, samePublic, missingPublic}) {
            StartupRequirementException e = failure(environment);
            assertThat(e.getMessage() + " | " + e.action())
                    .doesNotContain(weakAdmin, weakUser, "Same-Verify-Pass-1");
        }
    }

    @Test
    void messagesAndActions_neverContainTheDevDefaultsEither() {
        StartupRequirementException e = failure(publicEnv("Admin@123", "User@123"));

        assertThat(e.getMessage() + " | " + e.action()).doesNotContain("Admin@123", "User@123");
    }

    // ------------------------------------------------------------------ the bound value is what is checked

    /**
     * What {@code application-public.yml} does: it binds the variables onto the properties that {@code SeedProperties}
     * reads. The test environment gets the same mapping.
     */
    private static MockEnvironment yamlEnv(String... profiles) {
        MockEnvironment environment = env(profiles);
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig", Map.of(
                "app.seed.admin-password", "${SEED_ADMIN_PASSWORD}",
                "app.seed.user-password", "${SEED_USER_PASSWORD}")));
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

}

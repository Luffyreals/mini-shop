package com.minishop;

import com.minishop.support.StandaloneApp;
import com.minishop.support.StandaloneApp.Visitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code local} profile (PostgreSQL installed on the machine, no Docker; ADR-0012): AC-008-14, 15, 16.
 *
 * <p>The "installed PostgreSQL" is a throwaway container reached through {@code DB_URL}, {@code DB_USERNAME} and
 * {@code DB_PASSWORD}, exactly how a real installation is reached. What it proves is the configuration: the datasource
 * comes from those three variables, Docker Compose support is off, Flyway and the seed run, data survives a restart,
 * and a missing variable stops the app before it connects anywhere (never a silent H2 or Docker fallback). It is
 * <em>not</em> a test of a PostgreSQL on a real Windows machine; the owner confirms that part by following the README.
 * See {@link StandaloneApp} for the isolation rules (values as command-line arguments, one database per scenario).
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class LocalProfileTest {

    private static final String ADMIN_EMAIL = "admin@minishop.local";
    private static final String USER_EMAIL = "user@minishop.local";
    private static final String NEW_ADMIN = "Verify-Admin-2026";
    private static final String NEW_USER = "Verify-User-2026";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private static String[] localRun(String profiles, String database, String adminPassword, String userPassword) {
        return new String[]{"--spring.profiles.active=" + profiles,
                "--DB_URL=" + StandaloneApp.jdbcUrl(postgres, database),
                "--DB_USERNAME=" + postgres.getUsername(), "--DB_PASSWORD=" + postgres.getPassword(),
                "--SEED_ADMIN_PASSWORD=" + adminPassword, "--SEED_USER_PASSWORD=" + userPassword};
    }

    private static String[] devSeedLocalRun(String database) {
        return localRun("local", database, StandaloneApp.DEV_ADMIN_PASSWORD, StandaloneApp.DEV_USER_PASSWORD);
    }

    /** A run that must be refused before any connection (the URL points at a closed port). */
    private static String[] refusedRun(String profiles, String url, String username, String password) {
        return new String[]{"--spring.profiles.active=" + profiles, "--DB_URL=" + url, "--DB_USERNAME=" + username,
                "--DB_PASSWORD=" + password, "--SEED_ADMIN_PASSWORD=Verify-Admin-2026",
                "--SEED_USER_PASSWORD=Verify-User-2026"};
    }

    private void assertRefused(Throwable failure, CapturedOutput output, int offset, String message,
                               String... forbidden) {
        Throwable cause = StandaloneApp.causeNamed(failure, "StartupRequirementException");
        assertThat(cause).as("a StartupRequirementException in the cause chain of %s", failure).isNotNull();
        assertThat(cause.getMessage()).isEqualTo(message);
        String log = output.getAll().substring(offset);
        assertThat(log).contains("APPLICATION FAILED TO START").contains(message);
        assertThat(log).as("stopped before any connection and without an embedded database")
                .doesNotContain("Connection refused").doesNotContain("HikariPool")
                .doesNotContain("jdbc:h2:");
        for (String value : forbidden) {
            assertThat(log).as("no value in the log").doesNotContain(value);
        }
    }

    // ------------------------------------------------------------------ AC-008-15: missing or wrong variables

    @Test
    void start_withoutDbPassword_refusesAndNamesIt(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(
                refusedRun("local", "jdbc:postgresql://127.0.0.1:1/none", "someone", ""));

        assertRefused(failure, output, offset, "Profile 'local' requires environment variables: DB_PASSWORD",
                "someone");
    }

    @Test
    void start_withoutAnyDbVariable_namesAllThreeInOrder(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("local", "", "", ""));

        assertRefused(failure, output, offset,
                "Profile 'local' requires environment variables: DB_URL, DB_USERNAME, DB_PASSWORD");
        assertThat(output.getAll().substring(offset))
                .contains("Set them as described in README section 'Chạy với PostgreSQL cài trên máy (không Docker)'.");
    }

    @Test
    void start_withAnH2Url_isRefusedInsteadOfUsingH2(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(
                refusedRun("local", "jdbc:h2:mem:sneaky", "sa", "secret-db-pw"));

        assertRefused(failure, output, offset, "Profile 'local' requires DB_URL to start with jdbc:postgresql:",
                "secret-db-pw", "sneaky");
    }

    @Test
    void start_withH2AndLocalProfiles_isRefused(CapturedOutput output) {
        int offset = output.getAll().length();

        Throwable failure = StandaloneApp.startExpectingFailure(refusedRun("h2,local",
                "jdbc:postgresql://127.0.0.1:1/none", "someone", "secret-db-pw"));

        assertRefused(failure, output, offset, "Profiles 'h2' and 'local' cannot be active together", "secret-db-pw");
        assertThat(output.getAll().substring(offset)).contains(
                "Choose one database: 'h2' (in memory) or 'local' (PostgreSQL installed on this machine).");
    }

    // ------------------------------------------------------------------ AC-008-14: configuration of the profile

    @Test
    void profileFile_turnsDockerComposeOffAndTakesTheDatabaseFromTheThreeVariables() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("local", new ClassPathResource("application-local.yml"));

        assertThat(sources).hasSize(1);
        PropertySource<?> local = sources.get(0);
        assertThat(local.getProperty("spring.docker.compose.enabled")).isEqualTo(false);
        assertThat(local.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(local.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(local.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
    }

    // ------------------------------------------------------------------ AC-008-14: the app runs on that database

    @Test
    void start_withLocalProfile_usesTheConfiguredPostgresRunsFlywayAndSeedsAndKeepsDataAfterARestart(
            CapturedOutput output) throws Exception {
        String database = StandaloneApp.newDatabase(postgres);
        String url = StandaloneApp.jdbcUrl(postgres, database);

        try (ConfigurableApplicationContext app = StandaloneApp.start(devSeedLocalRun(database))) {
            assertThat(app.getEnvironment().getActiveProfiles()).containsExactly("local");
            assertThat(app.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(url);
            JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(database);
            assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 17");
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).as("phase 8 lock timeout")
                    .isEqualTo("5s");
        }
        assertThat(StandaloneApp.scalar(postgres, database,
                "select count(*) from flyway_schema_history where \"version\" = '2' and success")).isEqualTo("1");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from products")).isEqualTo("22");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from categories")).isEqualTo("5");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from users")).isEqualTo("2");

        // data written while the app is stopped is still there after the next start, and nothing is seeded twice
        StandaloneApp.execute(postgres, database, "insert into users (email, password_hash, full_name, role) values "
                + "('khac@minishop.test', 'x', 'Người khác', 'USER')");
        int offset = output.getAll().length();
        try (ConfigurableApplicationContext again = StandaloneApp.start(devSeedLocalRun(database))) {
            assertThat(again).isNotNull();
        }
        String log = output.getAll().substring(offset);
        assertThat(log).contains("Seed skipped for users");
        assertThat(log).contains("Seed skipped for categories and products");
        assertThat(log).doesNotContain("Seeded ");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from users")).isEqualTo("3");
        assertThat(StandaloneApp.scalar(postgres, database,
                "select count(*) from users where email = 'khac@minishop.test'")).isEqualTo("1");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from products")).isEqualTo("22");
        assertThat(StandaloneApp.scalar(postgres, database, "select count(*) from categories")).isEqualTo("5");
    }

    @Test
    void start_withLocalProfile_neverResetsDemoPasswords() throws Exception {
        String database = StandaloneApp.newDatabase(postgres);
        try (ConfigurableApplicationContext first = StandaloneApp.start(devSeedLocalRun(database))) {
            assertThat(first).isNotNull();
        }
        // a second dev run with other SEED_* values must not touch the stored hashes: only public mode resets
        try (ConfigurableApplicationContext second = StandaloneApp.start(
                localRun("local", database, "Other-Admin-Pass-9", "Other-User-Pass-9"))) {
            assertThat(second).isNotNull();
        }

        String adminHash = StandaloneApp.scalar(postgres, database,
                "select password_hash from users where email = '" + ADMIN_EMAIL + "'");
        assertThat(ENCODER.matches(StandaloneApp.DEV_ADMIN_PASSWORD, adminHash)).isTrue();
        assertThat(ENCODER.matches("Other-Admin-Pass-9", adminHash)).isFalse();
    }

    // ------------------------------------------------------------------ AC-008-16: local + public

    @Test
    void start_withLocalAndPublicProfiles_resetsDemoPasswordsOnTheConfiguredDatabase(CapturedOutput output)
            throws Exception {
        String database = StandaloneApp.newDatabase(postgres);
        try (ConfigurableApplicationContext dev = StandaloneApp.start(devSeedLocalRun(database))) {
            assertThat(dev).isNotNull();
        }
        int offset = output.getAll().length();

        try (ConfigurableApplicationContext app = StandaloneApp.start(
                localRun("local,public", database, NEW_ADMIN, NEW_USER))) {
            assertThat(app.getEnvironment().getActiveProfiles()).containsExactlyInAnyOrder("local", "public");
            assertThat(app.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo(StandaloneApp.jdbcUrl(postgres, database));
            assertThat(app.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            String log = output.getAll().substring(offset);
            assertThat(log).contains("Public mode: demo passwords updated for 2 account(s)");
            assertThat(log).doesNotContain(NEW_ADMIN, NEW_USER, "Admin@123", "User@123");

            String adminHash = StandaloneApp.scalar(postgres, database,
                    "select password_hash from users where email = '" + ADMIN_EMAIL + "'");
            String userHash = StandaloneApp.scalar(postgres, database,
                    "select password_hash from users where email = '" + USER_EMAIL + "'");
            assertThat(ENCODER.matches(NEW_ADMIN, adminHash)).isTrue();
            assertThat(ENCODER.matches(NEW_USER, userHash)).isTrue();
            assertThat(ENCODER.matches("Admin@123", adminHash)).isFalse();

            int port = StandaloneApp.portOf(app);
            HttpResponse<String> dev = new Visitor(port).login(ADMIN_EMAIL, "Admin@123");
            assertThat(Visitor.location(dev)).contains("/login?error");
            assertThat(Visitor.location(new Visitor(port).login(ADMIN_EMAIL, NEW_ADMIN))).doesNotContain("error");
        }
    }

    @Test
    void start_withLocalAndPublicProfiles_butWeakPassword_isRefusedBeforeConnecting(CapturedOutput output) {
        int offset = output.getAll().length();
        List<String> args = new ArrayList<>(List.of(refusedRun("local,public", "jdbc:postgresql://127.0.0.1:1/none",
                "someone", "db-pw-secret")));
        args.removeIf(a -> a.startsWith("--SEED_USER_PASSWORD="));
        args.add("--SEED_USER_PASSWORD=User@123");

        Throwable failure = StandaloneApp.startExpectingFailure(args.toArray(String[]::new));

        assertRefused(failure, output, offset, "Public mode requires non-default demo passwords: SEED_USER_PASSWORD",
                "User@123", "db-pw-secret");
    }
}

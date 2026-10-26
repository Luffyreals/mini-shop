package com.minishop.support;

import com.minishop.MiniShopApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application the way {@code spring-boot:run} does (a plain {@link SpringApplicationBuilder}, no test
 * context), for tests of startup rules (phase 8, QĐ10), now without a web server (ADR-0014):
 * {@code DataDirectoryStartupTest}.
 *
 * <p><b>Isolation rules built in</b>
 * <ul>
 *   <li>Every value goes in as a command-line argument ({@code --NAME=value}), the highest property source. So a real
 *   environment variable of the developer ({@code SEED_ADMIN_PASSWORD}, {@code DATA_DIR}...) can never leak into a test,
 *   and "missing" is simulated with an empty value ({@code --NAME=}), which beats the real variable too.</li>
 *   <li>Never a duplicate key: two {@code --a=1 --a=2} arguments would be joined into {@code "1,2"}. Callers add only
 *   keys that {@link #start} does not set.</li>
 *   <li>Built with {@code .web(WebApplicationType.NONE)}: no web server and no port (ADR-0014), banner off. The
 *   real {@code spring-boot:run} startup is a JavaFX {@code init()} that does the same.</li>
 *   <li>Classes under {@code target/test-classes} are kept out of component scanning with a {@link TypeExcludeFilter}
 *   bean (what Spring Boot's own test support does for a test context). Without it the scan would pick up
 *   test-only beans and the nested rogue {@code @Component} classes of the architecture tests.</li>
 *   <li>Each scenario gets its own H2 file database under {@code target/} ({@link #newDatabase}), so contexts that are
 *   open at the same time never see each other's rows, and a scenario can stop the app and start it again on the
 *   same data, like a real restart.</li>
 * </ul>
 */
public final class StandaloneApp {

    private StandaloneApp() {
    }

    /** What the dev defaults of application.yml are, passed explicitly so a real SEED_* variable is ignored. */
    public static final String DEV_ADMIN_PASSWORD = "Admin@123";
    public static final String DEV_USER_PASSWORD = "User@123";

    /** Starts the application; the caller must close the returned context. */
    public static ConfigurableApplicationContext start(String... args) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.main.banner-mode=off"));
        all.addAll(List.of(args));
        return builder().run(all.toArray(String[]::new));
    }

    /**
     * The builder behind {@link #start}: {@code web(NONE)} and test classes kept out of component scanning. Callers
     * that must build the context themselves (the JavaFX smoke test, which overrides {@code MiniShopApp.createContext})
     * start from this and add what they need.
     */
    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(MiniShopApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(context -> context.getBeanFactory().registerSingleton(
                        "excludeTestClassesFromScanning", new ExcludeTestClasses()));
    }

    /**
     * Starts the application and returns what made it fail. If it starts, the context is closed and the test fails,
     * so a missing check can never leave a server running.
     */
    public static Throwable startExpectingFailure(String... args) {
        ConfigurableApplicationContext context;
        try {
            context = start(args);
        } catch (Throwable failure) {
            return failure;
        }
        context.close();
        throw new AssertionError("the application was expected to refuse to start, but it started");
    }

    /** The first throwable in the cause chain (the given one included) whose class has this simple name, or null. */
    public static Throwable causeNamed(Throwable failure, String simpleName) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 30; depth++) {
            if (current.getClass().getSimpleName().equals(simpleName)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    // ------------------------------------------------------------------ databases

    /** Settings of {@code application.yml}; the test only chooses where the file lives. */
    private static final String H2_SETTINGS = ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    /** A new, empty H2 file database under {@code target/} (removed by {@code mvn clean}); returns its JDBC URL. */
    public static String newDatabase() {
        Path directory = Path.of("target", "standalone-db", UUID.randomUUID().toString()).toAbsolutePath();
        return "jdbc:h2:file:" + directory.resolve("minishop").toString().replace('\\', '/') + H2_SETTINGS;
    }

    /**
     * Opens a connection to a scenario database. While the app runs it shares the same database (same URL, same
     * JVM); when the app is stopped, the file is opened and closed again with the connection.
     */
    public static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    /** Single value of a query on a scenario database. */
    public static String scalar(String url, String sql) throws SQLException {
        try (Connection connection = connect(url); Statement statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    public static void execute(String url, String sql) throws SQLException {
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Keeps everything compiled under {@code target/test-classes} out of component scanning. */
    private static final class ExcludeTestClasses extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            return metadataReader.getResource().getURL().toString().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExcludeTestClasses;
        }

        @Override
        public int hashCode() {
            return ExcludeTestClasses.class.hashCode();
        }
    }
}

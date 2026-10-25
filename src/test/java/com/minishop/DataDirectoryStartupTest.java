package com.minishop;

import com.minishop.support.StandaloneApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013 on the real application: {@code DATA_DIR=target/...} (relative, without {@code ./}) used to stop the app
 * with H2's "implicitly relative" error. Now it starts, and the database file and the upload directory both land in
 * that directory.
 *
 * <p>The test configuration replaces {@code spring.datasource.url} (in-memory database) and the upload directory, so
 * this test passes both exactly as {@code application.yml} writes them (read from the file, placeholders included).
 */
class DataDirectoryStartupTest {

    @Test
    void relativeDataDirWithoutDotSlash_startsAndKeepsDatabaseAndUploadsThere() {
        String dataDir = "target/standalone-db/" + UUID.randomUUID();

        try (ConfigurableApplicationContext app = StandaloneApp.start(
                "--DATA_DIR=" + dataDir, "--spring.datasource.url=" + fromApplicationYml("spring.datasource.url"),
                "--app.storage.local.upload-dir=" + fromApplicationYml("app.storage.local.upload-dir"),
                "--SEED_ADMIN_PASSWORD=" + StandaloneApp.DEV_ADMIN_PASSWORD,
                "--SEED_USER_PASSWORD=" + StandaloneApp.DEV_USER_PASSWORD)) {

            assertThat(app.getEnvironment().getProperty("app.data-dir")).isEqualTo("./" + dataDir);
            assertThat(app.getEnvironment().getProperty("spring.datasource.url"))
                    .startsWith("jdbc:h2:file:./" + dataDir + "/minishop;");
            assertThat(app.getEnvironment().getProperty("app.storage.local.upload-dir"))
                    .isEqualTo("./" + dataDir + "/uploads");
            assertThat(Files.exists(Path.of(dataDir, "minishop.mv.db"))).as("the database file").isTrue();
        }
    }

    private static String fromApplicationYml(String key) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String value = yaml.getObject().getProperty(key);
        assertThat(value).as("application.yml builds %s from app.data-dir", key).contains("${app.data-dir}");
        return value;
    }
}

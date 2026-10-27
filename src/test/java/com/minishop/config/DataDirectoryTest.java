package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013: {@code DATA_DIR} goes into the H2 file URL, and H2 refuses a path that is implicitly relative to the
 * working directory. {@link DataDirectory} gives a plain relative value an explicit {@code ./} and leaves every form
 * H2 already accepts alone. BR-009-6 (D6): a packaged app without {@code DATA_DIR} uses {@code %LOCALAPPDATA%\MiniShop}
 * and logs to a file there. The real startup is {@code DataDirectoryStartupTest}.
 *
 * <p>Expected paths are built with {@link Path#of} so the tests also pass on the Linux CI runner, where a Windows
 * path is just a file name with backslashes in it.
 */
class DataDirectoryTest {

    private final DataDirectory dataDirectory = new DataDirectory();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "data2              | ./data2",
            "target/x           | ./target/x",
            "target\\x          | ./target\\x",
            "data2/             | ./data2",
            "'  data2  '        | ./data2",
            "minishop-data.v2   | ./minishop-data.v2",
            ".data              | ./.data"})
    void plainRelativePath_getsAnExplicitDotSlash(String value, String expected) {
        assertThat(DataDirectory.normalize(value)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "./data", ".\\data", "../shared/data", "..\\shared", ".", "..",
            "/d/minishop-data", "/var/lib/minishop", "\\\\server\\share",
            "C:\\minishop-data", "D:/minishop-data", "~/minishop-data"})
    void pathH2AlreadyAccepts_isLeftAsItIs(String value) {
        assertThat(DataDirectory.normalize(value)).isEqualTo(value);
    }

    @Test
    void blankOrMissing_fallsBackToTheDefault() {
        assertThat(DataDirectory.normalize(null)).isEqualTo("./data");
        assertThat(DataDirectory.normalize("")).isEqualTo("./data");
        assertThat(DataDirectory.normalize("   ")).isEqualTo("./data");
    }

    @Test
    void postProcess_overridesTheBoundValueWithTheNormalizedOne() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.data-dir", "data2");

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("app.data-dir")).isEqualTo("./data2");
    }

    @Test
    void postProcess_leavesAnAcceptedValueWithoutAddingAPropertySource() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.data-dir", "./data");
        int sources = environment.getPropertySources().size();

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("app.data-dir")).isEqualTo("./data");
        assertThat(environment.getPropertySources().size()).isEqualTo(sources);
    }

    // ------------------------------------------------------------------ resolve (BR-009-6)

    private static final String LOCAL_APP_DATA = "C:\\Users\\Nguyễn Văn A\\AppData\\Local";
    private static final String USER_HOME = "C:\\Users\\Nguyễn Văn A";

    @Test
    void resolve_withDataDirSet_winsEvenWhenPackaged() {
        Map<String, String> env = Map.of("LOCALAPPDATA", LOCAL_APP_DATA);

        assertThat(DataDirectory.resolve("D:/shop-data", true, env, USER_HOME)).isEqualTo("D:/shop-data");
        assertThat(DataDirectory.resolve("D:/shop-data", false, env, USER_HOME)).isEqualTo("D:/shop-data");
    }

    @Test
    void resolve_withPlainRelativeDataDirWhenPackaged_getsDotSlash() {
        Map<String, String> env = Map.of("LOCALAPPDATA", LOCAL_APP_DATA);

        assertThat(DataDirectory.resolve("data2", true, env, USER_HOME)).isEqualTo("./data2");
    }

    @Test
    void resolve_blankAndPackaged_usesLocalAppDataMiniShopKeepingNonAsciiCharacters() {
        Map<String, String> env = Map.of("LOCALAPPDATA", LOCAL_APP_DATA);

        String resolved = DataDirectory.resolve("", true, env, USER_HOME);

        assertThat(resolved).isEqualTo(Path.of(LOCAL_APP_DATA, "MiniShop").toString())
                .startsWith(LOCAL_APP_DATA).contains("Nguyễn Văn A").endsWith("MiniShop");
    }

    @Test
    void resolve_nullDataDirAndPackaged_alsoUsesLocalAppData() {
        Map<String, String> env = Map.of("LOCALAPPDATA", LOCAL_APP_DATA);

        assertThat(DataDirectory.resolve(null, true, env, USER_HOME))
                .isEqualTo(Path.of(LOCAL_APP_DATA, "MiniShop").toString());
    }

    @Test
    void resolve_localAppDataWithSurroundingSpaces_isStripped() {
        Map<String, String> env = Map.of("LOCALAPPDATA", "  " + LOCAL_APP_DATA + "  ");

        assertThat(DataDirectory.resolve(null, true, env, USER_HOME))
                .isEqualTo(Path.of(LOCAL_APP_DATA, "MiniShop").toString());
    }

    @Test
    void resolve_packagedWithoutLocalAppData_fallsBackToUserHomeAppDataLocalMiniShop() {
        String expected = Path.of(USER_HOME, "AppData", "Local", "MiniShop").toString();

        assertThat(DataDirectory.resolve("", true, Map.of(), USER_HOME)).isEqualTo(expected);
        assertThat(DataDirectory.resolve("", true, null, USER_HOME)).isEqualTo(expected);
        assertThat(DataDirectory.resolve(null, true, Map.of("LOCALAPPDATA", ""), USER_HOME)).isEqualTo(expected);
        assertThat(DataDirectory.resolve(null, true, Map.of("LOCALAPPDATA", "   "), USER_HOME)).isEqualTo(expected);
    }

    @Test
    void resolve_blankAndNotPackaged_isTheDefaultEvenWithLocalAppData() {
        Map<String, String> env = Map.of("LOCALAPPDATA", LOCAL_APP_DATA);

        assertThat(DataDirectory.resolve("", false, env, USER_HOME)).isEqualTo("./data");
        assertThat(DataDirectory.resolve(null, false, env, USER_HOME)).isEqualTo("./data");
        assertThat(DataDirectory.resolve("   ", false, null, USER_HOME)).isEqualTo("./data");
    }

    // ------------------------------------------------------------------ postProcess

    @Test
    void postProcess_notPackagedAndBlank_leavesTheDefaultAndAddsNoLogFile() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.data-dir", "");

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("app.data-dir")).isEqualTo("./data");
        assertThat(environment.containsProperty("logging.file.name")).isFalse();
    }

    @Test
    void postProcess_packagedWithDataDir_logsInsideTheDataDirectory() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.data-dir", "data2").withProperty("app.packaged", "true");

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("app.data-dir")).isEqualTo("./data2");
        assertThat(environment.getProperty("logging.file.name")).isEqualTo("./data2/logs/mini-shop.log");
    }

    @Test
    void postProcess_packagedWithoutDataDir_usesTheLocalAppDataFolderForDataAndLog() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.data-dir", "").withProperty("app.packaged", "true");

        dataDirectory.postProcessEnvironment(environment, null);

        String expected = DataDirectory.resolve(null, true, System.getenv(), System.getProperty("user.home"));
        assertThat(environment.getProperty("app.data-dir")).isEqualTo(expected).endsWith("MiniShop");
        assertThat(environment.getProperty("logging.file.name"))
                .isEqualTo(expected + "/logs/mini-shop.log");
    }

    @Test
    void postProcess_packagedButLogFileNameAlreadySet_isNotOverridden() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.data-dir", "./data")
                .withProperty("app.packaged", "true").withProperty("logging.file.name", "custom.log");

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.file.name")).isEqualTo("custom.log");
    }

    @Test
    void postProcess_packagedButLogFilePathAlreadySet_addsNoLogFileName() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.data-dir", "./data")
                .withProperty("app.packaged", "true").withProperty("logging.file.path", "C:/logs");

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.containsProperty("logging.file.name")).isFalse();
        assertThat(environment.getProperty("logging.file.path")).isEqualTo("C:/logs");
    }

    @Test
    void postProcess_packagedFalse_addsNoLogFileEvenWithDataDir() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.data-dir", "data2").withProperty("app.packaged", "false");

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.containsProperty("logging.file.name")).isFalse();
    }

    @Test
    void uploadDir_followsTheDataDirectoryThroughTheRealPlaceholder() {
        // application.yml: upload-dir: ${UPLOAD_DIR:${app.data-dir}/uploads}, read from the file as it is written
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String uploadDirExpression = yaml.getObject().getProperty("app.storage.local.upload-dir");
        assertThat(uploadDirExpression).contains("${app.data-dir}");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.data-dir", "data2", "app.storage.local.upload-dir", uploadDirExpression)));

        dataDirectory.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("app.data-dir")).isEqualTo("./data2");
        assertThat(environment.getProperty("app.storage.local.upload-dir")).isEqualTo("./data2/uploads");
    }

    @Test
    void order_isLowestPrecedence_soTheConfigFilesAreAlreadyRead() {
        assertThat(dataDirectory.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}

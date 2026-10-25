package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013: {@code DATA_DIR} goes into the H2 file URL, and H2 refuses a path that is implicitly relative to the
 * working directory. {@link DataDirectory} gives a plain relative value an explicit {@code ./} and leaves every form
 * H2 already accepts alone. The real startup is {@code DataDirectoryStartupTest}.
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

    @Test
    void order_isLowestPrecedence_soTheConfigFilesAreAlreadyRead() {
        assertThat(dataDirectory.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}

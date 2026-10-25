package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-008-1: the exception becomes Boot's "APPLICATION FAILED TO START" block, and nothing else does. */
class StartupRequirementFailureAnalyzerTest {

    private final StartupRequirementFailureAnalyzer analyzer = new StartupRequirementFailureAnalyzer();

    @Test
    void analyze_startupRequirementException_givesDescriptionActionAndCause() {
        StartupRequirementException exception = new StartupRequirementException(
                "Public mode requires environment variables: SEED_ADMIN_PASSWORD", "Set it as described in README.");

        FailureAnalysis analysis = analyzer.analyze(exception);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("Public mode requires environment variables: SEED_ADMIN_PASSWORD");
        assertThat(analysis.getAction()).isEqualTo("Set it as described in README.");
        assertThat(analysis.getCause()).isSameAs(exception);
    }

    @Test
    void analyze_whenWrappedInAnotherException_stillFindsIt() {
        StartupRequirementException cause = new StartupRequirementException("message", "action");
        Exception wrapped = new IllegalStateException("outer", new RuntimeException("middle", cause));

        FailureAnalysis analysis = analyzer.analyze(wrapped);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("message");
        assertThat(analysis.getAction()).isEqualTo("action");
        assertThat(analysis.getCause()).isSameAs(cause);
    }

    @Test
    void analyze_otherExceptions_returnNull() {
        assertThat(analyzer.analyze(new IllegalStateException("something else"))).isNull();
        assertThat(analyzer.analyze(new IllegalArgumentException("x", new RuntimeException("y")))).isNull();
    }
}

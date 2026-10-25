package com.minishop.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link StartupRequirementException} into Boot's "APPLICATION FAILED TO START" block, without a stack trace. */
public final class StartupRequirementFailureAnalyzer extends AbstractFailureAnalyzer<StartupRequirementException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, StartupRequirementException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}

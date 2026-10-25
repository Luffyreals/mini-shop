package com.minishop.config;

/**
 * Raised by {@link StartupRequirements} when the active profiles lack what they need. The message names
 * variables only, never their values; {@link #action()} tells the operator what to do about it.
 */
final class StartupRequirementException extends IllegalStateException {

    private final String action;

    StartupRequirementException(String message, String action) {
        super(message);
        this.action = action;
    }

    String action() {
        return action;
    }
}

package com.minishop.user;

/**
 * The emails of the two demo accounts seeded by {@code config.DataInitializer}. They are constants here so that
 * seeding and any later screen that mentions them can never drift apart. Not a bean.
 */
public final class DemoAccounts {

    public static final String ADMIN_EMAIL = "admin@minishop.local";
    public static final String USER_EMAIL = "user@minishop.local";

    private DemoAccounts() {
    }
}

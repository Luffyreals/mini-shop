package com.minishop.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two demo accounts seeded by {@code config.DataInitializer} and the only accounts whose password the public
 * mode resets (BR-008-2). The emails are constants here so that seeding and resetting can never drift apart.
 */
@Service
public class DemoAccountService {

    public static final String ADMIN_EMAIL = "admin@minishop.local";
    public static final String USER_EMAIL = "user@minishop.local";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    DemoAccountService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Replaces the password hash of exactly {@link #ADMIN_EMAIL} and {@link #USER_EMAIL} in one transaction.
     * No other account is touched. The hashes come from the application's {@link PasswordEncoder}.
     *
     * @return how many accounts were updated (0 to 2; 0 when the database has no demo accounts yet)
     * @throws IllegalArgumentException when a password is null or blank (nothing is changed)
     */
    @Transactional
    public int resetDemoPasswords(String adminPassword, String userPassword) {
        requireText(adminPassword);
        requireText(userPassword);
        return users.updatePasswordHash(ADMIN_EMAIL, passwordEncoder.encode(adminPassword))
                + users.updatePasswordHash(USER_EMAIL, passwordEncoder.encode(userPassword));
    }

    private static void requireText(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Demo password must not be blank");
        }
    }
}

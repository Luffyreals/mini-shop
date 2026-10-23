package com.minishop.user;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The single definition of the password rule; returns the first violation only. */
final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;
    /** BCrypt rejects input longer than 72 bytes when hashing. */
    private static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static Optional<String> violation(String password) {
        if (password == null) {
            return Optional.of("Mật khẩu phải có từ 8 đến 64 ký tự.");
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return Optional.of("Mật khẩu phải có từ 8 đến 64 ký tự.");
        }
        boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
        boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return Optional.of("Mật khẩu phải có cả chữ và số.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("Mật khẩu quá dài.");
        }
        return Optional.empty();
    }
}

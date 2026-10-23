package com.minishop.user;

/**
 * Fixture cho test ở package khác: dựng {@link CurrentUser} đăng nhập sẵn (header đọc {@code principal.fullName}).
 * Dùng: {@code .with(user(TestUsers.admin()))}.
 */
public final class TestUsers {

    private static final String FAKE_HASH = "{noop}x";

    private TestUsers() {
    }

    public static CurrentUser user() {
        return withName("Khách hàng mẫu", Role.USER);
    }

    public static CurrentUser admin() {
        return withName("Quản trị viên", Role.ADMIN);
    }

    public static CurrentUser withName(String fullName, Role role) {
        String email = role == Role.ADMIN ? "admin@test.local" : "user@test.local";
        return new CurrentUser(role == Role.ADMIN ? 1L : 2L, email, FAKE_HASH, fullName, role);
    }
}

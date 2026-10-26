package com.minishop.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0014: {@code CurrentUser} is a plain record of values, and its text form holds no personal data. */
class CurrentUserTest {

    private final CurrentUser user = new CurrentUser(7L, "an.nguyen@example.com", "Nguyễn Văn An", Role.USER);

    @Test
    void accessors_returnWhatWasGiven() {
        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.email()).isEqualTo("an.nguyen@example.com");
        assertThat(user.fullName()).isEqualTo("Nguyễn Văn An");
        assertThat(user.role()).isEqualTo(Role.USER);
    }

    @Test
    void toString_printsOnlyTheIdAndTheRole() {
        assertThat(user.toString()).isEqualTo("CurrentUser[id=7, role=USER]");
    }

    @Test
    void toString_doesNotContainEmailOrName() {
        assertThat(user.toString()).doesNotContain("an.nguyen", "@", "Nguyễn", "Văn An", "example.com");
        assertThat(new CurrentUser(1L, "admin@minishop.local", "Quản trị", Role.ADMIN).toString())
                .isEqualTo("CurrentUser[id=1, role=ADMIN]");
    }

    @Test
    void equals_comparesAllComponents() {
        assertThat(user).isEqualTo(new CurrentUser(7L, "an.nguyen@example.com", "Nguyễn Văn An", Role.USER))
                .isNotEqualTo(new CurrentUser(8L, "an.nguyen@example.com", "Nguyễn Văn An", Role.USER))
                .isNotEqualTo(new CurrentUser(7L, "an.nguyen@example.com", "Nguyễn Văn An", Role.ADMIN));
    }

    @Test
    void currentUser_isARecordWithNoPasswordComponent() {
        assertThat(CurrentUser.class.isRecord()).isTrue();
        assertThat(CurrentUser.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("id", "email", "fullName", "role");
    }
}

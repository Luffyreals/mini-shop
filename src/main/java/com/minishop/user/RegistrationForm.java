package com.minishop.user;

import com.minishop.common.FormText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration input. It deliberately has no {@code role} or {@code id} component, so request
 * parameters cannot set them (AC-003-7). Name and email are cleaned by {@link FormText} before validation
 * (invisible characters, control characters, special spaces, NFC, strip; BR-007-6); passwords never go through
 * it because whitespace is part of a password.
 */
@PasswordsMatch
record RegistrationForm(
        @NotBlank(message = "Vui lòng nhập họ và tên.")
        @Size(max = 100, message = "Họ và tên tối đa 100 ký tự.")
        String fullName,

        @NotBlank(message = "Vui lòng nhập email.")
        @Email(message = "Email không hợp lệ.")
        @Pattern(regexp = "\\p{ASCII}*", message = "Email không hợp lệ.")
        @Size(max = 255, message = "Email tối đa 255 ký tự.")
        String email,

        @ValidPassword
        String password,

        String confirmPassword) {

    RegistrationForm {
        fullName = FormText.clean(fullName, false);
        email = FormText.clean(email, false);
    }

    static RegistrationForm empty() {
        return new RegistrationForm("", "", "", "");
    }

    @Override
    public String toString() {
        return "RegistrationForm[fullName=" + fullName + ", email=" + email
                + ", password=****, confirmPassword=****]";
    }
}

package com.minishop.user;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra ràng buộc của {@link RegistrationForm} và {@link PasswordPolicy} bằng Jakarta Validator thuần
 * (AC-003-3, AC-003-6, AC-003-7, BR-003-2, BR-003-4). Mỗi case assert đúng chữ và đúng ô.
 */
class RegistrationFormValidationTest {

    private static final String LENGTH_MSG = "Mật khẩu phải có từ 8 đến 64 ký tự.";
    private static final String MIX_MSG = "Mật khẩu phải có cả chữ và số.";
    private static final String TOO_LONG_MSG = "Mật khẩu quá dài.";
    private static final String EMOJI = "😀";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static RegistrationForm form(String fullName, String email, String password, String confirm) {
        return new RegistrationForm(fullName, email, password, confirm);
    }

    private static RegistrationForm withPassword(String password) {
        return form("An", "an@example.com", password, password);
    }

    private static RegistrationForm withName(String fullName) {
        return form(fullName, "an@example.com", "abcdefg1", "abcdefg1");
    }

    private static RegistrationForm withEmail(String email) {
        return form("An", email, "abcdefg1", "abcdefg1");
    }

    /** Violations as "field: message", sorted for stable comparison. */
    private static List<String> errors(RegistrationForm form) {
        Set<ConstraintViolation<RegistrationForm>> violations = validator.validate(form);
        return violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .toList();
    }

    // ---- fullName (AC-003-6) ----

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void fullName_whenBlank_reportsRequiredMessageOnFullName(String name) {
        assertThat(errors(withName(name))).containsExactly("fullName: Vui lòng nhập họ và tên.");
    }

    @Test
    void fullName_when101Chars_reportsMaxLengthMessage() {
        assertThat(errors(withName("a".repeat(101)))).containsExactly("fullName: Họ và tên tối đa 100 ký tự.");
    }

    @Test
    void fullName_whenExactly100Chars_isValid() {
        assertThat(errors(withName("a".repeat(100)))).isEmpty();
    }

    @Test
    void fullName_whenNull_reportsRequiredMessage() {
        assertThat(errors(withName(null))).containsExactly("fullName: Vui lòng nhập họ và tên.");
    }

    @Test
    void fullName_whenPadded_isStripped() {
        assertThat(withName("  Nguyễn An  ").fullName()).isEqualTo("Nguyễn An");
    }

    // ---- email (AC-003-3, AC-003-6) ----

    @Test
    void email_whenEmpty_reportsRequiredMessageOnly() {
        assertThat(errors(withEmail(""))).containsExactly("email: Vui lòng nhập email.");
    }

    @ParameterizedTest
    @NullSource
    void email_whenNull_reportsRequiredMessage(String email) {
        assertThat(errors(withEmail(email))).containsExactly("email: Vui lòng nhập email.");
    }

    @Test
    void email_whenMalformed_reportsInvalidMessage() {
        assertThat(errors(withEmail("abc"))).containsExactly("email: Email không hợp lệ.");
    }

    @Test
    void email_whenNonAscii_reportsInvalidMessage() {
        assertThat(errors(withEmail("anh@exámple.com"))).contains("email: Email không hợp lệ.");
        assertThat(errors(withEmail("ánh@example.com"))).contains("email: Email không hợp lệ.");
    }

    @Test
    void email_when256WellFormedChars_reportsMaxLengthMessage() {
        String email = "a".repeat(64) + "@" + String.join(".", "b".repeat(63), "c".repeat(63), "d".repeat(63));
        assertThat(email).hasSize(256);

        assertThat(errors(withEmail(email))).contains("email: Email tối đa 255 ký tự.");
    }

    @Test
    void email_whenPaddedAndMixedCase_isValidAndStrippedButNotLowerCased() {
        RegistrationForm form = withEmail("  An@Example.COM ");

        assertThat(form.email()).isEqualTo("An@Example.COM");
        assertThat(errors(form)).isEmpty();
    }

    // ---- password length (BR-003-2) ----

    @Test
    void password_when7Chars_reportsLengthMessageOnly() {
        assertThat(errors(withPassword("abcdef1"))).containsExactly("password: " + LENGTH_MSG);
    }

    @Test
    void password_when8Chars_isValid() {
        assertThat(errors(withPassword("abcdefg1"))).isEmpty();
    }

    @Test
    void password_when64Chars_isValid() {
        assertThat(errors(withPassword("a".repeat(63) + "1"))).isEmpty();
    }

    @Test
    void password_when65Chars_reportsLengthMessageOnly() {
        assertThat(errors(withPassword("a".repeat(64) + "1"))).containsExactly("password: " + LENGTH_MSG);
    }

    @Test
    void password_whenNull_reportsLengthMessageOnly() {
        assertThat(errors(form("An", "an@example.com", null, null))).containsExactly("password: " + LENGTH_MSG);
    }

    @Test
    void password_whenShortAndNoDigit_reportsLengthMessageFirst() {
        assertThat(PasswordPolicy.violation("abc")).contains(LENGTH_MSG);
        assertThat(errors(withPassword("abc"))).containsExactly("password: " + LENGTH_MSG);
    }

    @Test
    void password_whenEmoji_countsAsOneCodePoint() {
        // 7 code points (6 letters + emoji) but String.length() is 8: still too short.
        String sevenCodePoints = "abcdef" + EMOJI;
        assertThat(sevenCodePoints.length()).isEqualTo(8);
        assertThat(PasswordPolicy.violation(sevenCodePoints)).contains(LENGTH_MSG);

        // 8 code points.
        assertThat(PasswordPolicy.violation("abcdef1" + EMOJI)).isEmpty();
    }

    // ---- password composition (BR-003-2) ----

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "abcdefgh"})
    void password_whenMissingLetterOrDigit_reportsMixMessage(String password) {
        assertThat(errors(withPassword(password))).containsExactly("password: " + MIX_MSG);
    }

    // ---- bcrypt 72-byte limit (BR-003-2) ----

    @Test
    void password_whenExactly72Bytes_isValid() {
        String password = "ệ".repeat(23) + "a12"; // 23 * 3 + 3 = 72 bytes, 26 chars
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        assertThat(errors(withPassword(password))).isEmpty();
    }

    @Test
    void password_whenOver72Bytes_reportsTooLongMessage() {
        String password = "ệ".repeat(25) + "1"; // 76 bytes, 26 chars
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(76);

        assertThat(errors(withPassword(password))).containsExactly("password: " + TOO_LONG_MSG);
    }

    @Test
    void password_when73Bytes_reportsTooLongMessage() {
        String password = "ệ".repeat(23) + "a123";
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(73);

        assertThat(PasswordPolicy.violation(password)).contains(TOO_LONG_MSG);
    }

    // ---- passwords are not stripped (BR-003-4) ----

    @Test
    void password_whenSurroundedBySpaces_isValidAndNotStripped() {
        RegistrationForm form = withPassword(" abc12345 ");

        assertThat(form.password()).isEqualTo(" abc12345 ");
        assertThat(form.confirmPassword()).isEqualTo(" abc12345 ");
        assertThat(errors(form)).isEmpty();
    }

    // ---- confirmPassword (AC-003-6) ----

    @Test
    void confirmPassword_whenDifferent_reportsMismatchOnConfirmPasswordField() {
        RegistrationForm form = form("An", "an@example.com", "abcdefg1", "abcdefg2");

        assertThat(errors(form)).containsExactly("confirmPassword: Mật khẩu nhập lại không khớp.");
    }

    @Test
    void confirmPassword_whenOnlyTrailingSpaceDiffers_reportsMismatch() {
        RegistrationForm form = form("An", "an@example.com", "abcdefg1", "abcdefg1 ");

        assertThat(errors(form)).containsExactly("confirmPassword: Mật khẩu nhập lại không khớp.");
    }

    @Test
    void passwordsMatch_whenNullAndEmptyMix_treatsNullAsEmpty() {
        PasswordsMatchValidator v = new PasswordsMatchValidator();

        assertThat(v.isValid(form("An", "a@b.com", null, null), null)).isTrue();
        assertThat(v.isValid(form("An", "a@b.com", "", ""), null)).isTrue();
        assertThat(v.isValid(form("An", "a@b.com", null, ""), null)).isTrue();
        assertThat(v.isValid(null, null)).isTrue();
    }

    @Test
    void passwordsMatch_whenOneNullAndOtherNotEmpty_isMismatch() {
        RegistrationForm form = form("An", "an@example.com", "abcdefg1", null);

        assertThat(errors(form)).contains("confirmPassword: Mật khẩu nhập lại không khớp.");
    }

    // ---- no password leak, no role (BR-003-4, AC-003-7) ----

    @Test
    void toString_doesNotContainPasswords() {
        RegistrationForm form = form("An", "an@example.com", "S3cretPass", "S3cretConfirm");

        assertThat(form.toString())
                .doesNotContain("S3cretPass")
                .doesNotContain("S3cretConfirm")
                .contains("an@example.com");
    }

    @Test
    void recordComponents_areExactlyFourAndHaveNoRole() {
        List<String> names = Arrays.stream(RegistrationForm.class.getRecordComponents())
                .map(c -> c.getName())
                .toList();

        assertThat(names).containsExactly("fullName", "email", "password", "confirmPassword");
    }

    @Test
    void validForm_hasNoViolations() {
        assertThat(errors(form("Nguyễn An", "an@example.com", "abcdefg1", "abcdefg1"))).isEmpty();
    }
}

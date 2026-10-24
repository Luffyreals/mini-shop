package com.minishop.order;

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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ràng buộc của {@link CheckoutForm} bằng Jakarta Validator thuần (AC-005-9, AC-005-10, BR-005-6).
 * Mỗi case assert đúng chữ và đúng ô.
 */
class CheckoutFormValidationTest {

    private static final String PHONE_MSG = "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.";
    private static final String PHONE_REQUIRED = "Vui lòng nhập số điện thoại.";
    private static final String NAME_REQUIRED = "Vui lòng nhập họ tên người nhận.";
    private static final String NAME_TOO_LONG = "Họ tên người nhận tối đa 100 ký tự.";
    private static final String ADDRESS_REQUIRED = "Vui lòng nhập địa chỉ giao hàng.";
    private static final String ADDRESS_TOO_LONG = "Địa chỉ giao hàng tối đa 500 ký tự.";
    private static final String NOTE_TOO_LONG = "Ghi chú tối đa 500 ký tự.";

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

    private static CheckoutForm form(String name, String phone, String address, String note) {
        return new CheckoutForm(name, phone, address, note);
    }

    private static CheckoutForm withPhone(String phone) {
        return form("An", phone, "12 Lê Lợi", null);
    }

    private static List<String> messages(CheckoutForm form, String property) {
        return validator.validateProperty(form, property).stream().map(ConstraintViolation::getMessage).toList();
    }

    private static Set<ConstraintViolation<CheckoutForm>> all(CheckoutForm form) {
        return validator.validate(form);
    }

    // ---------- phone ----------

    /** AC-005-10 */
    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "0912 345 678", "0912.345.678", "0912-345-678", " 0912345678 "})
    void phone_withValidFormat_hasNoViolationAndNormalizes(String phone) {
        CheckoutForm form = withPhone(phone);

        assertThat(all(form)).isEmpty();
        assertThat(form.normalizedPhone()).isEqualTo("0912345678");
    }

    /** AC-005-9 */
    @ParameterizedTest
    @ValueSource(strings = {"12345", "0abc456789", "09123456789", "1912345678", "091234567", "+840912345678",
            "+1 212 555 0100", "+8491234567"})
    void phone_withInvalidFormat_hasExactlyOnePhoneError(String phone) {
        CheckoutForm form = withPhone(phone);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "phone")).containsExactly(PHONE_MSG);
    }

    /** AC-007-16, BR-007-7: the international forms are accepted, stored as 0xxxxxxxxx, and shown back as typed. */
    @ParameterizedTest
    @ValueSource(strings = {"+84912345678", "+84 912 345 678", "+84-912-345-678", "84912345678"})
    void phone_withInternationalForm_isValidStoredWithLeadingZeroAndKeepsTypedText(String phone) {
        CheckoutForm form = withPhone(phone);

        assertThat(all(form)).isEmpty();
        assertThat(form.normalizedPhone()).isEqualTo("0912345678");
        assertThat(form.phone()).isEqualTo(phone);
    }

    /** AC-007-16: a no-break space typed inside the number is cleaned before the format check. */
    @Test
    void phone_withNoBreakSpaces_isValid() {
        CheckoutForm form = withPhone("+84 912 345 678");

        assertThat(all(form)).isEmpty();
        assertThat(form.normalizedPhone()).isEqualTo("0912345678");
    }

    /** AC-005-9: only ASCII digits count. */
    @Test
    void phone_withNonAsciiDigits_isRejected() {
        assertThat(messages(withPhone("0٩١٢345678"), "phone")).containsExactly(PHONE_MSG);
    }

    /** AC-005-9: a missing phone reports one error, not two. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void phone_whenBlank_hasExactlyOneRequiredError(String phone) {
        CheckoutForm form = withPhone(phone);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "phone")).containsExactly(PHONE_REQUIRED);
    }

    /** AC-005-9: a rejected form is shown back with what the customer typed, separators included. */
    @Test
    void phone_keepsTypedStringButStripped() {
        CheckoutForm form = withPhone("  0912.345.67 ");

        assertThat(form.phone()).isEqualTo("0912.345.67");
        assertThat(messages(form, "phone")).containsExactly(PHONE_MSG);
    }

    // ---------- name, address, note ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void receiverName_whenBlank_isRequired(String name) {
        assertThat(messages(form(name, "0912345678", "Addr", null), "receiverName"))
                .containsExactly(NAME_REQUIRED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void shippingAddress_whenBlank_isRequired(String address) {
        assertThat(messages(form("An", "0912345678", address, null), "shippingAddress"))
                .containsExactly(ADDRESS_REQUIRED);
    }

    @Test
    void receiverName_boundaryAtOneHundredCharacters() {
        assertThat(messages(form("a".repeat(100), "0912345678", "Addr", null), "receiverName")).isEmpty();
        assertThat(messages(form("a".repeat(101), "0912345678", "Addr", null), "receiverName"))
                .containsExactly(NAME_TOO_LONG);
    }

    @Test
    void shippingAddress_boundaryAtFiveHundredCharacters() {
        assertThat(messages(form("An", "0912345678", "a".repeat(500), null), "shippingAddress")).isEmpty();
        assertThat(messages(form("An", "0912345678", "a".repeat(501), null), "shippingAddress"))
                .containsExactly(ADDRESS_TOO_LONG);
    }

    @Test
    void note_boundaryAtFiveHundredCharacters() {
        assertThat(messages(form("An", "0912345678", "Addr", "a".repeat(500)), "note")).isEmpty();
        assertThat(messages(form("An", "0912345678", "Addr", "a".repeat(501)), "note"))
                .containsExactly(NOTE_TOO_LONG);
    }

    @Test
    void validForm_hasNoViolation() {
        assertThat(all(form("Nguyễn Văn An", "0912345678", "12 Lê Lợi\nQuận 1", "Giao buổi sáng"))).isEmpty();
    }

    // ---------- cleaning (BR-005-6) ----------

    @Test
    void fields_areStripped() {
        CheckoutForm form = form("  An  ", "  0912345678  ", "  12 Lê Lợi  ", "  ghi chú  ");

        assertThat(form.receiverName()).isEqualTo("An");
        assertThat(form.phone()).isEqualTo("0912345678");
        assertThat(form.shippingAddress()).isEqualTo("12 Lê Lợi");
        assertThat(form.note()).isEqualTo("ghi chú");
    }

    @Test
    void note_whenBlank_becomesNull() {
        assertThat(form("An", "0912345678", "Addr", "  ").note()).isNull();
        assertThat(form("An", "0912345678", "Addr", "").note()).isNull();
        assertThat(form("An", "0912345678", "Addr", null).note()).isNull();
    }

    @Test
    void nullFields_stayNull() {
        CheckoutForm form = form(null, null, null, null);

        assertThat(form.receiverName()).isNull();
        assertThat(form.phone()).isNull();
        assertThat(form.shippingAddress()).isNull();
        assertThat(form.normalizedPhone()).isNull();
    }

    @Test
    void controlCharacters_areRemovedFromEveryField() {
        CheckoutForm form = form("A\u0000n", "09\u000012345678", "12\u0000 Lê Lợi", "no\u0000te");

        assertThat(form.receiverName()).isEqualTo("An");
        assertThat(form.phone()).isEqualTo("0912345678");
        assertThat(form.shippingAddress()).isEqualTo("12 Lê Lợi");
        assertThat(form.note()).isEqualTo("note");
    }

    @Test
    void newlinesAndTabs_areKeptInAddressAndNoteButRemovedFromNameAndPhone() {
        CheckoutForm form = form("A\nn\tb", "09\n12\t345678", "12 Lê Lợi\nQuận 1\r\n\tTP", "dòng 1\ndòng 2");

        assertThat(form.receiverName()).isEqualTo("Anb");
        assertThat(form.phone()).isEqualTo("0912345678");
        assertThat(form.shippingAddress()).isEqualTo("12 Lê Lợi\nQuận 1\n\tTP");
        assertThat(form.note()).isEqualTo("dòng 1\ndòng 2");
    }

    @Test
    void nameMadeOnlyOfControlCharacters_isRequiredError() {
        assertThat(messages(form("\u0000", "0912345678", "Addr", null), "receiverName"))
                .containsExactly(NAME_REQUIRED);
    }

    // ---------- invisible characters (AC-007-14, 15, 19) ----------

    /** AC-007-14: name and address made only of invisible characters give one required error each. */
    @ParameterizedTest
    @ValueSource(strings = {"​", "‌", "‍", "⁠", "﻿", " ", "​  ﻿"})
    void nameAndAddress_whenOnlyInvisibleCharacters_haveOneRequiredErrorEach(String invisible) {
        CheckoutForm form = form(invisible, "0912345678", invisible, null);

        assertThat(all(form)).hasSize(2);
        assertThat(messages(form, "receiverName")).containsExactly(NAME_REQUIRED);
        assertThat(messages(form, "shippingAddress")).containsExactly(ADDRESS_REQUIRED);
    }

    /** AC-007-14: the optional note made only of invisible characters is "no note". */
    @ParameterizedTest
    @ValueSource(strings = {"​", "﻿", " ", "​\n  \t"})
    void note_whenOnlyInvisibleCharacters_becomesNull(String invisible) {
        CheckoutForm form = form("An", "0912345678", "Addr", invisible);

        assertThat(form.note()).isNull();
        assertThat(all(form)).isEmpty();
    }

    /** AC-007-15 */
    @Test
    void invisibleCharactersInTheMiddle_areDropped() {
        CheckoutForm form = form("Nguyễn​ Văn An", "0912345678", "12 Lê⁠ Lợi\nQuận﻿ 1", "ghi‍chú");

        assertThat(form.receiverName()).isEqualTo("Nguyễn Văn An");
        assertThat(form.shippingAddress()).isEqualTo("12 Lê Lợi\nQuận 1");
        assertThat(form.note()).isEqualTo("ghichú");
    }

    /** BR-007-6: the length limit counts the cleaned text, and a line break counts once. */
    @Test
    void shippingAddress_lengthCountsCrLfOnce() {
        String address = "a".repeat(248) + "\r\n" + "a".repeat(248) + "\r\nb";
        assertThat(address).hasSize(501);

        CheckoutForm form = form("An", "0912345678", address, null);

        assertThat(form.shippingAddress()).hasSize(499);
        assertThat(messages(form, "shippingAddress")).isEmpty();
    }

    // ---------- helpers on the record ----------

    @Test
    void prefill_hasEmptyAddressAndNoNote() {
        CheckoutForm form = CheckoutForm.prefill("An", "0912345678");

        assertThat(form.receiverName()).isEqualTo("An");
        assertThat(form.phone()).isEqualTo("0912345678");
        assertThat(form.shippingAddress()).isEmpty();
        assertThat(form.note()).isNull();
    }

    /** Spec §8: phone, address and note never reach a log line. */
    @Test
    void toString_doesNotContainAnyPersonalData() {
        String text = form("Nguyễn Văn An", "0912345678", "12 Lê Lợi", "Giao buổi sáng").toString();

        assertThat(text).doesNotContain("Nguyễn Văn An", "0912345678", "12 Lê Lợi", "Giao buổi sáng");
        assertThat(text).startsWith("CheckoutForm[");
    }

    @Test
    void phoneNumberValidatorNormalize_removesSpacesDotsAndHyphensOnly() {
        assertThat(PhoneNumberValidator.normalize("0912 345.678-9")).isEqualTo("09123456789");
        assertThat(PhoneNumberValidator.normalize("+84 912")).isEqualTo("+84912");
        assertThat(PhoneNumberValidator.normalize(null)).isNull();
    }
}

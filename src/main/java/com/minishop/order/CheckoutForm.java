package com.minishop.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Checkout input. It deliberately has no user id, status, total, payment method, price or id component, so
 * request parameters cannot set them (AC-005-18). Fields are stripped and cleaned of control characters
 * before validation. The phone keeps the separators the customer typed so a rejected form is shown back
 * unchanged; {@link #normalizedPhone()} is what gets stored.
 */
record CheckoutForm(
        @NotBlank(message = "Vui lòng nhập họ tên người nhận.")
        @Size(max = 100, message = "Họ tên người nhận tối đa 100 ký tự.")
        String receiverName,

        @NotBlank(message = "Vui lòng nhập số điện thoại.")
        @PhoneNumber
        String phone,

        @NotBlank(message = "Vui lòng nhập địa chỉ giao hàng.")
        @Size(max = 500, message = "Địa chỉ giao hàng tối đa 500 ký tự.")
        String shippingAddress,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        String note) {

    CheckoutForm {
        receiverName = clean(receiverName, false);
        phone = clean(phone, false);
        shippingAddress = clean(shippingAddress, true);
        note = clean(note, true);
        if (note != null && note.isEmpty()) {
            note = null;
        }
    }

    static CheckoutForm prefill(String fullName, String phone) {
        return new CheckoutForm(fullName, phone, "", "");
    }

    String normalizedPhone() {
        return PhoneNumberValidator.normalize(phone);
    }

    /** Strip, then drop control characters (PostgreSQL rejects 0x00); multi-line fields keep CR, LF and tab. */
    private static String clean(String value, boolean multiline) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        value.strip().codePoints()
                .filter(cp -> !Character.isISOControl(cp) || (multiline && (cp == '\n' || cp == '\r' || cp == '\t')))
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CheckoutForm[receiverName=***, phone=***, shippingAddress=***, note=***]";
    }
}

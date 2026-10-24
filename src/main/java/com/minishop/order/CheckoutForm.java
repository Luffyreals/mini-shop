package com.minishop.order;

import com.minishop.common.FormText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Checkout input. It deliberately has no user id, status, total, payment method, price or id component, so
 * request parameters cannot set them (AC-005-18). Fields are cleaned by {@link FormText} before validation
 * (invisible characters, control characters, special spaces, NFC, line breaks, strip; BR-007-6). The phone keeps
 * the separators the customer typed so a rejected form is shown back unchanged; {@link #normalizedPhone()} is
 * what gets stored. The phone accepts {@code 0xxxxxxxxx} and {@code +84}/{@code 84} forms (BR-007-7).
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
        receiverName = FormText.clean(receiverName, false);
        phone = FormText.clean(phone, false);
        shippingAddress = FormText.clean(shippingAddress, true);
        note = FormText.blankToNull(FormText.clean(note, true));
    }

    static CheckoutForm prefill(String fullName, String phone) {
        return new CheckoutForm(fullName, phone, "", "");
    }

    String normalizedPhone() {
        return PhoneNumberValidator.normalize(phone);
    }

    @Override
    public String toString() {
        return "CheckoutForm[receiverName=***, phone=***, shippingAddress=***, note=***]";
    }
}

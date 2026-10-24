package com.minishop.common;

import java.text.Normalizer;

/**
 * Làm sạch chữ người dùng nhập (BR-006-4), cùng quy tắc với {@code CheckoutForm} của GĐ5: chuẩn hóa NFC (để
 * {@code Đ} gõ kiểu tổ hợp vẫn bằng bản dựng sẵn), bỏ ký tự điều khiển (PostgreSQL từ chối byte 0x00), rồi cắt
 * khoảng trắng hai đầu. Ô nhiều dòng giữ xuống dòng và tab.
 */
public final class FormText {

    private FormText() {
    }

    /** @return {@code null} nếu {@code value} là {@code null}; còn lại là chuỗi đã làm sạch (có thể rỗng) */
    public static String clean(String value, boolean multiline) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(cp -> !Character.isISOControl(cp) || (multiline && (cp == '\n' || cp == '\r' || cp == '\t')))
                .forEach(sb::appendCodePoint);
        return sb.toString().strip();
    }

    /** @return {@code null} nếu {@code value} là {@code null} hoặc chỉ có khoảng trắng */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

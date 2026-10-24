package com.minishop.common;

import java.text.Normalizer;

/**
 * Làm sạch chữ người dùng nhập (BR-006-4, BR-007-6) cho mọi form có ô chữ: đăng ký, thanh toán, danh mục, sản phẩm.
 * Mật khẩu <b>không</b> đi qua đây (khoảng trắng là một phần của mật khẩu).
 * <p>
 * Các bước, theo đúng thứ tự:
 * <ol>
 *   <li>{@code null} giữ nguyên {@code null}.</li>
 *   <li>Ô nhiều dòng: {@code \r\n} và {@code \r} đơn lẻ đổi thành {@code \n}, để giới hạn {@code maxlength} của trình
 *       duyệt (đếm xuống dòng là 1) và giới hạn của server đếm giống nhau.</li>
 *   <li>Lọc từng code point:
 *     <ul>
 *       <li>dấu cách loại {@code Zs} ({@code U+00A0}, {@code U+202F}, {@code U+2007}, {@code U+2000}-{@code U+200A},
 *           {@code U+3000}...) đổi thành {@code U+0020};</li>
 *       <li>ký tự định dạng {@code Cf} ({@code U+200B}-{@code U+200D}, {@code U+2060}, {@code U+FEFF},
 *           {@code U+00AD}, ký tự điều khiển chiều chữ...) bị bỏ;</li>
 *       <li>ký tự điều khiển {@code Cc} bị bỏ (PostgreSQL từ chối byte 0x00); ô nhiều dòng giữ {@code \n} và
 *           {@code \t}.</li>
 *     </ul></li>
 *   <li>Chuẩn hóa NFC <b>sau</b> bước lọc, để dấu tiếng Việt bị một ký tự vô hình chen giữa vẫn ghép lại được và
 *       {@code Đ} gõ kiểu tổ hợp vẫn bằng bản dựng sẵn.</li>
 *   <li>{@link String#strip()} hai đầu.</li>
 * </ol>
 * Bước lọc phải chạy trước {@code strip()}: {@code String.strip()} không coi {@code U+00A0} là khoảng trắng, nên
 * một ô chỉ có NBSP hoặc ký tự vô hình sẽ không bị coi là trống nếu cắt trước.
 */
public final class FormText {

    private FormText() {
    }

    /** @return {@code null} nếu {@code value} là {@code null}; còn lại là chuỗi đã làm sạch (có thể rỗng) */
    public static String clean(String value, boolean multiline) {
        if (value == null) {
            return null;
        }
        String text = multiline ? value.replace("\r\n", "\n").replace('\r', '\n') : value;
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.SPACE_SEPARATOR) {
                sb.append(' ');
            } else if (type == Character.FORMAT) {
                // invisible: dropped
            } else if (type == Character.CONTROL) {
                if (multiline && (cp == '\n' || cp == '\t')) {
                    sb.appendCodePoint(cp);
                }
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return Normalizer.normalize(sb, Normalizer.Form.NFC).strip();
    }

    /** @return {@code null} nếu {@code value} là {@code null} hoặc chỉ có khoảng trắng */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

package com.minishop.desktop;

/** Loại thông báo ngắn ({@link Toasts}). Mỗi loại có biểu tượng và nhãn đọc riêng, không chỉ khác màu. */
public enum ToastKind {

    INFO("Thông báo", 3_000),
    SUCCESS("Thành công", 3_000),
    ERROR("Lỗi", 5_000);

    private final String spokenLabel;
    private final long displayMillis;

    ToastKind(String spokenLabel, long displayMillis) {
        this.spokenLabel = spokenLabel;
        this.displayMillis = displayMillis;
    }

    /** Nhãn đặt trước nội dung trong accessible text, để trình đọc màn hình nói rõ loại thông báo. */
    public String spokenLabel() {
        return spokenLabel;
    }

    /** Thời gian toast nằm yên trước khi tự ẩn (không tính hiệu ứng hiện/ẩn). */
    public long displayMillis() {
        return displayMillis;
    }
}

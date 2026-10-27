package com.minishop.desktop;

/**
 * Cổng mở một địa chỉ web trong trình duyệt mặc định. Màn hình chỉ biết interface này, nên test thay bằng bản giả
 * ghi lại địa chỉ mà không mở trình duyệt thật. Gọi trên luồng giao diện.
 */
public interface ExternalLinks {

    /**
     * Mở {@code httpsUrl} trong trình duyệt mặc định. Chỉ chấp nhận địa chỉ bắt đầu bằng {@code https://}; địa chỉ
     * khác bị từ chối (một dòng WARN, không kèm địa chỉ) và không có gì được mở. Không ném lỗi.
     */
    void open(String httpsUrl);
}

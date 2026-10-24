package com.minishop.order;

/**
 * Kết quả đổi trạng thái đơn của admin. Trả kết quả thay vì ném exception vì mọi nhánh "không cho phép"
 * chưa ghi gì, nên không có gì cần rollback; controller {@code switch} theo pattern.
 */
public sealed interface StatusChangeResult {

    /** Đã đổi sang trạng thái {@code statusLabel} (không phải hủy). */
    record Changed(String statusLabel) implements StatusChangeResult {
    }

    /** Đã hủy đơn và hoàn kho. */
    record Cancelled() implements StatusChangeResult {
    }

    /** Bước chuyển không hợp lệ (ví dụ đơn đã được khách hủy trong lúc admin đang xem). */
    record NotAllowed(String fromLabel, String toLabel) implements StatusChangeResult {
    }

    /** Trạng thái đích gửi lên không phải một trạng thái hợp lệ. */
    record InvalidStatus() implements StatusChangeResult {
    }
}

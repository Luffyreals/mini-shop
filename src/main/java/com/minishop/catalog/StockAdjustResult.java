package com.minishop.catalog;

/** Kết quả điều chỉnh tồn kho: controller {@code switch} theo pattern. Chỉ nhánh đầu có ghi dữ liệu. */
public sealed interface StockAdjustResult {

    /** Đã đổi; {@code stock} là tồn kho hiện tại, chắc chắn là kết quả của chính câu UPDATE vừa rồi. */
    record Adjusted(String name, int stock) implements StockAdjustResult {
    }

    /** Giảm quá số đang có; {@code requested} là lượng muốn giảm (dương), {@code current} là tồn kho hiện tại. */
    record NotEnough(String name, int requested, int current) implements StockAdjustResult {
    }

    /** Tồn kho sau điều chỉnh sẽ vượt giới hạn trên. */
    record WouldExceed(String name) implements StockAdjustResult {
    }
}

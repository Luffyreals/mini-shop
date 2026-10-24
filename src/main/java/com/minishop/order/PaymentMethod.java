package com.minishop.order;

enum PaymentMethod {
    COD;

    /** Nhãn hiển thị tiếng Việt. */
    String label() {
        return switch (this) {
            case COD -> "Thanh toán khi nhận hàng (COD)";
        };
    }
}

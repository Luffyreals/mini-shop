package com.minishop.desktop;

import javafx.scene.control.Label;
import javafx.scene.layout.Region;

/**
 * Nhãn trạng thái đơn hàng: viên thuốc chữ ink kèm chấm màu; chữ luôn là tên trạng thái nên trạng thái không chỉ
 * dựa vào màu.
 *
 * <p>Gom từ hai bản giống hệt nhau (D6, QĐ13): {@code order.OrderHistoryScreen.statusPill} (D4, danh sách đơn của
 * khách và chi tiết đơn) và {@code admin.AdminScreen.statusPill} (D5, màn quản trị). Chuyển nguyên văn, không đổi
 * kiểu: class {@code status-pill}, {@code status-{key}} và chấm {@code status-dot} nằm trong {@code theme.css}.
 * Gọi trên luồng giao diện.
 */
public final class StatusPill {

    private StatusPill() {
    }

    /**
     * @param statusLabel chữ hiện ra (ví dụ "Chờ xác nhận"); cũng là chữ cho trình đọc màn hình
     * @param statusKey   khóa chữ thường: {@code pending}, {@code confirmed}, {@code shipping},
     *                    {@code delivered}, {@code cancelled}
     */
    public static Label create(String statusLabel, String statusKey) {
        Label pill = new Label(statusLabel);
        pill.getStyleClass().addAll("status-pill", "status-" + statusKey);
        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        pill.setGraphic(dot);
        pill.setAccessibleText(statusLabel);
        return pill;
    }
}

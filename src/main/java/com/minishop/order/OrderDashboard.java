package com.minishop.order;

import java.math.BigDecimal;
import java.util.List;

/** Số liệu dashboard: đủ năm trạng thái theo thứ tự enum, doanh thu đơn đã giao, 5 đơn mới nhất. */
public record OrderDashboard(List<StatusCount> counts, BigDecimal deliveredRevenue, List<AdminOrderRow> latest) {
}

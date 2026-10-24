package com.minishop.order;

/** Một dòng kết quả của câu đếm đơn theo trạng thái (dashboard). */
record StatusTotal(OrderStatus status, long count) {
}

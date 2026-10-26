package com.minishop.desktop;

/**
 * Điểm đến của {@link Navigator}. Mỗi tính năng tự định nghĩa route của mình bằng một {@code record}
 * implements interface này (ví dụ {@code record ProductDetailRoute(long productId) implements Route}),
 * và một bean {@link ScreenFactory} dựng màn cho route đó. {@code desktop} không biết route cụ thể nào (QĐ4).
 * <p>
 * Route chỉ chứa dữ liệu thuần (id, từ khóa), không chứa node hay service.
 */
public interface Route {
}

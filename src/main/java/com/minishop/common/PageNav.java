package com.minishop.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Dữ liệu phân trang: cửa sổ tối đa 5 số trang quanh trang hiện tại (đánh số từ 1). Chỉ có số trang; cách vẽ
 * và việc chuyển trang do người gọi quyết định.
 */
public record PageNav(int current, int totalPages, List<Integer> pages) {

    private static final int WINDOW = 5;

    public static PageNav of(int current, int totalPages) {
        int total = Math.max(totalPages, 0);
        int cur = total == 0 ? Math.max(current, 1) : Math.min(Math.max(current, 1), total);

        int start = Math.max(1, cur - WINDOW / 2);
        int end = Math.min(total, start + WINDOW - 1);
        start = Math.max(1, end - WINDOW + 1);

        List<Integer> pages = new ArrayList<>();
        for (int n = start; n <= end; n++) {
            pages.add(n);
        }
        return new PageNav(cur, total, List.copyOf(pages));
    }

    public boolean hasPrevious() {
        return current > 1 && totalPages > 0;
    }

    public boolean hasNext() {
        return current < totalPages;
    }
}

package com.minishop.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Dữ liệu phân trang cho view: cửa sổ tối đa 5 số trang quanh trang hiện tại, cùng link trước/sau.
 * URL do người gọi dựng (để giữ nguyên bộ lọc); {@code previousUrl}/{@code nextUrl} là null ở đầu/cuối.
 */
public record PageNav(int current, int totalPages, List<PageLink> links, String previousUrl, String nextUrl) {

    private static final int WINDOW = 5;

    public record PageLink(int number, String url, boolean current) {
    }

    public static PageNav of(int current, int totalPages, IntFunction<String> urlForPage) {
        int total = Math.max(totalPages, 0);
        int cur = total == 0 ? Math.max(current, 1) : Math.min(Math.max(current, 1), total);

        int start = Math.max(1, cur - WINDOW / 2);
        int end = Math.min(total, start + WINDOW - 1);
        start = Math.max(1, end - WINDOW + 1);

        List<PageLink> links = new ArrayList<>();
        for (int n = start; n <= end; n++) {
            links.add(new PageLink(n, urlForPage.apply(n), n == cur));
        }
        String previous = cur > 1 && total > 0 ? urlForPage.apply(cur - 1) : null;
        String next = cur < total ? urlForPage.apply(cur + 1) : null;
        return new PageNav(cur, total, List.copyOf(links), previous, next);
    }
}

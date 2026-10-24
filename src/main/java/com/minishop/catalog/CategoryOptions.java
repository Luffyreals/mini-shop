package com.minishop.catalog;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Danh sách lựa chọn danh mục dùng chung cho storefront và admin. */
final class CategoryOptions {

    private CategoryOptions() {
    }

    /** Sắp bằng Collator tiếng Việt trong Java: collation của DB khác nhau giữa PostgreSQL (Alpine) và H2. */
    static List<CategoryOption> sortedByName(List<Category> categories) {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("vi")); // Collator không thread-safe
        return categories.stream()
                .sorted(Comparator.comparing(Category::getName, collator))
                .map(c -> new CategoryOption(c.getId(), c.getName()))
                .toList();
    }
}

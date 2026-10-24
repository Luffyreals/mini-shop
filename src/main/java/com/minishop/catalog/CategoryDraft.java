package com.minishop.catalog;

/**
 * Tên và mô tả danh mục đã được controller làm sạch. Constructor chỉ là chốt chặn (form đã kiểm trước):
 * tên không trống và tối đa 100 ký tự, mô tả tối đa 1000 ký tự, mô tả trống thành {@code null}.
 */
public record CategoryDraft(String name, String description) {

    static final int NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 1000;

    public CategoryDraft {
        if (name == null || name.isBlank() || name.length() > NAME_MAX) {
            throw new IllegalArgumentException("Category name must be 1.." + NAME_MAX + " characters");
        }
        if (description != null && description.isBlank()) {
            description = null;
        }
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("Category description must be at most " + DESCRIPTION_MAX + " characters");
        }
    }
}

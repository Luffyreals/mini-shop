package com.minishop.admin;

import com.minishop.catalog.CategoryDraft;
import com.minishop.catalog.CategoryEditView;
import com.minishop.common.FormText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Category input. Text is cleaned before validation (BR-006-4); a blank description becomes {@code null}.
 * {@link #toDraft()} is only called once validation has passed.
 */
record CategoryForm(
        @NotBlank(message = "Vui lòng nhập tên danh mục.")
        @Size(max = 100, message = "Tên danh mục tối đa 100 ký tự.")
        String name,

        @Size(max = 1000, message = "Mô tả tối đa 1000 ký tự.")
        String description) {

    CategoryForm {
        name = FormText.clean(name, false);
        description = FormText.blankToNull(FormText.clean(description, true));
    }

    static CategoryForm empty() {
        return new CategoryForm("", null);
    }

    static CategoryForm of(CategoryEditView view) {
        return new CategoryForm(view.name(), view.description());
    }

    CategoryDraft toDraft() {
        return new CategoryDraft(name, description);
    }
}

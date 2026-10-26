package com.minishop.admin;

import com.minishop.catalog.ProductDraft;
import com.minishop.catalog.ProductEditView;
import com.minishop.common.FormText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.file.Path;

/**
 * Product edit input. Besides what {@link NewProductForm} leaves out, it has no initial stock and no active flag:
 * stock changes only through the adjust action, visibility only through hide/show (AC-006-24). {@code image} is the
 * new file picked in the file chooser, or null to keep the current one.
 */
record EditProductForm(
        @NotBlank(message = "Vui lòng nhập tên sản phẩm.")
        @Size(max = 200, message = "Tên sản phẩm tối đa 200 ký tự.")
        String name,

        @NotBlank(message = "Vui lòng chọn danh mục.")
        @Pattern(regexp = "|[0-9]{1,18}", message = "Danh mục không tồn tại.")
        String categoryId,

        @NotBlank(message = "Vui lòng nhập giá.")
        @VndPrice
        String price,

        @Size(max = 4000, message = "Mô tả tối đa 4000 ký tự.")
        String description,

        Path image) {

    EditProductForm {
        name = FormText.clean(name, false);
        categoryId = FormText.clean(categoryId, false);
        price = FormText.clean(price, false);
        description = FormText.blankToNull(FormText.clean(description, true));
    }

    static EditProductForm of(ProductEditView view) {
        return new EditProductForm(view.name(), String.valueOf(view.categoryId()), view.price().toPlainString(),
                view.description(), null);
    }

    ProductDraft toDraft() {
        return new ProductDraft(name, Long.parseLong(categoryId), PriceInput.parse(price).orElseThrow(), description);
    }

    @Override
    public String toString() {
        return "EditProductForm[name=" + name + ", categoryId=" + categoryId + ", price=" + price + "]";
    }
}

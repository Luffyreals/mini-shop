package com.minishop.admin;

import com.minishop.catalog.ProductDraft;
import com.minishop.common.FormText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/**
 * New product input. It deliberately has no id, stock (other than the initial one), image path or timestamp
 * component, so request parameters cannot set them (AC-006-24). All fields stay raw strings so a rejected form
 * is shown back unchanged; {@link #toDraft()} and {@link #initialStockValue()} are only called after validation.
 * The image file is never shown back (browsers do not allow it).
 */
record NewProductForm(
        @NotBlank(message = "Vui lòng nhập tên sản phẩm.")
        @Size(max = 200, message = "Tên sản phẩm tối đa 200 ký tự.")
        String name,

        @NotBlank(message = "Vui lòng chọn danh mục.")
        @Pattern(regexp = "|[0-9]{1,18}", message = "Danh mục không tồn tại.")
        String categoryId,

        @NotBlank(message = "Vui lòng nhập giá.")
        @VndPrice
        String price,

        @NotBlank(message = "Vui lòng nhập tồn kho ban đầu.")
        @WholeNumber(min = 0, max = 100_000, message = "Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.")
        String initialStock,

        @Size(max = 4000, message = "Mô tả tối đa 4000 ký tự.")
        String description,

        Boolean active,

        MultipartFile image) {

    NewProductForm {
        name = FormText.clean(name, false);
        categoryId = FormText.clean(categoryId, false);
        price = FormText.clean(price, false);
        initialStock = FormText.clean(initialStock, false);
        description = FormText.blankToNull(FormText.clean(description, true));
    }

    /** The blank form of {@code GET /admin/products/new}: "Đang bán" is ticked by default (AC-006-18). */
    static NewProductForm empty() {
        return new NewProductForm("", "", "", "", null, Boolean.TRUE, null);
    }

    ProductDraft toDraft() {
        return new ProductDraft(name, Long.parseLong(categoryId), PriceInput.parse(price).orElseThrow(), description);
    }

    int initialStockValue() {
        return Integer.parseInt(initialStock);
    }

    /** A checkbox that is not ticked sends nothing, so null means false. */
    boolean isActiveChecked() {
        return Boolean.TRUE.equals(active);
    }

    @Override
    public String toString() {
        return "NewProductForm[name=" + name + ", categoryId=" + categoryId + ", price=" + price
                + ", initialStock=" + initialStock + ", active=" + active + "]";
    }
}

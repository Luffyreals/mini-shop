package com.minishop.admin;

import com.minishop.catalog.ProductDraft;
import com.minishop.catalog.ProductEditView;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NewProductForm} and {@link EditProductForm} with a plain Jakarta Validator (AC-006-20, 21, 24;
 * BR-006-4..6): exact texts, one error per field, no component an attacker could use to set id, stock, image
 * path or timestamps.
 */
class ProductFormValidationTest {

    private static final String NAME_REQUIRED = "Vui lòng nhập tên sản phẩm.";
    private static final String NAME_TOO_LONG = "Tên sản phẩm tối đa 200 ký tự.";
    private static final String CATEGORY_REQUIRED = "Vui lòng chọn danh mục.";
    private static final String CATEGORY_MISSING = "Danh mục không tồn tại.";
    private static final String PRICE_REQUIRED = "Vui lòng nhập giá.";
    private static final String PRICE_INVALID = "Giá phải là số nguyên từ 1 đến 999.999.999 ₫.";
    private static final String STOCK_REQUIRED = "Vui lòng nhập tồn kho ban đầu.";
    private static final String STOCK_INVALID = "Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.";
    private static final String DESCRIPTION_TOO_LONG = "Mô tả tối đa 4000 ký tự.";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static NewProductForm form(String name, String categoryId, String price, String stock,
                                       String description) {
        return new NewProductForm(name, categoryId, price, stock, description, true, null);
    }

    private static NewProductForm valid() {
        return form("Bình nước 1L", "2", "1250000", "30", "Bình giữ nhiệt");
    }

    private static NewProductForm withName(String name) {
        return form(name, "2", "1250000", "30", null);
    }

    private static NewProductForm withCategory(String categoryId) {
        return form("Bình nước", categoryId, "1250000", "30", null);
    }

    private static NewProductForm withPrice(String price) {
        return form("Bình nước", "2", price, "30", null);
    }

    private static NewProductForm withStock(String stock) {
        return form("Bình nước", "2", "1250000", stock, null);
    }

    private static <T> List<String> messages(T form, String property) {
        return validator.validateProperty(form, property).stream().map(ConstraintViolation::getMessage).toList();
    }

    private static <T> Set<ConstraintViolation<T>> all(T form) {
        return validator.validate(form);
    }

    // ---------- a valid form ----------

    @Test
    void validForm_hasNoViolationAndBuildsTheDraft() {
        NewProductForm form = valid();

        assertThat(all(form)).isEmpty();
        assertThat(form.toDraft()).isEqualTo(
                new ProductDraft("Bình nước 1L", 2, new BigDecimal("1250000"), "Bình giữ nhiệt"));
        assertThat(form.initialStockValue()).isEqualTo(30);
    }

    // ---------- name ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\u0000", " \t "})
    void name_whenBlank_hasExactlyOneRequiredError(String name) {
        NewProductForm form = withName(name);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "name")).containsExactly(NAME_REQUIRED);
    }

    @Test
    void name_boundaryAtTwoHundredCharacters() {
        assertThat(all(withName("a".repeat(200)))).isEmpty();

        NewProductForm tooLong = withName("a".repeat(201));
        assertThat(all(tooLong)).hasSize(1);
        assertThat(messages(tooLong, "name")).containsExactly(NAME_TOO_LONG);
    }

    @Test
    void name_isStrippedAndControlCharactersAreDropped() {
        assertThat(withName("  Bình\u0000 nước\u0007  ").name()).isEqualTo("Bình nước");
    }

    /** AC-007-14: a name made only of invisible characters is "missing", with one error, on both forms. */
    @ParameterizedTest
    @ValueSource(strings = {"​", "‌", "‍", "⁠", "﻿", " ", "​  ﻿"})
    void name_whenOnlyInvisibleCharacters_hasExactlyOneRequiredError(String name) {
        NewProductForm form = withName(name);
        EditProductForm edit = new EditProductForm(name, "2", "1250000", null, null);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "name")).containsExactly(NAME_REQUIRED);
        assertThat(all(edit)).hasSize(1);
        assertThat(messages(edit, "name")).containsExactly(NAME_REQUIRED);
    }

    /** AC-007-15 */
    @Test
    void name_dropsInvisibleCharactersInTheMiddle() {
        NewProductForm form = withName("Áo​ thun");

        assertThat(form.name()).isEqualTo("Áo thun");
        assertThat(form.toDraft().name()).isEqualTo("Áo thun");
        assertThat(all(form)).isEmpty();
        assertThat(new EditProductForm("Áo​ thun", "2", "1000", null, null).name()).isEqualTo("Áo thun");
    }

    // ---------- description ----------

    /** BR-007-6: a description made only of invisible characters is "no description". */
    @Test
    void description_whenOnlyInvisibleCharacters_becomesNull() {
        assertThat(form("A", "2", "1000", "1", "​ ﻿\n").description()).isNull();
    }

    /** BR-007-6: {@code \r\n} from a browser becomes {@code \n}, so the length limit counts it once. */
    @Test
    void description_convertsCrLfToLf() {
        assertThat(form("A", "2", "1000", "1", "dòng 1\r\ndòng 2\rdòng 3").description())
                .isEqualTo("dòng 1\ndòng 2\ndòng 3");
    }

    @Test
    void description_boundaryAtFourThousandCharacters() {
        assertThat(all(form("A", "2", "1000", "1", "d".repeat(4000)))).isEmpty();

        NewProductForm tooLong = form("A", "2", "1000", "1", "d".repeat(4001));
        assertThat(all(tooLong)).hasSize(1);
        assertThat(messages(tooLong, "description")).containsExactly(DESCRIPTION_TOO_LONG);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t"})
    void description_whenBlank_becomesNull(String description) {
        assertThat(form("A", "2", "1000", "1", description).description()).isNull();
    }

    @Test
    void description_keepsLineBreaks() {
        assertThat(form("A", "2", "1000", "1", " dòng 1\ndòng 2\u0000 ").description())
                .isEqualTo("dòng 1\ndòng 2");
    }

    // ---------- category ----------

    /** AC-006-21: a blank category is exactly one error: "choose", not "does not exist". */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void categoryId_whenBlank_hasOnlyTheChooseError(String categoryId) {
        NewProductForm form = withCategory(categoryId);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "categoryId")).containsExactly(CATEGORY_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "1.5", "7a", "1234567890123456789", "٣", "0x10"})
    void categoryId_whenNotANumber_isDoesNotExist(String categoryId) {
        NewProductForm form = withCategory(categoryId);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "categoryId")).containsExactly(CATEGORY_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", " 7 ", "999999999999999999"})
    void categoryId_whenNumeric_passesValidationAndParses(String categoryId) {
        NewProductForm form = withCategory(categoryId);

        assertThat(all(form)).isEmpty();
        assertThat(form.toDraft().categoryId()).isEqualTo(Long.parseLong(categoryId.strip()));
    }

    // ---------- price ----------

    /** AC-006-20: three accepted shapes, all stored as 1250000 with scale 0. */
    @ParameterizedTest
    @ValueSource(strings = {"1250000", "1.250.000", "1 250 000", " 1.250.000 "})
    void price_validShapes_giveTheSameWholeNumber(String price) {
        NewProductForm form = withPrice(price);

        assertThat(all(form)).isEmpty();
        BigDecimal parsed = form.toDraft().price();
        assertThat(parsed).isEqualTo(new BigDecimal("1250000"));
        assertThat(parsed.scale()).isZero();
    }

    /** AC-007-17, BR-007-8: pasted no-break, narrow no-break and figure spaces give the same price. */
    @ParameterizedTest
    @ValueSource(strings = {"1 250 000", "1 250 000", "1 250 000", " 1250000 "})
    void price_withSpecialSpaces_givesTheSameWholeNumber(String price) {
        NewProductForm form = withPrice(price);
        EditProductForm edit = new EditProductForm("Bình nước", "2", price, null, null);

        assertThat(all(form)).isEmpty();
        assertThat(form.toDraft().price()).isEqualTo(new BigDecimal("1250000"));
        assertThat(form.toDraft().price().scale()).isZero();
        assertThat(all(edit)).isEmpty();
        assertThat(edit.toDraft().price()).isEqualTo(new BigDecimal("1250000"));
    }

    /** A price made only of invisible characters is "missing", not "invalid". */
    @Test
    void price_whenOnlyInvisibleCharacters_hasOnlyTheRequiredError() {
        NewProductForm form = withPrice("​ ");

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "price")).containsExactly(PRICE_REQUIRED);
    }

    /** BR-007-8: mixed separators and a leading zero stay invalid with special spaces too. */
    @ParameterizedTest
    @ValueSource(strings = {"1 250.000", "1.250 000", "01 250"})
    void price_withSpecialSpacesButBadGrouping_hasExactlyOnePriceError(String price) {
        NewProductForm form = withPrice(price);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "price")).containsExactly(PRICE_INVALID);
    }

    @Test
    void price_boundaries() {
        assertThat(all(withPrice("1"))).isEmpty();
        assertThat(all(withPrice("999999999"))).isEmpty();
        assertThat(all(withPrice("999.999.999"))).isEmpty();
        assertThat(withPrice("999.999.999").toDraft().price()).isEqualTo(new BigDecimal("999999999"));
    }

    /** AC-006-20: a missing price is one error, not two. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void price_whenBlank_hasOnlyTheRequiredError(String price) {
        NewProductForm form = withPrice(price);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "price")).containsExactly(PRICE_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "-5", "12,5", "1.5", "abc", "1.000.000.000", "1000000000", "1.250 000",
            "12.50.000", "1250.000", "1 25 000", "1.2500.000", "1,250,000", "1e6", "+5", "٣٤", "1 250  000",
            "1.250.000.", ".500", "500."})
    void price_invalid_hasExactlyOnePriceError(String price) {
        NewProductForm form = withPrice(price);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "price")).containsExactly(PRICE_INVALID);
    }

    // ---------- initial stock ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void initialStock_whenBlank_hasOnlyTheRequiredError(String stock) {
        NewProductForm form = withStock(stock);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "initialStock")).containsExactly(STOCK_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "100001", "abc", "1000000", "+5", "1 000", "1e3", "٣"})
    void initialStock_invalid_hasExactlyOneStockError(String stock) {
        NewProductForm form = withStock(stock);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "initialStock")).containsExactly(STOCK_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "100000"})
    void initialStock_inRange_isValid(String stock) {
        NewProductForm form = withStock(stock);

        assertThat(all(form)).isEmpty();
        assertThat(form.initialStockValue()).isEqualTo(Integer.parseInt(stock));
    }

    // ---------- active checkbox ----------

    /** An unticked checkbox sends nothing: null means false. */
    @Test
    void active_whenNull_isNotChecked() {
        assertThat(new NewProductForm("A", "2", "1000", "1", null, null, null).isActiveChecked()).isFalse();
        assertThat(new NewProductForm("A", "2", "1000", "1", null, false, null).isActiveChecked()).isFalse();
        assertThat(new NewProductForm("A", "2", "1000", "1", null, true, null).isActiveChecked()).isTrue();
    }

    /** AC-006-18: the blank "new product" form has "Đang bán" ticked and all text fields empty. */
    @Test
    void empty_hasActiveTickedAndBlankFields() {
        NewProductForm empty = NewProductForm.empty();

        assertThat(empty.active()).isTrue();
        assertThat(empty.isActiveChecked()).isTrue();
        assertThat(empty.name()).isEmpty();
        assertThat(empty.categoryId()).isEmpty();
        assertThat(empty.price()).isEmpty();
        assertThat(empty.initialStock()).isEmpty();
        assertThat(empty.description()).isNull();
        assertThat(empty.image()).isNull();
    }

    // ---------- every field wrong at once ----------

    /** AC-006-21: one error under each wrong field, never more. */
    @Test
    void everyFieldInvalid_givesExactlyOneErrorPerField() {
        NewProductForm form = new NewProductForm("", "", "", "", "d".repeat(4001), true, null);

        assertThat(all(form)).hasSize(5);
        assertThat(messages(form, "name")).containsExactly(NAME_REQUIRED);
        assertThat(messages(form, "categoryId")).containsExactly(CATEGORY_REQUIRED);
        assertThat(messages(form, "price")).containsExactly(PRICE_REQUIRED);
        assertThat(messages(form, "initialStock")).containsExactly(STOCK_REQUIRED);
        assertThat(messages(form, "description")).containsExactly(DESCRIPTION_TOO_LONG);
    }

    // ---------- logging ----------

    /** ADR-0014: the picked file's path is a local path of the admin and never ends up in a log line. */
    @Test
    void toString_doesNotPrintTheImagePath() {
        Path picked = Path.of("C:", "Users", "an", "Pictures", "my-private-photo.jpg");

        NewProductForm created = new NewProductForm("Bình nước", "2", "1250000", "30", null, true, picked);
        EditProductForm edited = new EditProductForm("Bình nước", "2", "1250000", null, picked);

        assertThat(created.toString()).doesNotContain("my-private-photo", "Pictures", "Users", "image=")
                .contains("Bình nước");
        assertThat(edited.toString()).doesNotContain("my-private-photo", "Pictures", "Users", "image=")
                .contains("Bình nước");
    }

    // ---------- mass assignment ----------

    /** AC-006-24: nothing to bind id, stock, image path or timestamps to. */
    @Test
    void newProductForm_hasOnlyTheDocumentedComponents() {
        assertThat(componentNames(NewProductForm.class))
                .containsExactly("name", "categoryId", "price", "initialStock", "description", "active", "image")
                .doesNotContain("id", "stock", "imagePath", "createdAt", "updatedAt");
    }

    /** AC-006-24: the edit form has neither stock, nor active, nor id. */
    @Test
    void editProductForm_hasOnlyTheDocumentedComponents() {
        assertThat(componentNames(EditProductForm.class))
                .containsExactly("name", "categoryId", "price", "description", "image")
                .doesNotContain("id", "stock", "initialStock", "active", "imagePath", "createdAt", "updatedAt");
    }

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).toList();
    }

    // ---------- edit form ----------

    @Test
    void editForm_validatesTheSameFieldsWithTheSameTexts() {
        EditProductForm form = new EditProductForm("", "", "", "d".repeat(4001), null);

        assertThat(all(form)).hasSize(4);
        assertThat(messages(form, "name")).containsExactly(NAME_REQUIRED);
        assertThat(messages(form, "categoryId")).containsExactly(CATEGORY_REQUIRED);
        assertThat(messages(form, "price")).containsExactly(PRICE_REQUIRED);
        assertThat(messages(form, "description")).containsExactly(DESCRIPTION_TOO_LONG);
    }

    @Test
    void editForm_invalidValuesGiveTheSpecTexts() {
        EditProductForm form = new EditProductForm("a".repeat(201), "abc", "12,5", null, null);

        assertThat(messages(form, "name")).containsExactly(NAME_TOO_LONG);
        assertThat(messages(form, "categoryId")).containsExactly(CATEGORY_MISSING);
        assertThat(messages(form, "price")).containsExactly(PRICE_INVALID);
    }

    @Test
    void editForm_of_copiesTheViewAndRendersThePriceAsPlainDigits() {
        EditProductForm form = EditProductForm.of(new ProductEditView(7, "Áo thun", 2, new BigDecimal("1250000"),
                "Mô tả", 10, false, "/images/products/ao-thun.jpg"));

        assertThat(form.name()).isEqualTo("Áo thun");
        assertThat(form.categoryId()).isEqualTo("2");
        assertThat(form.price()).isEqualTo("1250000");
        assertThat(form.description()).isEqualTo("Mô tả");
        assertThat(form.image()).isNull();
        assertThat(all(form)).isEmpty();
        assertThat(form.toDraft().price()).isEqualTo(new BigDecimal("1250000"));
    }
}

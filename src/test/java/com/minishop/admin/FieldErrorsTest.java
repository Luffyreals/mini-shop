package com.minishop.admin;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FieldErrors#firstPerField} (QĐ13 of the D5 plan) with a real Jakarta Validator: one message per field, keys in
 * the order of the record components, {@code @NotBlank} before every other constraint, then the natural message order.
 */
class FieldErrorsTest {

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

    /** Components are declared in a deliberate non-alphabetical order: gamma, alpha, beta. */
    record Sample(
            @NotBlank(message = "gamma required") String gamma,
            @NotBlank(message = "alpha required") String alpha,
            @NotBlank(message = "beta required") String beta) {
    }

    /** {@code name} breaks three constraints at once when it is empty; the NotBlank message sorts last on purpose. */
    record Layered(
            @Size(min = 3, message = "A too short")
            @NotBlank(message = "Z required")
            @Pattern(regexp = "[a-z]+", message = "M letters only")
            String name,

            @Size(min = 5, message = "Y too short")
            @Pattern(regexp = "[a-z]+", message = "B letters only")
            String code) {
    }

    /** Two checks that are not record components (class-level style): the property paths are "zeta" and "alpha". */
    record WithExtras(
            @NotBlank(message = "name required") String name,
            boolean zetaOk,
            boolean alphaOk) {

        @AssertTrue(message = "zeta must hold")
        boolean isZeta() {
            return zetaOk;
        }

        @AssertTrue(message = "alpha must hold")
        boolean isAlpha() {
            return alphaOk;
        }
    }

    record Inner(@NotBlank(message = "inner required") String value) {
    }

    record Outer(@Valid Inner inner, @NotBlank(message = "title required") String title) {
    }

    private static <T> Map<String, String> errors(T bean) {
        Set<ConstraintViolation<T>> violations = validator.validate(bean);
        return FieldErrors.firstPerField(violations);
    }

    @Test
    void firstPerField_withNoViolations_isEmpty() {
        assertThat(errors(new Sample("g", "a", "b"))).isEmpty();
        assertThat(FieldErrors.firstPerField(Set.<ConstraintViolation<Sample>>of())).isEmpty();
    }

    @Test
    void firstPerField_keysFollowTheRecordComponentsNotTheAlphabet() {
        Map<String, String> errors = errors(new Sample("", "", ""));

        assertThat(errors).containsExactly(
                Map.entry("gamma", "gamma required"),
                Map.entry("alpha", "alpha required"),
                Map.entry("beta", "beta required"));
    }

    @Test
    void firstPerField_aFieldWithoutErrorIsNotInTheMap_soTheFirstKeyIsTheFieldToFocus() {
        Map<String, String> errors = errors(new Sample("ok", "", "  "));

        assertThat(errors.keySet()).containsExactly("alpha", "beta");
        assertThat(errors.keySet().iterator().next()).isEqualTo("alpha");
    }

    @Test
    void firstPerField_notBlankWinsOverEveryOtherConstraintOfTheSameField() {
        Map<String, String> errors = errors(new Layered("", "ab"));

        assertThat(errors).containsEntry("name", "Z required");
    }

    @Test
    void firstPerField_withoutNotBlank_theMessageThatSortsFirstWins() {
        // "code" breaks two constraints and has no NotBlank: "B letters only" sorts before "Y too short"
        Map<String, String> errors = errors(new Layered("abc", "12"));

        assertThat(errors).containsOnlyKeys("code").containsEntry("code", "B letters only");
    }

    @Test
    void firstPerField_oneMessagePerFieldEvenWhenManyConstraintsFail() {
        Map<String, String> errors = errors(new Layered("", "1"));

        assertThat(errors).hasSize(2);
        assertThat(errors.keySet()).containsExactly("name", "code");
        assertThat(errors.get("name")).isEqualTo("Z required");
        assertThat(errors.get("code")).isEqualTo("B letters only");
    }

    @Test
    void firstPerField_keysThatAreNotRecordComponentsComeLastSortedByName() {
        Map<String, String> errors = errors(new WithExtras("", false, false));

        assertThat(errors.keySet()).containsExactly("name", "alpha", "zeta");
        assertThat(errors).containsEntry("alpha", "alpha must hold").containsEntry("zeta", "zeta must hold");
    }

    @Test
    void firstPerField_nestedPropertyPathIsAKeyAfterTheComponents() {
        Map<String, String> errors = errors(new Outer(new Inner(""), ""));

        assertThat(errors.keySet()).containsExactly("title", "inner.value");
    }

    @Test
    void firstPerField_resultIsStableForTheSameInput() {
        Layered form = new Layered("", "1");

        assertThat(errors(form)).isEqualTo(errors(form)).containsExactlyEntriesOf(errors(form));
    }

    // ---------- the real forms ----------

    @Test
    void firstPerField_categoryForm_nameBeforeDescription() {
        Map<String, String> errors = errors(new CategoryForm("", "x".repeat(1001)));

        assertThat(errors).containsExactly(
                Map.entry("name", "Vui lòng nhập tên danh mục."),
                Map.entry("description", "Mô tả tối đa 1000 ký tự."));
    }

    @Test
    void firstPerField_blankNewProductForm_oneNotBlankMessagePerFieldInFormOrder() {
        Map<String, String> errors = errors(NewProductForm.empty());

        assertThat(errors).containsExactly(
                Map.entry("name", "Vui lòng nhập tên sản phẩm."),
                Map.entry("categoryId", "Vui lòng chọn danh mục."),
                Map.entry("price", "Vui lòng nhập giá."),
                Map.entry("initialStock", "Vui lòng nhập tồn kho ban đầu."));
    }
}

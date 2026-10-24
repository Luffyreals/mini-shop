package com.minishop.admin;

import com.minishop.catalog.CategoryDraft;
import com.minishop.catalog.CategoryEditView;
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

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link CategoryForm} with a plain Jakarta Validator (AC-006-11, 12; BR-006-2, 4): exact texts, one error per field. */
class CategoryFormValidationTest {

    private static final String NAME_REQUIRED = "Vui lòng nhập tên danh mục.";
    private static final String NAME_TOO_LONG = "Tên danh mục tối đa 100 ký tự.";
    private static final String DESCRIPTION_TOO_LONG = "Mô tả tối đa 1000 ký tự.";

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

    private static List<String> messages(CategoryForm form, String property) {
        return validator.validateProperty(form, property).stream().map(ConstraintViolation::getMessage).toList();
    }

    private static Set<ConstraintViolation<CategoryForm>> all(CategoryForm form) {
        return validator.validate(form);
    }

    // ---------- name ----------

    /** AC-006-12: a missing name is one error, not two. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t \n", "\u0000", "\u0000  \u0007"})
    void name_whenBlank_hasExactlyOneRequiredError(String name) {
        CategoryForm form = new CategoryForm(name, null);

        assertThat(all(form)).hasSize(1);
        assertThat(messages(form, "name")).containsExactly(NAME_REQUIRED);
    }

    @Test
    void name_boundaryAtOneHundredCharacters() {
        assertThat(all(new CategoryForm("a".repeat(100), null))).isEmpty();

        CategoryForm tooLong = new CategoryForm("a".repeat(101), null);
        assertThat(all(tooLong)).hasSize(1);
        assertThat(messages(tooLong, "name")).containsExactly(NAME_TOO_LONG);
    }

    /** BR-006-4: the limit counts the cleaned text, so padding does not push a valid name over it. */
    @Test
    void name_lengthIsCountedAfterStripping() {
        assertThat(all(new CategoryForm("  " + "a".repeat(100) + "  ", null))).isEmpty();
    }

    /** AC-006-11 */
    @Test
    void name_isStripped() {
        CategoryForm form = new CategoryForm("  Đồ chơi  ", "");

        assertThat(form.name()).isEqualTo("Đồ chơi");
        assertThat(all(form)).isEmpty();
        assertThat(form.toDraft().name()).isEqualTo("Đồ chơi");
    }

    /** BR-006-2: a decomposed (NFD) "Đồ chơi" becomes the precomposed text so duplicate checks match. */
    @Test
    void name_isNormalizedToNfc() {
        String nfd = Normalizer.normalize("Áo thun Đồ chơi", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("Áo thun Đồ chơi");

        CategoryForm form = new CategoryForm(nfd, null);

        assertThat(form.name()).isEqualTo("Áo thun Đồ chơi");
        assertThat(Normalizer.isNormalized(form.name(), Normalizer.Form.NFC)).isTrue();
    }

    /** PostgreSQL rejects 0x00; control characters never reach the service. */
    @Test
    void name_dropsControlCharacters() {
        CategoryForm form = new CategoryForm("Đồ\u0000 chơi\u0007", null);

        assertThat(form.name()).isEqualTo("Đồ chơi");
    }

    @Test
    void name_newlineInsideASingleLineFieldIsDropped() {
        assertThat(new CategoryForm("Đồ\nchơi", null).name()).isEqualTo("Đồchơi");
    }

    // ---------- description ----------

    @Test
    void description_boundaryAtOneThousandCharacters() {
        assertThat(all(new CategoryForm("Áo", "d".repeat(1000)))).isEmpty();

        CategoryForm tooLong = new CategoryForm("Áo", "d".repeat(1001));
        assertThat(all(tooLong)).hasSize(1);
        assertThat(messages(tooLong, "description")).containsExactly(DESCRIPTION_TOO_LONG);
    }

    /** AC-006-11: a blank description is stored as null. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", " \n \t ", "\u0000"})
    void description_whenBlank_becomesNull(String description) {
        CategoryForm form = new CategoryForm("Áo", description);

        assertThat(form.description()).isNull();
        assertThat(form.toDraft().description()).isNull();
        assertThat(all(form)).isEmpty();
    }

    /** BR-006-4: a multi-line field keeps its line breaks. */
    @Test
    void description_keepsLineBreaksButStripsTheEnds() {
        CategoryForm form = new CategoryForm("Áo", "  dòng 1\r\ndòng 2\n\tdòng 3  ");

        assertThat(form.description()).isEqualTo("dòng 1\r\ndòng 2\n\tdòng 3");
    }

    @Test
    void description_dropsOtherControlCharacters() {
        assertThat(new CategoryForm("Áo", "a\u0000b\u0007c").description()).isEqualTo("abc");
    }

    // ---------- combined / factories ----------

    /** AC-006-12: two bad fields give one error each, on their own field. */
    @Test
    void bothFieldsInvalid_giveOneErrorEach() {
        CategoryForm form = new CategoryForm("", "d".repeat(1001));

        assertThat(all(form)).hasSize(2);
        assertThat(messages(form, "name")).containsExactly(NAME_REQUIRED);
        assertThat(messages(form, "description")).containsExactly(DESCRIPTION_TOO_LONG);
    }

    @Test
    void toDraft_carriesTheCleanedValues() {
        assertThat(new CategoryForm(" Đồ chơi ", " Cho bé ").toDraft()).isEqualTo(new CategoryDraft("Đồ chơi", "Cho bé"));
    }

    @Test
    void empty_hasBlankNameAndNoDescription() {
        CategoryForm form = CategoryForm.empty();

        assertThat(form.name()).isEmpty();
        assertThat(form.description()).isNull();
    }

    @Test
    void of_copiesTheEditView() {
        CategoryForm form = CategoryForm.of(new CategoryEditView(4, "Đồ chơi", "Cho bé"));

        assertThat(form.name()).isEqualTo("Đồ chơi");
        assertThat(form.description()).isEqualTo("Cho bé");
    }
}

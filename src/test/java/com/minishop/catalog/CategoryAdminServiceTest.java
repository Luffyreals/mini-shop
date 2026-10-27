package com.minishop.catalog;

import com.minishop.common.AdminAccess;
import com.minishop.common.NotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Category administration rules (AC-006-10, 11, 13..16; BR-006-2, 3). Repositories are mocks; the real race on
 * {@code uq_categories_name} and the delete-versus-add lock need PostgreSQL (TW5).
 */
@ExtendWith(MockitoExtension.class)
class CategoryAdminServiceTest {

    private static final long ADMIN_ID = 1L;

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    AdminAccess adminAccess;

    CategoryAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(adminAccess.requireAdmin()).thenReturn(ADMIN_ID);
        service = new CategoryAdminService(categoryRepository, productRepository, adminAccess);
    }

    private static Category category(long id, String name, String description) {
        Category c = new Category(name, description);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violates " + constraintName, new SQLException("boom"),
                        constraintName));
    }

    // ---------- list / get / options ----------

    /** AC-006-10 */
    @Test
    void list_sortsByVietnameseCollationAndKeepsProductCount() {
        when(categoryRepository.findAllWithProductCount()).thenReturn(List.of(
                new CategoryRow(4, "Ô", null, 4),
                new CategoryRow(3, "Đèn", "Đèn bàn", 0),
                new CategoryRow(2, "Bàn", null, 2),
                new CategoryRow(1, "Áo", "Áo thun", 7)));

        List<CategoryRow> rows = service.list();

        assertThat(rows).extracting(CategoryRow::name).containsExactly("Áo", "Bàn", "Đèn", "Ô");
        assertThat(rows).extracting(CategoryRow::productCount).containsExactly(7L, 2L, 0L, 4L);
    }

    @Test
    void list_withNoCategories_isEmpty() {
        when(categoryRepository.findAllWithProductCount()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();
    }

    @Test
    void get_returnsTheEditView() {
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(category(5, "Đồ chơi", "Cho bé")));

        assertThat(service.get(5)).isEqualTo(new CategoryEditView(5, "Đồ chơi", "Cho bé"));
    }

    @Test
    void get_whenMissing_throwsNotFound() {
        when(categoryRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(5)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void options_areSortedByVietnameseName() {
        when(categoryRepository.findAll()).thenReturn(List.of(category(2, "Đèn", null), category(1, "Áo", null)));

        assertThat(service.options()).containsExactly(new CategoryOption(1L, "Áo"), new CategoryOption(2L, "Đèn"));
    }

    // ---------- create ----------

    /** AC-006-11 */
    @Test
    void create_savesNameAndNullDescriptionAndReturnsTheName() {
        when(categoryRepository.findAll()).thenReturn(List.of());
        when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 9L);
            return c;
        });

        String name = service.create(new CategoryDraft("Đồ chơi", "  "));

        assertThat(name).isEqualTo("Đồ chơi");
        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Đồ chơi");
        assertThat(saved.getValue().getDescription()).isNull();
    }

    /** AC-006-13, BR-006-2 */
    @Test
    void create_withNameThatDiffersOnlyByCase_throwsAndSavesNothing() {
        when(categoryRepository.findAll()).thenReturn(List.of(category(1, "Đồ chơi", null)));

        assertThatThrownBy(() -> service.create(new CategoryDraft("đồ chơi", null)))
                .isInstanceOf(CategoryNameTakenException.class);
        assertThatThrownBy(() -> service.create(new CategoryDraft("ĐỒ CHƠI", null)))
                .isInstanceOf(CategoryNameTakenException.class);

        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_withNameDifferentFromAllOthers_isAccepted() {
        when(categoryRepository.findAll()).thenReturn(List.of(category(1, "Áo", null), category(2, "Quần", null)));
        when(categoryRepository.saveAndFlush(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(new CategoryDraft("Áo khoác", null))).isEqualTo("Áo khoác");
    }

    /** AC-006-13: the database constraint is the safety net when two requests race. */
    @Test
    void create_whenUniqueConstraintFires_throwsNameTaken() {
        when(categoryRepository.findAll()).thenReturn(List.of());
        when(categoryRepository.saveAndFlush(any(Category.class))).thenThrow(violation("UQ_CATEGORIES_NAME"));

        assertThatThrownBy(() -> service.create(new CategoryDraft("Đồ chơi", null)))
                .isInstanceOf(CategoryNameTakenException.class);
    }

    @Test
    void create_whenUniqueConstraintNameIsPrefixedBySchema_stillThrowsNameTaken() {
        when(categoryRepository.findAll()).thenReturn(List.of());
        when(categoryRepository.saveAndFlush(any(Category.class)))
                .thenThrow(violation("PUBLIC.UQ_CATEGORIES_NAME_INDEX_8 ON PUBLIC.CATEGORIES(NAME NULLS FIRST)"));

        assertThatThrownBy(() -> service.create(new CategoryDraft("Đồ chơi", null)))
                .isInstanceOf(CategoryNameTakenException.class);
    }

    @Test
    void create_whenAnotherConstraintFires_rethrowsTheOriginalException() {
        DataIntegrityViolationException other = violation("ck_something_else");
        when(categoryRepository.findAll()).thenReturn(List.of());
        when(categoryRepository.saveAndFlush(any(Category.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(new CategoryDraft("Đồ chơi", null)))
                .isSameAs(other);
    }

    @Test
    void create_whenIntegrityErrorHasNoConstraintName_rethrowsTheOriginalException() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("plain");
        when(categoryRepository.findAll()).thenReturn(List.of());
        when(categoryRepository.saveAndFlush(any(Category.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(new CategoryDraft("X", null))).isSameAs(other);
    }

    // ---------- update ----------

    /** AC-006-14 */
    @Test
    void update_changesNameAndDescriptionAndReturnsTheNewName() {
        Category existing = category(5, "Đồ chơi", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findAll()).thenReturn(List.of(existing, category(6, "Áo", null)));

        String name = service.update(5, new CategoryDraft("Đồ chơi trẻ em", "Cho bé"));

        assertThat(name).isEqualTo("Đồ chơi trẻ em");
        assertThat(existing.getName()).isEqualTo("Đồ chơi trẻ em");
        assertThat(existing.getDescription()).isEqualTo("Cho bé");
        verify(categoryRepository).saveAndFlush(existing);
    }

    /** AC-006-14: keeping its own name is valid. */
    @Test
    void update_keepingItsOwnNameIsAllowed() {
        Category existing = category(5, "Đồ chơi", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findAll()).thenReturn(List.of(existing));

        assertThat(service.update(5, new CategoryDraft("Đồ chơi", "Mô tả mới"))).isEqualTo("Đồ chơi");
    }

    @Test
    void update_changingOnlyTheCaseOfItsOwnNameIsAllowed() {
        Category existing = category(5, "Đồ chơi", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findAll()).thenReturn(List.of(existing));

        assertThat(service.update(5, new CategoryDraft("ĐỒ CHƠI", null))).isEqualTo("ĐỒ CHƠI");
    }

    /** AC-006-13 */
    @Test
    void update_toTheNameOfAnotherCategoryIgnoringCase_throwsAndSavesNothing() {
        Category existing = category(5, "Áo", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findAll()).thenReturn(List.of(existing, category(6, "Đồ chơi", null)));

        assertThatThrownBy(() -> service.update(5, new CategoryDraft("Đồ Chơi", null)))
                .isInstanceOf(CategoryNameTakenException.class);

        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_whenUniqueConstraintFires_throwsNameTaken() {
        Category existing = category(5, "Áo", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findAll()).thenReturn(List.of(existing));
        when(categoryRepository.saveAndFlush(existing)).thenThrow(violation("uq_categories_name"));

        assertThatThrownBy(() -> service.update(5, new CategoryDraft("Quần", null)))
                .isInstanceOf(CategoryNameTakenException.class);
    }

    /** AC-006-14: the row is locked, so a concurrent delete cannot slip in between. */
    @Test
    void update_locksTheRowAndNeverUsesFindById() {
        Category existing = category(5, "Áo", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findAll()).thenReturn(List.of(existing));

        service.update(5, new CategoryDraft("Quần", null));

        InOrder order = inOrder(categoryRepository);
        order.verify(categoryRepository).lockById(5L);
        order.verify(categoryRepository).saveAndFlush(existing);
        verify(categoryRepository, never()).findById(any());
    }

    /** AC-006-5 */
    @Test
    void update_whenMissing_throwsNotFound() {
        when(categoryRepository.lockById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(5, new CategoryDraft("X", null)))
                .isInstanceOf(NotFoundException.class);

        verify(categoryRepository, never()).saveAndFlush(any());
    }

    // ---------- delete ----------

    /** AC-006-15 */
    @Test
    void delete_emptyCategory_locksThenCountsThenDeletesAndFlushes() {
        Category existing = category(5, "Đồ chơi", null);
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(existing));
        when(productRepository.countByCategoryId(5L)).thenReturn(0L);

        CategoryDeleteResult result = service.delete(5);

        assertThat(result).isEqualTo(new CategoryDeleteResult.Deleted("Đồ chơi"));
        InOrder order = inOrder(categoryRepository, productRepository);
        order.verify(categoryRepository).lockById(5L);
        order.verify(productRepository).countByCategoryId(5L);
        order.verify(categoryRepository).delete(existing);
        order.verify(categoryRepository).flush();
    }

    /** AC-006-16, BR-006-3 */
    @Test
    void delete_categoryWithProducts_isInUseAndDeletesNothing() {
        when(categoryRepository.lockById(5L)).thenReturn(Optional.of(category(5, "Đồ chơi", null)));
        when(productRepository.countByCategoryId(5L)).thenReturn(2L);

        CategoryDeleteResult result = service.delete(5);

        assertThat(result).isEqualTo(new CategoryDeleteResult.InUse("Đồ chơi", 2));
        verify(categoryRepository, never()).delete(any(Category.class));
        verify(categoryRepository, never()).flush();
    }

    /** AC-006-5 */
    @Test
    void delete_whenMissing_throwsNotFoundWithoutCounting() {
        when(categoryRepository.lockById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(5)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(productRepository);
        verify(categoryRepository, never()).delete(any(Category.class));
    }
}

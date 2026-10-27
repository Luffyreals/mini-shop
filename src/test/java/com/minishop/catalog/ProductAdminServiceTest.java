package com.minishop.catalog;

import com.minishop.common.AdminAccess;
import com.minishop.common.NotFoundException;
import com.minishop.storage.ImageUpload;
import com.minishop.storage.StorageService;
import com.minishop.support.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Product administration rules (AC-006-17, 19, 21..26, 30, 32..34, 37; BR-006-4, 6..9, 11). Repositories and
 * storage are mocks; concurrency with customers is proven on PostgreSQL in TW5.
 */
@ExtendWith(MockitoExtension.class)
class ProductAdminServiceTest {

    private static final long ADMIN_ID = 1L;

    @Mock
    ProductRepository productRepository;

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    StorageService storage;

    @Mock
    AdminAccess adminAccess;

    ProductAdminService service;

    private final Category oldCategory = category(2, "Áo");
    private final Category newCategory = category(3, "Quần");

    @BeforeEach
    void setUp() {
        lenient().when(storage.toUrl(any())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            return path == null || path.isBlank() ? null : path;
        });
        lenient().when(adminAccess.requireAdmin()).thenReturn(ADMIN_ID);
        service = new ProductAdminService(productRepository, categoryRepository, storage, new ProductImages(storage),
                adminAccess);
    }

    // ---------- helpers ----------

    private static Category category(long id, String name) {
        Category c = new Category(name, null);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private Product product(long id, int stock, boolean active) {
        Product p = new Product(oldCategory, "Áo thun", "Mô tả", new BigDecimal("100000"), stock);
        ReflectionTestUtils.setField(p, "id", id);
        p.setActive(active);
        return p;
    }

    private void stubSaveWithId() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 11L);
            return p;
        });
    }

    private static ProductDraft draft(long categoryId) {
        return new ProductDraft("Bình nước 1L", categoryId, new BigDecimal("1250000"), "Bình giữ nhiệt");
    }

    private static ImageUpload image() {
        byte[] data = TestImages.png(300);
        return ImageUpload.from("a.png", "image/png", data.length, new ByteArrayResource(data)).orElseThrow();
    }

    // ---------- create ----------

    /** AC-006-19: the category is locked before the image is written, the product saved last. */
    @Test
    void create_withImage_locksCategoryThenStoresImageThenSaves() {
        ImageUpload image = image();
        when(categoryRepository.lockById(2L)).thenReturn(Optional.of(oldCategory));
        when(storage.store(image)).thenReturn("/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png");
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 11L);
            return p;
        });

        SavedProduct saved = service.create(draft(2), 30, true, image);

        assertThat(saved).isEqualTo(new SavedProduct(11, "Bình nước 1L"));
        InOrder order = inOrder(categoryRepository, storage, productRepository);
        order.verify(categoryRepository).lockById(2L);
        order.verify(storage).store(image);
        order.verify(productRepository).save(any(Product.class));
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        Product product = captor.getValue();
        assertThat(product.getName()).isEqualTo("Bình nước 1L");
        assertThat(product.getCategory()).isSameAs(oldCategory);
        assertThat(product.getPrice()).isEqualByComparingTo("1250000");
        assertThat(product.getStock()).isEqualTo(30);
        assertThat(product.isActive()).isTrue();
        assertThat(product.getImagePath()).isEqualTo("/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png");
        assertThat(product.getDescription()).isEqualTo("Bình giữ nhiệt");
    }

    @Test
    void create_withoutImage_neverTouchesStorageAndLeavesNoImagePath() {
        when(categoryRepository.lockById(2L)).thenReturn(Optional.of(oldCategory));
        stubSaveWithId();

        service.create(draft(2), 0, false, null);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getImagePath()).isNull();
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getStock()).isZero();
        verify(storage, never()).store(any());
    }

    /** AC-006-21: a missing category leaves no file behind and saves nothing. */
    @Test
    void create_whenCategoryIsGone_throwsAndNeverStoresOrSaves() {
        when(categoryRepository.lockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(draft(99), 5, true, image()))
                .isInstanceOf(CategoryNotFoundException.class);

        verifyNoInteractions(storage);
        verify(productRepository, never()).save(any());
    }

    /** BR-006-6 */
    @ParameterizedTest
    @ValueSource(ints = {-1, 100_001, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void create_withInitialStockOutOfRange_throwsBeforeLockingAnything(int stock) {
        assertThatThrownBy(() -> service.create(draft(2), stock, true, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(categoryRepository, storage, productRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100_000})
    void create_withInitialStockAtTheBoundaries_isAccepted(int stock) {
        when(categoryRepository.lockById(2L)).thenReturn(Optional.of(oldCategory));
        stubSaveWithId();

        service.create(draft(2), stock, true, null);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getStock()).isEqualTo(stock);
    }

    // ---------- update ----------

    /** AC-006-22: without a new image the old one stays and storage is untouched. */
    @Test
    void update_withoutImage_changesDetailsKeepsImageAndNeverTouchesStorage() {
        Product existing = product(7, 10, true);
        existing.setImagePath("/images/products/ao-thun.jpg");
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.lockById(3L)).thenReturn(Optional.of(newCategory));

        String name = service.update(7, draft(3), null);

        assertThat(name).isEqualTo("Bình nước 1L");
        assertThat(existing.getName()).isEqualTo("Bình nước 1L");
        assertThat(existing.getDescription()).isEqualTo("Bình giữ nhiệt");
        assertThat(existing.getPrice()).isEqualByComparingTo("1250000");
        assertThat(existing.getCategory()).isSameAs(newCategory);
        assertThat(existing.getImagePath()).isEqualTo("/images/products/ao-thun.jpg");
        assertThat(existing.getStock()).isEqualTo(10);
        assertThat(existing.isActive()).isTrue();
        verify(storage, never()).store(any());
        verify(storage, never()).deleteAfterCommit(any());
    }

    /** AC-006-30 */
    @Test
    void update_withImage_storesNewThenDeletesOldAfterCommit() {
        Product existing = product(7, 10, true);
        existing.setImagePath("/uploads/11111111-aaaa-bbbb-cccc-0123456789ab.png");
        ImageUpload image = image();
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.lockById(3L)).thenReturn(Optional.of(newCategory));
        when(storage.store(image)).thenReturn("/uploads/22222222-aaaa-bbbb-cccc-0123456789ab.png");

        service.update(7, draft(3), image);

        assertThat(existing.getImagePath()).isEqualTo("/uploads/22222222-aaaa-bbbb-cccc-0123456789ab.png");
        InOrder order = inOrder(categoryRepository, storage);
        order.verify(categoryRepository).lockById(3L);
        order.verify(storage).store(image);
        order.verify(storage).deleteAfterCommit("/uploads/11111111-aaaa-bbbb-cccc-0123456789ab.png");
    }

    /** AC-006-30: the seed image path is handed to storage, which is the one that ignores it. */
    @Test
    void update_withImageOverSeedImage_passesTheOldPathToDeleteAfterCommit() {
        Product existing = product(7, 10, true);
        existing.setImagePath("/images/products/ao-thun.jpg");
        ImageUpload image = image();
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.lockById(2L)).thenReturn(Optional.of(oldCategory));
        when(storage.store(image)).thenReturn("/uploads/22222222-aaaa-bbbb-cccc-0123456789ab.png");

        service.update(7, draft(2), image);

        verify(storage).deleteAfterCommit("/images/products/ao-thun.jpg");
    }

    @Test
    void update_withImageOnProductWithoutImage_passesNullToDeleteAfterCommit() {
        Product existing = product(7, 10, true);
        ImageUpload image = image();
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.lockById(2L)).thenReturn(Optional.of(oldCategory));
        when(storage.store(image)).thenReturn("/uploads/22222222-aaaa-bbbb-cccc-0123456789ab.png");

        service.update(7, draft(2), image);

        verify(storage).deleteAfterCommit(null);
    }

    /** BR-006-8: editing never touches stock. */
    @Test
    void update_neverAdjustsOrDecrementsOrIncrementsStock() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 10, true)));
        when(categoryRepository.lockById(2L)).thenReturn(Optional.of(oldCategory));

        service.update(7, draft(2), null);

        verify(productRepository, never()).adjustStock(anyLong(), anyInt());
        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
        verify(productRepository, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void update_whenProductMissing_throwsNotFoundBeforeLockingTheCategory() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(7, draft(2), image()))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(categoryRepository, storage);
    }

    /** AC-006-21 */
    @Test
    void update_whenTargetCategoryIsGone_throwsAndNeverStores() {
        Product existing = product(7, 10, true);
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.lockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(7, draft(99), image()))
                .isInstanceOf(CategoryNotFoundException.class);

        verifyNoInteractions(storage);
        assertThat(existing.getName()).isEqualTo("Áo thun");
    }

    // ---------- setActive ----------

    /** AC-006-25 */
    @Test
    void setActive_false_hidesAndReturnsTheName() {
        Product existing = product(7, 10, true);
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThat(service.setActive(7, false)).isEqualTo("Áo thun");

        assertThat(existing.isActive()).isFalse();
        assertThat(existing.getStock()).isEqualTo(10);
    }

    /** AC-006-26 */
    @Test
    void setActive_true_showsAgainAndReturnsTheName() {
        Product existing = product(7, 10, false);
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThat(service.setActive(7, true)).isEqualTo("Áo thun");

        assertThat(existing.isActive()).isTrue();
    }

    @Test
    void setActive_whenMissing_throwsNotFound() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setActive(7, true)).isInstanceOf(NotFoundException.class);
    }

    // ---------- adjustStock ----------

    /** AC-006-32 */
    @Test
    void adjustStock_whenOneRowChanges_returnsAdjustedWithTheReloadedStock() {
        when(productRepository.adjustStock(7L, 5)).thenReturn(1);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 15, true)));

        StockAdjustResult result = service.adjustStock(7, 5);

        assertThat(result).isEqualTo(new StockAdjustResult.Adjusted("Áo thun", 15));
        InOrder order = inOrder(productRepository);
        order.verify(productRepository).adjustStock(7L, 5);
        order.verify(productRepository).findById(7L);
    }

    /** AC-006-33 */
    @Test
    void adjustStock_whenNegativeAndNoRowChanges_returnsNotEnoughWithCurrentStock() {
        when(productRepository.adjustStock(7L, -5)).thenReturn(0);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 3, true)));

        assertThat(service.adjustStock(7, -5))
                .isEqualTo(new StockAdjustResult.NotEnough("Áo thun", 5, 3));
    }

    @Test
    void adjustStock_whenDecreasingByAvailableAmount_isAdjusted() {
        when(productRepository.adjustStock(7L, -2)).thenReturn(1);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 1, true)));

        assertThat(service.adjustStock(7, -2)).isEqualTo(new StockAdjustResult.Adjusted("Áo thun", 1));
    }

    /** AC-006-34 */
    @Test
    void adjustStock_whenPositiveAndNoRowChanges_returnsWouldExceed() {
        when(productRepository.adjustStock(7L, 10)).thenReturn(0);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 99_995, true)));

        assertThat(service.adjustStock(7, 10)).isEqualTo(new StockAdjustResult.WouldExceed("Áo thun"));
    }

    /** AC-006-5: a missing product is a 404, not a "not enough" message. */
    @Test
    void adjustStock_whenProductMissing_throwsNotFound() {
        when(productRepository.adjustStock(7L, 5)).thenReturn(0);
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adjustStock(7, 5)).isInstanceOf(NotFoundException.class);
    }

    /** AC-006-34: the service is the last guard even though the controller checks first. */
    @ParameterizedTest
    @ValueSource(ints = {0, 100_001, -100_001, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void adjustStock_withInvalidDelta_throwsWithoutCallingTheRepository(int delta) {
        assertThatThrownBy(() -> service.adjustStock(7, delta))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(productRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {100_000, -100_000})
    void adjustStock_withDeltaAtTheBoundary_callsTheRepository(int delta) {
        when(productRepository.adjustStock(7L, delta)).thenReturn(1);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 100_000, true)));

        assertThat(service.adjustStock(7, delta)).isInstanceOf(StockAdjustResult.Adjusted.class);
    }

    /** AC-006-37: hidden products can be adjusted; nothing in the flow looks at {@code active}. */
    @Test
    void adjustStock_onHiddenProduct_isStillAdjusted() {
        when(productRepository.adjustStock(7L, 20)).thenReturn(1);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 20, false)));

        assertThat(service.adjustStock(7, 20)).isEqualTo(new StockAdjustResult.Adjusted("Áo thun", 20));
    }

    @Test
    void adjustStock_neverUsesTheOrderStockQueriesOrSaves() {
        when(productRepository.adjustStock(7L, 5)).thenReturn(1);
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7, 15, true)));

        service.adjustStock(7, 5);

        verify(productRepository, never()).decrementStock(anyLong(), anyInt());
        verify(productRepository, never()).incrementStock(anyLong(), anyInt());
        verify(productRepository, never()).save(any());
        verifyNoMoreInteractions(categoryRepository);
    }

    // ---------- list ----------

    /** AC-006-17 */
    @Test
    @SuppressWarnings("unchecked")
    void list_withQueryAndBadPage_asksPageOneOfTwentySortedNewestFirst() {
        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.<Pageable>getArgument(1), 0));

        AdminProductPage page = service.list("  áo  ", "abc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(any(Specification.class), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().toList()).containsExactly(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(page.q()).isEqualTo("áo");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.isEmpty()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_mapsRowsIncludingHiddenProductsAndImageUrls() {
        Product shown = product(7, 4, true);
        shown.setImagePath("/uploads/22222222-aaaa-bbbb-cccc-0123456789ab.png");
        Product hidden = product(6, 0, false);
        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(shown, hidden), inv.<Pageable>getArgument(1), 2));

        AdminProductPage page = service.list(null, "1");

        assertThat(page.products()).containsExactly(
                new AdminProductRow(7, "Áo thun", "Áo", new BigDecimal("100000"), 4, true,
                        "/uploads/22222222-aaaa-bbbb-cccc-0123456789ab.png"),
                new AdminProductRow(6, "Áo thun", "Áo", new BigDecimal("100000"), 0, false, null));
        assertThat(page.q()).isNull();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.isEmpty()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_whenPageIsBeyondTheEnd_fallsBackToPageOne() {
        when(productRepository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(1);
            List<Product> content = pageable.getPageNumber() == 0 ? List.of(product(7, 4, true)) : List.of();
            return new PageImpl<>(content, pageable, 25);
        });

        AdminProductPage page = service.list(null, "9");

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.products()).hasSize(1);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    // ---------- lowStock / getForEdit ----------

    /** AC-006-7: only the repository decides which products qualify, with the fixed threshold of 5. */
    @Test
    void lowStock_asksForThresholdFiveAndMapsRows() {
        when(productRepository.findTop10ByActiveTrueAndStockLessThanEqualOrderByStockAscIdAsc(5))
                .thenReturn(List.of(product(1, 0, true), product(2, 3, true), product(3, 5, true)));

        assertThat(service.lowStock()).containsExactly(
                new LowStockRow(1, "Áo thun", 0), new LowStockRow(2, "Áo thun", 3), new LowStockRow(3, "Áo thun", 5));
    }

    @Test
    void getForEdit_returnsTheViewWithTheCurrentStockAndImageUrl() {
        Product existing = product(7, 10, false);
        existing.setImagePath("/images/products/ao-thun.jpg");
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThat(service.getForEdit(7)).isEqualTo(new ProductEditView(7, "Áo thun", 2, new BigDecimal("100000"),
                "Mô tả", 10, false, "/images/products/ao-thun.jpg"));
    }

    @Test
    void getForEdit_whenMissing_throwsNotFound() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForEdit(7)).isInstanceOf(NotFoundException.class);
    }
}

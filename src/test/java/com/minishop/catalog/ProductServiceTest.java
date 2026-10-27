package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chuẩn hóa tham số, phân trang, sắp xếp và ánh xạ DTO của {@link ProductService} (AC-002-5..8, 14..16). */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    ProductRepository productRepository;

    @Mock
    CategoryRepository categoryRepository;

    ProductService service;

    private static ProductImages productImages() {
        StorageService storage = org.mockito.Mockito.mock(StorageService.class);
        lenient().when(storage.toUrl(any())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            return path == null || path.isBlank() ? null : path;
        });
        return new ProductImages(storage);
    }

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, productImages());
    }

    private static ProductSearchParams params(String q, String category, String sort, String page) {
        return new ProductSearchParams(q, category, sort, page);
    }

    @SuppressWarnings("unchecked")
    private void stubPage(List<Product> content, long total) {
        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(content, inv.<Pageable>getArgument(1), total));
        when(categoryRepository.findAll()).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private Pageable searchAndCapturePageable(ProductSearchParams params) {
        stubPage(List.of(), 0);
        service.search(params);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(any(Specification.class), captor.capture());
        return captor.getValue();
    }

    // ---- page (AC-002-8)

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"abc", "0", "-3", "99999999999", "", " "})
    void search_whenPageInvalid_fallsBackToFirstPage(String page) {
        Pageable pageable = searchAndCapturePageable(params(null, null, null, page));

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(12);
        assertThat(ProductFilter.from(params(null, null, null, page)).page()).isEqualTo(1);
    }

    @Test
    void search_whenPageTwo_requestsZeroBasedPageOneWithSizeTwelve() {
        Pageable pageable = searchAndCapturePageable(params(null, null, null, "2"));

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(ProductService.PAGE_SIZE).isEqualTo(12);
    }

    @Test
    void search_returnsNormalizedFilterWithOneBasedPage() {
        stubPage(List.of(), 0);

        ProductListView view = service.search(params(null, null, null, "3"));

        assertThat(view.filter().page()).isEqualTo(3);
    }

    // ---- sort (AC-002-7)

    @Test
    void search_whenSortPriceAsc_sortsByPriceAscThenIdAsc() {
        Sort sort = searchAndCapturePageable(params(null, null, "price_asc", null)).getSort();

        assertThat(sort).containsExactly(Sort.Order.asc("price"), Sort.Order.asc("id"));
    }

    @Test
    void search_whenSortPriceDesc_sortsByPriceDescThenIdAsc() {
        Sort sort = searchAndCapturePageable(params(null, null, "price_desc", null)).getSort();

        assertThat(sort).containsExactly(Sort.Order.desc("price"), Sort.Order.asc("id"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"xyz", "newest", "PRICE_ASC", ""})
    void search_whenSortNewestUnknownOrNull_sortsByCreatedAtDescThenIdDesc(String sortKey) {
        Sort sort = searchAndCapturePageable(params(null, null, sortKey, null)).getSort();

        assertThat(sort).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(ProductFilter.from(params(null, null, sortKey, null)).sort()).isEqualTo(ProductSort.NEWEST);
    }

    // ---- q (AC-002-5)

    @Test
    void filter_whenQueryLongerThanHundred_truncatesToHundredAfterStrip() {
        String raw = "   " + "a".repeat(150) + "   ";

        String q = ProductFilter.from(params(raw, null, null, null)).q();

        assertThat(q).hasSize(100).isEqualTo("a".repeat(100));
    }

    @Test
    void filter_whenTruncatingAtEmoji_neverSplitsSurrogatePair() {
        String raw = "a".repeat(99) + "😀😀";

        String q = ProductFilter.from(params(raw, null, null, null)).q();

        assertThat(q).isEqualTo("a".repeat(99) + "😀");
        assertThat(q.codePointCount(0, q.length())).isEqualTo(100);
    }

    @Test
    void filter_removesControlCharactersFromQuery() {
        assertThat(ProductFilter.from(params("áo\u0000 thun\t", null, null, null)).q()).isEqualTo("áo thun");
        assertThat(ProductFilter.from(params("\u0000\u0007", null, null, null)).q()).isNull();
    }

    @Test
    void filter_whenPageAboveMaximum_clampsToMaxPage() {
        assertThat(ProductFilter.from(params(null, null, null, "200000000")).page()).isEqualTo(ProductFilter.MAX_PAGE);
        assertThat(ProductFilter.from(params(null, null, null, String.valueOf(Integer.MAX_VALUE))).page())
                .isEqualTo(ProductFilter.MAX_PAGE);
    }

    @Test
    void filter_whenQueryBlankOrNull_isNull() {
        assertThat(ProductFilter.from(params("   ", null, null, null)).q()).isNull();
        assertThat(ProductFilter.from(params("", null, null, null)).q()).isNull();
        assertThat(ProductFilter.from(params(null, null, null, null)).q()).isNull();
    }

    @Test
    void filter_whenQueryHasSurroundingSpaces_stripsThem() {
        assertThat(ProductFilter.from(params("  áo THUN ", null, null, null)).q()).isEqualTo("áo THUN");
    }

    // ---- category (AC-002-6)

    @Test
    void filter_whenCategoryNotNumeric_isNull() {
        assertThat(ProductFilter.from(params(null, "abc", null, null)).categoryId()).isNull();
        assertThat(ProductFilter.from(params(null, "", null, null)).categoryId()).isNull();
        assertThat(ProductFilter.from(params(null, null, null, null)).categoryId()).isNull();
        assertThat(ProductFilter.from(params(null, "99999999999999999999", null, null)).categoryId()).isNull();
    }

    @Test
    void filter_whenCategoryNumeric_parsesToLong() {
        assertThat(ProductFilter.from(params(null, "5", null, null)).categoryId()).isEqualTo(5L);
    }

    // ---- category options order (QĐ5)

    @Test
    void search_sortsCategoryOptionsWithVietnameseCollation() {
        stubPage(List.of(), 0);
        when(categoryRepository.findAll()).thenReturn(List.of(
                new Category("Thời trang", null), new Category("Điện tử", null),
                new Category("Sách", null), new Category("Nhà cửa", null)));

        ProductListView view = service.search(params(null, null, null, null));

        assertThat(view.categories()).extracting(CategoryOption::name)
                .containsExactly("Điện tử", "Nhà cửa", "Sách", "Thời trang");
    }

    // ---- mapping

    @Test
    void search_mapsProductsToCardsAndPassesTotals() {
        Category category = new Category("Thời trang", null);
        Product inStock = new Product(category, "Áo", null, new BigDecimal("1250000"), 3);
        inStock.setImagePath("/images/products/ao.jpg");
        Product outOfStock = new Product(category, "Quần", null, new BigDecimal("500000"), 0);
        stubPage(List.of(inStock, outOfStock), 25);

        ProductListView view = service.search(params(null, null, null, null));

        assertThat(view.totalElements()).isEqualTo(25);
        assertThat(view.totalPages()).isEqualTo(3);
        assertThat(view.products()).containsExactly(
                new ProductCardView(null, "Áo", "Thời trang", new BigDecimal("1250000"), true,
                        "/images/products/ao.jpg"),
                new ProductCardView(null, "Quần", "Thời trang", new BigDecimal("500000"), false, null));
    }

    // ---- image (AC-002-16)

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void search_whenImagePathBlank_imageUrlIsNull(String imagePath) {
        Product p = new Product(new Category("C", null), "P", null, BigDecimal.TEN, 1);
        p.setImagePath(imagePath);
        stubPage(List.of(p), 1);

        ProductListView view = service.search(params(null, null, null, null));

        assertThat(view.products().get(0).imageUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void getDetail_whenImagePathBlank_imageUrlIsNull(String imagePath) {
        Product p = new Product(new Category("C", null), "P", "mô tả", BigDecimal.TEN, 1);
        p.setImagePath(imagePath);
        when(productRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(p));

        assertThat(service.getDetail(1L).imageUrl()).isNull();
    }

    // ---- getDetail (AC-002-14, 15)

    @Test
    void getDetail_whenNotFoundOrInactive_throwsNotFound() {
        when(productRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("7");
    }

    @Test
    void getDetail_whenFound_mapsAllFields() {
        Product p = new Product(new Category("Sách", null), "Truyện", "Mô tả", new BigDecimal("90000"), 0);
        when(productRepository.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(p));

        ProductDetailView view = service.getDetail(2L);

        assertThat(view).isEqualTo(new ProductDetailView(null, "Truyện", "Sách", new BigDecimal("90000"),
                "Mô tả", false, null));
    }

    // ---- home (QĐ5, AC-009-1, AC-002-18)

    private static final Category HOME_CATEGORY = new Category("Thời trang", null);

    private static Product homeProduct(long id, String name, int stock, String imagePath) {
        Product p = new Product(HOME_CATEGORY, name, "Mô tả " + name, BigDecimal.valueOf(1000L * id), stock);
        ReflectionTestUtils.setField(p, "id", id);
        p.setImagePath(imagePath);
        return p;
    }

    private void stubHome(List<Product> newest, List<Product> lowStock, List<Category> allCategories) {
        when(productRepository.findTop3ByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(newest);
        when(productRepository.findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(1, 5)).thenReturn(lowStock);
        when(categoryRepository.findAll()).thenReturn(allCategories);
    }

    @Test
    void home_whenNoProducts_hasNoFeaturedAndEmptyLists() {
        stubHome(List.of(), List.of(), List.of());

        HomeView view = service.home();

        assertThat(view.featured()).isNull();
        assertThat(view.moreNew()).isEmpty();
        assertThat(view.lowStock()).isEmpty();
        assertThat(view.categories()).isEmpty();
    }

    @Test
    void home_whenOneProduct_featuredIsItAndMoreNewIsEmpty() {
        stubHome(List.of(homeProduct(1, "Áo", 3, null)), List.of(), List.of());

        HomeView view = service.home();

        assertThat(view.featured()).isEqualTo(new ProductDetailView(1L, "Áo", "Thời trang",
                BigDecimal.valueOf(1000), "Mô tả Áo", true, null));
        assertThat(view.moreNew()).isEmpty();
    }

    @Test
    void home_whenTwoProducts_featuredIsFirstAndMoreNewIsSecond() {
        stubHome(List.of(homeProduct(2, "Mới nhất", 3, null), homeProduct(1, "Cũ hơn", 0, null)),
                List.of(), List.of());

        HomeView view = service.home();

        assertThat(view.featured().name()).isEqualTo("Mới nhất");
        assertThat(view.moreNew()).containsExactly(new ProductCardView(1L, "Cũ hơn", "Thời trang",
                BigDecimal.valueOf(1000), false, null));
    }

    @Test
    void home_whenThreeProducts_featuredIsFirstAndMoreNewIsSecondAndThirdInOrder() {
        stubHome(List.of(homeProduct(3, "A", 1, null), homeProduct(2, "B", 1, null), homeProduct(1, "C", 1, null)),
                List.of(), List.of());

        HomeView view = service.home();

        assertThat(view.featured().id()).isEqualTo(3L);
        assertThat(view.moreNew()).extracting(ProductCardView::id).containsExactly(2L, 1L);
        assertThat(view.moreNew()).extracting(ProductCardView::name).containsExactly("B", "C");
    }

    @Test
    void home_queriesLowStockBetweenOneAndThresholdFive() {
        stubHome(List.of(), List.of(), List.of());

        service.home();

        assertThat(ProductAdminService.LOW_STOCK_THRESHOLD).isEqualTo(5);
        verify(productRepository).findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(1, 5);
    }

    @Test
    void home_mapsLowStockRowsKeepingRepositoryOrder() {
        stubHome(List.of(), List.of(homeProduct(7, "Gần hết", 1, "/images/products/a.jpg"),
                homeProduct(4, "Còn ít", 3, null)), List.of());

        HomeView view = service.home();

        assertThat(view.lowStock()).containsExactly(
                new LowStockCardView(7L, "Gần hết", 1, "/images/products/a.jpg"),
                new LowStockCardView(4L, "Còn ít", 3, null));
    }

    @Test
    void home_buildsEveryImageUrlThroughProductImages() {
        StorageService storage = org.mockito.Mockito.mock(StorageService.class);
        lenient().when(storage.toUrl(any())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            return path == null ? null : "file:///resolved" + path;
        });
        ProductService withResolver = new ProductService(productRepository, categoryRepository,
                new ProductImages(storage));
        stubHome(List.of(homeProduct(3, "A", 1, "/x/a.jpg"), homeProduct(2, "B", 1, "/x/b.jpg"),
                        homeProduct(1, "C", 1, null)),
                List.of(homeProduct(9, "D", 2, "/x/d.jpg")), List.of());

        HomeView view = withResolver.home();

        assertThat(view.featured().imageUrl()).isEqualTo("file:///resolved/x/a.jpg");
        assertThat(view.moreNew()).extracting(ProductCardView::imageUrl)
                .containsExactly("file:///resolved/x/b.jpg", null);
        assertThat(view.lowStock()).extracting(LowStockCardView::imageUrl).containsExactly("file:///resolved/x/d.jpg");
    }

    @Test
    void home_whenImagePathBlank_imageUrlIsNull() {
        stubHome(List.of(homeProduct(2, "A", 1, " ")), List.of(homeProduct(9, "D", 2, "")), List.of());

        HomeView view = service.home();

        assertThat(view.featured().imageUrl()).isNull();
        assertThat(view.lowStock().get(0).imageUrl()).isNull();
    }

    @Test
    void home_sortsCategoriesWithVietnameseCollation() {
        stubHome(List.of(), List.of(), List.of(
                new Category("Thời trang", null), new Category("Điện tử", null),
                new Category("Sách", null), new Category("Nhà cửa", null)));

        HomeView view = service.home();

        assertThat(view.categories()).extracting(CategoryOption::name)
                .containsExactly("Điện tử", "Nhà cửa", "Sách", "Thời trang");
    }

    @Test
    void home_returnsListsThatCannotBeModified() {
        stubHome(List.of(homeProduct(2, "A", 1, null), homeProduct(1, "B", 1, null)),
                List.of(homeProduct(9, "D", 2, null)), List.of(new Category("Sách", null)));

        HomeView view = service.home();

        assertThatThrownBy(() -> view.moreNew().add(view.moreNew().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.lowStock().add(view.lowStock().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.categories().add(view.categories().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void homeView_copiesItsListsSoLaterChangesToTheSourceDoNotShowThrough() {
        List<ProductCardView> moreNew = new ArrayList<>();
        List<LowStockCardView> lowStock = new ArrayList<>();
        List<CategoryOption> categories = new ArrayList<>();

        HomeView view = new HomeView(null, moreNew, lowStock, categories);
        moreNew.add(new ProductCardView(1L, "A", "C", BigDecimal.ONE, true, null));
        lowStock.add(new LowStockCardView(1L, "A", 1, null));
        categories.add(new CategoryOption(1L, "C"));

        assertThat(view.featured()).isNull();
        assertThat(view.moreNew()).isEmpty();
        assertThat(view.lowStock()).isEmpty();
        assertThat(view.categories()).isEmpty();
    }

    // ---- categories() for the top-bar menu

    @Test
    void categories_sortsWithVietnameseCollationAndKeepsIds() {
        Category dienTu = new Category("Điện tử", null);
        ReflectionTestUtils.setField(dienTu, "id", 8L);
        Category sach = new Category("Sách", null);
        ReflectionTestUtils.setField(sach, "id", 3L);
        Category nhaCua = new Category("Nhà cửa", null);
        ReflectionTestUtils.setField(nhaCua, "id", 5L);
        when(categoryRepository.findAll()).thenReturn(List.of(sach, dienTu, nhaCua));

        List<CategoryOption> options = service.categories();

        assertThat(options).containsExactly(new CategoryOption(8L, "Điện tử"), new CategoryOption(5L, "Nhà cửa"),
                new CategoryOption(3L, "Sách"));
    }

    @Test
    void categories_whenNone_isEmpty() {
        when(categoryRepository.findAll()).thenReturn(List.of());

        assertThat(service.categories()).isEmpty();
    }
}

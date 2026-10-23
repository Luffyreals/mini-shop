package com.minishop.catalog;

import com.minishop.common.NotFoundException;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository);
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
}

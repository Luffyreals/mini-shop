package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.storage.ImageUpload;
import com.minishop.storage.StorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Truy vấn danh sách sản phẩm trên PostgreSQL thật, đi qua {@link ProductService} thật (QĐ13):
 * AC-002-1, 3, 4, 6, 7, 8, 11, 14, 15, 18.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductService.class, ProductImages.class,
        ProductQueryTest.StorageStub.class})
class ProductQueryTest {

    /** Giống LocalStorageService.toUrl: null/trống thành null, còn lại giữ nguyên. */
    @TestConfiguration
    static class StorageStub {
        @Bean
        StorageService storageService() {
            return new StorageService() {
                @Override
                public String store(ImageUpload image) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void deleteAfterCommit(String imagePath) {
                }

                @Override
                public String toUrl(String imagePath) {
                    return imagePath == null || imagePath.isBlank() ? null : imagePath;
                }
            };
        }
    }

    @Autowired
    ProductService service;

    @Autowired
    ProductRepository products;

    @Autowired
    CategoryRepository categories;

    @Autowired
    EntityManager em;

    @Autowired
    EntityManagerFactory emf;

    private Category fashion;
    private Category electronics;

    @BeforeEach
    void cleanAndCreateCategories() {
        // Không giả định DB trống: xóa trong transaction của test (được rollback sau mỗi test).
        products.deleteAllInBatch();
        categories.deleteAllInBatch();
        fashion = categories.save(new Category("Thời trang", null));
        electronics = categories.save(new Category("Điện tử", null));
    }

    private Product save(Category category, String name, long price, boolean active) {
        Product p = new Product(category, name, "Mô tả " + name, BigDecimal.valueOf(price), 5);
        p.setActive(active);
        return products.saveAndFlush(p);
    }

    private static ProductSearchParams params(String q, String category, String sort, String page) {
        return new ProductSearchParams(q, category, sort, page);
    }

    private static ProductSearchParams page(int page) {
        return params(null, null, null, String.valueOf(page));
    }

    private static List<String> names(ProductListView view) {
        return view.products().stream().map(ProductCardView::name).toList();
    }

    private void saveNumbered(int count) {
        for (int i = 1; i <= count; i++) {
            save(fashion, String.format("P%02d", i), 1000L * i, true);
        }
    }

    // ---- phân trang và sắp xếp mặc định (AC-002-1, 8, 11)

    @Test
    void search_with25ActiveAndOneHidden_pagesTwelveNewestFirstAndTotalsExcludeHidden() {
        saveNumbered(12);
        save(fashion, "Ẩn", 1, false);
        for (int i = 13; i <= 25; i++) {
            save(fashion, String.format("P%02d", i), 1000L * i, true);
        }

        ProductListView first = service.search(page(1));
        ProductListView second = service.search(page(2));
        ProductListView third = service.search(page(3));
        ProductListView fourth = service.search(page(4));

        assertThat(first.totalElements()).isEqualTo(25);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(names(first)).hasSize(12).startsWith("P25", "P24").endsWith("P14");
        assertThat(names(second)).hasSize(12).startsWith("P13").endsWith("P02");
        assertThat(names(third)).containsExactly("P01");
        assertThat(fourth.products()).isEmpty();
        assertThat(fourth.totalElements()).isEqualTo(25);
    }

    @Test
    void search_returnsAllCategoriesInVietnameseOrderEvenWithoutProducts() {
        ProductListView view = service.search(params(null, null, null, null));

        assertThat(view.categories()).extracting(CategoryOption::name).containsExactly("Điện tử", "Thời trang");
        assertThat(view.products()).isEmpty();
        assertThat(view.totalElements()).isZero();
    }

    // ---- sản phẩm ẩn (AC-002-3)

    @Test
    void search_neverReturnsOrCountsHiddenProductForAnyFilterCombination() {
        save(fashion, "Áo ẩn", 1, false);
        save(fashion, "Áo thường", 2000, true);
        List<String> qs = new ArrayList<>(java.util.Arrays.asList(null, "áo", "ẩn", "Áo ẩn"));
        List<String> cats = new ArrayList<>(java.util.Arrays.asList(null, String.valueOf(fashion.getId())));

        for (String q : qs) {
            for (String cat : cats) {
                for (String sort : List.of("newest", "price_asc", "price_desc")) {
                    ProductListView view = service.search(params(q, cat, sort, null));

                    assertThat(names(view)).as("q=%s category=%s sort=%s", q, cat, sort).doesNotContain("Áo ẩn");
                    assertThat(view.totalElements()).as("count q=%s category=%s sort=%s", q, cat, sort)
                            .isEqualTo(view.products().size());
                }
            }
        }
    }

    // ---- tìm kiếm (AC-002-4, QĐ3)

    /**
     * Bằng chứng cho rủi ro lower() ở QĐ3: PostgreSQL phải gập được chữ "Á" hoa thành "á".
     */
    @Test
    void search_whenQueryHasVietnameseUppercaseAndSpaces_findsCaseInsensitively() {
        save(fashion, "Áo thun cổ tròn", 150000, true);
        save(fashion, "Quần jean", 400000, true);

        ProductListView view = service.search(params("  áo THUN ", null, null, null));

        assertThat(names(view)).containsExactly("Áo thun cổ tròn");
        assertThat(view.totalElements()).isEqualTo(1);
    }

    /** BR-002-3: page cực lớn (offset vượt int) không được gây lỗi 500, chỉ trả trang rỗng. */
    @Test
    void search_whenPageIsHugeButFitsInt_returnsEmptyPageWithoutError() {
        save(fashion, "Áo thun cổ tròn", 150000, true);

        ProductListView view = service.search(params(null, null, null, "200000000"));

        assertThat(view.products()).isEmpty();
        assertThat(view.totalElements()).isEqualTo(1);
    }

    /** BR-002-3: PostgreSQL từ chối byte 0x00, nên ký tự điều khiển trong q phải bị loại trước khi truy vấn. */
    @Test
    void search_whenQueryContainsControlCharacters_ignoresThemWithoutError() {
        save(fashion, "Áo thun cổ tròn", 150000, true);

        assertThat(names(service.search(params("áo\u0000 thun", null, null, null)))).containsExactly("Áo thun cổ tròn");
        assertThat(service.search(params("\u0000", null, null, null)).totalElements()).isEqualTo(1);
    }

    @Test
    void search_whenQueryIsUppercaseVietnamese_matchesLowercaseName() {
        save(fashion, "áo khoác gió", 300000, true);

        assertThat(names(service.search(params("ÁO KHOÁC", null, null, null)))).containsExactly("áo khoác gió");
    }

    @Test
    void search_whenQueryHasNoDiacritics_doesNotMatchDiacriticName() {
        save(fashion, "Áo thun cổ tròn", 150000, true);

        assertThat(service.search(params("ao thun", null, null, null)).products()).isEmpty();
    }

    @Test
    void search_whenQueryContainsPercent_treatsItAsLiteral() {
        save(fashion, "Áo thun cổ tròn", 150000, true);
        save(fashion, "Giảm 50%", 1000, true);

        assertThat(names(service.search(params("50%", null, null, null)))).containsExactly("Giảm 50%");
        assertThat(names(service.search(params("%", null, null, null)))).containsExactly("Giảm 50%");
    }

    @Test
    void search_whenQueryContainsUnderscoreOrBackslash_treatsThemAsLiteral() {
        save(fashion, "Áo thun", 1000, true);
        save(fashion, "Mã a_b", 1000, true);
        save(fashion, "Đường C:\\tmp", 1000, true);

        assertThat(names(service.search(params("_", null, null, null)))).containsExactly("Mã a_b");
        assertThat(names(service.search(params("a_b", null, null, null)))).containsExactly("Mã a_b");
        assertThat(names(service.search(params("\\", null, null, null)))).containsExactly("Đường C:\\tmp");
    }

    @Test
    void search_whenQueryBlank_doesNotFilter() {
        save(fashion, "A", 1000, true);
        save(fashion, "B", 1000, true);

        assertThat(service.search(params("   ", null, null, null)).totalElements()).isEqualTo(2);
    }

    // ---- danh mục (AC-002-6)

    @Test
    void search_whenCategoryGiven_returnsOnlyThatCategory() {
        save(fashion, "Áo", 1000, true);
        save(electronics, "Tai nghe", 2000, true);

        ProductListView view = service.search(params(null, String.valueOf(electronics.getId()), null, null));

        assertThat(names(view)).containsExactly("Tai nghe");
        assertThat(view.totalElements()).isEqualTo(1);
        assertThat(view.products().get(0).categoryName()).isEqualTo("Điện tử");
    }

    @Test
    void search_whenCategoryIdUnknown_returnsEmptyListWithZeroTotal() {
        save(fashion, "Áo", 1000, true);

        ProductListView view = service.search(params(null, "999999999", null, null));

        assertThat(view.products()).isEmpty();
        assertThat(view.totalElements()).isZero();
    }

    @Test
    void search_whenCategoryNotNumeric_ignoresFilter() {
        save(fashion, "Áo", 1000, true);
        save(electronics, "Tai nghe", 2000, true);

        assertThat(service.search(params(null, "abc", null, null)).totalElements()).isEqualTo(2);
    }

    @Test
    void search_combinesQueryAndCategory() {
        save(fashion, "Áo thun", 1000, true);
        save(electronics, "Áo cho loa", 2000, true);

        ProductListView view = service.search(params("áo", String.valueOf(electronics.getId()), null, null));

        assertThat(names(view)).containsExactly("Áo cho loa");
    }

    // ---- sắp theo giá (AC-002-7)

    @Test
    void search_sortByPrice_ordersAscendingAndDescending() {
        save(fashion, "Mid", 2000, true);
        save(fashion, "Low", 1000, true);
        save(fashion, "High", 3000, true);

        assertThat(names(service.search(params(null, null, "price_asc", null)))).containsExactly("Low", "Mid", "High");
        assertThat(names(service.search(params(null, null, "price_desc", null)))).containsExactly("High", "Mid", "Low");
    }

    @Test
    void search_sortByPrice_whenEqualPrices_ordersByIdAscendingStableAcrossPages() {
        for (int i = 0; i < 25; i++) {
            save(fashion, "Same " + i, 5000, true);
        }

        for (String sort : List.of("price_asc", "price_desc")) {
            List<Long> ids = new ArrayList<>();
            for (int p = 1; p <= 3; p++) {
                service.search(params(null, null, sort, String.valueOf(p))).products()
                        .forEach(card -> ids.add(card.id()));
            }
            assertThat(ids).as(sort).hasSize(25).doesNotHaveDuplicates()
                    .isSortedAccordingTo(Comparator.naturalOrder());
        }
    }

    @Test
    void search_whenUnknownSort_usesNewestFirst() {
        save(fashion, "Cũ", 1000, true);
        save(fashion, "Mới", 1000, true);

        assertThat(names(service.search(params(null, null, "xyz", null)))).containsExactly("Mới", "Cũ");
    }

    // ---- N+1 (AC-002-18)

    @Test
    void search_doesNotRunMoreStatementsAsProductsOnPageGrow() {
        // Cùng 13 sản phẩm (trang 1 đầy 12 dòng nên câu count luôn chạy), chỉ khác số danh mục.
        Category single = categories.save(new Category("Một danh mục", null));
        for (int i = 0; i < 13; i++) {
            save(single, "Cùng " + i, 1000, true);
        }
        long statementsOneCategory = countStatements(page(1));
        products.deleteAllInBatch();

        for (int i = 0; i < 13; i++) {
            save(categories.save(new Category("Danh mục " + i, null)), "Khác " + i, 1000, true);
        }
        long statementsManyCategories = countStatements(page(1));

        assertThat(statementsManyCategories).isEqualTo(statementsOneCategory).isLessThanOrEqualTo(3);
    }

    @Test
    void search_whenSingleProduct_runsAtMostThreeStatements() {
        save(fashion, "Một mình", 1000, true);

        assertThat(countStatements(page(1))).isLessThanOrEqualTo(3);
    }

    /** flush + clear trước khi đo, nếu không danh mục nằm sẵn trong cache cấp 1 và che mất N+1. */
    private long countStatements(ProductSearchParams params) {
        em.flush();
        em.clear();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        ProductListView view = service.search(params);

        assertThat(view.products()).isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    // ---- trang chủ (QĐ5, D3: AC-009-1, AC-002-3, AC-002-18)

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Product saveWithStock(Category category, String name, int stock, boolean active) {
        Product p = new Product(category, name, "Mô tả " + name, BigDecimal.valueOf(1000), stock);
        p.setActive(active);
        return products.saveAndFlush(p);
    }

    /** {@code created_at} do @PrePersist đặt và {@code updatable = false}, nên đổi bằng SQL để điều khiển thứ tự. */
    private void setCreatedAt(Product product, Instant createdAt) {
        em.createNativeQuery("update products set created_at = :t where id = :id")
                .setParameter("t", createdAt)
                .setParameter("id", product.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private static List<Long> ids(List<Product> list) {
        return list.stream().map(Product::getId).toList();
    }

    @Test
    void findTop3Newest_ordersByCreatedAtDescThenIdDescAndLimitsToThree() {
        Product oldest = save(fashion, "Cũ nhất", 1000, true);
        Product older = save(fashion, "Cũ hơn", 1000, true);
        Product tiedLowerId = save(fashion, "Cùng giờ id nhỏ", 1000, true);
        Product tiedHigherId = save(fashion, "Cùng giờ id lớn", 1000, true);
        setCreatedAt(oldest, T0);
        setCreatedAt(older, T0.plusSeconds(3600));
        setCreatedAt(tiedLowerId, T0.plusSeconds(7200));
        setCreatedAt(tiedHigherId, T0.plusSeconds(7200));

        List<Product> newest = products.findTop3ByActiveTrueOrderByCreatedAtDescIdDesc();

        assertThat(tiedHigherId.getId()).isGreaterThan(tiedLowerId.getId());
        assertThat(ids(newest)).containsExactly(tiedHigherId.getId(), tiedLowerId.getId(), older.getId());
    }

    @Test
    void findTop3Newest_excludesInactiveProductsEvenWhenNewest() {
        Product visibleOld = save(fashion, "Đang bán cũ", 1000, true);
        Product visibleNew = save(fashion, "Đang bán mới", 1000, true);
        Product hiddenNewest = save(fashion, "Ẩn mới nhất", 1000, false);
        setCreatedAt(visibleOld, T0);
        setCreatedAt(visibleNew, T0.plusSeconds(60));
        setCreatedAt(hiddenNewest, T0.plusSeconds(600));

        List<Product> newest = products.findTop3ByActiveTrueOrderByCreatedAtDescIdDesc();

        assertThat(ids(newest)).containsExactly(visibleNew.getId(), visibleOld.getId());
    }

    @Test
    void findTop3Newest_loadsCategoryInTheSameStatement() {
        save(fashion, "Áo", 1000, true);
        save(electronics, "Tai nghe", 1000, true);
        em.flush();
        em.clear();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<Product> newest = products.findTop3ByActiveTrueOrderByCreatedAtDescIdDesc();
        newest.forEach(p -> p.getCategory().getName());

        assertThat(newest).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void findTop3LowStock_includesOnlyStockOneToFive() {
        saveWithStock(fashion, "Hết hàng", 0, true);
        Product one = saveWithStock(fashion, "Còn 1", 1, true);
        Product five = saveWithStock(fashion, "Còn 5", 5, true);
        saveWithStock(fashion, "Còn 6", 6, true);
        saveWithStock(fashion, "Còn nhiều", 100, true);

        List<Product> low = products.findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(1, 5);

        assertThat(ids(low)).containsExactly(one.getId(), five.getId());
    }

    @Test
    void findTop3LowStock_excludesInactiveProductWithLowStock() {
        saveWithStock(fashion, "Ẩn còn 2", 2, false);
        Product visible = saveWithStock(fashion, "Bán còn 4", 4, true);

        List<Product> low = products.findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(1, 5);

        assertThat(ids(low)).containsExactly(visible.getId());
    }

    @Test
    void findTop3LowStock_returnsAtMostThreeOrderedByStockThenId() {
        Product four = saveWithStock(fashion, "Còn 4", 4, true);
        Product threeFirst = saveWithStock(fashion, "Còn 3 trước", 3, true);
        Product two = saveWithStock(fashion, "Còn 2", 2, true);
        Product threeSecond = saveWithStock(fashion, "Còn 3 sau", 3, true);
        Product five = saveWithStock(fashion, "Còn 5", 5, true);

        List<Product> low = products.findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(1, 5);

        assertThat(threeFirst.getId()).isLessThan(threeSecond.getId());
        assertThat(ids(low)).containsExactly(two.getId(), threeFirst.getId(), threeSecond.getId());
        assertThat(ids(low)).doesNotContain(four.getId(), five.getId());
    }

    @Test
    void home_buildsFeaturedMoreNewAndLowStockFromRealData() {
        Product oldest = saveWithStock(fashion, "Cũ nhất", 50, true);
        Product middle = saveWithStock(electronics, "Giữa", 3, true);
        Product newest = saveWithStock(fashion, "Mới nhất", 50, true);
        Product hiddenNewest = saveWithStock(fashion, "Ẩn", 2, false);
        Product soldOut = saveWithStock(fashion, "Hết hàng", 0, true);
        setCreatedAt(soldOut, T0.minusSeconds(60));
        setCreatedAt(oldest, T0);
        setCreatedAt(middle, T0.plusSeconds(60));
        setCreatedAt(newest, T0.plusSeconds(120));
        setCreatedAt(hiddenNewest, T0.plusSeconds(180));

        HomeView view = service.home();

        assertThat(view.featured().id()).isEqualTo(newest.getId());
        assertThat(view.featured().categoryName()).isEqualTo("Thời trang");
        assertThat(view.moreNew()).extracting(ProductCardView::id).containsExactly(middle.getId(), oldest.getId());
        assertThat(view.lowStock()).containsExactly(
                new LowStockCardView(middle.getId(), "Giữa", 3, null));
        assertThat(view.categories()).extracting(CategoryOption::name).containsExactly("Điện tử", "Thời trang");
    }

    @Test
    void home_runsExactlyThreeStatementsEvenWithThreeProductsInThreeCategories() {
        Category books = categories.save(new Category("Sách", null));
        saveWithStock(fashion, "Áo", 3, true);
        saveWithStock(electronics, "Tai nghe", 2, true);
        saveWithStock(books, "Truyện", 4, true);
        em.flush();
        em.clear();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        HomeView view = service.home();

        assertThat(view.featured()).isNotNull();
        assertThat(view.moreNew()).hasSize(2);
        assertThat(view.lowStock()).hasSize(3);
        assertThat(view.categories()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void home_whenNoProducts_hasNoFeaturedAndEmptyProductLists() {
        HomeView view = service.home();

        assertThat(view.featured()).isNull();
        assertThat(view.moreNew()).isEmpty();
        assertThat(view.lowStock()).isEmpty();
        assertThat(view.categories()).extracting(CategoryOption::name).containsExactly("Điện tử", "Thời trang");
    }

    @Test
    void home_whenDatabaseHasNoProductsAndNoCategories_isCompletelyEmpty() {
        categories.deleteAllInBatch();
        em.clear();

        HomeView view = service.home();

        assertThat(view.featured()).isNull();
        assertThat(view.moreNew()).isEmpty();
        assertThat(view.lowStock()).isEmpty();
        assertThat(view.categories()).isEmpty();
    }

    @Test
    void categories_returnsAllCategoriesInVietnameseOrder() {
        assertThat(service.categories()).extracting(CategoryOption::name).containsExactly("Điện tử", "Thời trang");
    }

    // ---- chi tiết (AC-002-14, 15)

    @Test
    void getDetail_whenActive_returnsCategoryName() {
        Product p = save(electronics, "Tai nghe", 990000, true);
        em.clear();

        ProductDetailView view = service.getDetail(p.getId());

        assertThat(view.name()).isEqualTo("Tai nghe");
        assertThat(view.categoryName()).isEqualTo("Điện tử");
        assertThat(view.price()).isEqualByComparingTo("990000");
        assertThat(view.inStock()).isTrue();
    }

    @Test
    void getDetail_whenHidden_throwsNotFound() {
        Product hidden = save(fashion, "Ly thủy tinh", 1000, false);

        assertThatThrownBy(() -> service.getDetail(hidden.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDetail_whenUnknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.getDetail(999_999_999L)).isInstanceOf(NotFoundException.class);
    }
}

package com.minishop.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link CatalogRoute} is the source of truth of the list screen's filters (QĐ3 of the D3 plan): two routes that mean
 * the same must be equal, and changing a filter goes back to page 1 (AC-002-9). Plain Java, no toolkit.
 */
class CatalogRouteTest {

    private static final CatalogRoute DEEP = new CatalogRoute("áo", 3L, "price_asc", 4);

    @Test
    void all_isNewestFirstOnPageOneWithoutFilters() {
        CatalogRoute route = CatalogRoute.all();

        assertThat(route.query()).isNull();
        assertThat(route.categoryId()).isNull();
        assertThat(route.sortKey()).isEqualTo("newest");
        assertThat(route.page()).isEqualTo(1);
    }

    @Test
    void withQuery_goesBackToPageOneAndKeepsTheOtherFilters() {
        assertThat(DEEP.withQuery("quần")).isEqualTo(new CatalogRoute("quần", 3L, "price_asc", 1));
    }

    @Test
    void withCategory_goesBackToPageOneAndKeepsTheOtherFilters() {
        assertThat(DEEP.withCategory(9L)).isEqualTo(new CatalogRoute("áo", 9L, "price_asc", 1));
        assertThat(DEEP.withCategory(null)).isEqualTo(new CatalogRoute("áo", null, "price_asc", 1));
    }

    @Test
    void withSort_goesBackToPageOneAndKeepsTheOtherFilters() {
        assertThat(DEEP.withSort("price_desc")).isEqualTo(new CatalogRoute("áo", 3L, "price_desc", 1));
    }

    @Test
    void withPage_keepsAllThreeFilters() {
        assertThat(DEEP.withPage(2)).isEqualTo(new CatalogRoute("áo", 3L, "price_asc", 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void constructor_withAPageBelowOne_isRejected(int page) {
        assertThatIllegalArgumentException().isThrownBy(() -> new CatalogRoute(null, null, "newest", page));
    }

    @Test
    void withPage_zero_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> CatalogRoute.all().withPage(0));
    }

    @Test
    void constructor_withAPageBeyondTheLastPage_isStillValid() {
        // AC-002-11: the screen shows "not found" for it, the route does not refuse it.
        assertThat(new CatalogRoute(null, null, "newest", 10_000).page()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void constructor_withABlankQuery_storesNull(String blank) {
        assertThat(new CatalogRoute(blank, null, "newest", 1).query()).isNull();
    }

    @Test
    void constructor_stripsAndCleansTheQuery() {
        assertThat(new CatalogRoute("  áo thun\u0000 ", null, "newest", 1).query()).isEqualTo("áo thun");
    }

    @Test
    void constructor_keepsScriptTextVerbatim() {
        // Output encoding is not the route's job (AC-002-17); the text must survive unchanged.
        assertThat(CatalogRoute.search("<script>alert(1)</script>").query()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void constructor_cutsTheQueryTo100CodePoints() {
        String longQuery = "a".repeat(150);

        assertThat(new CatalogRoute(longQuery, null, "newest", 1).query()).isEqualTo("a".repeat(100));
    }

    @Test
    void constructor_doesNotSplitASurrogatePairWhenCutting() {
        String emojis = "😀".repeat(120);

        String query = new CatalogRoute(emojis, null, "newest", 1).query();

        assertThat(query.codePointCount(0, query.length())).isEqualTo(100);
        assertThat(query).isEqualTo("😀".repeat(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bogus", "PRICE_ASC", "price", " newest"})
    void constructor_withAnUnknownSort_fallsBackToNewest(String sort) {
        assertThat(new CatalogRoute(null, null, sort, 1).sortKey()).isEqualTo("newest");
    }

    @Test
    void constructor_withANullSort_fallsBackToNewest() {
        assertThat(new CatalogRoute(null, null, null, 1).sortKey()).isEqualTo("newest");
    }

    @ParameterizedTest
    @ValueSource(strings = {"newest", "price_asc", "price_desc"})
    void constructor_keepsAKnownSort(String sort) {
        assertThat(new CatalogRoute(null, null, sort, 1).sortKey()).isEqualTo(sort);
    }

    @Test
    void search_withAnEmptyOrBlankQuery_equalsAll() {
        assertThat(CatalogRoute.search("")).isEqualTo(CatalogRoute.all());
        assertThat(CatalogRoute.search("   ")).isEqualTo(CatalogRoute.all());
        assertThat(CatalogRoute.search(null)).isEqualTo(CatalogRoute.all());
    }

    @Test
    void search_withText_filtersOnlyByTheQuery() {
        assertThat(CatalogRoute.search("áo")).isEqualTo(new CatalogRoute("áo", null, "newest", 1));
    }

    @Test
    void inCategory_filtersOnlyByTheCategory() {
        assertThat(CatalogRoute.inCategory(4)).isEqualTo(new CatalogRoute(null, 4L, "newest", 1));
    }

    @Test
    void routesThatMeanTheSame_areEqual() {
        assertThat(new CatalogRoute("  áo ", null, "nonsense", 1)).isEqualTo(new CatalogRoute("áo", null, "newest", 1))
                .hasSameHashCodeAs(new CatalogRoute("áo", null, "newest", 1));
    }

    @Test
    void productDetailRoute_withANullBack_goesBackToTheFullList() {
        assertThat(new ProductDetailRoute(5L, null).back()).isEqualTo(CatalogRoute.all());
    }

    @Test
    void productDetailRoute_keepsTheBackRoute() {
        assertThat(new ProductDetailRoute(5L, DEEP).back()).isEqualTo(DEEP);
    }
}

package com.minishop.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin routes are the single source of truth of a screen's state (QĐ4 of the D5 plan): two routes with the same
 * meaning must be equal, so {@code Navigator} and the presenters can tell "same screen" from "new screen". Plain Java,
 * no JavaFX toolkit.
 */
class AdminRoutesTest {

    // ------------------------------------------------------------------ AdminProductsRoute

    @Test
    void productsRoute_first_hasNoQueryAndPageOne() {
        AdminProductsRoute route = AdminProductsRoute.first();

        assertThat(route.query()).isNull();
        assertThat(route.page()).isEqualTo(1);
    }

    @Test
    void productsRoute_stripsTheQuery() {
        assertThat(new AdminProductsRoute("  bàn phím  ", 1).query()).isEqualTo("bàn phím");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "\u0007"})
    void productsRoute_blankOrControlOnlyQuery_becomesNull(String raw) {
        assertThat(new AdminProductsRoute(raw, 1).query()).isNull();
    }

    @Test
    void productsRoute_dropsControlCharactersInsideTheQuery() {
        assertThat(new AdminProductsRoute("bàn\u0000 phím\u0007", 1).query()).isEqualTo("bàn phím");
    }

    @Test
    void productsRoute_cutsTheQueryAt100CodePoints_notInTheMiddleOfASurrogatePair() {
        String emoji = "😀";   // one code point, two chars
        String raw = "a".repeat(99) + emoji + "tail";

        String query = new AdminProductsRoute(raw, 1).query();

        assertThat(query.codePointCount(0, query.length())).isEqualTo(AdminProductsRoute.MAX_QUERY_LENGTH);
        assertThat(query).endsWith(emoji);
    }

    @Test
    void productsRoute_aQueryOfExactly100CodePoints_isKept() {
        String raw = "x".repeat(100);

        assertThat(new AdminProductsRoute(raw, 1).query()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void productsRoute_pageBelowOne_becomesOne(int page) {
        assertThat(new AdminProductsRoute(null, page).page()).isEqualTo(1);
    }

    @Test
    void productsRoute_aPagePastTheEnd_staysValid() {
        assertThat(new AdminProductsRoute(null, 9_999).page()).isEqualTo(9_999);
    }

    @Test
    void productsRoute_search_goesBackToPageOne_andNormalisesTheQuery() {
        AdminProductsRoute route = new AdminProductsRoute("cũ", 4).search("  mới ");

        assertThat(route).isEqualTo(new AdminProductsRoute("mới", 1));
    }

    @Test
    void productsRoute_searchWithBlank_clearsTheQuery() {
        assertThat(new AdminProductsRoute("cũ", 4).search("  ")).isEqualTo(AdminProductsRoute.first());
    }

    @Test
    void productsRoute_withPage_keepsTheQuery() {
        assertThat(new AdminProductsRoute("bàn", 1).withPage(3)).isEqualTo(new AdminProductsRoute("bàn", 3));
    }

    @Test
    void productsRoute_withPageBelowOne_becomesPageOne() {
        assertThat(new AdminProductsRoute("bàn", 5).withPage(0).page()).isEqualTo(1);
    }

    @Test
    void productsRoute_routesWithTheSameMeaning_areEqual() {
        assertThat(new AdminProductsRoute(" bàn ", 0)).isEqualTo(new AdminProductsRoute("bàn", 1));
        assertThat(new AdminProductsRoute("", 1)).isEqualTo(AdminProductsRoute.first());
        assertThat(new AdminProductsRoute(" bàn ", 0)).hasSameHashCodeAs(new AdminProductsRoute("bàn", 1));
    }

    @Test
    void productsRoute_differentPage_isNotEqual() {
        assertThat(new AdminProductsRoute("bàn", 1)).isNotEqualTo(new AdminProductsRoute("bàn", 2));
    }

    // ------------------------------------------------------------------ AdminOrdersRoute

    @Test
    void ordersRoute_all_hasNoStatusAndPageOne() {
        AdminOrdersRoute route = AdminOrdersRoute.all();

        assertThat(route.status()).isNull();
        assertThat(route.page()).isEqualTo(1);
    }

    @Test
    void ordersRoute_stripsTheStatus() {
        assertThat(new AdminOrdersRoute("  PENDING ", 1).status()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void ordersRoute_blankStatus_becomesNull(String raw) {
        assertThat(new AdminOrdersRoute(raw, 1).status()).isNull();
    }

    @Test
    void ordersRoute_anUnknownStatus_isKept_becauseTheServiceTreatsItAsAll() {
        assertThat(new AdminOrdersRoute("NO_SUCH_STATUS", 1).status()).isEqualTo("NO_SUCH_STATUS");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -7})
    void ordersRoute_pageBelowOne_becomesOne(int page) {
        assertThat(new AdminOrdersRoute("PENDING", page).page()).isEqualTo(1);
    }

    @Test
    void ordersRoute_withStatus_goesBackToPageOne() {
        AdminOrdersRoute route = new AdminOrdersRoute("PENDING", 4).withStatus("SHIPPING");

        assertThat(route).isEqualTo(new AdminOrdersRoute("SHIPPING", 1));
    }

    @Test
    void ordersRoute_withStatusNull_isTheAllFilterOnPageOne() {
        assertThat(new AdminOrdersRoute("PENDING", 4).withStatus(null)).isEqualTo(AdminOrdersRoute.all());
    }

    @Test
    void ordersRoute_withPage_keepsTheStatus() {
        assertThat(new AdminOrdersRoute("PENDING", 1).withPage(3)).isEqualTo(new AdminOrdersRoute("PENDING", 3));
    }

    @Test
    void ordersRoute_routesWithTheSameMeaning_areEqual() {
        assertThat(new AdminOrdersRoute(" ", -1)).isEqualTo(AdminOrdersRoute.all());
        assertThat(new AdminOrdersRoute(" PENDING", 2)).isEqualTo(new AdminOrdersRoute("PENDING", 2));
    }

    // ------------------------------------------------------------------ AdminProductFormRoute

    @Test
    void productFormRoute_newProduct_isNewAndKeepsBack() {
        AdminProductsRoute back = new AdminProductsRoute("bàn", 3);

        AdminProductFormRoute route = AdminProductFormRoute.newProduct(back);

        assertThat(route.isNew()).isTrue();
        assertThat(route.productId()).isNull();
        assertThat(route.back()).isEqualTo(back);
    }

    @Test
    void productFormRoute_edit_isNotNewAndKeepsIdAndBack() {
        AdminProductsRoute back = new AdminProductsRoute("bàn", 3);

        AdminProductFormRoute route = AdminProductFormRoute.edit(42L, back);

        assertThat(route.isNew()).isFalse();
        assertThat(route.productId()).isEqualTo(42L);
        assertThat(route.back()).isEqualTo(back);
    }

    @Test
    void productFormRoute_nullBack_becomesTheFirstProductsPage() {
        assertThat(new AdminProductFormRoute(7L, null).back()).isEqualTo(AdminProductsRoute.first());
        assertThat(AdminProductFormRoute.newProduct(null).back()).isEqualTo(AdminProductsRoute.first());
        assertThat(AdminProductFormRoute.edit(7L, null).back()).isEqualTo(AdminProductsRoute.first());
    }

    @Test
    void productFormRoute_equalsForTheSameMeaning() {
        assertThat(AdminProductFormRoute.edit(7L, null))
                .isEqualTo(AdminProductFormRoute.edit(7L, AdminProductsRoute.first()));
        assertThat(AdminProductFormRoute.edit(7L, AdminProductsRoute.first()))
                .isNotEqualTo(AdminProductFormRoute.edit(8L, AdminProductsRoute.first()));
        assertThat(AdminProductFormRoute.newProduct(null)).isNotEqualTo(AdminProductFormRoute.edit(7L, null));
    }

    // ------------------------------------------------------------------ AdminCategoryFormRoute

    @Test
    void categoryFormRoute_newCategory_isNewWithoutId() {
        AdminCategoryFormRoute route = AdminCategoryFormRoute.newCategory();

        assertThat(route.isNew()).isTrue();
        assertThat(route.categoryId()).isNull();
    }

    @Test
    void categoryFormRoute_edit_isNotNewAndCarriesTheId() {
        AdminCategoryFormRoute route = AdminCategoryFormRoute.edit(5L);

        assertThat(route.isNew()).isFalse();
        assertThat(route.categoryId()).isEqualTo(5L);
        assertThat(route).isEqualTo(new AdminCategoryFormRoute(5L)).isNotEqualTo(AdminCategoryFormRoute.edit(6L));
    }

    // ------------------------------------------------------------------ AdminOrderDetailRoute

    @Test
    void orderDetailRoute_nullBack_becomesTheFullOrderList() {
        assertThat(new AdminOrderDetailRoute(9L, null).back()).isEqualTo(AdminOrdersRoute.all());
    }

    @Test
    void orderDetailRoute_keepsTheBackRouteWithFilterAndPage() {
        AdminOrdersRoute back = new AdminOrdersRoute("SHIPPING", 3);

        AdminOrderDetailRoute route = new AdminOrderDetailRoute(9L, back);

        assertThat(route.orderId()).isEqualTo(9L);
        assertThat(route.back()).isEqualTo(back);
        assertThat(route).isEqualTo(new AdminOrderDetailRoute(9L, new AdminOrdersRoute(" SHIPPING", 3)));
    }

    // ------------------------------------------------------------------ AdminSection

    @Test
    void section_hasTheFourEntriesInTheSidebarOrder() {
        assertThat(AdminSection.values()).containsExactly(AdminSection.DASHBOARD, AdminSection.ORDERS,
                AdminSection.PRODUCTS, AdminSection.CATEGORIES);
        assertThat(Arrays.stream(AdminSection.values()).map(AdminSection::label))
                .containsExactly("Tổng quan", "Đơn hàng", "Sản phẩm", "Danh mục");
    }

    @Test
    void section_keysAndNavIds_areStableForTests() {
        assertThat(Arrays.stream(AdminSection.values()).map(AdminSection::key))
                .containsExactly("dashboard", "orders", "products", "categories");
        assertThat(Arrays.stream(AdminSection.values()).map(AdminSection::navId))
                .containsExactly("adminNav-dashboard", "adminNav-orders", "adminNav-products",
                        "adminNav-categories");
    }

    @Test
    void section_route_isTheFirstRouteOfTheSection() {
        assertThat(AdminSection.DASHBOARD.route()).isEqualTo(new AdminDashboardRoute());
        assertThat(AdminSection.ORDERS.route()).isEqualTo(AdminOrdersRoute.all());
        assertThat(AdminSection.PRODUCTS.route()).isEqualTo(AdminProductsRoute.first());
        assertThat(AdminSection.CATEGORIES.route()).isEqualTo(new AdminCategoriesRoute());
    }

    @Test
    void section_route_returnsAnEqualRouteEachTime_soReopeningTheSectionIsSafe() {
        for (AdminSection section : AdminSection.values()) {
            assertThat(section.route()).isEqualTo(section.route());
        }
    }
}

package com.minishop.catalog;

import com.minishop.common.PageNav;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.support.ManualBackgroundTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CatalogPresenter}: the list screen's state and decisions (AC-002-9, -10, -11, -17, AC-004-7, QĐ3, QĐ4, QĐ11
 * of the D3 plan). No JavaFX toolkit: the shell presenter is the real one (only {@code javafx.beans}); the service,
 * the navigator, the toasts and {@link AddToCart} are mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogPresenterTest {

    private static final List<CategoryOption> CATEGORIES = List.of(
            new CategoryOption(3L, "Thời trang"), new CategoryOption(4L, "Sách"));

    @Mock
    private ProductService productService;
    @Mock
    private AddToCart addToCart;
    @Mock
    private StorefrontPresenter storefront;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private UnexpectedErrors errors;

    private final ShellPresenter shell = new ShellPresenter();
    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(productService.search(any())).thenReturn(listOf(2, 21L, 2, null, 1));
    }

    private CatalogPresenter presenter(BackgroundTasks tasks) {
        return new CatalogPresenter(productService, addToCart, shell, storefront, navigator, tasks, toasts, errors);
    }

    private static ProductCardView card(long id, String name) {
        return new ProductCardView(id, name, "Thời trang", new BigDecimal("250000"), true, "/images/products/x.jpg");
    }

    private static ProductListView listOf(int cards, long total, int totalPages, String q, int page) {
        List<ProductCardView> products = new ArrayList<>();
        for (int i = 1; i <= cards; i++) {
            products.add(card(i, "Sản phẩm " + i));
        }
        return new ProductListView(products, total, totalPages,
                new ProductFilter(q, null, ProductSort.NEWEST, page), CATEGORIES);
    }

    // ------------------------------------------------------------------ route -> query

    @Test
    void load_mapsTheRouteToTheSearchParams() {
        CatalogPresenter presenter = presenter(direct);

        presenter.load(new CatalogRoute("áo", 3L, "price_asc", 4));

        verify(productService).search(new ProductSearchParams("áo", "3", "price_asc", "4"));
    }

    @Test
    void load_withNoFilters_sendsNullQueryAndNullCategory() {
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        verify(productService).search(new ProductSearchParams(null, null, "newest", "1"));
    }

    @Test
    void load_setsTheSelectedCategoryAndSortAtOnce_beforeTheDataArrives() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CatalogPresenter presenter = presenter(manual);

        presenter.load(new CatalogRoute(null, 3L, "price_desc", 1));

        assertThat(presenter.selectedCategoryIdProperty().get()).isEqualTo(3L);
        assertThat(presenter.sortKeyProperty().get()).isEqualTo("price_desc");
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        verifyNoInteractions(productService);
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter(direct).load(null));
    }

    // ------------------------------------------------------------------ what the screen shows

    @Test
    void load_showsTheCardsTheCategoriesAndThePageNav() {
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        assertThat(presenter.cards()).extracting(ProductCardView::name).containsExactly("Sản phẩm 1", "Sản phẩm 2");
        assertThat(presenter.categories()).containsExactlyElementsOf(CATEGORIES);
        assertThat(presenter.pageNavProperty().get()).isEqualTo(PageNav.of(1, 2));
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.contentVersionProperty().get()).isEqualTo(1);
    }

    @Test
    void load_resultText_usesTheTotalNumberOfElementsNotThePageSize() {
        // AC-002-10: 21 products in total, 12 on this page
        when(productService.search(any())).thenReturn(listOf(12, 21L, 2, null, 1));
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        assertThat(presenter.cards()).hasSize(12);
        assertThat(presenter.resultTextProperty().get()).isEqualTo("Tìm thấy 21 sản phẩm");
    }

    @Test
    void load_withoutProducts_setsEmpty() {
        // AC-002-11
        when(productService.search(any())).thenReturn(listOf(0, 0L, 0, "zzz", 1));
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.search("zzz"));

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.cards()).isEmpty();
        assertThat(presenter.resultTextProperty().get()).isEqualTo("Tìm thấy 0 sản phẩm");
    }

    @Test
    void load_withProducts_emptyIsFalse() {
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        assertThat(presenter.emptyProperty().get()).isFalse();
    }

    @Test
    void load_pageBeyondTheEndWithProductsElsewhere_isEmptyButKeepsTheTotal() {
        when(productService.search(any())).thenReturn(listOf(0, 21L, 2, null, 9));
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all().withPage(9));

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.resultTextProperty().get()).isEqualTo("Tìm thấy 21 sản phẩm");
    }

    @Test
    void load_putsTheNormalisedQueryInTheSearchBox() {
        when(productService.search(any())).thenReturn(listOf(1, 1L, 1, "áo thun", 1));
        shell.setSearchText("  ÁO THUN  ");
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.search("  áo thun "));

        assertThat(shell.searchTextProperty().get()).isEqualTo("áo thun");
    }

    @Test
    void load_withAScriptQuery_putsTheTextInTheSearchBoxVerbatim() {
        // AC-002-17: a text control shows it as plain text; nothing is rewritten or dropped.
        String script = "<script>alert(1)</script>";
        when(productService.search(any())).thenReturn(listOf(0, 0L, 0, script, 1));
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.search(script));

        assertThat(shell.searchTextProperty().get()).isEqualTo(script);
        verify(productService).search(new ProductSearchParams(script, null, "newest", "1"));
    }

    @Test
    void load_withoutAQuery_clearsTheSearchBox() {
        shell.setSearchText("old");
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        assertThat(shell.searchTextProperty().get()).isEmpty();
    }

    @Test
    void load_tellsTheStorefrontTheCategories() {
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        verify(storefront).updateCategories(CATEGORIES);
    }

    @Test
    void load_whileWaiting_isLoadingAndShowsNothingYet() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CatalogPresenter presenter = presenter(manual);

        presenter.load(CatalogRoute.all());

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.cards()).isEmpty();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.cards()).hasSize(2);
    }

    // ------------------------------------------------------------------ navigation (route is the truth)

    private CatalogPresenter loaded(CatalogRoute route) {
        CatalogPresenter presenter = presenter(direct);
        presenter.load(route);
        return presenter;
    }

    @Test
    void goToPage_navigatesToTheOtherPageAndKeepsAllThreeFilters() {
        // AC-002-9
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 1));

        presenter.goToPage(2);

        verify(navigator).navigateTo(new CatalogRoute("áo", 3L, "price_asc", 2));
    }

    @Test
    void goToPage_belowOne_goesToPageOne() {
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 3));

        presenter.goToPage(-4);

        verify(navigator).navigateTo(new CatalogRoute("áo", 3L, "price_asc", 1));
    }

    @Test
    void goToPage_theCurrentPage_doesNothing() {
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 2));

        presenter.goToPage(2);

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void selectSort_goesBackToPageOneAndKeepsQueryAndCategory() {
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 4));

        presenter.selectSort("price_desc");

        verify(navigator).navigateTo(new CatalogRoute("áo", 3L, "price_desc", 1));
    }

    @Test
    void selectSort_theCurrentSortOnPageOne_doesNothing() {
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 1));

        presenter.selectSort("price_asc");

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void selectSort_theCurrentSortOnALaterPage_goesBackToPageOne() {
        // Same rule as selectCategory: a filter change resets the page, even to the value already chosen.
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 4));

        presenter.selectSort("price_asc");

        verify(navigator).navigateTo(new CatalogRoute("áo", 3L, "price_asc", 1));
    }

    @Test
    void selectSort_unknownKey_isTreatedAsNewest() {
        CatalogPresenter presenter = loaded(new CatalogRoute(null, null, "price_asc", 1));

        presenter.selectSort("bogus");

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void selectCategory_goesBackToPageOneAndKeepsQueryAndSort() {
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 4));

        presenter.selectCategory(4L);

        verify(navigator).navigateTo(new CatalogRoute("áo", 4L, "price_asc", 1));
    }

    @Test
    void selectCategory_null_removesTheCategoryFilter() {
        CatalogPresenter presenter = loaded(new CatalogRoute("áo", 3L, "price_asc", 4));

        presenter.selectCategory(null);

        verify(navigator).navigateTo(new CatalogRoute("áo", null, "price_asc", 1));
    }

    @Test
    void selectCategory_theCurrentCategoryOnPageOne_doesNothing() {
        CatalogPresenter presenter = loaded(new CatalogRoute(null, 3L, "newest", 1));

        presenter.selectCategory(3L);

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void selectCategory_theCurrentCategoryOnALaterPage_goesBackToPageOne() {
        CatalogPresenter presenter = loaded(new CatalogRoute(null, 3L, "newest", 2));

        presenter.selectCategory(3L);

        verify(navigator).navigateTo(new CatalogRoute(null, 3L, "newest", 1));
    }

    @Test
    void clearFilters_navigatesToTheFullList() {
        CatalogPresenter presenter = loaded(new CatalogRoute("zzz", 3L, "price_desc", 5));

        presenter.clearFilters();

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void clearFilters_onTheFullList_stillNavigatesSoTheScreenIsRebuilt() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());

        presenter.clearFilters();

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void openDetail_remembersThisRouteAsTheWayBack() {
        CatalogRoute route = new CatalogRoute("áo", 3L, "price_asc", 2);
        CatalogPresenter presenter = loaded(route);

        presenter.openDetail(7L);

        verify(navigator).navigateTo(new ProductDetailRoute(7L, route));
    }

    @Test
    void route_isTheOneGivenToLoad() {
        CatalogRoute route = new CatalogRoute("áo", 3L, "price_asc", 2);

        assertThat(loaded(route).route()).isEqualTo(route);
    }

    // ------------------------------------------------------------------ dispose and failures

    @Test
    void dispose_beforeTheLoadCompletes_changesNoProperty() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CatalogPresenter presenter = presenter(manual);
        shell.setSearchText("keep");
        presenter.load(CatalogRoute.all());

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.cards()).isEmpty();
        assertThat(presenter.categories()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.resultTextProperty().get()).isEmpty();
        assertThat(presenter.pageNavProperty().get()).isNull();
        assertThat(presenter.contentVersionProperty().get()).isZero();
        assertThat(shell.searchTextProperty().get()).isEqualTo("keep");
        verifyNoInteractions(storefront, errors, toasts, navigator);
    }

    @Test
    void dispose_calledTwiceOrBeforeLoad_isHarmless() {
        CatalogPresenter presenter = presenter(direct);

        presenter.dispose();
        presenter.dispose();

        verifyNoInteractions(productService);
    }

    @Test
    void load_whenTheServiceFails_reportsOnceAndStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.search(any())).thenThrow(failure);
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.all());

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(direct.unhandled()).isEmpty();
        verifyNoInteractions(toasts);
    }

    @Test
    void load_whenTheServiceFailsAfterAGoodLoad_keepsTheCardsOnScreen() {
        CatalogPresenter presenter = presenter(direct);
        presenter.load(CatalogRoute.all());
        when(productService.search(any())).thenThrow(new IllegalStateException("boom"));

        presenter.load(CatalogRoute.all().withPage(2));

        assertThat(presenter.cards()).hasSize(2);
        assertThat(presenter.loadingProperty().get()).isFalse();
    }

    @Test
    void load_aSecondTime_cancelsTheFirstOne() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CatalogPresenter presenter = presenter(manual);
        presenter.load(CatalogRoute.all());

        presenter.load(CatalogRoute.all().withPage(2));

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(manual.handle(1).isCancelled()).isFalse();
    }

    // ------------------------------------------------------------------ add to cart

    @SuppressWarnings("unchecked")
    private Consumer<AddToCartResult> captureAddCallback(long productId) {
        ArgumentCaptor<Consumer<AddToCartResult>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(addToCart).add(eq(productId), eq("1"),
                callback.capture());
        return callback.getValue();
    }

    @Test
    void add_sendsExactlyOneUnit() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());

        presenter.add(5L);

        verify(addToCart).add(eq(5L), eq("1"), any());
    }

    @Test
    void add_whileWaiting_marksTheProductBusyAndIgnoresASecondClick() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());

        presenter.add(5L);
        presenter.add(5L);

        assertThat(presenter.busyProducts()).containsExactly(5L);
        verify(addToCart, times(1)).add(eq(5L), any(), any());
    }

    @Test
    void add_twoDifferentProducts_areIndependent() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());

        presenter.add(5L);
        presenter.add(6L);

        assertThat(presenter.busyProducts()).containsExactlyInAnyOrder(5L, 6L);
    }

    @Test
    void add_whenFinishedWithAdded_freesTheProductAndDoesNothingElse() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);

        captureAddCallback(5L).accept(AddToCartResult.ADDED);

        assertThat(presenter.busyProducts()).isEmpty();
        verifyNoInteractions(toasts);
        verify(productService, times(1)).search(any());
    }

    @Test
    void add_afterItFinished_theSameProductCanBeAddedAgain() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);
        captureAddCallback(5L).accept(AddToCartResult.ADDED);

        presenter.add(5L);

        verify(addToCart, times(2)).add(eq(5L), any(), any());
        assertThat(presenter.busyProducts()).containsExactly(5L);
    }

    @Test
    void add_whenTheProductIsGone_showsTheNotFoundToastAndReloads() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);

        captureAddCallback(5L).accept(AddToCartResult.PRODUCT_GONE);

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(productService, times(2)).search(any());
        assertThat(presenter.busyProducts()).isEmpty();
    }

    @Test
    void add_whenOutOfStock_reloadsWithoutAToast() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);

        captureAddCallback(5L).accept(AddToCartResult.OUT_OF_STOCK);

        verify(productService, times(2)).search(any());
        verifyNoInteractions(toasts);
        assertThat(presenter.busyProducts()).isEmpty();
    }

    @Test
    void add_whenFailedOrInvalid_doesNotReloadAndFreesTheProduct() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);
        captureAddCallback(5L).accept(AddToCartResult.FAILED);
        assertThat(presenter.busyProducts()).isEmpty();

        presenter.add(6L);
        captureAddCallback(6L).accept(AddToCartResult.INVALID_QUANTITY);

        assertThat(presenter.busyProducts()).isEmpty();
        verify(productService, times(1)).search(any());
        verifyNoInteractions(toasts, errors);
    }

    @Test
    void add_afterDispose_isIgnored() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.dispose();

        presenter.add(5L);

        verifyNoInteractions(addToCart);
        assertThat(presenter.busyProducts()).isEmpty();
    }

    @Test
    void add_whenTheScreenIsDisposedBeforeTheResult_doesNotReloadOrToast() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);
        presenter.dispose();

        captureAddCallback(5L).accept(AddToCartResult.PRODUCT_GONE);

        verifyNoInteractions(toasts);
        verify(productService, times(1)).search(any());
    }

    // ------------------------------------------------------------------ search box and categories on reload

    @Test
    void load_firstSuccessfulLoad_setsTheSearchText() {
        when(productService.search(any())).thenReturn(listOf(1, 1L, 1, "áo", 1));
        shell.setSearchText("something else");
        CatalogPresenter presenter = presenter(direct);

        presenter.load(CatalogRoute.search("áo"));

        assertThat(shell.searchTextProperty().get()).isEqualTo("áo");
    }

    @Test
    void reloadAfterOutOfStock_doesNotOverwriteWhatTheUserTypedInTheSearchBox() {
        when(productService.search(any())).thenReturn(listOf(1, 1L, 1, "áo", 1));
        CatalogPresenter presenter = loaded(CatalogRoute.search("áo"));
        presenter.add(5L);
        shell.setSearchText("giày");

        captureAddCallback(5L).accept(AddToCartResult.OUT_OF_STOCK);

        verify(productService, times(2)).search(any());
        assertThat(shell.searchTextProperty().get()).isEqualTo("giày");
    }

    @Test
    void reloadAfterProductGone_doesNotOverwriteWhatTheUserTypedInTheSearchBox() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);
        shell.setSearchText("typed later");

        captureAddCallback(5L).accept(AddToCartResult.PRODUCT_GONE);

        assertThat(shell.searchTextProperty().get()).isEqualTo("typed later");
    }

    @Test
    void load_whenTheFirstLoadFailed_theNextSuccessfulLoadStillSetsTheSearchText() {
        when(productService.search(any())).thenThrow(new IllegalStateException("boom"));
        CatalogPresenter presenter = presenter(direct);
        presenter.load(CatalogRoute.all());
        shell.setSearchText("stale");
        org.mockito.Mockito.doReturn(listOf(1, 1L, 1, "áo", 1)).when(productService).search(any());

        presenter.load(CatalogRoute.search("áo"));

        assertThat(shell.searchTextProperty().get()).isEqualTo("áo");
    }

    @Test
    void reload_updatesTheStorefrontCategoriesOnEveryLoad() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);

        captureAddCallback(5L).accept(AddToCartResult.OUT_OF_STOCK);

        verify(storefront, times(2)).updateCategories(CATEGORIES);
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    private static final CatalogRoute SEARCH_ROUTE = new CatalogRoute("áo", 3L, "price_asc", 2);
    private static final ProductSearchParams SEARCH_PARAMS = new ProductSearchParams("áo", "3", "price_asc", "2");

    @Test
    void load_firstLoadFails_setsLoadFailed_reportsOnce_andStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.search(any())).thenThrow(failure);
        CatalogPresenter presenter = presenter(direct);

        presenter.load(SEARCH_ROUTE);

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.cards()).isEmpty();
        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsTheSameRouteAgain_clearsLoadFailed_andShowsTheContent() {
        when(productService.search(any())).thenThrow(new IllegalStateException("down"))
                .thenReturn(listOf(2, 21L, 2, "áo", 2));
        CatalogPresenter presenter = presenter(direct);
        presenter.load(SEARCH_ROUTE);

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.cards()).hasSize(2);
        assertThat(presenter.route()).isEqualTo(SEARCH_ROUTE);
        verify(productService, times(2)).search(SEARCH_PARAMS);
        verify(errors, times(1)).report(any());
        assertThat(shell.searchTextProperty().get()).isEqualTo("áo");
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        RuntimeException failure = new IllegalStateException("down");
        when(productService.search(any())).thenThrow(failure);
        CatalogPresenter presenter = presenter(direct);
        presenter.load(SEARCH_ROUTE);

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        when(productService.search(any())).thenThrow(new IllegalStateException("down"))
                .thenReturn(listOf(2, 21L, 2, null, 1));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CatalogPresenter presenter = presenter(manual);
        presenter.load(SEARCH_ROUTE);
        manual.run(0);
        presenter.retry();

        presenter.retry();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.loadingProperty().get()).isTrue();
    }

    @Test
    void retry_afterDispose_doesNothing() {
        when(productService.search(any())).thenThrow(new IllegalStateException("down"));
        CatalogPresenter presenter = presenter(direct);
        presenter.load(SEARCH_ROUTE);
        presenter.dispose();

        presenter.retry();

        verify(productService, times(1)).search(any());
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void retry_whenNothingFailed_doesNothing() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());

        presenter.retry();

        verify(productService, times(1)).search(any());
        assertThat(presenter.contentVersionProperty().get()).isEqualTo(1);
    }

    @Test
    void reload_failsAfterContentWasShown_keepsTheContent_andLoadFailedStaysFalse() {
        CatalogPresenter presenter = loaded(CatalogRoute.all());
        presenter.add(5L);
        RuntimeException failure = new IllegalStateException("down");
        when(productService.search(any())).thenThrow(failure);

        captureAddCallback(5L).accept(AddToCartResult.OUT_OF_STOCK);

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.cards()).hasSize(2);
        verify(errors, times(1)).report(failure);
        presenter.retry();
        verify(productService, times(2)).search(any());
    }
}

package com.minishop.catalog;

import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
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
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link HomePresenter}: the home page's state and decisions (AC-009-1, AC-009-3, QĐ5, QĐ11 of the D3 plan). No
 * JavaFX toolkit: the shell presenter is the real one; the service, navigator, toasts and {@link AddToCart} are mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomePresenterTest {

    private static final String SENTENCE = "Trả tiền khi hàng tới tay.";

    private static final ProductDetailView FEATURED = new ProductDetailView(9L, "Tai nghe không dây", "Điện tử",
            new BigDecimal("1250000"), "Mô tả", true, "/images/products/tai-nghe.jpg");
    private static final List<ProductCardView> MORE_NEW = List.of(
            new ProductCardView(8L, "Loa mini", "Điện tử", new BigDecimal("450000"), true, "/images/products/loa.jpg"),
            new ProductCardView(7L, "Sách Java", "Sách", new BigDecimal("99000"), true, "/images/products/java.jpg"));
    private static final List<LowStockCardView> LOW_STOCK = List.of(
            new LowStockCardView(5L, "Đèn bàn", 1, "/images/products/den.jpg"),
            new LowStockCardView(6L, "Bóng đá", 3, "/images/products/bong.jpg"));
    private static final List<CategoryOption> CATEGORIES = List.of(
            new CategoryOption(2L, "Điện tử"), new CategoryOption(1L, "Sách"));
    private static final HomeView HOME = new HomeView(FEATURED, MORE_NEW, LOW_STOCK, CATEGORIES);

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
        when(productService.home()).thenReturn(HOME);
    }

    private HomePresenter presenter(BackgroundTasks tasks) {
        return new HomePresenter(productService, addToCart, shell, storefront, navigator, tasks, toasts, errors);
    }

    private HomePresenter loaded() {
        HomePresenter presenter = presenter(direct);
        presenter.load();
        return presenter;
    }

    private static List<CategoryOption> categories(String... names) {
        return java.util.stream.IntStream.range(0, names.length)
                .mapToObj(i -> new CategoryOption((long) i + 1, names[i])).toList();
    }

    @SuppressWarnings("unchecked")
    private Consumer<AddToCartResult> captureAddCallback(long productId) {
        ArgumentCaptor<Consumer<AddToCartResult>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(addToCart).add(eq(productId), eq("1"), callback.capture());
        return callback.getValue();
    }

    // ------------------------------------------------------------------ load

    @Test
    void load_setsFeaturedMoreNewAndLowStockFromTheHomeView() {
        HomePresenter presenter = loaded();

        assertThat(presenter.featuredProperty().get()).isEqualTo(FEATURED);
        assertThat(presenter.featuredPriceTextProperty().get()).isEqualTo("1.250.000 ₫");
        assertThat(presenter.moreNew()).containsExactlyElementsOf(MORE_NEW);
        assertThat(presenter.lowStock()).containsExactlyElementsOf(LOW_STOCK);
        assertThat(presenter.hasLowStockProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.contentVersionProperty().get()).isEqualTo(1);
    }

    @Test
    void load_withNoLowStock_hasLowStockIsFalse() {
        when(productService.home()).thenReturn(new HomeView(FEATURED, MORE_NEW, List.of(), CATEGORIES));

        HomePresenter presenter = loaded();

        assertThat(presenter.lowStock()).isEmpty();
        assertThat(presenter.hasLowStockProperty().get()).isFalse();
    }

    @Test
    void load_withNoFeaturedProduct_leavesFeaturedNullAndThePriceEmpty() {
        when(productService.home()).thenReturn(new HomeView(null, List.of(), List.of(), List.of()));

        HomePresenter presenter = loaded();

        assertThat(presenter.featuredProperty().get()).isNull();
        assertThat(presenter.featuredPriceTextProperty().get()).isEmpty();
        assertThat(presenter.moreNew()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.leadTextProperty().get()).isEqualTo(SENTENCE);
    }

    @Test
    void load_buildsTheLeadTextFromTheCategories() {
        HomePresenter presenter = loaded();

        assertThat(presenter.leadTextProperty().get()).isEqualTo("Điện tử và Sách. " + SENTENCE);
    }

    @Test
    void load_clearsTheSearchBox() {
        shell.setSearchText("áo");

        loaded();

        assertThat(shell.searchTextProperty().get()).isEmpty();
    }

    @Test
    void load_tellsTheStorefrontTheCategories() {
        loaded();

        verify(storefront).updateCategories(CATEGORIES);
    }

    @Test
    void load_whileWaiting_isLoadingAndShowsNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        HomePresenter presenter = presenter(manual);

        presenter.load();

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.featuredProperty().get()).isNull();
        verifyNoInteractions(productService);
    }

    @Test
    void load_whenTheServiceFails_reportsOnceAndStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.home()).thenThrow(failure);
        HomePresenter presenter = presenter(direct);

        presenter.load();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        verifyNoInteractions(toasts, storefront);
    }

    // ------------------------------------------------------------------ leadText

    @Test
    void leadText_withoutCategories_isOnlyThePaymentSentence() {
        assertThat(HomePresenter.leadText(List.of())).isEqualTo(SENTENCE);
    }

    @Test
    void leadText_withOneCategory_isItsNameThenTheSentence() {
        assertThat(HomePresenter.leadText(categories("Sách"))).isEqualTo("Sách. " + SENTENCE);
    }

    @Test
    void leadText_withTwoCategories_joinsThemWithVa() {
        assertThat(HomePresenter.leadText(categories("Điện tử", "Sách"))).isEqualTo("Điện tử và Sách. " + SENTENCE);
    }

    @Test
    void leadText_withFiveCategories_usesCommasAndVaBeforeTheLast() {
        // The seed, in Vietnamese Collator order
        assertThat(HomePresenter.leadText(categories("Điện tử", "Nhà cửa", "Sách", "Thể thao", "Thời trang")))
                .isEqualTo("Điện tử, Nhà cửa, Sách, Thể thao và Thời trang. " + SENTENCE);
    }

    @Test
    void leadText_keepsTheOrderItWasGiven() {
        assertThat(HomePresenter.leadText(categories("Sách", "Áo"))).isEqualTo("Sách và Áo. " + SENTENCE);
    }

    @Test
    void leadText_skipsBlankAndNullNames() {
        List<CategoryOption> options = List.of(new CategoryOption(1L, "Sách"), new CategoryOption(2L, "  "),
                new CategoryOption(3L, null));

        assertThat(HomePresenter.leadText(options)).isEqualTo("Sách. " + SENTENCE);
    }

    // ------------------------------------------------------------------ openDetail

    @Test
    void openDetail_usesTheFullListAsTheWayBack() {
        HomePresenter presenter = loaded();

        presenter.openDetail(9L);

        verify(navigator).navigateTo(new ProductDetailRoute(9L, CatalogRoute.all()));
    }

    @Test
    void openDetail_afterDispose_doesNothing() {
        HomePresenter presenter = loaded();
        presenter.dispose();

        presenter.openDetail(9L);

        verifyNoInteractions(navigator);
    }

    // ------------------------------------------------------------------ add to cart

    @Test
    void add_sendsExactlyOneUnit() {
        HomePresenter presenter = loaded();

        presenter.add(9L);

        verify(addToCart).add(eq(9L), eq("1"), any());
    }

    @Test
    void add_whileWaiting_marksTheProductBusyAndIgnoresASecondClick() {
        HomePresenter presenter = loaded();

        presenter.add(9L);
        presenter.add(9L);

        assertThat(presenter.busyProducts()).containsExactly(9L);
        verify(addToCart, times(1)).add(eq(9L), any(), any());
    }

    @Test
    void add_whenAdded_freesTheProductAndDoesNothingElse() {
        HomePresenter presenter = loaded();
        presenter.add(9L);

        captureAddCallback(9L).accept(AddToCartResult.ADDED);

        assertThat(presenter.busyProducts()).isEmpty();
        verifyNoInteractions(toasts);
        verify(productService, times(1)).home();
    }

    @Test
    void add_whenTheProductIsGone_showsTheNotFoundToastAndReloads() {
        HomePresenter presenter = loaded();
        presenter.add(9L);

        captureAddCallback(9L).accept(AddToCartResult.PRODUCT_GONE);

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(productService, times(2)).home();
        assertThat(presenter.busyProducts()).isEmpty();
    }

    @Test
    void add_whenOutOfStock_reloadsWithoutAToast() {
        HomePresenter presenter = loaded();
        presenter.add(9L);

        captureAddCallback(9L).accept(AddToCartResult.OUT_OF_STOCK);

        verify(productService, times(2)).home();
        verifyNoInteractions(toasts);
    }

    @Test
    void add_whenFailedOrInvalid_doesNotReload() {
        HomePresenter presenter = loaded();
        presenter.add(9L);
        captureAddCallback(9L).accept(AddToCartResult.FAILED);
        presenter.add(8L);
        captureAddCallback(8L).accept(AddToCartResult.INVALID_QUANTITY);

        assertThat(presenter.busyProducts()).isEmpty();
        verify(productService, times(1)).home();
        verifyNoInteractions(toasts, errors);
    }

    // ------------------------------------------------------------------ dispose

    @Test
    void dispose_beforeTheLoadCompletes_changesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        HomePresenter presenter = presenter(manual);
        shell.setSearchText("keep");
        presenter.load();

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.featuredProperty().get()).isNull();
        assertThat(presenter.moreNew()).isEmpty();
        assertThat(presenter.lowStock()).isEmpty();
        assertThat(presenter.leadTextProperty().get()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.contentVersionProperty().get()).isZero();
        assertThat(shell.searchTextProperty().get()).isEqualTo("keep");
        verifyNoInteractions(storefront, errors, toasts, navigator);
    }

    @Test
    void dispose_whileAdding_stopsTheReactionToTheResult() {
        HomePresenter presenter = loaded();
        presenter.add(9L);
        presenter.dispose();

        captureAddCallback(9L).accept(AddToCartResult.PRODUCT_GONE);

        verifyNoInteractions(toasts);
        verify(productService, times(1)).home();
    }

    @Test
    void dispose_thenAdd_isIgnored() {
        HomePresenter presenter = loaded();
        presenter.dispose();

        presenter.add(9L);

        verifyNoInteractions(addToCart);
    }

    // ------------------------------------------------------------------ search box and categories on reload

    @Test
    void load_firstSuccessfulLoad_clearsTheSearchText() {
        shell.setSearchText("typed before");

        loaded();

        assertThat(shell.searchTextProperty().get()).isEmpty();
    }

    @Test
    void reloadAfterOutOfStock_doesNotOverwriteWhatTheUserTypedInTheSearchBox() {
        HomePresenter presenter = loaded();
        presenter.add(9L);
        shell.setSearchText("giày");

        captureAddCallback(9L).accept(AddToCartResult.OUT_OF_STOCK);

        verify(productService, times(2)).home();
        assertThat(shell.searchTextProperty().get()).isEqualTo("giày");
    }

    @Test
    void reloadAfterProductGone_doesNotOverwriteWhatTheUserTypedInTheSearchBox() {
        HomePresenter presenter = loaded();
        presenter.add(9L);
        shell.setSearchText("typed later");

        captureAddCallback(9L).accept(AddToCartResult.PRODUCT_GONE);

        assertThat(shell.searchTextProperty().get()).isEqualTo("typed later");
    }

    @Test
    void reload_updatesTheStorefrontCategoriesOnEveryLoad() {
        HomePresenter presenter = loaded();
        presenter.add(9L);

        captureAddCallback(9L).accept(AddToCartResult.OUT_OF_STOCK);

        verify(storefront, times(2)).updateCategories(CATEGORIES);
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    @Test
    void load_firstLoadFails_setsLoadFailed_reportsOnce_andStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.home()).thenThrow(failure);
        HomePresenter presenter = presenter(direct);

        presenter.load();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        verify(errors, times(1)).report(failure);
    }

    @Test
    void load_whenItSucceeds_loadFailedStaysFalse() {
        HomePresenter presenter = loaded();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsAgain_clearsLoadFailed_andShowsTheContent() {
        when(productService.home()).thenThrow(new IllegalStateException("down")).thenReturn(HOME);
        HomePresenter presenter = presenter(direct);
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.featuredProperty().get()).isEqualTo(FEATURED);
        assertThat(presenter.contentVersionProperty().get()).isEqualTo(1);
        verify(productService, times(2)).home();
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        RuntimeException failure = new IllegalStateException("down");
        when(productService.home()).thenThrow(failure);
        HomePresenter presenter = presenter(direct);
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        when(productService.home()).thenThrow(new IllegalStateException("down")).thenReturn(HOME);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        HomePresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.retry();

        presenter.retry();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadFailedProperty().get()).isFalse();
    }

    @Test
    void retry_afterDispose_doesNothing() {
        when(productService.home()).thenThrow(new IllegalStateException("down")).thenReturn(HOME);
        HomePresenter presenter = presenter(direct);
        presenter.load();
        presenter.dispose();

        presenter.retry();

        verify(productService, times(1)).home();
        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void retry_whenNothingFailed_doesNothing() {
        HomePresenter presenter = loaded();

        presenter.retry();

        verify(productService, times(1)).home();
        assertThat(presenter.contentVersionProperty().get()).isEqualTo(1);
    }

    @Test
    void reload_failsAfterContentWasShown_keepsTheContent_andLoadFailedStaysFalse() {
        HomePresenter presenter = loaded();
        presenter.add(9L);
        RuntimeException failure = new IllegalStateException("down");
        when(productService.home()).thenThrow(failure);

        captureAddCallback(9L).accept(AddToCartResult.OUT_OF_STOCK);

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.featuredProperty().get()).isEqualTo(FEATURED);
        assertThat(presenter.moreNew()).containsExactlyElementsOf(MORE_NEW);
        verify(errors, times(1)).report(failure);
    }

    @Test
    void retry_afterAFailedReloadThatKeptTheContent_doesNothing() {
        HomePresenter presenter = loaded();
        presenter.add(9L);
        when(productService.home()).thenThrow(new IllegalStateException("down"));
        captureAddCallback(9L).accept(AddToCartResult.OUT_OF_STOCK);

        presenter.retry();

        verify(productService, times(2)).home();
    }
}

package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ProductDetailPresenter}: the detail screen's state and decisions (AC-002-12 to -14, AC-004-7, AC-009-3,
 * AC-009-13; QĐ3, QĐ11 of the D3 plan). No JavaFX toolkit: the service, navigator, toasts and {@link AddToCart} are
 * mocks, the background tasks are synchronous or manual.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductDetailPresenterTest {

    private static final CatalogRoute BACK = new CatalogRoute("áo", 3L, "price_asc", 2);
    private static final ProductDetailRoute ROUTE = new ProductDetailRoute(7L, BACK);
    private static final ProductDetailView IN_STOCK = new ProductDetailView(7L, "Áo thun cotton cổ tròn", "Thời trang",
            new BigDecimal("1250000"), "Áo thun mềm, thoáng mát.", true, "/images/products/ao-thun.jpg");
    private static final ProductDetailView SOLD_OUT = new ProductDetailView(7L, "Áo thun cotton cổ tròn", "Thời trang",
            new BigDecimal("1250000"), "Áo thun mềm, thoáng mát.", false, "/images/products/ao-thun.jpg");

    @Mock
    private ProductService productService;
    @Mock
    private AddToCart addToCart;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(productService.getDetail(7L)).thenReturn(IN_STOCK);
    }

    private ProductDetailPresenter presenter(BackgroundTasks tasks) {
        return new ProductDetailPresenter(productService, addToCart, navigator, tasks, toasts, errors);
    }

    private ProductDetailPresenter loaded() {
        ProductDetailPresenter presenter = presenter(direct);
        presenter.load(ROUTE);
        return presenter;
    }

    @SuppressWarnings("unchecked")
    private Consumer<AddToCartResult> captureAddCallback(String rawQuantity) {
        ArgumentCaptor<Consumer<AddToCartResult>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(addToCart).add(eq(7L), eq(rawQuantity), callback.capture());
        return callback.getValue();
    }

    // ------------------------------------------------------------------ load

    @Test
    void load_setsEveryFieldFromTheView() {
        ProductDetailPresenter presenter = loaded();

        assertThat(presenter.productProperty().get()).isEqualTo(IN_STOCK);
        assertThat(presenter.priceTextProperty().get()).isEqualTo("1.250.000 ₫");
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Còn hàng");
        assertThat(presenter.inStockProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.quantityTextProperty().get()).isEqualTo("1");
    }

    @Test
    void load_askedForTheRouteProduct() {
        loaded();

        verify(productService).getDetail(7L);
    }

    @Test
    void load_whenSoldOut_showsHetHangAndIsNotInStock() {
        when(productService.getDetail(7L)).thenReturn(SOLD_OUT);

        ProductDetailPresenter presenter = loaded();

        assertThat(presenter.stockTextProperty().get()).isEqualTo("Hết hàng");
        assertThat(presenter.inStockProperty().get()).isFalse();
        assertThat(presenter.priceTextProperty().get()).isEqualTo("1.250.000 ₫");
    }

    @Test
    void stockText_neverShowsANumber() {
        // AC-002-12 to -14: only "Còn hàng" or "Hết hàng", the exact stock stays private.
        ProductDetailPresenter presenter = loaded();

        assertThat(presenter.stockTextProperty().get()).doesNotContainPattern("\\d");
    }

    @Test
    void load_whileWaiting_isLoadingWithNoProductYet() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        ProductDetailPresenter presenter = presenter(manual);

        presenter.load(ROUTE);

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.productProperty().get()).isNull();
        verifyNoInteractions(productService);

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.productProperty().get()).isEqualTo(IN_STOCK);
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter(direct).load(null));
    }

    @Test
    void load_whenTheProductIsNotFound_showsAnErrorToastAndGoesBack() {
        // AC-009-13
        when(productService.getDetail(7L)).thenThrow(new NotFoundException("Product 7"));
        ProductDetailPresenter presenter = presenter(direct);

        presenter.load(ROUTE);

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator).navigateTo(BACK);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.productProperty().get()).isNull();
        verifyNoInteractions(errors);
    }

    @Test
    void load_whenTheServiceFailsUnexpectedly_reportsOnceAndDoesNotNavigate() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.getDetail(7L)).thenThrow(failure);
        ProductDetailPresenter presenter = presenter(direct);

        presenter.load(ROUTE);

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(toasts, navigator);
        assertThat(direct.unhandled()).isEmpty();
    }

    // ------------------------------------------------------------------ add to cart

    @Test
    void addToCart_sendsTheRawQuantityText() {
        ProductDetailPresenter presenter = loaded();
        presenter.quantityTextProperty().set("abc");

        presenter.addToCart();

        verify(addToCart).add(eq(7L), eq("abc"), any());
    }

    @Test
    void addToCart_withTheDefaultText_sendsOne() {
        ProductDetailPresenter presenter = loaded();

        presenter.addToCart();

        verify(addToCart).add(eq(7L), eq("1"), any());
    }

    @Test
    void addToCart_doesNotCleanOrClampTheText() {
        ProductDetailPresenter presenter = loaded();
        presenter.quantityTextProperty().set("  100 ");

        presenter.addToCart();

        verify(addToCart).add(eq(7L), eq("  100 "), any());
        assertThat(presenter.quantityTextProperty().get()).isEqualTo("  100 ");
    }

    @Test
    void addToCart_isBusyWhileWaiting_andCalledOnceForTwoClicks() {
        ProductDetailPresenter presenter = loaded();

        presenter.addToCart();
        presenter.addToCart();

        assertThat(presenter.busyProperty().get()).isTrue();
        verify(addToCart, times(1)).add(eq(7L), any(), any());
    }

    @Test
    void addToCart_whenAdded_freesTheButtonWithoutReloadingOrNavigating() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();

        captureAddCallback("1").accept(AddToCartResult.ADDED);

        assertThat(presenter.busyProperty().get()).isFalse();
        verify(productService, times(1)).getDetail(7L);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void addToCart_afterItFinished_canBeClickedAgain() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();
        captureAddCallback("1").accept(AddToCartResult.ADDED);

        presenter.addToCart();

        verify(addToCart, times(2)).add(eq(7L), eq("1"), any());
    }

    @Test
    void addToCart_whenInvalidOrFailed_onlyFreesTheButton() {
        ProductDetailPresenter presenter = loaded();
        presenter.quantityTextProperty().set("0");
        presenter.addToCart();

        captureAddCallback("0").accept(AddToCartResult.INVALID_QUANTITY);

        assertThat(presenter.busyProperty().get()).isFalse();
        presenter.addToCart();
        verify(addToCart, times(2)).add(eq(7L), eq("0"), any());
        verifyNoInteractions(toasts, navigator, errors);
        verify(productService, times(1)).getDetail(7L);
    }

    @Test
    void addToCart_whenTheProductIsGone_behavesLikeNotFound() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();

        captureAddCallback("1").accept(AddToCartResult.PRODUCT_GONE);

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator).navigateTo(BACK);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
    }

    @Test
    void addToCart_whenOutOfStock_reloadsTheProduct() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();
        when(productService.getDetail(7L)).thenReturn(SOLD_OUT);

        captureAddCallback("1").accept(AddToCartResult.OUT_OF_STOCK);

        verify(productService, times(2)).getDetail(7L);
        assertThat(presenter.inStockProperty().get()).isFalse();
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Hết hàng");
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void addToCart_whenTheProductIsSoldOut_doesNothing() {
        when(productService.getDetail(7L)).thenReturn(SOLD_OUT);
        ProductDetailPresenter presenter = loaded();

        presenter.addToCart();

        verifyNoInteractions(addToCart);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void addToCart_beforeTheFirstLoadFinished_doesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        ProductDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);

        presenter.addToCart();

        verifyNoInteractions(addToCart);
    }

    @Test
    void addToCart_beforeLoad_doesNothing() {
        ProductDetailPresenter presenter = presenter(direct);

        presenter.addToCart();

        verifyNoInteractions(addToCart);
    }

    // ------------------------------------------------------------------ back

    @Test
    void back_navigatesToTheRouteThatOpenedTheScreen() {
        ProductDetailPresenter presenter = loaded();

        presenter.back();

        verify(navigator).navigateTo(BACK);
    }

    @Test
    void back_whenOpenedWithoutABackRoute_goesToTheFullList() {
        ProductDetailPresenter presenter = presenter(direct);
        presenter.load(new ProductDetailRoute(7L, null));

        presenter.back();

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void back_beforeLoad_doesNothing() {
        presenter(direct).back();

        verifyNoInteractions(navigator);
    }

    // ------------------------------------------------------------------ dispose

    @Test
    void dispose_beforeTheLoadCompletes_changesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        ProductDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.productProperty().get()).isNull();
        assertThat(presenter.priceTextProperty().get()).isEmpty();
        assertThat(presenter.stockTextProperty().get()).isEmpty();
        verifyNoInteractions(toasts, navigator, errors);
    }

    @Test
    void dispose_beforeANotFoundArrives_showsNoToastAndDoesNotNavigate() {
        when(productService.getDetail(7L)).thenThrow(new NotFoundException("Product 7"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        ProductDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);

        presenter.dispose();
        manual.runAll();

        verifyNoInteractions(toasts, navigator, errors);
    }

    @Test
    void dispose_whileAddingToCart_stopsTheReactionToTheResult() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();
        presenter.dispose();

        captureAddCallback("1").accept(AddToCartResult.PRODUCT_GONE);

        verifyNoInteractions(toasts, navigator);
        verify(productService, times(1)).getDetail(7L);
    }

    @Test
    void dispose_whileAddingToCart_outOfStockDoesNotReload() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();
        presenter.dispose();

        captureAddCallback("1").accept(AddToCartResult.OUT_OF_STOCK);

        verify(productService, times(1)).getDetail(7L);
    }

    @Test
    void dispose_thenAddToCartOrBack_doNothing() {
        ProductDetailPresenter presenter = loaded();
        presenter.dispose();

        presenter.addToCart();
        presenter.back();

        verifyNoInteractions(addToCart, navigator);
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    @Test
    void load_firstLoadFails_setsLoadFailed_reportsOnce_andStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(productService.getDetail(7L)).thenThrow(failure);
        ProductDetailPresenter presenter = presenter(direct);

        presenter.load(ROUTE);

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.productProperty().get()).isNull();
        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void load_whenTheProductIsNotFound_loadFailedStaysFalse() {
        when(productService.getDetail(7L)).thenThrow(new NotFoundException("Product 7"));
        ProductDetailPresenter presenter = presenter(direct);

        presenter.load(ROUTE);

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsTheSameProductAgain_clearsLoadFailed_andShowsIt() {
        when(productService.getDetail(7L)).thenThrow(new IllegalStateException("down")).thenReturn(IN_STOCK);
        ProductDetailPresenter presenter = presenter(direct);
        presenter.load(ROUTE);

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.productProperty().get()).isEqualTo(IN_STOCK);
        assertThat(presenter.priceTextProperty().get()).isEqualTo("1.250.000 ₫");
        verify(productService, times(2)).getDetail(7L);
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryIsNotFound_goesBackWithAToast_andLoadFailedStaysFalse() {
        when(productService.getDetail(7L)).thenThrow(new IllegalStateException("down"))
                .thenThrow(new NotFoundException("Product 7"));
        ProductDetailPresenter presenter = presenter(direct);
        presenter.load(ROUTE);

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator).navigateTo(BACK);
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        RuntimeException failure = new IllegalStateException("down");
        when(productService.getDetail(7L)).thenThrow(failure);
        ProductDetailPresenter presenter = presenter(direct);
        presenter.load(ROUTE);

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        when(productService.getDetail(7L)).thenThrow(new IllegalStateException("down")).thenReturn(IN_STOCK);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        ProductDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);
        manual.run(0);
        presenter.retry();

        presenter.retry();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.loadingProperty().get()).isTrue();
    }

    @Test
    void retry_afterDispose_doesNothing() {
        when(productService.getDetail(7L)).thenThrow(new IllegalStateException("down"));
        ProductDetailPresenter presenter = presenter(direct);
        presenter.load(ROUTE);
        presenter.dispose();

        presenter.retry();

        verify(productService, times(1)).getDetail(7L);
        assertThat(presenter.productProperty().get()).isNull();
    }

    @Test
    void retry_beforeAnyLoad_orWhenNothingFailed_doesNothing() {
        presenter(direct).retry();
        verifyNoInteractions(productService);

        ProductDetailPresenter presenter = loaded();
        presenter.retry();

        verify(productService, times(1)).getDetail(7L);
    }

    @Test
    void reload_failsAfterTheProductWasShown_keepsIt_andLoadFailedStaysFalse() {
        ProductDetailPresenter presenter = loaded();
        presenter.addToCart();
        RuntimeException failure = new IllegalStateException("down");
        when(productService.getDetail(7L)).thenThrow(failure);

        captureAddCallback("1").accept(AddToCartResult.OUT_OF_STOCK);

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.productProperty().get()).isEqualTo(IN_STOCK);
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(1)).report(failure);
        presenter.retry();
        verify(productService, times(2)).getDetail(7L);
    }
}

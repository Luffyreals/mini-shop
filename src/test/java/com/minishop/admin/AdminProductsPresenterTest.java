package com.minishop.admin;

import com.minishop.catalog.AdminProductPage;
import com.minishop.catalog.AdminProductRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.common.PageNav;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminProductsPresenter}: the "Sản phẩm" list, search, paging and hide/show (AC-006-17, -25, -26;
 * AC-009-10 to -13; QĐ5, QĐ10 of the D5 plan). No JavaFX toolkit and no Spring: the service, navigator and toasts are
 * mocks; {@link AdminFailures} is the real class on top of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminProductsPresenterTest {

    private static final AdminProductRow KEYBOARD = new AdminProductRow(5, "Bàn phím cơ", "Điện tử",
            new BigDecimal("1250000"), 7, true, "file:/images/keyboard.jpg");
    private static final AdminProductRow MOUSE = new AdminProductRow(6, "Chuột không dây", "Điện tử",
            new BigDecimal("190000"), 0, false, null);

    @Mock
    private ProductAdminService service;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(service.list(any(), any())).thenReturn(page(List.of(KEYBOARD, MOUSE), null, 1, 3, 45));
    }

    private static AdminProductPage page(List<AdminProductRow> rows, String q, int page, int totalPages, long total) {
        return new AdminProductPage(rows, q, page, totalPages, total);
    }

    private AdminProductsPresenter presenter(BackgroundTasks tasks) {
        return new AdminProductsPresenter(service, tasks, navigator, new AdminFailures(toasts, navigator, errors),
                toasts);
    }

    private AdminProductsPresenter loaded(AdminProductsRoute route) {
        AdminProductsPresenter presenter = presenter(direct);
        presenter.load(route);
        return presenter;
    }

    // ------------------------------------------------------------------ load (AC-006-17)

    @Test
    void load_passesTheQueryAndThePageAsStringsToTheService() {
        loaded(new AdminProductsRoute("bàn", 3));

        verify(service, times(1)).list("bàn", "3");
    }

    @Test
    void load_withoutAQuery_passesANullQuery() {
        loaded(AdminProductsRoute.first());

        verify(service).list(null, "1");
    }

    @Test
    void load_showsTheRowsWithFormattedPriceStockAndFlag() {
        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        assertThat(presenter.rows()).containsExactly(
                new AdminProductsPresenter.ProductRow(5, "Bàn phím cơ", "Điện tử", "1.250.000 ₫", "7", true,
                        "Đang bán", "file:/images/keyboard.jpg"),
                new AdminProductsPresenter.ProductRow(6, "Chuột không dây", "Điện tử", "190.000 ₫", "0", false,
                        "Đã ẩn", null));
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.emptyTextProperty().get()).isEmpty();
    }

    @Test
    void productRow_togglesBetweenHideAndShowWithAccessibleTexts() {
        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        AdminProductsPresenter.ProductRow active = presenter.rows().get(0);
        AdminProductsPresenter.ProductRow hidden = presenter.rows().get(1);

        assertThat(active.toggleLabel()).isEqualTo("Ẩn");
        assertThat(active.toggleAccessibleText()).isEqualTo("Ẩn Bàn phím cơ");
        assertThat(active.editAccessibleText()).isEqualTo("Sửa Bàn phím cơ");
        assertThat(hidden.toggleLabel()).isEqualTo("Hiện lại");
        assertThat(hidden.toggleAccessibleText()).isEqualTo("Hiện lại Chuột không dây");
    }

    @Test
    void load_pageNavFollowsThePageTheServiceReturned_notTheRequestedOne() {
        when(service.list("bàn", "9")).thenReturn(page(List.of(KEYBOARD), "bàn", 1, 2, 25));

        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 9));

        assertThat(presenter.pageNavProperty().get()).isEqualTo(PageNav.of(1, 2));
    }

    @Test
    void load_withNoProducts_andNoQuery_showsTheFirstEmptyMessage() {
        when(service.list(any(), any())).thenReturn(page(List.of(), null, 1, 0, 0));

        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.emptyTextProperty().get()).isEqualTo("Chưa có sản phẩm nào.");
        assertThat(presenter.rows()).isEmpty();
    }

    @Test
    void load_withNoMatch_forAQuery_showsTheSearchEmptyMessage() {
        when(service.list(any(), any())).thenReturn(page(List.of(), "xyz", 1, 0, 0));

        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("xyz", 1));

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.emptyTextProperty().get()).isEqualTo("Không tìm thấy sản phẩm phù hợp.");
    }

    @Test
    void load_putsTheNormalisedQueryInTheSearchBoxOnlyOnce() {
        when(service.list(any(), any())).thenReturn(page(List.of(KEYBOARD), "bàn", 1, 1, 1));
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 1));
        assertThat(presenter.queryTextProperty().get()).isEqualTo("bàn");

        // the hide/show reload must not overwrite what the admin is typing
        when(service.list(any(), any())).thenReturn(page(List.of(KEYBOARD), "khác", 1, 1, 1));
        when(service.setActive(5L, false)).thenReturn("Bàn phím cơ");
        presenter.hide(5L);

        assertThat(presenter.queryTextProperty().get()).isEqualTo("bàn");
    }

    @Test
    void load_withoutAQuery_leavesTheSearchBoxEmpty() {
        assertThat(loaded(AdminProductsRoute.first()).queryTextProperty().get()).isEmpty();
    }

    @Test
    void load_whilePending_isLoadingAndNotLoaded() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);

        presenter.load(AdminProductsRoute.first());

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.pageNavProperty().get()).isNull();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void load_asANonAdmin_toastsAndGoesHome() {
        when(service.list(any(), any())).thenThrow(new AccessDeniedException());

        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_withAnUnexpectedFailure_reportsItOnce() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.list(any(), any())).thenThrow(failure);

        loaded(AdminProductsRoute.first());

        verify(errors, times(1)).report(failure);
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter(direct).load(null));
    }

    // ------------------------------------------------------------------ search and paging

    @Test
    void search_navigatesToTheSearchRouteOnPageOne() {
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("cũ", 4));

        presenter.search("  mới  ");

        verify(navigator).navigateTo(new AdminProductsRoute("mới", 1));
    }

    @Test
    void search_withABlankText_clearsTheQuery() {
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("cũ", 4));

        presenter.search("   ");

        verify(navigator).navigateTo(AdminProductsRoute.first());
    }

    @Test
    void search_forTheSameQueryOnPageOne_doesNotNavigate() {
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 1));

        presenter.search(" bàn ");

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void search_forTheSameQueryOnALaterPage_goesBackToPageOne() {
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 2));

        presenter.search("bàn");

        verify(navigator).navigateTo(new AdminProductsRoute("bàn", 1));
    }

    @Test
    void goToPage_navigatesToThatPage_keepingTheQuery() {
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 1));

        presenter.goToPage(3);

        verify(navigator).navigateTo(new AdminProductsRoute("bàn", 3));
    }

    @Test
    void goToPage_theCurrentPageOrBelowOne_doesNothing() {
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 2));

        presenter.goToPage(2);
        presenter.goToPage(0);
        presenter.goToPage(-4);

        verify(navigator, never()).navigateTo(any());
    }

    // ------------------------------------------------------------------ add and edit keep the current route as back

    @Test
    void add_opensTheNewProductFormWithTheCurrentListAsBack() {
        AdminProductsRoute route = new AdminProductsRoute("bàn", 3);
        loaded(route).add();

        verify(navigator).navigateTo(AdminProductFormRoute.newProduct(route));
    }

    @Test
    void edit_opensTheEditFormWithTheCurrentListAsBack() {
        AdminProductsRoute route = new AdminProductsRoute("bàn", 3);
        loaded(route).edit(6L);

        verify(navigator).navigateTo(AdminProductFormRoute.edit(6L, route));
    }

    // ------------------------------------------------------------------ hide and show (AC-006-25, -26)

    @Test
    void hide_callsSetActiveFalse_withoutAnyConfirmation_toastsAndReloadsTheSamePage() {
        when(service.setActive(5L, false)).thenReturn("Bàn phím cơ");
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 2));

        presenter.hide(5L);

        verify(service, times(1)).setActive(5L, false);
        verify(toasts).show("Đã ẩn sản phẩm Bàn phím cơ. Khách sẽ không thấy sản phẩm này nữa.",
                ToastKind.SUCCESS);
        verify(service, times(2)).list("bàn", "2");
        assertThat(presenter.writingIds()).isEmpty();
        verifyNoInteractions(errors);
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void show_callsSetActiveTrue_toastsAndReloads() {
        when(service.setActive(6L, true)).thenReturn("Chuột không dây");
        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        presenter.show(6L);

        verify(service, times(1)).setActive(6L, true);
        verify(toasts).show("Đã hiện lại sản phẩm Chuột không dây.", ToastKind.SUCCESS);
        verify(service, times(2)).list(null, "1");
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void messages_matchTheSpecTexts() {
        assertThat(AdminProductsPresenter.hiddenMessage("A"))
                .isEqualTo("Đã ẩn sản phẩm A. Khách sẽ không thấy sản phẩm này nữa.");
        assertThat(AdminProductsPresenter.shownMessage("A")).isEqualTo("Đã hiện lại sản phẩm A.");
    }

    @Test
    void hide_whileTheWriteRuns_theRowIsWriting_andASecondClickOnItSendsNothing() {
        when(service.setActive(5L, false)).thenReturn("Bàn phím cơ");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());
        manual.run(0);

        presenter.hide(5L);
        presenter.hide(5L);
        presenter.show(5L);

        assertThat(presenter.writingIds()).containsExactly(5L);
        assertThat(manual.submittedCount()).isEqualTo(2);   // the load and one write
    }

    @Test
    void writingIds_stayUntilTheReloadFinishes_notJustUntilTheWriteFinishes() {
        when(service.setActive(5L, false)).thenReturn("Bàn phím cơ");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());
        manual.run(0);
        presenter.hide(5L);

        manual.run(1);   // the write is done, the reload is queued

        assertThat(presenter.writingIds()).containsExactly(5L);
        presenter.hide(5L);
        assertThat(manual.submittedCount()).isEqualTo(3);

        manual.run(2);   // the reload finished

        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void hide_ofAnotherRow_whileOneIsWriting_isAllowed() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());
        manual.run(0);

        presenter.hide(5L);
        presenter.show(6L);

        assertThat(presenter.writingIds()).containsExactlyInAnyOrder(5L, 6L);
        assertThat(manual.submittedCount()).isEqualTo(3);
    }

    @Test
    void hide_whenTheProductIsGone_toastsNotFound_andReloadsThePage() {
        when(service.setActive(5L, false)).thenThrow(new NotFoundException("gone"));
        AdminProductsPresenter presenter = loaded(new AdminProductsRoute("bàn", 2));

        presenter.hide(5L);

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(service, times(2)).list("bàn", "2");
        assertThat(presenter.writingIds()).isEmpty();
        verifyNoInteractions(errors);
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void hide_asANonAdmin_toastsAndGoesHome_andUnlocksTheRow() {
        when(service.setActive(5L, false)).thenThrow(new AccessDeniedException());
        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        presenter.hide(5L);

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(service, times(1)).list(any(), any());
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void hide_withAnUnexpectedFailure_reportsOnce_unlocksTheRow_andDoesNotReload() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.setActive(5L, false)).thenThrow(failure);
        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());

        presenter.hide(5L);

        verify(errors, times(1)).report(failure);
        verify(service, times(1)).list(any(), any());
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void anOlderReloadResult_neverOverwritesANewerOne() {
        when(service.setActive(5L, false)).thenReturn("Bàn phím cơ");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());   // task 0 stays pending
        presenter.hide(5L);                            // task 1
        manual.run(1);                                 // the write finishes and starts the reload: task 2

        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.rows()).hasSize(2);
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    // ------------------------------------------------------------------ dispose (AC-009-10, QĐ10)

    @Test
    void dispose_cancelsThePendingLoad_soNothingChangesWhenItArrives() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.rows()).isEmpty();
    }

    @Test
    void dispose_doesNotCancelAWriteInFlight_lateSuccessStillToasts_butDoesNotReloadOrChangeProperties() {
        when(service.setActive(5L, false)).thenReturn("Bàn phím cơ");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());
        manual.run(0);
        presenter.hide(5L);

        presenter.dispose();
        manual.run(1);

        assertThat(manual.handle(1).isCancelled()).isFalse();
        verify(toasts).show("Đã ẩn sản phẩm Bàn phím cơ. Khách sẽ không thấy sản phẩm này nữa.",
                ToastKind.SUCCESS);
        assertThat(manual.submittedCount()).isEqualTo(2);   // no reload
        assertThat(presenter.rows()).hasSize(2);
        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    @Test
    void dispose_lateBusinessFailures_areIgnored_butAnUnexpectedOneIsStillReported() {
        when(service.setActive(5L, false)).thenThrow(new NotFoundException("gone"));
        IllegalStateException boom = new IllegalStateException("boom");
        when(service.setActive(6L, true)).thenThrow(boom);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductsPresenter presenter = presenter(manual);
        presenter.load(AdminProductsRoute.first());
        manual.run(0);
        presenter.hide(5L);
        presenter.show(6L);

        presenter.dispose();
        manual.run(1);
        manual.run(2);

        verifyNoInteractions(toasts);
        verify(errors, times(1)).report(boom);
        verify(navigator, never()).goHome();
    }

    @Test
    void afterDispose_everyAction_doesNothing() {
        AdminProductsPresenter presenter = loaded(AdminProductsRoute.first());
        presenter.dispose();

        presenter.search("x");
        presenter.goToPage(2);
        presenter.add();
        presenter.edit(5L);
        presenter.hide(5L);
        presenter.show(6L);

        verifyNoInteractions(navigator);
        verify(service, never()).setActive(anyLong(), anyBoolean());
    }
}

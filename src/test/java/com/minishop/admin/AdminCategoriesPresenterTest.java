package com.minishop.admin;

import com.minishop.catalog.CategoriesMenu;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDeleteResult;
import com.minishop.catalog.CategoryRow;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.ConfirmDialog;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminCategoriesPresenter}: the "Danh mục" list and the confirmed delete (AC-006-10, -15, -16; AC-009-10,
 * -12, -13; QĐ5, QĐ9, QĐ10, QĐ11 of the D5 plan). No JavaFX toolkit and no Spring. The confirm dialog mock only
 * records the question; a test decides whether the user "confirms" by running the captured action.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminCategoriesPresenterTest {

    private static final CategoryRow BOOKS = new CategoryRow(1, "Sách", "Sách giấy và truyện tranh", 4);
    private static final CategoryRow TOYS = new CategoryRow(2, "Đồ chơi", null, 0);

    @Mock
    private CategoryAdminService service;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private ConfirmDialog confirmDialog;
    @Mock
    private CategoriesMenu categoriesMenu;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(service.list()).thenReturn(List.of(BOOKS, TOYS));
    }

    private AdminCategoriesPresenter presenter(com.minishop.desktop.BackgroundTasks tasks) {
        return new AdminCategoriesPresenter(service, tasks, navigator, toasts, confirmDialog, categoriesMenu,
                new AdminFailures(toasts, navigator, errors));
    }

    private AdminCategoriesPresenter loaded() {
        AdminCategoriesPresenter presenter = presenter(direct);
        presenter.load();
        return presenter;
    }

    private Runnable confirmedAction(AdminCategoriesPresenter presenter, AdminCategoriesPresenter.Row row) {
        presenter.requestDelete(row);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(eq("Xóa danh mục " + row.name() + "? Không thể hoàn tác."), eq("Xóa"),
                eq("Quay lại"), action.capture());
        return action.getValue();
    }

    // ------------------------------------------------------------------ load (AC-006-10)

    @Test
    void load_showsTheRowsInTheServiceOrderWithFormattedText() {
        AdminCategoriesPresenter presenter = loaded();

        assertThat(presenter.rows()).containsExactly(
                new AdminCategoriesPresenter.Row(1, "Sách", "Sách giấy và truyện tranh", "4", "Sửa Sách", "Xóa Sách"),
                new AdminCategoriesPresenter.Row(2, "Đồ chơi", "", "0", "Sửa Đồ chơi", "Xóa Đồ chơi"));
        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(service, times(1)).list();
        verifyNoInteractions(toasts, errors, navigator, categoriesMenu);
    }

    @Test
    void load_withNoCategories_setsTheEmptyFlag() {
        when(service.list()).thenReturn(List.of());

        AdminCategoriesPresenter presenter = loaded();

        assertThat(presenter.rows()).isEmpty();
        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void load_whilePending_isLoadingAndNotLoaded() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);

        presenter.load();

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void load_focusTargetIsTheTopOfTheScreen() {
        AdminCategoriesPresenter presenter = loaded();

        assertThat(presenter.focusTargetProperty().get()).isEqualTo(new AdminCategoriesPresenter.FocusTarget(null));
    }

    @Test
    void load_asANonAdmin_toastsAndGoesHome() {
        when(service.list()).thenThrow(new AccessDeniedException());

        AdminCategoriesPresenter presenter = presenter(direct);
        presenter.load();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_withAnUnexpectedFailure_reportsItOnce() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.list()).thenThrow(failure);

        AdminCategoriesPresenter presenter = presenter(direct);
        presenter.load();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(toasts);
    }

    // ------------------------------------------------------------------ navigation

    @Test
    void add_opensTheNewCategoryForm() {
        loaded().add();

        verify(navigator).navigateTo(AdminCategoryFormRoute.newCategory());
    }

    @Test
    void edit_opensTheEditFormOfThatCategory() {
        loaded().edit(2L);

        verify(navigator).navigateTo(AdminCategoryFormRoute.edit(2L));
    }

    // ------------------------------------------------------------------ confirm dialog (AC-006-15, QĐ9)

    @Test
    void requestDelete_onlyOpensTheConfirmDialog_theServiceIsNotCalledYet() {
        AdminCategoriesPresenter presenter = loaded();

        presenter.requestDelete(presenter.rows().get(0));

        verify(confirmDialog).ask(eq("Xóa danh mục Sách? Không thể hoàn tác."), eq("Xóa"), eq("Quay lại"),
                any(Runnable.class));
        verify(service, never()).delete(anyLong());
        assertThat(presenter.writingIds()).isEmpty();
        verifyNoInteractions(toasts);
    }

    @Test
    void requestDelete_whenTheUserDismisses_nothingIsCalled() {
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0));   // captured but never run: dismissed

        verify(service, never()).delete(anyLong());
        verify(categoriesMenu, never()).refresh();
        verify(service, times(1)).list();
    }

    @Test
    void confirmedDelete_callsDeleteExactlyOnceForThatId() {
        when(service.delete(1L)).thenReturn(new CategoryDeleteResult.Deleted("Sách"));
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        verify(service, times(1)).delete(1L);
        verify(service, never()).delete(2L);
    }

    @Test
    void confirmedDelete_deleted_toastsSuccess_refreshesTheTopBarMenu_andReloads() {
        when(service.delete(1L)).thenReturn(new CategoryDeleteResult.Deleted("Sách"));
        when(service.list()).thenReturn(List.of(BOOKS, TOYS)).thenReturn(List.of(TOYS));
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        verify(toasts).show("Đã xóa danh mục Sách.", ToastKind.SUCCESS);
        verify(categoriesMenu, times(1)).refresh();
        verify(service, times(2)).list();
        assertThat(presenter.rows()).extracting(AdminCategoriesPresenter.Row::id).containsExactly(2L);
        assertThat(presenter.writingIds()).isEmpty();
        assertThat(presenter.focusTargetProperty().get()).isEqualTo(new AdminCategoriesPresenter.FocusTarget(null));
    }

    @Test
    void confirmedDelete_inUse_toastsTheErrorWithTheCount_reloads_andKeepsFocusOnThatRow() {
        when(service.delete(1L)).thenReturn(new CategoryDeleteResult.InUse("Sách", 4));
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        verify(toasts).show("Không thể xóa danh mục Sách vì còn 4 sản phẩm (kể cả sản phẩm đã ẩn).",
                ToastKind.ERROR);
        verify(categoriesMenu, never()).refresh();
        verify(service, times(2)).list();
        assertThat(presenter.rows()).hasSize(2);
        assertThat(presenter.writingIds()).isEmpty();
        assertThat(presenter.focusTargetProperty().get()).isEqualTo(new AdminCategoriesPresenter.FocusTarget(1L));
        verifyNoInteractions(errors);
    }

    @Test
    void confirmedDelete_inUse_andTheRowIsGoneAfterTheReload_focusFallsBackToTheTopOfTheScreen() {
        when(service.delete(1L)).thenReturn(new CategoryDeleteResult.InUse("Sách", 1));
        when(service.list()).thenReturn(List.of(BOOKS, TOYS)).thenReturn(List.of(TOYS));
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        assertThat(presenter.focusTargetProperty().get()).isEqualTo(new AdminCategoriesPresenter.FocusTarget(null));
    }

    @Test
    void confirmedDelete_whenTheCategoryIsAlreadyGone_toastsNotFound_refreshesTheMenu_andReloads() {
        when(service.delete(1L)).thenThrow(new NotFoundException("gone"));
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        verify(toasts).show("Không tìm thấy danh mục.", ToastKind.ERROR);
        verify(categoriesMenu, times(1)).refresh();   // the top-bar menu may still list the vanished category
        verify(service, times(2)).list();
        assertThat(presenter.writingIds()).isEmpty();
        verifyNoInteractions(errors);
    }

    @Test
    void confirmedDelete_whenTheCategoryIsAlreadyGone_refreshesTheMenuBeforeTheReload() {
        when(service.delete(1L)).thenThrow(new NotFoundException("gone"));
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(toasts, categoriesMenu, service);
        order.verify(toasts).show("Không tìm thấy danh mục.", ToastKind.ERROR);
        order.verify(categoriesMenu).refresh();
        order.verify(service).list();   // the reload (the initial load came before the toast)
    }

    @Test
    void confirmedDelete_asANonAdmin_toastsAndGoesHome_withoutReloading() {
        when(service.delete(1L)).thenThrow(new AccessDeniedException());
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(service, times(1)).list();
        verify(categoriesMenu, never()).refresh();
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void confirmedDelete_withAnUnexpectedFailure_reportsOnce_unlocksTheRow_andDoesNotReload() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.delete(1L)).thenThrow(failure);
        AdminCategoriesPresenter presenter = loaded();

        confirmedAction(presenter, presenter.rows().get(0)).run();

        verify(errors, times(1)).report(failure);
        verify(service, times(1)).list();
        verify(categoriesMenu, never()).refresh();
        assertThat(presenter.writingIds()).isEmpty();
        verify(toasts, never()).show(any(), any());
    }

    // ------------------------------------------------------------------ double-click guard (writingIds)

    @Test
    void whileTheDeleteRuns_theRowIsWriting_andASecondRequestOnItDoesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);
        presenter.load();
        manual.runAll();
        AdminCategoriesPresenter.Row books = presenter.rows().get(0);
        presenter.requestDelete(books);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(any(), any(), any(), action.capture());

        action.getValue().run();

        assertThat(presenter.writingIds()).containsExactly(1L);
        // a second click on the same row: no new dialog, and a second confirm would send nothing
        presenter.requestDelete(books);
        action.getValue().run();
        verify(confirmDialog, times(1)).ask(any(), any(), any(), any(Runnable.class));
        assertThat(manual.submittedCount()).isEqualTo(2);   // the load and one delete
    }

    @Test
    void whileOneRowIsDeleting_anotherRowCanStillAskToBeDeleted() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);
        presenter.load();
        manual.runAll();
        presenter.delete(1L);

        presenter.requestDelete(presenter.rows().get(1));

        assertThat(presenter.writingIds()).containsExactly(1L);
        verify(confirmDialog).ask(eq("Xóa danh mục Đồ chơi? Không thể hoàn tác."), eq("Xóa"), eq("Quay lại"),
                any(Runnable.class));
    }

    @Test
    void delete_calledTwiceForTheSameId_submitsOnlyOneService() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);

        presenter.delete(1L);
        presenter.delete(1L);

        assertThat(manual.submittedCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ dispose (AC-009-10, QĐ10)

    @Test
    void dispose_cancelsThePendingLoad_soTheResultChangesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.rows()).isEmpty();
    }

    @Test
    void dispose_doesNotCancelADeleteInFlight_lateSuccessStillToastsAndRefreshesTheMenu_butChangesNothingElse() {
        when(service.delete(1L)).thenReturn(new CategoryDeleteResult.Deleted("Sách"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.delete(1L);

        presenter.dispose();
        manual.run(1);

        assertThat(manual.handle(1).isCancelled()).isFalse();
        verify(toasts).show("Đã xóa danh mục Sách.", ToastKind.SUCCESS);
        verify(categoriesMenu, times(1)).refresh();
        assertThat(manual.submittedCount()).isEqualTo(2);   // no reload was started
        assertThat(presenter.writingIds()).containsExactly(1L);
        assertThat(presenter.rows()).hasSize(2);
        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    @Test
    void dispose_lateInUse_stillToastsTheError_butDoesNotReload() {
        when(service.delete(1L)).thenReturn(new CategoryDeleteResult.InUse("Sách", 4));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.delete(1L);

        presenter.dispose();
        manual.run(1);

        verify(toasts).show("Không thể xóa danh mục Sách vì còn 4 sản phẩm (kể cả sản phẩm đã ẩn).",
                ToastKind.ERROR);
        assertThat(manual.submittedCount()).isEqualTo(2);
    }

    @Test
    void dispose_lateBusinessFailures_areIgnored_butAnUnexpectedOneIsStillReported() {
        when(service.delete(1L)).thenThrow(new NotFoundException("gone"));
        IllegalStateException boom = new IllegalStateException("boom");
        when(service.delete(2L)).thenThrow(boom);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoriesPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.delete(1L);
        presenter.delete(2L);

        presenter.dispose();
        manual.run(1);
        manual.run(2);

        verifyNoInteractions(toasts);
        verify(categoriesMenu, never()).refresh();   // a disposed screen does not refresh anything on not-found
        verify(errors, times(1)).report(boom);
        verify(navigator, never()).goHome();
    }

    @Test
    void afterDispose_everyAction_doesNothing() {
        AdminCategoriesPresenter presenter = loaded();
        AdminCategoriesPresenter.Row row = presenter.rows().get(0);
        presenter.dispose();

        presenter.add();
        presenter.edit(1L);
        presenter.requestDelete(row);
        presenter.delete(1L);

        verifyNoInteractions(navigator, confirmDialog);
        verify(service, never()).delete(anyLong());
    }
}

package com.minishop.admin;

import com.minishop.catalog.CategoriesMenu;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDraft;
import com.minishop.catalog.CategoryEditView;
import com.minishop.catalog.CategoryNameTakenException;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.support.ManualBackgroundTasks;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminCategoryFormPresenter}: the add and edit category forms (AC-006-11 to -14; AC-009-10, -12, -13; QĐ3,
 * QĐ5, QĐ11, QĐ13 of the D5 plan). No JavaFX toolkit and no Spring; the Jakarta {@link Validator} is the real one, so
 * the texts are the ones the user sees.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminCategoryFormPresenterTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Mock
    private CategoryAdminService service;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private CategoriesMenu categoriesMenu;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    private AdminCategoryFormPresenter presenter(BackgroundTasks tasks) {
        return new AdminCategoryFormPresenter(service, tasks, navigator, toasts, categoriesMenu,
                new AdminFailures(toasts, navigator, errors), errors, validator);
    }

    private AdminCategoryFormPresenter newForm() {
        AdminCategoryFormPresenter presenter = presenter(direct);
        presenter.load(AdminCategoryFormRoute.newCategory());
        return presenter;
    }

    private AdminCategoryFormPresenter editForm() {
        when(service.get(5L)).thenReturn(new CategoryEditView(5L, "Sách", "Sách giấy"));
        AdminCategoryFormPresenter presenter = presenter(direct);
        presenter.load(AdminCategoryFormRoute.edit(5L));
        return presenter;
    }

    // ------------------------------------------------------------------ load

    @Test
    void load_new_isReadyAtOnce_withTheNewTitle_andNeverCallsTheService() {
        AdminCategoryFormPresenter presenter = newForm();

        assertThat(presenter.titleProperty().get()).isEqualTo("Thêm danh mục");
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.nameProperty().get()).isEmpty();
        assertThat(presenter.descriptionProperty().get()).isEmpty();
        assertThat(direct.submittedCount()).isZero();
        verifyNoInteractions(service, toasts, errors, navigator, categoriesMenu);
    }

    @Test
    void load_edit_prefillsBothFieldsFromTheService() {
        AdminCategoryFormPresenter presenter = editForm();

        assertThat(presenter.titleProperty().get()).isEqualTo("Sửa danh mục");
        assertThat(presenter.nameProperty().get()).isEqualTo("Sách");
        assertThat(presenter.descriptionProperty().get()).isEqualTo("Sách giấy");
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(service, times(1)).get(5L);
    }

    @Test
    void load_edit_withANullDescription_showsAnEmptyField() {
        when(service.get(5L)).thenReturn(new CategoryEditView(5L, "Sách", null));
        AdminCategoryFormPresenter presenter = presenter(direct);

        presenter.load(AdminCategoryFormRoute.edit(5L));

        assertThat(presenter.descriptionProperty().get()).isEmpty();
    }

    @Test
    void load_edit_whilePending_isLoadingAndSaveIsIgnored() {
        when(service.get(5L)).thenReturn(new CategoryEditView(5L, "Sách", "Sách giấy"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);

        presenter.load(AdminCategoryFormRoute.edit(5L));

        assertThat(presenter.titleProperty().get()).isEqualTo("Sửa danh mục");
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();

        presenter.save();

        assertThat(manual.submittedCount()).isEqualTo(1);
        verify(service, never()).update(anyLong(), any());
    }

    @Test
    void load_edit_whenTheCategoryIsGone_toastsNotFoundAndGoesBackToTheList() {
        when(service.get(5L)).thenThrow(new NotFoundException("gone"));

        AdminCategoryFormPresenter presenter = presenter(direct);
        presenter.load(AdminCategoryFormRoute.edit(5L));

        verify(toasts).show("Không tìm thấy danh mục.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(new AdminCategoriesRoute());
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_edit_asANonAdmin_toastsAndGoesHome_notBackToTheList() {
        when(service.get(5L)).thenThrow(new AccessDeniedException());

        presenter(direct).load(AdminCategoryFormRoute.edit(5L));

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void load_edit_withAnUnexpectedFailure_reportsOnce_andLeavesTheEmptyForm() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.get(5L)).thenThrow(failure);

        presenter(direct).load(AdminCategoryFormRoute.edit(5L));

        verify(errors, times(1)).report(failure);
        verify(navigator, times(1)).navigateTo(new AdminCategoriesRoute());
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter(direct).load(null));
    }

    // ------------------------------------------------------------------ validation (AC-006-12)

    @Test
    void save_withABlankName_showsTheNameError_focusesIt_andCallsNoService() {
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("   ");

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEqualTo("Vui lòng nhập tên danh mục.");
        assertThat(presenter.descriptionErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminCategoryFormPresenter.Field.NAME);
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(service, never()).create(any());
        verifyNoInteractions(toasts, categoriesMenu, navigator);
        assertThat(direct.submittedCount()).isZero();
    }

    @Test
    void save_withANameOver100Characters_showsTheLengthError_andKeepsTheTypedText() {
        AdminCategoryFormPresenter presenter = newForm();
        String longName = "a".repeat(101);
        presenter.nameProperty().set(longName);
        presenter.descriptionProperty().set("mô tả");

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEqualTo("Tên danh mục tối đa 100 ký tự.");
        assertThat(presenter.nameProperty().get()).isEqualTo(longName);
        assertThat(presenter.descriptionProperty().get()).isEqualTo("mô tả");
        verify(service, never()).create(any());
    }

    @Test
    void save_withADescriptionOver1000Characters_showsOnlyTheDescriptionError_andFocusesIt() {
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");
        presenter.descriptionProperty().set("m".repeat(1001));

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        assertThat(presenter.descriptionErrorProperty().get()).isEqualTo("Mô tả tối đa 1000 ký tự.");
        assertThat(presenter.firstErrorFieldProperty().get())
                .isEqualTo(AdminCategoryFormPresenter.Field.DESCRIPTION);
        verify(service, never()).create(any());
    }

    @Test
    void save_withBothFieldsWrong_showsBothErrors_andFocusesTheNameFirst() {
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("");
        presenter.descriptionProperty().set("m".repeat(1001));

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isNotEmpty();
        assertThat(presenter.descriptionErrorProperty().get()).isNotEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminCategoryFormPresenter.Field.NAME);
    }

    @Test
    void typingIntoAField_clearsItsError() {
        AdminCategoryFormPresenter presenter = newForm();
        presenter.save();
        assertThat(presenter.nameErrorProperty().get()).isNotEmpty();

        presenter.nameProperty().set("S");

        assertThat(presenter.nameErrorProperty().get()).isEmpty();
    }

    @Test
    void save_aSecondTimeAfterFixing_clearsTheOldErrorsAndSends() {
        when(service.create(any())).thenReturn("Sách");
        AdminCategoryFormPresenter presenter = newForm();
        presenter.save();
        presenter.nameProperty().set("Sách");

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        verify(service, times(1)).create(any());
    }

    // ------------------------------------------------------------------ create (AC-006-11)

    @Test
    void save_new_createsWithTheCleanedDraftExactlyOnce_toastsAddsAndRefreshesTheMenu_thenGoesToTheList() {
        when(service.create(any())).thenReturn("Sách");
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("  Sách  ");
        presenter.descriptionProperty().set("   ");

        presenter.save();

        verify(service, times(1)).create(new CategoryDraft("Sách", null));
        verify(service, never()).update(anyLong(), any());
        verify(toasts).show("Đã thêm danh mục Sách.", ToastKind.SUCCESS);
        verify(categoriesMenu, times(1)).refresh();
        verify(navigator, times(1)).navigateTo(new AdminCategoriesRoute());
        verifyNoInteractions(errors);
    }

    @Test
    void save_new_withADescription_passesItInTheDraft() {
        when(service.create(any())).thenReturn("Sách");
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");
        presenter.descriptionProperty().set("Sách giấy");

        presenter.save();

        verify(service).create(new CategoryDraft("Sách", "Sách giấy"));
    }

    // ------------------------------------------------------------------ update (AC-006-14)

    @Test
    void save_edit_updatesThatIdExactlyOnce_toastsUpdatedAndRefreshesTheMenu() {
        when(service.update(anyLong(), any())).thenReturn("Sách mới");
        AdminCategoryFormPresenter presenter = editForm();
        presenter.nameProperty().set("Sách mới");

        presenter.save();

        verify(service, times(1)).update(5L, new CategoryDraft("Sách mới", "Sách giấy"));
        verify(service, never()).create(any());
        verify(toasts).show("Đã cập nhật danh mục Sách mới.", ToastKind.SUCCESS);
        verify(categoriesMenu, times(1)).refresh();
        verify(navigator, times(1)).navigateTo(new AdminCategoriesRoute());
    }

    // ------------------------------------------------------------------ failures (AC-006-13)

    @Test
    void save_withATakenName_showsTheErrorUnderTheName_keepsTheText_andDoesNotRefreshOrNavigate() {
        when(service.create(any())).thenThrow(new CategoryNameTakenException("taken"));
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");
        presenter.descriptionProperty().set("Sách giấy");

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEqualTo("Tên danh mục đã tồn tại.");
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminCategoryFormPresenter.Field.NAME);
        assertThat(presenter.nameProperty().get()).isEqualTo("Sách");
        assertThat(presenter.descriptionProperty().get()).isEqualTo("Sách giấy");
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(categoriesMenu, never()).refresh();
        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void save_afterATakenName_canBeRetriedWithAnotherName() {
        when(service.create(new CategoryDraft("Sách", null))).thenThrow(new CategoryNameTakenException("taken"));
        when(service.create(new CategoryDraft("Truyện", null))).thenReturn("Truyện");
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");
        presenter.save();

        presenter.nameProperty().set("Truyện");
        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        verify(toasts).show("Đã thêm danh mục Truyện.", ToastKind.SUCCESS);
        verify(categoriesMenu, times(1)).refresh();
    }

    @Test
    void save_edit_whenTheCategoryIsGone_toastsNotFound_andGoesBackToTheList() {
        when(service.update(anyLong(), any())).thenThrow(new NotFoundException("gone"));
        AdminCategoryFormPresenter presenter = editForm();

        presenter.save();

        verify(toasts).show("Không tìm thấy danh mục.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(new AdminCategoriesRoute());
        verify(categoriesMenu, never()).refresh();
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void save_asANonAdmin_toastsAndGoesHome() {
        when(service.create(any())).thenThrow(new AccessDeniedException());
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");

        presenter.save();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(categoriesMenu, never()).refresh();
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void save_withAnUnexpectedFailure_reportsOnce_unlocksTheForm_andKeepsTheText() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.create(any())).thenThrow(failure);
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");

        presenter.save();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.nameProperty().get()).isEqualTo("Sách");
        verify(categoriesMenu, never()).refresh();
        verify(navigator, never()).navigateTo(any());
    }

    // ------------------------------------------------------------------ double-submit guard

    @Test
    void save_whileASaveIsRunning_isBusy_andASecondClickSendsNothing() {
        when(service.create(any())).thenReturn("Sách");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);
        presenter.load(AdminCategoryFormRoute.newCategory());
        presenter.nameProperty().set("Sách");

        presenter.save();
        presenter.save();

        assertThat(presenter.busyProperty().get()).isTrue();
        assertThat(manual.submittedCount()).isEqualTo(1);

        manual.runAll();

        verify(service, times(1)).create(any());
        verify(toasts, times(1)).show("Đã thêm danh mục Sách.", ToastKind.SUCCESS);
    }

    @Test
    void cancel_goesBackToTheList_discardingTheText_withoutCallingTheService() {
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");

        presenter.cancel();

        verify(navigator).navigateTo(new AdminCategoriesRoute());
        verify(service, never()).create(any());
        verifyNoInteractions(categoriesMenu, toasts);
    }

    @Test
    void cancel_whileASaveIsRunning_isIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);
        presenter.load(AdminCategoryFormRoute.newCategory());
        presenter.nameProperty().set("Sách");
        presenter.save();

        presenter.cancel();

        verify(navigator, never()).navigateTo(any());
    }

    // ------------------------------------------------------------------ dispose (AC-009-10, QĐ10)

    @Test
    void dispose_cancelsThePendingEditLoad() {
        when(service.get(5L)).thenReturn(new CategoryEditView(5L, "Sách", "Sách giấy"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);
        presenter.load(AdminCategoryFormRoute.edit(5L));

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.nameProperty().get()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void dispose_doesNotCancelASaveInFlight_lateSuccessToastsAndRefreshesTheMenu_butDoesNotNavigate() {
        when(service.create(any())).thenReturn("Sách");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);
        presenter.load(AdminCategoryFormRoute.newCategory());
        presenter.nameProperty().set("Sách");
        presenter.save();

        presenter.dispose();
        manual.run(0);

        assertThat(manual.handle(0).isCancelled()).isFalse();
        verify(toasts).show("Đã thêm danh mục Sách.", ToastKind.SUCCESS);
        verify(categoriesMenu, times(1)).refresh();
        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    @Test
    void dispose_lateBusinessFailures_areIgnored_noNameErrorAndNoToast() {
        when(service.create(any())).thenThrow(new CategoryNameTakenException("taken"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);
        presenter.load(AdminCategoryFormRoute.newCategory());
        presenter.nameProperty().set("Sách");
        presenter.save();

        presenter.dispose();
        manual.run(0);

        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void dispose_lateUnexpectedFailure_isStillReported() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.create(any())).thenThrow(failure);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminCategoryFormPresenter presenter = presenter(manual);
        presenter.load(AdminCategoryFormRoute.newCategory());
        presenter.nameProperty().set("Sách");
        presenter.save();

        presenter.dispose();
        manual.run(0);

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts);
    }

    @Test
    void afterDispose_saveAndCancel_doNothing() {
        AdminCategoryFormPresenter presenter = newForm();
        presenter.nameProperty().set("Sách");
        presenter.dispose();

        presenter.save();
        presenter.cancel();

        verify(service, never()).create(any());
        verifyNoInteractions(navigator);
    }
}

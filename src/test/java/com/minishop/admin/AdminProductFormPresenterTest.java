package com.minishop.admin;

import com.minishop.catalog.CatalogRoute;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryNotFoundException;
import com.minishop.catalog.CategoryOption;
import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.ProductDetailRoute;
import com.minishop.catalog.ProductDraft;
import com.minishop.catalog.ProductEditView;
import com.minishop.catalog.SavedProduct;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.storage.ImageFormat;
import com.minishop.storage.ImageUpload;
import com.minishop.storage.InvalidImageException;
import com.minishop.support.ManualBackgroundTasks;
import com.minishop.support.TestImages;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminProductFormPresenter}: the add and edit product forms with the three independent parts (info and image,
 * stock adjustment, hide/show) (AC-006-18 to -22, -25 to -30, -32 to -34, -37; AC-009-10 to -13; QĐ5 to QĐ8, QĐ10, QĐ13
 * of the D5 plan). No JavaFX toolkit and no Spring: both services, the navigator and the toasts are mocks, the Jakarta
 * {@link Validator} is the real one and the image files are real files in a temp folder, so
 * {@code ImageUpload.fromFile} runs for real. A {@link ManualBackgroundTasks} shows what happens while a task is
 * pending (busy flags, the file being read only when the task runs).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminProductFormPresenterTest {

    private static final CategoryOption BOOKS = new CategoryOption(1L, "Sách");
    private static final CategoryOption TOYS = new CategoryOption(2L, "Đồ chơi");
    private static final AdminProductsRoute BACK = new AdminProductsRoute("bàn", 3);
    private static final long PRODUCT_ID = 9L;

    private static final String IMAGE_TYPE = "Ảnh chỉ được là JPG, PNG hoặc WebP.";
    private static final String IMAGE_SIZE = "Ảnh tối đa 2 MB.";
    private static final String PRICE_INVALID = "Giá phải là số nguyên từ 1 đến 999.999.999 ₫.";
    private static final String STOCK_INVALID = "Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.";
    private static final String DELTA_INVALID =
            "Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.";

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

    @TempDir
    Path tmp;

    @Mock
    private CategoryAdminService categoryAdmin;
    @Mock
    private ProductAdminService productAdmin;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(categoryAdmin.options()).thenReturn(List.of(BOOKS, TOYS));
        when(productAdmin.getForEdit(PRODUCT_ID)).thenReturn(view("Bàn phím cơ", "1250000", "Gõ êm", 7, true,
                "file:/uploads/old.png"));
    }

    // ------------------------------------------------------------------ helpers

    private static ProductEditView view(String name, String price, String description, int stock, boolean active,
                                        String imageUrl) {
        return new ProductEditView(PRODUCT_ID, name, 1L, new BigDecimal(price), description, stock, active,
                imageUrl);
    }

    private AdminProductFormPresenter presenter(BackgroundTasks tasks) {
        return new AdminProductFormPresenter(categoryAdmin, productAdmin, tasks, navigator, toasts,
                new AdminFailures(toasts, navigator, errors), validator);
    }

    private AdminProductFormPresenter newForm() {
        AdminProductFormPresenter presenter = presenter(direct);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        return presenter;
    }

    private AdminProductFormPresenter editForm() {
        AdminProductFormPresenter presenter = presenter(direct);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        return presenter;
    }

    private static void fillValid(AdminProductFormPresenter presenter) {
        presenter.nameProperty().set("Bàn phím");
        presenter.categoryProperty().set(BOOKS);
        presenter.priceProperty().set("1250000");
        presenter.initialStockProperty().set("10");
    }

    private Path png(String name) throws IOException {
        Path file = tmp.resolve(name);
        Files.write(file, TestImages.png(100));
        return file;
    }

    // ------------------------------------------------------------------ load, new

    @Test
    void load_new_fillsTheCategories_andReadsNoProduct() {
        AdminProductFormPresenter presenter = newForm();

        assertThat(presenter.isNew()).isTrue();
        assertThat(presenter.categories()).containsExactly(BOOKS, TOYS);
        assertThat(presenter.noCategoriesProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.activeCheckedProperty().get()).isTrue();
        assertThat(presenter.categoryProperty().get()).isNull();
        verify(categoryAdmin, times(1)).options();
        verify(productAdmin, never()).getForEdit(anyLong());
        verifyNoInteractions(toasts, errors, navigator);
        assertThat(direct.submittedCount()).isEqualTo(1);
    }

    @Test
    void load_new_withoutCategories_setsNoCategories_andSaveIsIgnored() {
        when(categoryAdmin.options()).thenReturn(List.of());
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);

        presenter.save();

        assertThat(presenter.noCategoriesProperty().get()).isTrue();
        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
        assertThat(direct.submittedCount()).isEqualTo(1);
    }

    @Test
    void addCategory_opensTheNewCategoryForm() {
        newForm().addCategory();

        verify(navigator).navigateTo(AdminCategoryFormRoute.newCategory());
    }

    @Test
    void load_whilePending_isLoadingAndNotLoaded_andASecondLoadIsIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);

        presenter.load(AdminProductFormRoute.newProduct(BACK));
        presenter.load(AdminProductFormRoute.newProduct(BACK));

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(manual.submittedCount()).isEqualTo(1);

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    // ------------------------------------------------------------------ load, edit (QĐ7, QĐ8)

    @Test
    void load_edit_readsBothServicesOnceAndFillsEveryField() {
        AdminProductFormPresenter presenter = editForm();

        assertThat(presenter.isNew()).isFalse();
        assertThat(presenter.nameProperty().get()).isEqualTo("Bàn phím cơ");
        assertThat(presenter.priceProperty().get()).isEqualTo("1250000");
        assertThat(presenter.descriptionProperty().get()).isEqualTo("Gõ êm");
        assertThat(presenter.categoryProperty().get()).isEqualTo(BOOKS);
        assertThat(presenter.currentImageUrlProperty().get()).isEqualTo("file:/uploads/old.png");
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 7");
        assertThat(presenter.activeNowProperty().get()).isTrue();
        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(presenter.noCategoriesProperty().get()).isFalse();
        verify(categoryAdmin, times(1)).options();
        verify(productAdmin, times(1)).getForEdit(PRODUCT_ID);
        assertThat(direct.submittedCount()).isEqualTo(1);
    }

    @Test
    void load_edit_ofAHiddenProductWithoutDescriptionOrImage_showsEmptyTextAndTheHiddenState() {
        when(productAdmin.getForEdit(PRODUCT_ID)).thenReturn(view("Bàn phím cơ", "1250000", null, 0, false, null));

        AdminProductFormPresenter presenter = editForm();

        assertThat(presenter.descriptionProperty().get()).isEmpty();
        assertThat(presenter.currentImageUrlProperty().get()).isEmpty();
        assertThat(presenter.activeNowProperty().get()).isFalse();
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 0");
    }

    @Test
    void load_edit_whenTheProductIsGone_toastsNotFound_andGoesBackToTheListItCameFrom() {
        when(productAdmin.getForEdit(PRODUCT_ID)).thenThrow(new NotFoundException("gone"));

        AdminProductFormPresenter presenter = editForm();

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_asANonAdmin_toastsAndGoesHome() {
        when(categoryAdmin.options()).thenThrow(new AccessDeniedException());

        newForm();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void load_new_withAnUnexpectedFailure_reportsOnce_andGoesBackToTheListItCameFrom() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(categoryAdmin.options()).thenThrow(failure);

        AdminProductFormPresenter presenter = newForm();

        verify(errors, times(1)).report(failure);
        verify(navigator, times(1)).navigateTo(BACK);
        verify(navigator, never()).goHome();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void load_edit_withAnUnexpectedFailure_reportsOnce_andGoesBackToTheListItCameFrom() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(productAdmin.getForEdit(PRODUCT_ID)).thenThrow(failure);

        AdminProductFormPresenter presenter = editForm();

        verify(errors, times(1)).report(failure);
        verify(navigator, times(1)).navigateTo(BACK);
        verify(navigator, never()).goHome();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void load_withAnUnexpectedFailure_reportsBeforeItGoesBack() {
        when(categoryAdmin.options()).thenThrow(new IllegalStateException("boom"));

        newForm();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(errors, navigator);
        order.verify(errors).report(any(IllegalStateException.class));
        order.verify(navigator).navigateTo(BACK);
    }

    @Test
    void load_withAnUnexpectedFailureAfterDispose_doesNotNavigate() {
        when(categoryAdmin.options()).thenThrow(new IllegalStateException("boom"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.ignoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));

        presenter.dispose();
        manual.runAll();

        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    // ------------------------------------------------------------------ validation (AC-006-20, -21, QĐ13)

    @Test
    void save_new_emptyForm_showsOneErrorPerField_focusesTheNameFirst_andTouchesNothing() {
        AdminProductFormPresenter presenter = newForm();

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEqualTo("Vui lòng nhập tên sản phẩm.");
        assertThat(presenter.categoryErrorProperty().get()).isEqualTo("Vui lòng chọn danh mục.");
        assertThat(presenter.priceErrorProperty().get()).isEqualTo("Vui lòng nhập giá.");
        assertThat(presenter.initialStockErrorProperty().get()).isEqualTo("Vui lòng nhập tồn kho ban đầu.");
        assertThat(presenter.descriptionErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.NAME);
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
        assertThat(direct.submittedCount()).isEqualTo(1);   // only the load
        verifyNoInteractions(toasts, errors, navigator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5", "1.5", "1000000000", "1.25"})
    void save_new_withAnInvalidPrice_showsThePriceError_andFocusesIt(String price) {
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);
        presenter.priceProperty().set(price);

        presenter.save();

        assertThat(presenter.priceErrorProperty().get()).isEqualTo(PRICE_INVALID);
        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        assertThat(presenter.initialStockErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.PRICE);
        assertThat(presenter.priceProperty().get()).isEqualTo(price);   // the typed text stays
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "100001", "1.5"})
    void save_new_withAnInvalidInitialStock_showsTheStockError_andFocusesIt(String stock) {
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);
        presenter.initialStockProperty().set(stock);

        presenter.save();

        assertThat(presenter.initialStockErrorProperty().get()).isEqualTo(STOCK_INVALID);
        assertThat(presenter.firstErrorFieldProperty().get())
                .isEqualTo(AdminProductFormPresenter.Field.INITIAL_STOCK);
        assertThat(presenter.initialStockProperty().get()).isEqualTo(stock);
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void save_new_withANameOver200Characters_andADescriptionOver4000_showsBothErrors() {
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);
        presenter.nameProperty().set("n".repeat(201));
        presenter.descriptionProperty().set("d".repeat(4001));

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isEqualTo("Tên sản phẩm tối đa 200 ký tự.");
        assertThat(presenter.descriptionErrorProperty().get()).isEqualTo("Mô tả tối đa 4000 ký tự.");
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.NAME);
    }

    @Test
    void save_new_withOnlyTheDescriptionWrong_focusesTheDescription() {
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);
        presenter.descriptionProperty().set("d".repeat(4001));

        presenter.save();

        assertThat(presenter.firstErrorFieldProperty().get())
                .isEqualTo(AdminProductFormPresenter.Field.DESCRIPTION);
    }

    @Test
    void save_edit_hasNoStockField_soAnInitialStockIsNeverRequired() {
        AdminProductFormPresenter presenter = editForm();
        presenter.initialStockProperty().set("");
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Bàn phím cơ");

        presenter.save();

        assertThat(presenter.initialStockErrorProperty().get()).isEmpty();
        verify(productAdmin, times(1)).update(eq(PRODUCT_ID), any(), isNull());
    }

    @Test
    void save_withAFormErrorAndAChosenImage_neverReadsTheFileAndKeepsTheChoice() throws IOException {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        Path image = png("a.png");
        presenter.imageChosen(image);
        manual.run(1);
        fillValid(presenter);
        presenter.priceProperty().set("abc");

        presenter.save();

        assertThat(manual.submittedCount()).isEqualTo(2);   // load and image check only: no save task, no file read
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        assertThat(presenter.priceErrorProperty().get()).isEqualTo(PRICE_INVALID);
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void typingIntoAField_clearsItsErrorOnly() {
        AdminProductFormPresenter presenter = newForm();
        presenter.save();

        presenter.nameProperty().set("B");

        assertThat(presenter.nameErrorProperty().get()).isEmpty();
        assertThat(presenter.priceErrorProperty().get()).isNotEmpty();
        assertThat(presenter.categoryErrorProperty().get()).isNotEmpty();
    }

    // ------------------------------------------------------------------ create (AC-006-19)

    @Test
    void save_new_createsOnceWithTheCleanedDraft_toastsAdded_andOpensTheEditFormOfTheNewIdWithTheSameBack() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenReturn(new SavedProduct(77, "Bàn phím"));
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);
        presenter.nameProperty().set("  Bàn phím  ");
        presenter.descriptionProperty().set("   ");

        presenter.save();

        verify(productAdmin, times(1)).create(eq(new ProductDraft("Bàn phím", 1L, new BigDecimal("1250000"), null)),
                eq(10), eq(true), isNull());
        verify(toasts).show("Đã thêm sản phẩm Bàn phím.", ToastKind.SUCCESS);
        verify(navigator, times(1)).navigateTo(AdminProductFormRoute.edit(77L, BACK));
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void save_new_withTheActiveBoxOffAndADescription_passesBothToTheService() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenReturn(new SavedProduct(77, "Bàn phím"));
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);
        presenter.activeCheckedProperty().set(false);
        presenter.descriptionProperty().set("Gõ êm");
        presenter.initialStockProperty().set("0");
        presenter.categoryProperty().set(TOYS);

        presenter.save();

        verify(productAdmin).create(eq(new ProductDraft("Bàn phím", 2L, new BigDecimal("1250000"), "Gõ êm")),
                eq(0), eq(false), isNull());
    }

    @Test
    void save_new_withAnImage_readsItInsideTheBackgroundTask_thenCallsCreateOnceWithTheUpload() throws IOException {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenReturn(new SavedProduct(77, "Bàn phím"));
        Path image = png("keyboard.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        presenter.imageChosen(image);
        manual.run(1);
        fillValid(presenter);

        presenter.save();

        // nothing has been read or sent yet: the work is queued
        assertThat(presenter.busyProperty().get()).isTrue();
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());

        manual.run(2);

        ArgumentCaptor<ImageUpload> upload = ArgumentCaptor.forClass(ImageUpload.class);
        verify(productAdmin, times(1)).create(any(), eq(10), eq(true), upload.capture());
        assertThat(upload.getValue().format()).isEqualTo(ImageFormat.PNG);
        assertThat(upload.getValue().size()).isEqualTo(Files.size(image));
        verify(toasts).show("Đã thêm sản phẩm Bàn phím.", ToastKind.SUCCESS);
    }

    @Test
    void save_new_readsTheFileAgainAtSaveTime_soAFileDeletedAfterChoosingFailsWithoutCallingTheService()
            throws IOException {
        Path image = png("gone.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        presenter.imageChosen(image);
        manual.run(1);
        fillValid(presenter);
        presenter.save();
        Files.delete(image);

        manual.run(2);

        ArgumentCaptor<Throwable> reported = ArgumentCaptor.forClass(Throwable.class);
        verify(errors, times(1)).report(reported.capture());
        assertThat(reported.getValue()).isInstanceOf(UncheckedIOException.class);
        assertThat(reported.getValue().getMessage()).doesNotContain(tmp.toString());
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
    }

    @Test
    void save_new_whenTheChosenFileBecameAnHtmlFile_showsTheTypeErrorUnderTheImage_andCallsNoService()
            throws IOException {
        Path image = png("a.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        presenter.imageChosen(image);
        manual.run(1);
        fillValid(presenter);
        presenter.save();
        Files.write(image, TestImages.htmlNamedJpg());   // same name, different content

        manual.run(2);

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_TYPE);
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.IMAGE);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.nameProperty().get()).isEqualTo("Bàn phím");   // typed text stays
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
        verifyNoInteractions(errors, toasts, navigator);
    }

    @Test
    void save_new_whenTheChosenFileGrewPast2MiB_showsTheSizeError_andCallsNoService() throws IOException {
        Path image = png("big.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        presenter.imageChosen(image);
        manual.run(1);
        fillValid(presenter);
        presenter.save();
        Files.write(image, TestImages.png((int) ImageUpload.MAX_BYTES + 1));

        manual.run(2);

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_SIZE);
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.IMAGE);
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void save_new_whenTheServiceRejectsTheImage_showsTheMatchingErrorUnderTheImage() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any()))
                .thenThrow(new InvalidImageException(InvalidImageException.Reason.TOO_LARGE, "too large"));
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);

        presenter.save();

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_SIZE);
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.IMAGE);
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void save_new_whenTheCategoryWasDeleted_showsTheCategoryError_keepsTheTextAndStays() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any()))
                .thenThrow(new CategoryNotFoundException("gone"));
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);

        presenter.save();

        assertThat(presenter.categoryErrorProperty().get()).isEqualTo("Danh mục không tồn tại.");
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.CATEGORY);
        assertThat(presenter.nameProperty().get()).isEqualTo("Bàn phím");
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void save_new_whenTheProductGoneIsReported_toastsNotFound_andGoesBack() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenThrow(new NotFoundException("gone"));
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);

        presenter.save();

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void save_asANonAdmin_toastsAndGoesHome() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenThrow(new AccessDeniedException());
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);

        presenter.save();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(navigator, never()).navigateTo(any());
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void save_withAnUnexpectedFailure_reportsOnce_unlocksTheForm_andKeepsTheText() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenThrow(failure);
        AdminProductFormPresenter presenter = newForm();
        fillValid(presenter);

        presenter.save();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.priceProperty().get()).isEqualTo("1250000");
        verify(navigator, never()).navigateTo(any());
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void save_whileASaveIsRunning_isBusy_andASecondClickSendsNothing() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenReturn(new SavedProduct(77, "Bàn phím"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        fillValid(presenter);

        presenter.save();
        presenter.save();

        assertThat(presenter.busyProperty().get()).isTrue();
        assertThat(manual.submittedCount()).isEqualTo(2);

        manual.runAll();

        verify(productAdmin, times(1)).create(any(), anyInt(), anyBoolean(), any());
        verify(toasts, times(1)).show("Đã thêm sản phẩm Bàn phím.", ToastKind.SUCCESS);
    }

    @Test
    void save_whileTheImageIsBeingChecked_isIgnored() throws IOException {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        fillValid(presenter);
        presenter.imageChosen(png("a.png"));

        presenter.save();

        assertThat(presenter.checkingImageProperty().get()).isTrue();
        assertThat(manual.submittedCount()).isEqualTo(2);
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
    }

    // ------------------------------------------------------------------ update (AC-006-22)

    @Test
    void save_edit_updatesOnce_toastsTheNewName_andReloadsTheWholeFormFromTheService() {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Bàn phím mới");
        when(productAdmin.getForEdit(PRODUCT_ID))
                .thenReturn(view("Bàn phím cơ", "1250000", "Gõ êm", 7, true, "file:/uploads/old.png"))
                .thenReturn(view("Bàn phím mới", "1300000", null, 7, true, "file:/uploads/new.png"));
        AdminProductFormPresenter presenter = editForm();
        presenter.nameProperty().set("  Bàn phím mới ");
        presenter.priceProperty().set("1.300.000");

        presenter.save();

        verify(productAdmin, times(1)).update(eq(PRODUCT_ID),
                eq(new ProductDraft("Bàn phím mới", 1L, new BigDecimal("1300000"), "Gõ êm")), isNull());
        verify(toasts).show("Đã cập nhật sản phẩm Bàn phím mới.", ToastKind.SUCCESS);
        assertThat(presenter.nameProperty().get()).isEqualTo("Bàn phím mới");
        assertThat(presenter.priceProperty().get()).isEqualTo("1300000");
        assertThat(presenter.descriptionProperty().get()).isEmpty();
        assertThat(presenter.currentImageUrlProperty().get()).isEqualTo("file:/uploads/new.png");
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(navigator, never()).navigateTo(any());
        verify(productAdmin, never()).create(any(), anyInt(), anyBoolean(), any());
        verifyNoInteractions(errors);
    }

    @Test
    void save_edit_withANewImage_sendsTheUpload_andClearsTheSelectionAfterwards() throws IOException {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Bàn phím cơ");
        Path image = png("new.png");
        AdminProductFormPresenter presenter = editForm();
        presenter.imageChosen(image);
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);

        presenter.save();

        ArgumentCaptor<ImageUpload> upload = ArgumentCaptor.forClass(ImageUpload.class);
        verify(productAdmin, times(1)).update(eq(PRODUCT_ID), any(), upload.capture());
        assertThat(upload.getValue().format()).isEqualTo(ImageFormat.PNG);
        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(presenter.selectedImageNameProperty().get()).isEmpty();
        assertThat(presenter.previewUrlProperty().get()).isEmpty();
    }

    @Test
    void save_edit_whenTheProductIsGone_toastsNotFound_andGoesBack() {
        when(productAdmin.update(anyLong(), any(), any())).thenThrow(new NotFoundException("gone"));
        AdminProductFormPresenter presenter = editForm();

        presenter.save();

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    /**
     * The update commits, then the read-back fails with {@code readBackFailure}: the user still sees the success
     * toast, nothing is reported, the (already uploaded) image selection is dropped and the same route is opened
     * again so the screen reloads from the service.
     */
    private void assertReadBackFailureAfterASavedUpdate(RuntimeException readBackFailure) throws IOException {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Tên mới");
        when(productAdmin.getForEdit(PRODUCT_ID))
                .thenReturn(view("Bàn phím cơ", "1250000", "Gõ êm", 7, true, "file:/uploads/old.png"))
                .thenThrow(readBackFailure);
        Path image = png("new.png");
        AdminProductFormPresenter presenter = editForm();
        presenter.imageChosen(image);
        presenter.nameProperty().set("Tên mới");

        presenter.save();

        ArgumentCaptor<ImageUpload> upload = ArgumentCaptor.forClass(ImageUpload.class);
        verify(productAdmin, times(1)).update(eq(PRODUCT_ID), any(), upload.capture());
        assertThat(upload.getValue().format()).isEqualTo(ImageFormat.PNG);
        verify(toasts, times(1)).show("Đã cập nhật sản phẩm Tên mới.", ToastKind.SUCCESS);
        verify(toasts, times(1)).show(any(), any());
        verifyNoInteractions(errors);
        assertThat(direct.unhandled()).isEmpty();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(presenter.selectedImageNameProperty().get()).isEmpty();
        assertThat(presenter.previewUrlProperty().get()).isEmpty();
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
        verify(navigator, times(1)).navigateTo(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        verify(navigator, never()).goHome();
        verify(productAdmin, times(2)).getForEdit(PRODUCT_ID);   // the load and the failed read-back
    }

    @Test
    void save_edit_whenTheReadBackFailsUnexpectedly_stillToastsSuccess_reportsNothing_andReopensTheSameRoute()
            throws IOException {
        assertReadBackFailureAfterASavedUpdate(new IllegalStateException("read-back boom"));
    }

    @Test
    void save_edit_whenTheReadBackFindsNothing_stillToastsSuccess_reportsNothing_andReopensTheSameRoute()
            throws IOException {
        assertReadBackFailureAfterASavedUpdate(new NotFoundException("gone after the update"));
    }

    @Test
    void save_edit_whenTheReadBackFails_toastsBeforeReopening_andLeavesTheTypedTextToTheReload() {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Tên mới");
        when(productAdmin.getForEdit(PRODUCT_ID))
                .thenReturn(view("Bàn phím cơ", "1250000", "Gõ êm", 7, true, null))
                .thenThrow(new IllegalStateException("boom"));
        AdminProductFormPresenter presenter = editForm();
        presenter.nameProperty().set("Tên mới");

        presenter.save();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(toasts, navigator);
        order.verify(toasts).show("Đã cập nhật sản phẩm Tên mới.", ToastKind.SUCCESS);
        order.verify(navigator).navigateTo(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        assertThat(presenter.nameProperty().get()).isEqualTo("Tên mới");   // no applyView: the new screen loads it
    }

    @Test
    void save_edit_whenUpdateFailsUnexpectedly_reportsOnce_unlocks_keepsTheSelection_andReadsNothingBack()
            throws IOException {
        IllegalStateException failure = new IllegalStateException("boom");
        when(productAdmin.update(anyLong(), any(), any())).thenThrow(failure);
        Path image = png("new.png");
        AdminProductFormPresenter presenter = editForm();
        presenter.imageChosen(image);

        presenter.save();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        verify(productAdmin, times(1)).getForEdit(PRODUCT_ID);   // the load only
        verify(navigator, never()).navigateTo(any());
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void save_edit_whenTheImageFileCannotBeReadAtSaveTime_reportsOnce_andNeverCallsUpdate() throws IOException {
        Path image = png("gone.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.imageChosen(image);
        manual.run(1);
        presenter.save();
        Files.delete(image);

        manual.run(2);

        verify(errors, times(1)).report(any(UncheckedIOException.class));
        verify(productAdmin, never()).update(anyLong(), any(), any());
        verify(productAdmin, times(1)).getForEdit(PRODUCT_ID);
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(navigator, never()).navigateTo(any());
        verify(toasts, never()).show(any(), any());
    }

    // ------------------------------------------------------------------ choosing an image (QĐ6)

    @Test
    void imageChosen_aValidFile_isCheckedInTheBackground_thenSelectedWithNameAndPreview() throws IOException {
        Path image = png("keyboard.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);

        presenter.imageChosen(image);

        assertThat(presenter.checkingImageProperty().get()).isTrue();
        assertThat(presenter.selectedImageProperty().get()).isNull();

        manual.run(1);

        assertThat(presenter.checkingImageProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        assertThat(presenter.selectedImageNameProperty().get()).isEqualTo("keyboard.png");
        assertThat(presenter.previewUrlProperty().get()).isEqualTo(image.toUri().toString());
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
        verifyNoInteractions(errors, toasts);
    }

    @Test
    void imageChosen_aWebpFile_isAccepted() throws IOException {
        Path image = tmp.resolve("photo.webp");
        Files.write(image, TestImages.webp(100));
        AdminProductFormPresenter presenter = newForm();

        presenter.imageChosen(image);

        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
    }

    @Test
    void imageChosen_anHtmlFileNamedPng_showsTheTypeError_andSelectsNothing() throws IOException {
        Path image = tmp.resolve("evil.png");
        Files.write(image, TestImages.htmlNamedJpg());
        AdminProductFormPresenter presenter = newForm();

        presenter.imageChosen(image);

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_TYPE);
        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(presenter.previewUrlProperty().get()).isEmpty();
        assertThat(presenter.checkingImageProperty().get()).isFalse();
        verifyNoInteractions(errors, toasts);
    }

    @Test
    void imageChosen_aGifFile_showsTheTypeError() throws IOException {
        Path image = tmp.resolve("anim.gif");
        Files.write(image, TestImages.gif());
        AdminProductFormPresenter presenter = newForm();

        presenter.imageChosen(image);

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_TYPE);
    }

    @Test
    void imageChosen_aFileOver2MiB_showsTheSizeError() throws IOException {
        Path image = tmp.resolve("big.png");
        Files.write(image, TestImages.png((int) ImageUpload.MAX_BYTES + 1));
        AdminProductFormPresenter presenter = newForm();

        presenter.imageChosen(image);

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_SIZE);
        assertThat(presenter.selectedImageProperty().get()).isNull();
    }

    @Test
    void imageChosen_aFileOfExactly2MiB_isAccepted() throws IOException {
        Path image = tmp.resolve("limit.png");
        Files.write(image, TestImages.png((int) ImageUpload.MAX_BYTES));
        AdminProductFormPresenter presenter = newForm();

        presenter.imageChosen(image);

        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
    }

    @Test
    void imageChosen_aBadFileAfterAGoodOne_keepsTheEarlierSelection() throws IOException {
        Path good = png("good.png");
        Path bad = tmp.resolve("bad.png");
        Files.write(bad, TestImages.htmlNamedJpg());
        AdminProductFormPresenter presenter = newForm();
        presenter.imageChosen(good);

        presenter.imageChosen(bad);

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_TYPE);
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(good);
        assertThat(presenter.selectedImageNameProperty().get()).isEqualTo("good.png");
        assertThat(presenter.previewUrlProperty().get()).isEqualTo(good.toUri().toString());
    }

    @Test
    void imageChosen_aGoodFileAfterABadOne_clearsTheError() throws IOException {
        Path bad = tmp.resolve("bad.png");
        Files.write(bad, TestImages.htmlNamedJpg());
        AdminProductFormPresenter presenter = newForm();
        presenter.imageChosen(bad);

        presenter.imageChosen(png("good.png"));

        assertThat(presenter.imageErrorProperty().get()).isEmpty();
        assertThat(presenter.selectedImageNameProperty().get()).isEqualTo("good.png");
    }

    @Test
    void imageChosen_aFileThatCannotBeRead_isReportedAsAnUnexpectedError_withoutAnImageError() {
        Path missing = tmp.resolve("missing.png");
        AdminProductFormPresenter presenter = newForm();

        presenter.imageChosen(missing);

        verify(errors, times(1)).report(any(UncheckedIOException.class));
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(presenter.checkingImageProperty().get()).isFalse();
    }

    @Test
    void imageChosen_twice_theOlderCheckIsCancelled_andOnlyTheNewerFileIsSelected() throws IOException {
        Path first = png("first.png");
        Path second = png("second.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);

        presenter.imageChosen(first);
        presenter.imageChosen(second);
        manual.runAll();

        assertThat(manual.handle(1).isCancelled()).isTrue();
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(second);
        assertThat(presenter.selectedImageNameProperty().get()).isEqualTo("second.png");
    }

    @Test
    void imageChosen_withNull_orBeforeLoading_isIgnored_andWhileBusyItSubmitsNoCheckYet() throws IOException {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        presenter.imageChosen(png("early.png"));   // not loaded yet
        manual.run(0);
        presenter.imageChosen(null);
        fillValid(presenter);
        presenter.save();                           // busy now

        presenter.imageChosen(png("late.png"));

        assertThat(manual.submittedCount()).isEqualTo(2);   // load and the save only
    }

    private AdminProductFormPresenter hidingEditForm(ManualBackgroundTasks manual) {
        when(productAdmin.setActive(PRODUCT_ID, false)).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.hide();   // task 1: a write is running, so the form is busy
        return presenter;
    }

    @Test
    void imageChosen_whileAWriteRuns_isCheckedOnlyWhenTheWriteEnds_thenSelected() throws IOException {
        Path image = png("late.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = hidingEditForm(manual);

        presenter.imageChosen(image);

        assertThat(presenter.busyProperty().get()).isTrue();
        assertThat(manual.submittedCount()).isEqualTo(2);   // load and hide: no check yet
        assertThat(presenter.checkingImageProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isNull();

        manual.run(1);   // the write ends

        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(manual.submittedCount()).isEqualTo(3);   // the normal check started
        assertThat(presenter.checkingImageProperty().get()).isTrue();

        manual.run(2);

        assertThat(presenter.checkingImageProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        assertThat(presenter.selectedImageNameProperty().get()).isEqualTo("late.png");
        assertThat(presenter.previewUrlProperty().get()).isEqualTo(image.toUri().toString());
        verifyNoInteractions(errors);
    }

    @Test
    void imageChosen_twiceWhileAWriteRuns_checksOnlyTheLatestFile() throws IOException {
        Path first = png("first.png");
        Path second = png("second.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = hidingEditForm(manual);

        presenter.imageChosen(first);
        presenter.imageChosen(second);
        manual.runAll();

        assertThat(manual.submittedCount()).isEqualTo(3);   // load, hide and one check
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(second);
        assertThat(presenter.selectedImageNameProperty().get()).isEqualTo("second.png");
    }

    @Test
    void imageChosen_whileAWriteRuns_isAlsoCheckedWhenTheWriteFails() throws IOException {
        Path image = png("late.png");
        when(productAdmin.adjustStock(PRODUCT_ID, 5)).thenThrow(new IllegalStateException("boom"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.deltaProperty().set("5");
        presenter.adjustStock();
        presenter.imageChosen(image);

        manual.runAll();

        verify(errors, times(1)).report(any(IllegalStateException.class));
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
    }

    @Test
    void imageChosen_whileAWriteRuns_isCheckedWithTheNormalRules_soABadFileShowsTheTypeError() throws IOException {
        Path html = tmp.resolve("fake.png");
        Files.writeString(html, "<html>not an image</html>");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = hidingEditForm(manual);
        presenter.imageChosen(html);

        manual.runAll();

        assertThat(presenter.imageErrorProperty().get()).isEqualTo(IMAGE_TYPE);
        assertThat(presenter.selectedImageProperty().get()).isNull();
    }

    @Test
    void imageChosen_whileASaveRuns_isCheckedAfterTheSave_andSelectedOnTheReloadedForm() throws IOException {
        Path image = png("during-save.png");
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Bàn phím cơ");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.save();   // task 1
        presenter.imageChosen(image);

        manual.run(1);

        // the save reloaded the form (selection reset) and the remembered file went to the normal check
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(manual.submittedCount()).isEqualTo(3);

        manual.run(2);

        assertThat(presenter.selectedImageProperty().get()).isEqualTo(image);
        verify(productAdmin, times(1)).update(anyLong(), any(), isNull());   // the new file was not sent by that save
    }

    @Test
    void imageChosen_whileAWriteRuns_andThenDispose_startsNothingWhenTheWriteEnds() throws IOException {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = hidingEditForm(manual);
        presenter.imageChosen(png("late.png"));

        presenter.dispose();
        manual.run(1);

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.checkingImageProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isNull();
    }

    @Test
    void imageChosen_whenNoWriteRuns_isCheckedAtOnce_andLeavesNothingRememberedForALaterWrite() throws IOException {
        Path image = png("now.png");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        when(productAdmin.setActive(PRODUCT_ID, false)).thenReturn("Bàn phím cơ");

        presenter.imageChosen(image);
        manual.run(1);
        presenter.clearImage();
        presenter.hide();   // task 2
        manual.run(2);      // the write ends: no old file comes back

        assertThat(manual.submittedCount()).isEqualTo(3);
        assertThat(presenter.selectedImageProperty().get()).isNull();
    }

    @Test
    void clearImage_dropsTheUnsavedSelection_theError_andThePreview() throws IOException {
        AdminProductFormPresenter presenter = editForm();
        presenter.imageChosen(png("a.png"));

        presenter.clearImage();

        assertThat(presenter.selectedImageProperty().get()).isNull();
        assertThat(presenter.selectedImageNameProperty().get()).isEmpty();
        assertThat(presenter.previewUrlProperty().get()).isEmpty();
        assertThat(presenter.imageErrorProperty().get()).isEmpty();
        assertThat(presenter.currentImageUrlProperty().get()).isEqualTo("file:/uploads/old.png");   // saved image stays
    }

    @Test
    void clearImage_whileTheCheckRuns_cancelsIt_soTheFileIsNotSelectedLater() throws IOException {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        presenter.imageChosen(png("a.png"));

        presenter.clearImage();
        manual.runAll();

        assertThat(presenter.checkingImageProperty().get()).isFalse();
        assertThat(presenter.selectedImageProperty().get()).isNull();
    }

    // ------------------------------------------------------------------ stock adjustment (QĐ7)

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "0", "abc", "1.5", "100001", "-100001", "+0"})
    void adjustStock_withAnInvalidDelta_showsTheErrorUnderTheField_andCallsNoService(String raw) {
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set(raw);

        presenter.adjustStock();

        assertThat(presenter.deltaErrorProperty().get()).isEqualTo(DELTA_INVALID);
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.DELTA);
        assertThat(presenter.deltaProperty().get()).isEqualTo(raw);
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(productAdmin, never()).adjustStock(anyLong(), anyInt());
    }

    @Test
    void adjustStock_adjusted_toastsTheNewStock_updatesTheLabel_clearsTheField_andLeavesTheFormAlone() {
        when(productAdmin.adjustStock(PRODUCT_ID, 5)).thenReturn(new StockAdjustResult.Adjusted("Bàn phím cơ", 12));
        AdminProductFormPresenter presenter = editForm();
        presenter.nameProperty().set("Tên đang gõ dở");
        presenter.priceProperty().set("999");
        presenter.deltaProperty().set(" +5 ");

        presenter.adjustStock();

        verify(productAdmin, times(1)).adjustStock(PRODUCT_ID, 5);
        verify(toasts).show("Đã cập nhật tồn kho sản phẩm Bàn phím cơ. Tồn kho hiện tại: 12.", ToastKind.SUCCESS);
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 12");
        assertThat(presenter.deltaProperty().get()).isEmpty();
        assertThat(presenter.deltaErrorProperty().get()).isEmpty();
        assertThat(presenter.nameProperty().get()).isEqualTo("Tên đang gõ dở");
        assertThat(presenter.priceProperty().get()).isEqualTo("999");
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(productAdmin, times(1)).getForEdit(PRODUCT_ID);   // no reload of the form
        verifyNoInteractions(errors);
    }

    @Test
    void adjustStock_aNegativeDelta_isSentAsNegative() {
        when(productAdmin.adjustStock(PRODUCT_ID, -3)).thenReturn(new StockAdjustResult.Adjusted("Bàn phím cơ", 4));
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("-3");

        presenter.adjustStock();

        verify(productAdmin).adjustStock(PRODUCT_ID, -3);
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 4");
    }

    @Test
    void adjustStock_notEnough_showsTheMessageUnderTheField_updatesTheLabel_keepsTheTypedNumber_withoutAToast() {
        when(productAdmin.adjustStock(PRODUCT_ID, -9))
                .thenReturn(new StockAdjustResult.NotEnough("Bàn phím cơ", 9, 7));
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("-9");

        presenter.adjustStock();

        assertThat(presenter.deltaErrorProperty().get())
                .isEqualTo("Không thể giảm 9 vì tồn kho hiện tại chỉ còn 7.");
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 7");
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(AdminProductFormPresenter.Field.DELTA);
        assertThat(presenter.deltaProperty().get()).isEqualTo("-9");
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(toasts, errors);
    }

    @Test
    void adjustStock_wouldExceed_showsTheLimitMessage_keepsTheNumberAndTheLabel_withoutAToast() {
        when(productAdmin.adjustStock(PRODUCT_ID, 100_000)).thenReturn(new StockAdjustResult.WouldExceed("Bàn phím cơ"));
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("100000");

        presenter.adjustStock();

        assertThat(presenter.deltaErrorProperty().get())
                .isEqualTo("Tồn kho sau điều chỉnh không được vượt quá 100.000.");
        assertThat(presenter.deltaProperty().get()).isEqualTo("100000");
        assertThat(presenter.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 7");
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(toasts, errors);
    }

    @Test
    void adjustStock_typingInTheDeltaField_clearsItsError() {
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("abc");
        presenter.adjustStock();
        assertThat(presenter.deltaErrorProperty().get()).isNotEmpty();

        presenter.deltaProperty().set("5");

        assertThat(presenter.deltaErrorProperty().get()).isEmpty();
    }

    /** An edit form whose stock adjustment of -9 was refused, so the delta error is showing and "-9" is typed. */
    private AdminProductFormPresenter editFormShowingADeltaError() {
        when(productAdmin.adjustStock(PRODUCT_ID, -9))
                .thenReturn(new StockAdjustResult.NotEnough("Bàn phím cơ", 9, 7));
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("-9");
        presenter.adjustStock();
        assertThat(presenter.deltaErrorProperty().get()).isNotEmpty();
        return presenter;
    }

    @Test
    void save_edit_succeeding_clearsAStaleDeltaError_butKeepsTheTypedDelta() {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = editFormShowingADeltaError();

        presenter.save();

        verify(productAdmin, times(1)).update(eq(PRODUCT_ID), any(), isNull());
        assertThat(presenter.deltaErrorProperty().get()).isEmpty();
        assertThat(presenter.deltaProperty().get()).isEqualTo("-9");
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void save_edit_succeeding_alsoClearsADeltaErrorAboutAnInvalidNumber() {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("abc");
        presenter.adjustStock();
        assertThat(presenter.deltaErrorProperty().get()).isEqualTo(DELTA_INVALID);

        presenter.save();

        assertThat(presenter.deltaErrorProperty().get()).isEmpty();
        assertThat(presenter.deltaProperty().get()).isEqualTo("abc");
    }

    @Test
    void save_edit_withAFormError_keepsTheDeltaError() {
        AdminProductFormPresenter presenter = editFormShowingADeltaError();
        String shownBefore = presenter.deltaErrorProperty().get();
        presenter.nameProperty().set("");

        presenter.save();

        assertThat(presenter.nameErrorProperty().get()).isNotEmpty();
        assertThat(presenter.deltaErrorProperty().get()).isEqualTo(shownBefore);
        assertThat(presenter.deltaProperty().get()).isEqualTo("-9");
        verify(productAdmin, never()).update(anyLong(), any(), any());
    }

    @Test
    void save_edit_whenTheServiceRefusesTheCategory_keepsTheDeltaError() {
        when(productAdmin.update(anyLong(), any(), any())).thenThrow(new CategoryNotFoundException("gone"));
        AdminProductFormPresenter presenter = editFormShowingADeltaError();
        String shownBefore = presenter.deltaErrorProperty().get();

        presenter.save();

        assertThat(presenter.categoryErrorProperty().get()).isNotEmpty();
        assertThat(presenter.deltaErrorProperty().get()).isEqualTo(shownBefore);
    }

    @Test
    void save_edit_whenTheUpdateFailsUnexpectedly_keepsTheDeltaError() {
        IllegalStateException boom = new IllegalStateException("boom");
        when(productAdmin.update(anyLong(), any(), any())).thenThrow(boom);
        AdminProductFormPresenter presenter = editFormShowingADeltaError();
        String shownBefore = presenter.deltaErrorProperty().get();

        presenter.save();

        verify(errors, times(1)).report(boom);
        assertThat(presenter.deltaErrorProperty().get()).isEqualTo(shownBefore);
    }

    @Test
    void hide_doesNotTouchTheDeltaError() {
        when(productAdmin.setActive(PRODUCT_ID, false)).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = editFormShowingADeltaError();
        String shownBefore = presenter.deltaErrorProperty().get();

        presenter.hide();

        assertThat(presenter.deltaErrorProperty().get()).isEqualTo(shownBefore);
    }

    @Test
    void adjustStock_whenTheProductIsGone_toastsNotFound_andGoesBack() {
        when(productAdmin.adjustStock(anyLong(), anyInt())).thenThrow(new NotFoundException("gone"));
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("5");

        presenter.adjustStock();

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void adjustStock_asANonAdmin_toastsAndGoesHome() {
        when(productAdmin.adjustStock(anyLong(), anyInt())).thenThrow(new AccessDeniedException());
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("5");

        presenter.adjustStock();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
    }

    @Test
    void adjustStock_withAnUnexpectedFailure_reportsOnce_andUnlocks() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(productAdmin.adjustStock(anyLong(), anyInt())).thenThrow(failure);
        AdminProductFormPresenter presenter = editForm();
        presenter.deltaProperty().set("5");

        presenter.adjustStock();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.deltaProperty().get()).isEqualTo("5");
    }

    @Test
    void adjustStock_onTheNewForm_isIgnored() {
        AdminProductFormPresenter presenter = newForm();
        presenter.deltaProperty().set("5");

        presenter.adjustStock();

        verify(productAdmin, never()).adjustStock(anyLong(), anyInt());
        assertThat(presenter.deltaErrorProperty().get()).isEmpty();
    }

    @Test
    void adjustStock_whileAnotherWriteRuns_isIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.deltaProperty().set("5");

        presenter.adjustStock();
        presenter.adjustStock();
        presenter.save();
        presenter.hide();

        assertThat(manual.submittedCount()).isEqualTo(2);   // the load and one stock adjustment
        assertThat(presenter.busyProperty().get()).isTrue();
    }

    // ------------------------------------------------------------------ hide and show (AC-006-25, -26, QĐ7)

    @Test
    void hide_callsSetActiveFalse_toasts_changesOnlyTheStateAndLeavesTheFormAlone() {
        when(productAdmin.setActive(PRODUCT_ID, false)).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = editForm();
        presenter.nameProperty().set("Tên đang gõ dở");
        presenter.deltaProperty().set("12");

        presenter.hide();

        verify(productAdmin, times(1)).setActive(PRODUCT_ID, false);
        verify(toasts).show("Đã ẩn sản phẩm Bàn phím cơ. Khách sẽ không thấy sản phẩm này nữa.",
                ToastKind.SUCCESS);
        assertThat(presenter.activeNowProperty().get()).isFalse();
        assertThat(presenter.nameProperty().get()).isEqualTo("Tên đang gõ dở");
        assertThat(presenter.deltaProperty().get()).isEqualTo("12");
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(productAdmin, times(1)).getForEdit(PRODUCT_ID);   // no reload
        verifyNoInteractions(errors);
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void show_callsSetActiveTrue_andToastsTheShownMessage() {
        when(productAdmin.getForEdit(PRODUCT_ID)).thenReturn(view("Bàn phím cơ", "1250000", null, 7, false, null));
        when(productAdmin.setActive(PRODUCT_ID, true)).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = editForm();
        assertThat(presenter.activeNowProperty().get()).isFalse();

        presenter.show();

        verify(productAdmin, times(1)).setActive(PRODUCT_ID, true);
        verify(toasts).show("Đã hiện lại sản phẩm Bàn phím cơ.", ToastKind.SUCCESS);
        assertThat(presenter.activeNowProperty().get()).isTrue();
    }

    @Test
    void hide_onTheNewForm_isIgnored() {
        newForm().hide();

        verify(productAdmin, never()).setActive(anyLong(), anyBoolean());
    }

    @Test
    void hide_whenTheProductIsGone_toastsNotFound_andGoesBack_leavingTheStateAsItWas() {
        when(productAdmin.setActive(anyLong(), anyBoolean())).thenThrow(new NotFoundException("gone"));
        AdminProductFormPresenter presenter = editForm();

        presenter.hide();

        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.activeNowProperty().get()).isTrue();
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void hide_withAnUnexpectedFailure_reportsOnce_andUnlocks() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(productAdmin.setActive(anyLong(), anyBoolean())).thenThrow(failure);
        AdminProductFormPresenter presenter = editForm();

        presenter.hide();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.activeNowProperty().get()).isTrue();
    }

    @Test
    void hide_twice_sendsOnce_andShowWhileItRunsIsIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);

        presenter.hide();
        presenter.hide();
        presenter.show();

        assertThat(manual.submittedCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ navigation

    @Test
    void cancel_goesBackToTheListWithTheSameFilterAndPage_withoutCallingTheServices() {
        AdminProductFormPresenter presenter = editForm();
        presenter.nameProperty().set("đang gõ");

        presenter.cancel();

        verify(navigator).navigateTo(BACK);
        verify(productAdmin, never()).update(anyLong(), any(), any());
    }

    @Test
    void viewInStore_whenActive_opensTheStoreDetailWithTheFullListAsBack() {
        editForm().viewInStore();

        verify(navigator).navigateTo(new ProductDetailRoute(PRODUCT_ID, CatalogRoute.all()));
    }

    @Test
    void viewInStore_whenHiddenOrNew_doesNothing() {
        when(productAdmin.getForEdit(PRODUCT_ID)).thenReturn(view("Bàn phím cơ", "1250000", null, 7, false, null));
        editForm().viewInStore();
        newForm().viewInStore();

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void viewInStore_afterHidingItInThisForm_doesNothing() {
        when(productAdmin.setActive(PRODUCT_ID, false)).thenReturn("Bàn phím cơ");
        AdminProductFormPresenter presenter = editForm();
        presenter.hide();

        presenter.viewInStore();

        verify(navigator, never()).navigateTo(any());
    }

    // ------------------------------------------------------------------ dispose (AC-009-10, QĐ10)

    @Test
    void dispose_cancelsTheLoad_soNothingChangesWhenItArrives() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.nameProperty().get()).isEmpty();
    }

    @Test
    void dispose_cancelsTheImageCheck_soNoFileIsSelectedLater() throws IOException {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        presenter.imageChosen(png("a.png"));

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(1).isCancelled()).isTrue();
        assertThat(presenter.selectedImageProperty().get()).isNull();
    }

    @Test
    void dispose_doesNotCancelACreateInFlight_lateSuccessStillToasts_butDoesNotNavigate() {
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenReturn(new SavedProduct(77, "Bàn phím"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        fillValid(presenter);
        presenter.save();

        presenter.dispose();
        manual.run(1);

        assertThat(manual.handle(1).isCancelled()).isFalse();
        verify(productAdmin, times(1)).create(any(), anyInt(), anyBoolean(), any());
        verify(toasts).show("Đã thêm sản phẩm Bàn phím.", ToastKind.SUCCESS);
        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    @Test
    void dispose_lateUpdateSuccess_toasts_butDoesNotRewriteTheForm() {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Tên mới");
        when(productAdmin.getForEdit(PRODUCT_ID))
                .thenReturn(view("Bàn phím cơ", "1250000", "Gõ êm", 7, true, null))
                .thenReturn(view("Tên mới", "1300000", null, 7, true, null));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.save();

        presenter.dispose();
        manual.run(1);

        verify(toasts).show("Đã cập nhật sản phẩm Tên mới.", ToastKind.SUCCESS);
        assertThat(presenter.nameProperty().get()).isEqualTo("Bàn phím cơ");
        assertThat(presenter.busyProperty().get()).isTrue();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void dispose_lateUpdateSuccessWithAFailedReadBack_toasts_butDoesNotReopenTheRoute() {
        when(productAdmin.update(anyLong(), any(), any())).thenReturn("Tên mới");
        when(productAdmin.getForEdit(PRODUCT_ID))
                .thenReturn(view("Bàn phím cơ", "1250000", "Gõ êm", 7, true, null))
                .thenThrow(new IllegalStateException("boom"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        manual.run(0);
        presenter.save();

        presenter.dispose();
        manual.run(1);

        verify(toasts).show("Đã cập nhật sản phẩm Tên mới.", ToastKind.SUCCESS);
        verifyNoInteractions(errors);
        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    @Test
    void dispose_lateStockAndHideResults_followTheSameRule() {
        when(productAdmin.adjustStock(PRODUCT_ID, 5)).thenReturn(new StockAdjustResult.Adjusted("Bàn phím cơ", 12));
        when(productAdmin.setActive(PRODUCT_ID, false)).thenReturn("Bàn phím cơ");
        when(productAdmin.adjustStock(PRODUCT_ID, -50)).thenReturn(new StockAdjustResult.NotEnough("Bàn phím cơ", 50, 7));
        ManualBackgroundTasks first = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter adjusting = presenter(first);
        adjusting.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        first.run(0);
        adjusting.deltaProperty().set("5");
        adjusting.adjustStock();
        ManualBackgroundTasks second = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter hiding = presenter(second);
        hiding.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        second.run(0);
        hiding.hide();
        ManualBackgroundTasks third = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter notEnough = presenter(third);
        notEnough.load(AdminProductFormRoute.edit(PRODUCT_ID, BACK));
        third.run(0);
        notEnough.deltaProperty().set("-50");
        notEnough.adjustStock();

        adjusting.dispose();
        hiding.dispose();
        notEnough.dispose();
        first.run(1);
        second.run(1);
        third.run(1);

        verify(toasts).show("Đã cập nhật tồn kho sản phẩm Bàn phím cơ. Tồn kho hiện tại: 12.", ToastKind.SUCCESS);
        verify(toasts).show("Đã ẩn sản phẩm Bàn phím cơ. Khách sẽ không thấy sản phẩm này nữa.", ToastKind.SUCCESS);
        verify(toasts, times(2)).show(any(), any());   // the "not enough" result is a field error: no toast
        assertThat(adjusting.stockTextProperty().get()).isEqualTo("Tồn kho hiện tại: 7");
        assertThat(adjusting.deltaProperty().get()).isEqualTo("5");
        assertThat(hiding.activeNowProperty().get()).isTrue();
        assertThat(notEnough.deltaErrorProperty().get()).isEmpty();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void dispose_lateBusinessFailures_areIgnored() {
        List<RuntimeException> businessFailures = List.of(
                new InvalidImageException(InvalidImageException.Reason.UNSUPPORTED_TYPE, "x"),
                new CategoryNotFoundException("gone"), new NotFoundException("gone"), new AccessDeniedException());
        for (RuntimeException failure : businessFailures) {
            org.mockito.Mockito.doThrow(failure).when(productAdmin).create(any(), anyInt(), anyBoolean(), any());
            ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
            AdminProductFormPresenter presenter = presenter(manual);
            presenter.load(AdminProductFormRoute.newProduct(BACK));
            manual.run(0);
            fillValid(presenter);
            presenter.save();

            presenter.dispose();
            manual.run(1);

            assertThat(presenter.imageErrorProperty().get()).isEmpty();
            assertThat(presenter.categoryErrorProperty().get()).isEmpty();
        }

        verifyNoInteractions(toasts, errors);
        verify(navigator, never()).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void dispose_lateUnexpectedFailure_isStillReported() {
        IllegalStateException boom = new IllegalStateException("boom");
        when(productAdmin.create(any(), anyInt(), anyBoolean(), any())).thenThrow(boom);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminProductFormPresenter presenter = presenter(manual);
        presenter.load(AdminProductFormRoute.newProduct(BACK));
        manual.run(0);
        fillValid(presenter);
        presenter.save();

        presenter.dispose();
        manual.run(1);

        verify(errors, times(1)).report(boom);
        verifyNoInteractions(toasts);
        verify(navigator, never()).goHome();
    }

    @Test
    void afterDispose_everyAction_doesNothing() throws IOException {
        AdminProductFormPresenter presenter = editForm();
        presenter.dispose();
        presenter.deltaProperty().set("5");

        presenter.save();
        presenter.adjustStock();
        presenter.hide();
        presenter.show();
        presenter.cancel();
        presenter.viewInStore();
        presenter.addCategory();
        presenter.imageChosen(png("a.png"));
        presenter.clearImage();

        verifyNoInteractions(navigator);
        verify(productAdmin, never()).update(anyLong(), any(), any());
        verify(productAdmin, never()).adjustStock(anyLong(), anyInt());
        verify(productAdmin, never()).setActive(anyLong(), anyBoolean());
        assertThat(direct.submittedCount()).isEqualTo(1);   // only the load
    }
}

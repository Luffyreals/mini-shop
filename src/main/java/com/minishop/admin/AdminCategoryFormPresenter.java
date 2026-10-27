package com.minishop.admin;

import com.minishop.catalog.CategoriesMenu;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDraft;
import com.minishop.catalog.CategoryEditView;
import com.minishop.catalog.CategoryNameTakenException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của form "Thêm danh mục" / "Sửa danh mục" (AC-006-11 đến -14, QĐ3, QĐ5, QĐ13 của D5).
 * Mỗi lần điều hướng tới một {@link AdminCategoryFormRoute} có một presenter mới ({@code prototype}); màn gọi
 * {@link #load} một lần và {@link #dispose} khi bị thay.
 *
 * <p><b>Nạp:</b> form thêm mới sẵn sàng ngay (không gọi service); form sửa nạp {@code CategoryAdminService.get}
 * ở nền rồi điền hai ô. Danh mục không còn thì "Không tìm thấy danh mục." rồi về danh sách (AC-009-13).
 *
 * <p><b>Lưu ({@link #save}):</b> {@link CategoryForm} tự làm sạch chữ; {@link Validator} kiểm trên luồng giao
 * diện, mỗi ô một lỗi ({@link FieldErrors}), {@link #firstErrorFieldProperty()} cho biết ô cần focus, chữ đã nhập
 * giữ nguyên và service không được gọi. Form đúng thì khóa ({@link #busyProperty()}, bấm lần hai bị bỏ qua) và gọi
 * {@code create(draft)} hoặc {@code update(id, draft)} đúng một lần. Thành công: toast, làm mới menu "Danh mục" của
 * thanh trên ({@link CategoriesMenu#refresh()}), về danh sách. {@link CategoryNameTakenException} thì lỗi dưới ô
 * tên; mọi lỗi khác đi qua {@link AdminFailures}.
 *
 * <p><b>{@link #dispose}:</b> hủy việc nạp; việc lưu đang chạy <em>không</em> bị hủy. Xong sau {@code dispose} thì
 * toast thành công và làm mới menu vẫn chạy, nhưng không đổi property và không điều hướng; lỗi nghiệp vụ bị bỏ
 * qua, lỗi lạ vẫn được báo.
 *
 * <p>Presenter chỉ import {@code javafx.beans}, không ghi log và không đưa chữ đã nhập vào thông báo ngoài tên
 * danh mục service trả về.
 */
@Component
@Scope("prototype")
class AdminCategoryFormPresenter {

    static final String TITLE_NEW = "Thêm danh mục";
    static final String TITLE_EDIT = "Sửa danh mục";
    static final String MSG_ADDED = "Đã thêm danh mục %s.";
    static final String MSG_UPDATED = "Đã cập nhật danh mục %s.";
    static final String MSG_NAME_TAKEN = "Tên danh mục đã tồn tại.";

    /** Ô của form; màn dùng để đưa focus tới ô lỗi đầu tiên. */
    enum Field { NAME, DESCRIPTION }

    private final CategoryAdminService service;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final Toasts toasts;
    private final CategoriesMenu categoriesMenu;
    private final AdminFailures failures;
    private final UnexpectedErrors errors;
    private final Validator validator;

    private final StringProperty title = new SimpleStringProperty(TITLE_NEW);
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty description = new SimpleStringProperty("");
    private final ReadOnlyStringWrapper nameError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper descriptionError = new ReadOnlyStringWrapper("");
    private final ObjectProperty<Field> firstErrorField = new SimpleObjectProperty<>();

    private AdminCategoryFormRoute route;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    AdminCategoryFormPresenter(CategoryAdminService service, BackgroundTasks tasks, Navigator navigator,
                               Toasts toasts, CategoriesMenu categoriesMenu, AdminFailures failures,
                               UnexpectedErrors errors, Validator validator) {
        this.service = service;
        this.tasks = tasks;
        this.navigator = navigator;
        this.toasts = toasts;
        this.categoriesMenu = categoriesMenu;
        this.failures = failures;
        this.errors = errors;
        this.validator = validator;
        // Gõ lại vào một ô thì lỗi của ô đó biến mất
        name.addListener((observable, oldValue, newValue) -> nameError.set(""));
        description.addListener((observable, oldValue, newValue) -> descriptionError.set(""));
    }

    // ------------------------------------------------------------------ nạp

    /** Nhận route; sửa thì nạp danh mục ở nền. Gọi một lần. */
    void load(AdminCategoryFormRoute newRoute) {
        this.route = Objects.requireNonNull(newRoute, "route");
        title.set(newRoute.isNew() ? TITLE_NEW : TITLE_EDIT);
        if (newRoute.isNew()) {
            loaded.set(true);
            return;
        }
        long id = newRoute.categoryId();
        loading.set(true);
        pending = tasks.submit(() -> service.get(id), this::onLoaded, this::onLoadFailed);
    }

    private void onLoaded(CategoryEditView view) {
        pending = null;
        loading.set(false);
        name.set(nullToEmpty(view.name()));
        description.set(nullToEmpty(view.description()));
        loaded.set(true);
    }

    private void onLoadFailed(Throwable failure) {
        pending = null;
        loading.set(false);
        failures.handle(failure, AdminFailures.MSG_CATEGORY_NOT_FOUND, this::backToList);
        if (!(failure instanceof NotFoundException) && !failures.isAccessDenied(failure)) {
            backToList(); // không để người dùng kẹt ở form rỗng sau một lỗi lạ (đã báo ở trên)
        }
    }

    // ------------------------------------------------------------------ lưu

    /** "Lưu". Xem mô tả lớp. */
    void save() {
        if (disposed || busy.get() || !loaded.get()) {
            return;
        }
        clearErrors();
        CategoryForm form = new CategoryForm(name.get(), description.get());
        Set<ConstraintViolation<CategoryForm>> violations = validator.validate(form);
        if (!violations.isEmpty()) {
            showViolations(FieldErrors.firstPerField(violations));
            return;
        }
        CategoryDraft draft = form.toDraft();
        Long id = route.categoryId();
        boolean creating = id == null;
        busy.set(true);
        // Handle không được giữ: dispose() không hủy việc lưu (hủy callback không hủy transaction)
        tasks.submit(() -> creating ? service.create(draft) : service.update(id, draft),
                savedName -> onSaved(creating, savedName), this::onSaveFailed);
    }

    private void onSaved(boolean creating, String savedName) {
        toasts.show((creating ? MSG_ADDED : MSG_UPDATED).formatted(savedName), ToastKind.SUCCESS);
        categoriesMenu.refresh();
        if (disposed) {
            return; // người dùng đã đi chỗ khác: chỉ báo kết quả, không điều hướng
        }
        // busy giữ nguyên tới khi màn bị thay, để không thể bấm lần hai
        navigator.navigateTo(new AdminCategoriesRoute());
    }

    private void onSaveFailed(Throwable failure) {
        if (disposed) {
            if (!(failure instanceof CategoryNameTakenException) && !(failure instanceof NotFoundException)
                    && !failures.isAccessDenied(failure)) {
                failures.handle(failure);
            }
            return;
        }
        busy.set(false);
        if (failure instanceof CategoryNameTakenException) {
            nameError.set(MSG_NAME_TAKEN);
            firstErrorField.set(null);
            firstErrorField.set(Field.NAME);
            return;
        }
        failures.handle(failure, AdminFailures.MSG_CATEGORY_NOT_FOUND, this::backToList);
    }

    /** "Hủy": về danh sách, bỏ chữ đã nhập (như bản web). Đang lưu thì bỏ qua. */
    void cancel() {
        if (!busy.get()) {
            backToList();
        }
    }

    private void backToList() {
        if (!disposed) {
            navigator.navigateTo(new AdminCategoriesRoute());
        }
    }

    // ------------------------------------------------------------------ lỗi form

    private void clearErrors() {
        nameError.set("");
        descriptionError.set("");
        firstErrorField.set(null);
    }

    private void showViolations(Map<String, String> byField) {
        String nameMessage = byField.get("name");
        String descriptionMessage = byField.get("description");
        if (nameMessage != null) {
            nameError.set(nameMessage);
        }
        if (descriptionMessage != null) {
            descriptionError.set(descriptionMessage);
        }
        if (nameMessage != null) {
            firstErrorField.set(Field.NAME);
        } else if (descriptionMessage != null) {
            firstErrorField.set(Field.DESCRIPTION);
        } else {
            // Một ràng buộc không gắn được vào ô nào: lỗi lập trình, không để người dùng nhìn form im lặng
            errors.report(new IllegalStateException("Category violation without a field"));
        }
    }

    // ------------------------------------------------------------------ vòng đời

    /** Hủy việc nạp đang chờ; việc lưu đang chạy không bị hủy. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------------ property cho màn

    /** "Thêm danh mục" hoặc "Sửa danh mục"; đặt ngay khi {@link #load} bắt đầu. */
    ReadOnlyStringProperty titleProperty() {
        return title;
    }

    /** {@code true} trong lúc nạp danh mục để sửa. */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} khi form dùng được (thêm mới: ngay; sửa: sau khi nạp xong). */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** {@code true} từ lúc gửi việc lưu tới khi màn bị thay hoặc việc lưu hỏng. */
    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    StringProperty nameProperty() {
        return name;
    }

    StringProperty descriptionProperty() {
        return description;
    }

    ReadOnlyStringProperty nameErrorProperty() {
        return nameError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty descriptionErrorProperty() {
        return descriptionError.getReadOnlyProperty();
    }

    /**
     * Ô cần focus sau một lần kiểm form sai hoặc lỗi trùng tên; đặt về {@code null} rồi mới đặt giá trị mới ở mỗi
     * lần, nên màn nghe thay đổi và chỉ xử lý giá trị khác {@code null}.
     */
    ReadOnlyObjectProperty<Field> firstErrorFieldProperty() {
        return firstErrorField;
    }
}

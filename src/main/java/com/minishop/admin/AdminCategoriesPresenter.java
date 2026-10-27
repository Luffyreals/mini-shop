package com.minishop.admin;

import com.minishop.catalog.CategoriesMenu;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryDeleteResult;
import com.minishop.catalog.CategoryRow;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.ConfirmDialog;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import java.util.List;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của màn "Danh mục" (AC-006-10, -15, -16, QĐ5, QĐ9, QĐ10, QĐ11 của D5). Mỗi lần điều
 * hướng tới {@link AdminCategoriesRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần
 * và {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> {@code CategoryAdminService.list} và {@code delete} chạy qua {@link BackgroundTasks}; property
 * chỉ đổi trên luồng giao diện. Thứ tự các dòng do service quyết (Collator tiếng Việt).
 *
 * <p><b>Xóa:</b> {@link #requestDelete} chỉ mở hộp xác nhận ("Xóa danh mục {tên}? Không thể hoàn tác."); service
 * chưa được gọi tới khi người dùng xác nhận ({@link #delete}). {@link #writingIds()} chặn bấm hai lần trên cùng một
 * dòng (nút không bị khóa: nút đang có focus mà bị khóa thì focus nhảy sang nút khác). Kết quả
 * {@link CategoryDeleteResult} ánh xạ bằng {@code switch} đủ nhánh: {@code Deleted} thì toast, làm mới menu
 * "Danh mục" của thanh trên rồi nạp lại; {@code InUse} thì toast lỗi và nạp lại. Danh mục đã bị xóa từ trước thì
 * "Không tìm thấy danh mục." rồi nạp lại.
 *
 * <p><b>{@link #dispose}:</b> hủy việc nạp. Việc xóa đang chạy <em>không</em> bị hủy (hủy callback không hủy
 * transaction): xong thì toast kết quả và làm mới menu thanh trên vẫn chạy, nhưng không đổi property, không nạp lại
 * và không điều hướng. Lỗi nghiệp vụ sau {@code dispose} bị bỏ qua; lỗi lạ vẫn được báo.
 *
 * <p>Presenter chỉ import {@code javafx.beans} và {@code javafx.collections}, không ghi log.
 */
@Component
@Scope("prototype")
class AdminCategoriesPresenter {

    static final String MSG_DELETED = "Đã xóa danh mục %s.";
    static final String MSG_IN_USE = "Không thể xóa danh mục %s vì còn %d sản phẩm (kể cả sản phẩm đã ẩn).";
    static final String CONFIRM_MESSAGE = "Xóa danh mục %s? Không thể hoàn tác.";
    static final String CONFIRM_LABEL = "Xóa";
    static final String DISMISS_LABEL = "Quay lại";

    private final CategoryAdminService service;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final Toasts toasts;
    private final ConfirmDialog confirmDialog;
    private final CategoriesMenu categoriesMenu;
    private final AdminFailures failures;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final BooleanProperty empty = new SimpleBooleanProperty(false);
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final ObservableSet<Long> writingIds = FXCollections.observableSet();
    private final ObjectProperty<FocusTarget> focusTarget = new SimpleObjectProperty<>();

    private BackgroundTasks.Handle pending;
    private boolean refocus;
    private Long refocusRowId;
    private boolean disposed;

    AdminCategoriesPresenter(CategoryAdminService service, BackgroundTasks tasks, Navigator navigator,
                             Toasts toasts, ConfirmDialog confirmDialog, CategoriesMenu categoriesMenu,
                             AdminFailures failures) {
        this.service = service;
        this.tasks = tasks;
        this.navigator = navigator;
        this.toasts = toasts;
        this.confirmDialog = confirmDialog;
        this.categoriesMenu = categoriesMenu;
        this.failures = failures;
    }

    /**
     * Một dòng của bảng, chữ đã định dạng sẵn.
     *
     * @param id                  id danh mục (id node {@code categoryRow-{id}}...)
     * @param name                tên
     * @param description         mô tả, {@code ""} khi không có
     * @param countText           số sản phẩm (cả sản phẩm đã ẩn)
     * @param editAccessibleText  "Sửa {tên}"
     * @param deleteAccessibleText "Xóa {tên}"
     */
    record Row(long id, String name, String description, String countText, String editAccessibleText,
               String deleteAccessibleText) {
    }

    /**
     * Chỗ màn nên đặt focus sau một lần nạp: dòng có {@code rowId} (nút "Xóa" của dòng đó) nếu nó còn, không thì
     * nút "Thêm danh mục" ở đầu màn ({@code rowId == null}).
     */
    record FocusTarget(Long rowId) {
    }

    // ------------------------------------------------------------------ nạp

    /** Nạp danh sách ở nền (lần đầu). Xong thì focus nút "Thêm danh mục". */
    void load() {
        reload(null);
    }

    private void reload(Long keepFocusRowId) {
        if (disposed) {
            return;
        }
        cancelLoad();
        refocus = true;
        refocusRowId = keepFocusRowId;
        loading.set(true);
        pending = tasks.submit(service::list, this::onLoaded, this::onLoadFailed);
    }

    private void onLoaded(List<CategoryRow> categories) {
        pending = null;
        loading.set(false);
        rows.setAll(categories.stream().map(AdminCategoriesPresenter::toRow).toList());
        empty.set(categories.isEmpty());
        loaded.set(true);
        if (refocus) {
            refocus = false;
            Long id = refocusRowId;
            boolean stillThere = id != null && rows.stream().anyMatch(row -> row.id() == id);
            // Về null trước để màn luôn thấy một thay đổi, kể cả khi hai lần liền cùng một đích
            focusTarget.set(null);
            focusTarget.set(new FocusTarget(stillThere ? id : null));
        }
    }

    private void onLoadFailed(Throwable failure) {
        pending = null;
        loading.set(false);
        refocus = false;
        failures.handle(failure);
    }

    private static Row toRow(CategoryRow category) {
        String description = category.description() == null ? "" : category.description();
        return new Row(category.id(), category.name(), description, Long.toString(category.productCount()),
                "Sửa " + category.name(), "Xóa " + category.name());
    }

    // ------------------------------------------------------------------ lối đi

    /** "Thêm danh mục". */
    void add() {
        if (!disposed) {
            navigator.navigateTo(AdminCategoryFormRoute.newCategory());
        }
    }

    /** "Sửa" một dòng. */
    void edit(long id) {
        if (!disposed) {
            navigator.navigateTo(AdminCategoryFormRoute.edit(id));
        }
    }

    // ------------------------------------------------------------------ xóa

    /**
     * "Xóa" một dòng: chỉ mở hộp xác nhận (nút từ chối "Quay lại"). Service chưa được gọi. Dòng đang bị xóa thì bỏ
     * qua.
     */
    void requestDelete(Row row) {
        if (disposed || writingIds.contains(row.id())) {
            return;
        }
        confirmDialog.ask(CONFIRM_MESSAGE.formatted(row.name()), CONFIRM_LABEL, DISMISS_LABEL,
                () -> delete(row.id()));
    }

    /** Gọi service xóa (chỉ từ hộp xác nhận). Mỗi dòng chỉ một việc xóa tại một lúc. */
    void delete(long id) {
        if (disposed || !writingIds.add(id)) {
            return;
        }
        // Handle không được giữ: dispose() không hủy việc xóa (hủy callback không hủy transaction)
        tasks.submit(() -> service.delete(id), result -> onDeleteResult(id, result),
                failure -> onDeleteFailed(id, failure));
    }

    private void onDeleteResult(long id, CategoryDeleteResult result) {
        switch (result) {
            case CategoryDeleteResult.Deleted deleted -> {
                toasts.show(MSG_DELETED.formatted(deleted.name()), ToastKind.SUCCESS);
                categoriesMenu.refresh();
                settle(id, null);
            }
            case CategoryDeleteResult.InUse inUse -> {
                toasts.show(MSG_IN_USE.formatted(inUse.name(), inUse.productCount()), ToastKind.ERROR);
                settle(id, id);
            }
        }
    }

    private void onDeleteFailed(long id, Throwable failure) {
        if (disposed) {
            if (!(failure instanceof NotFoundException) && !failures.isAccessDenied(failure)) {
                failures.handle(failure);
            }
            return;
        }
        writingIds.remove(id);
        failures.handle(failure, AdminFailures.MSG_CATEGORY_NOT_FOUND, () -> {
            categoriesMenu.refresh();   // danh mục đã mất ở nơi khác: menu "Danh mục" ở thanh trên có thể đã cũ
            reload(null);
        });
    }

    /** Một việc xóa đã xong: mở khóa dòng và nạp lại (focus về dòng {@code keepFocusRowId} nếu còn). */
    private void settle(long id, Long keepFocusRowId) {
        if (disposed) {
            return;
        }
        writingIds.remove(id);
        reload(keepFocusRowId);
    }

    // ------------------------------------------------------------------ vòng đời

    private void cancelLoad() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    /** Hủy việc nạp đang chờ; việc xóa đang chạy không bị hủy. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        cancelLoad();
    }

    // ------------------------------------------------------------------ property cho màn

    /** {@code true} trong lúc đang nạp. */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lần nạp thành công đầu tiên. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** {@code true} khi chưa có danh mục nào ("Chưa có danh mục nào."). */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty;
    }

    /** Các dòng, theo thứ tự service trả về. */
    ObservableList<Row> rows() {
        return rows;
    }

    /** Id các danh mục đang được xóa; màn hiện vòng quay nhỏ ở dòng đó. */
    ObservableSet<Long> writingIds() {
        return writingIds;
    }

    /** Đích focus sau mỗi lần nạp; màn chỉ xử lý giá trị khác {@code null}. */
    ReadOnlyObjectProperty<FocusTarget> focusTargetProperty() {
        return focusTarget;
    }
}

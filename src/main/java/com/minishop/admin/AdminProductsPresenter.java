package com.minishop.admin;

import com.minishop.catalog.AdminProductPage;
import com.minishop.catalog.AdminProductRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.common.NotFoundException;
import com.minishop.common.PageNav;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của màn "Sản phẩm" (danh sách, AC-006-17, -25, -26, F3 của D5). Mỗi lần điều hướng tới
 * một {@link AdminProductsRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần và
 * {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> {@code ProductAdminService.list} và {@code setActive} chạy qua {@link BackgroundTasks}; property
 * chỉ đổi trên luồng giao diện. Trang và từ khóa truyền cho service là chuỗi của route
 * ({@code Integer.toString(page)}); trang vượt cuối thì service trả trang 1 và {@link #pageNavProperty()} theo
 * trang service trả về. Tìm và đổi trang là điều hướng tới một route mới (route là nguồn sự thật), nên màn và
 * presenter được dựng lại; một route bằng route hiện tại thì không làm gì.
 *
 * <p><b>Ẩn và hiện lại</b> không hỏi xác nhận (spec 006 câu 11, câu hỏi 2 của D5): {@link #hide} và {@link #show}
 * gọi {@code setActive}, hiện toast thành công rồi nạp lại trang tại chỗ (cùng route). {@link #writingIds()} giữ id
 * hàng đang ghi, từ lúc bấm tới khi trang nạp lại xong, nên bấm lần hai vào cùng một hàng bị bỏ qua mà nút không
 * cần khóa (khóa nút đang có focus làm focus nhảy đi). {@code NotFoundException} (sản phẩm đã bị xóa) hiện
 * "Không tìm thấy sản phẩm." rồi nạp lại trang.
 *
 * <p>{@link #dispose} hủy việc nạp nhưng <b>không</b> hủy việc ghi đang chạy (hủy callback không hủy giao dịch): ghi
 * xong thì vẫn hiện toast thành công, không đổi property, không điều hướng; lỗi nghiệp vụ bị bỏ qua, lỗi lạ vẫn
 * báo qua {@link AdminFailures}. Lỗi đi qua {@link AdminFailures} (quyền, không tìm thấy, lỗi lạ).
 *
 * <p>Presenter chỉ import {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class AdminProductsPresenter {

    /** Câu rỗng khi không tìm kiếm (spec 006 §6). */
    static final String MSG_EMPTY = "Chưa có sản phẩm nào.";
    /** Câu rỗng khi có từ khóa (spec 006 §6). */
    static final String MSG_EMPTY_SEARCH = "Không tìm thấy sản phẩm phù hợp.";
    /** Nhãn trạng thái đang bán. */
    static final String FLAG_ACTIVE = "Đang bán";
    /** Nhãn trạng thái đã ẩn. */
    static final String FLAG_HIDDEN = "Đã ẩn";

    private static final VndFormatter MONEY = new VndFormatter();

    private final ProductAdminService productAdminService;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final AdminFailures failures;
    private final Toasts toasts;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final BooleanProperty loadFailed = new SimpleBooleanProperty(false);
    private final BooleanProperty empty = new SimpleBooleanProperty(false);
    private final StringProperty emptyText = new SimpleStringProperty("");
    private final StringProperty queryText = new SimpleStringProperty("");
    private final ObservableList<ProductRow> rows = FXCollections.observableArrayList();
    private final ObjectProperty<PageNav> pageNav = new SimpleObjectProperty<>();
    private final ObservableSet<Long> writingIds = FXCollections.observableSet();

    /** Hàng đã ghi xong, chờ trang nạp lại mới được mở khóa (tránh bấm lần hai trong lúc nạp lại). */
    private final Set<Long> settled = new HashSet<>();

    private AdminProductsRoute route;
    private boolean queryShown;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    AdminProductsPresenter(ProductAdminService productAdminService, BackgroundTasks tasks, Navigator navigator,
                           AdminFailures failures, Toasts toasts) {
        this.productAdminService = productAdminService;
        this.tasks = tasks;
        this.navigator = navigator;
        this.failures = failures;
        this.toasts = toasts;
    }

    /**
     * Một hàng của danh sách, chữ đã định dạng sẵn.
     *
     * @param id           id sản phẩm
     * @param name         tên sản phẩm
     * @param categoryName tên danh mục
     * @param priceText    giá, ví dụ {@code 1.250.000 ₫}
     * @param stockText    tồn kho
     * @param active       {@code true} khi đang bán (nút là "Ẩn"), {@code false} khi đã ẩn (nút là "Hiện lại")
     * @param flagText     "Đang bán" hoặc "Đã ẩn"
     * @param imageUrl     URL ảnh đã qua {@code toUrl}; {@code null} khi chưa có ảnh
     */
    record ProductRow(long id, String name, String categoryName, String priceText, String stockText,
                      boolean active, String flagText, String imageUrl) {

        /** Chữ cho trình đọc màn hình của nút "Sửa". */
        String editAccessibleText() {
            return "Sửa " + name;
        }

        /** Chữ của nút thứ hai: "Ẩn" khi đang bán, "Hiện lại" khi đã ẩn. */
        String toggleLabel() {
            return active ? "Ẩn" : "Hiện lại";
        }

        /** Chữ cho trình đọc màn hình của nút thứ hai: "Ẩn {tên}" hoặc "Hiện lại {tên}". */
        String toggleAccessibleText() {
            return toggleLabel() + " " + name;
        }
    }

    // ------------------------------------------------------------------ nạp

    /** Nhận route của màn và nạp trang sản phẩm ở nền. */
    void load(AdminProductsRoute route) {
        this.route = Objects.requireNonNull(route, "route");
        reload();
    }

    /** Nạp lại trang của route hiện tại; một lần nạp đang chờ bị hủy callback (kết quả cũ không đè kết quả mới). */
    private void reload() {
        if (disposed || route == null) {
            return;
        }
        if (pending != null) {
            pending.cancel();
        }
        String rawQuery = route.query();
        String rawPage = Integer.toString(route.page());
        loading.set(true);
        pending = tasks.submit(() -> productAdminService.list(rawQuery, rawPage), this::apply, this::loadFailed);
    }

    private void apply(AdminProductPage page) {
        rows.setAll(page.products().stream().map(AdminProductsPresenter::toRow).toList());
        boolean noResults = page.isEmpty();
        empty.set(noResults);
        emptyText.set(noResults ? (page.q() == null ? MSG_EMPTY : MSG_EMPTY_SEARCH) : "");
        pageNav.set(PageNav.of(page.page(), page.totalPages()));
        if (!queryShown) {
            // Đặt một lần: nạp lại sau "Ẩn" không được ghi đè chữ người dùng đang gõ
            queryShown = true;
            queryText.set(page.q() == null ? "" : page.q());
        }
        loadFailed.set(false);
        loaded.set(true);
        loading.set(false);
        releaseSettled();
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        releaseSettled();
        boolean unexpected = failures.handle(failure);
        // Chỉ lần nạp đầu mới thay màn bằng khối lỗi; nạp lại sau khi ghi thì giữ danh sách cũ (QĐ12)
        if (unexpected && !loaded.get()) {
            loadFailed.set(true);
        }
    }

    /**
     * "Thử lại" sau lỗi lạ của lần nạp đầu (QĐ12): tắt {@code loadFailed} rồi nạp lại đúng route. Bỏ qua sau
     * {@link #dispose}, khi đang nạp hoặc khi không có lỗi nào để thử lại.
     */
    void retry() {
        if (disposed || route == null || loading.get() || !loadFailed.get()) {
            return;
        }
        loadFailed.set(false);
        reload();
    }

    private static ProductRow toRow(AdminProductRow product) {
        return new ProductRow(product.id(), product.name(), product.categoryName(),
                MONEY.format(product.price()), Integer.toString(product.stock()), product.active(),
                product.active() ? FLAG_ACTIVE : FLAG_HIDDEN, product.imageUrl());
    }

    private void releaseSettled() {
        if (!settled.isEmpty()) {
            writingIds.removeAll(settled);
            settled.clear();
        }
    }

    // ------------------------------------------------------------------ lối đi

    /**
     * Tìm theo tên (Enter hoặc "Tìm"): điều hướng tới {@code route.search(raw)}, về trang 1. Từ khóa được chuẩn hóa
     * như {@code CatalogRoute}; route bằng route hiện tại thì không làm gì.
     */
    void search(String raw) {
        if (disposed || route == null) {
            return;
        }
        navigateUnlessCurrent(route.search(raw));
    }

    /** Đổi sang trang {@code page} (từ 1), giữ ô tìm. Số nhỏ hơn 1 hoặc đúng trang đang xem bị bỏ qua. */
    void goToPage(int page) {
        if (disposed || route == null || page < 1) {
            return;
        }
        navigateUnlessCurrent(route.withPage(page));
    }

    private void navigateUnlessCurrent(AdminProductsRoute target) {
        if (!target.equals(route)) {
            navigator.navigateTo(target);
        }
    }

    /** "Thêm sản phẩm": mở form thêm mới; "Hủy" ở đó về đúng trang này. */
    void add() {
        if (!disposed && route != null) {
            navigator.navigateTo(AdminProductFormRoute.newProduct(route));
        }
    }

    /** "Sửa": mở form sửa sản phẩm {@code productId}; "Hủy" ở đó về đúng trang này. */
    void edit(long productId) {
        if (!disposed && route != null) {
            navigator.navigateTo(AdminProductFormRoute.edit(productId, route));
        }
    }

    // ------------------------------------------------------------------ ẩn và hiện lại

    /** "Ẩn" (AC-006-25): không hỏi xác nhận; hiện lại được. */
    void hide(long productId) {
        setActive(productId, false);
    }

    /** "Hiện lại" (AC-006-26). */
    void show(long productId) {
        setActive(productId, true);
    }

    private void setActive(long productId, boolean active) {
        if (disposed || writingIds.contains(productId)) {
            return;
        }
        writingIds.add(productId);
        tasks.submit(() -> productAdminService.setActive(productId, active),
                name -> written(productId, active, name), failure -> writeFailed(productId, failure));
    }

    private void written(long productId, boolean active, String name) {
        // Toast cả khi màn đã bị thay: người dùng cần biết thay đổi đã được lưu
        toasts.show(active ? shownMessage(name) : hiddenMessage(name), ToastKind.SUCCESS);
        if (disposed) {
            return;
        }
        settled.add(productId);
        reload();
    }

    private void writeFailed(long productId, Throwable failure) {
        if (disposed) {
            if (!(failure instanceof NotFoundException) && !failures.isAccessDenied(failure)) {
                failures.handle(failure);
            }
            return;
        }
        if (failure instanceof NotFoundException) {
            settled.add(productId);   // mở khóa khi trang nạp lại xong
        } else {
            writingIds.remove(productId);
        }
        failures.handle(failure, AdminFailures.MSG_PRODUCT_NOT_FOUND, this::reload);
    }

    /** Toast sau khi ẩn (spec 006 §6). */
    static String hiddenMessage(String name) {
        return "Đã ẩn sản phẩm " + name + ". Khách sẽ không thấy sản phẩm này nữa.";
    }

    /** Toast sau khi hiện lại (spec 006 §6). */
    static String shownMessage(String name) {
        return "Đã hiện lại sản phẩm " + name + ".";
    }

    /** Hủy việc nạp đang chờ: sau đó không callback nào của màn này đổi property hay điều hướng. Vô hại khi gọi lại. */
    void dispose() {
        disposed = true;
        if (pending != null) {
            pending.cancel();
        }
    }

    // ------------------------------------------------------------------ property cho màn

    /** {@code true} trong lúc đang nạp (kể cả nạp lại sau khi ghi). */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lần nạp thành công đầu tiên. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** {@code true} khi lần nạp đầu lỗi vì một lỗi lạ (màn hiện khối "Thử lại"); xem {@link #retry()}. */
    ReadOnlyBooleanProperty loadFailedProperty() {
        return loadFailed;
    }

    /** {@code true} khi không có sản phẩm nào khớp (không chỉ là trang rỗng). */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty;
    }

    /** Câu rỗng: {@link #MSG_EMPTY} khi không tìm, {@link #MSG_EMPTY_SEARCH} khi có từ khóa; rỗng khi có sản phẩm. */
    ReadOnlyStringProperty emptyTextProperty() {
        return emptyText;
    }

    /** Từ khóa service đã chuẩn hóa; đặt một lần ở lần nạp thành công đầu tiên (màn điền ô tìm, chỉ khi còn trống). */
    ReadOnlyStringProperty queryTextProperty() {
        return queryText;
    }

    /** Các hàng của trang hiện tại, mới thêm trước (thứ tự do service quyết định). */
    ObservableList<ProductRow> rows() {
        return rows;
    }

    /** Dữ liệu phân trang theo trang service trả về; {@code null} tới lần nạp thành công đầu tiên. */
    ReadOnlyObjectProperty<PageNav> pageNavProperty() {
        return pageNav;
    }

    /** Id hàng đang ghi (ẩn hoặc hiện lại), từ lúc bấm tới khi trang nạp lại xong. Màn chỉ đọc. */
    ObservableSet<Long> writingIds() {
        return writingIds;
    }
}

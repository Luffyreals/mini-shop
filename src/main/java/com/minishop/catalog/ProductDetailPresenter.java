package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Trạng thái và quyết định của màn chi tiết sản phẩm (QĐ3, QĐ11 của D3). Mỗi lần điều hướng tới một
 * {@link ProductDetailRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần, và
 * {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> {@code ProductService.getDetail} chạy qua {@link BackgroundTasks}; property chỉ đổi trên luồng
 * giao diện. Sản phẩm không còn (lúc nạp hoặc lúc thêm vào giỏ) thì báo "Không tìm thấy sản phẩm." và về
 * {@link ProductDetailRoute#back()} (AC-009-13); lỗi lạ đi vào {@link UnexpectedErrors} và màn giữ nội dung đang
 * có; nếu đó là lần nạp đầu thì {@link #loadFailedProperty()} bật và {@link #retry()} nạp lại (QĐ12). Sau
 * {@link #dispose} không callback nào đổi property hay điều hướng nữa.
 *
 * <p><b>Số lượng:</b> {@link #quantityTextProperty()} giữ chữ người dùng gõ, nguyên văn; {@link AddToCart} phân
 * tích và báo "Số lượng không hợp lệ." (AC-004-7). Presenter không tự kẹp hay sửa số.
 *
 * <p>Presenter chỉ import {@code javafx.beans}.
 */
@Component
@Scope("prototype")
class ProductDetailPresenter {

    static final String MSG_NOT_FOUND = "Không tìm thấy sản phẩm.";
    static final String STOCK_IN = "Còn hàng";
    static final String STOCK_OUT = "Hết hàng";
    static final String DEFAULT_QUANTITY = "1";

    private static final VndFormatter PRICE = new VndFormatter();

    private final ProductService productService;
    private final AddToCart addToCart;
    private final Navigator navigator;
    private final BackgroundTasks tasks;
    private final Toasts toasts;
    private final UnexpectedErrors errors;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loadFailed = new SimpleBooleanProperty(false);
    private final ObjectProperty<ProductDetailView> product = new SimpleObjectProperty<>();
    private final StringProperty priceText = new SimpleStringProperty("");
    private final StringProperty stockText = new SimpleStringProperty("");
    private final BooleanProperty inStock = new SimpleBooleanProperty(false);
    private final StringProperty quantityText = new SimpleStringProperty(DEFAULT_QUANTITY);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private ProductDetailRoute route;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    ProductDetailPresenter(ProductService productService, AddToCart addToCart, Navigator navigator,
                           BackgroundTasks tasks, Toasts toasts, UnexpectedErrors errors) {
        this.productService = productService;
        this.addToCart = addToCart;
        this.navigator = navigator;
        this.tasks = tasks;
        this.toasts = toasts;
        this.errors = errors;
    }

    /** Nhận route của màn và nạp sản phẩm ở nền. */
    void load(ProductDetailRoute newRoute) {
        this.route = Objects.requireNonNull(newRoute, "route");
        reload();
    }

    private void reload() {
        if (pending != null) {
            pending.cancel();
        }
        long id = route.productId();
        loading.set(true);
        pending = tasks.submit(() -> productService.getDetail(id), this::show, this::loadFailed);
    }

    private void show(ProductDetailView view) {
        priceText.set(PRICE.format(view.price()));
        stockText.set(view.inStock() ? STOCK_IN : STOCK_OUT);
        inStock.set(view.inStock());
        product.set(view);
        loadFailed.set(false);
        loading.set(false);
    }

    private void loadFailed(Throwable failure) {
        if (failure instanceof NotFoundException) {
            gone();
            return;
        }
        loading.set(false);
        if (product.get() == null) {
            loadFailed.set(true);   // lần nạp đầu lỗi: màn hiện khối lỗi thay vì để trống
        }
        errors.report(failure);
    }

    /**
     * "Thử lại" sau một lỗi lạ ở lần nạp đầu (QĐ12): tắt {@link #loadFailedProperty()} rồi nạp lại đúng route. Bỏ
     * qua khi đã {@link #dispose}, khi đang nạp hoặc khi chưa có lỗi.
     */
    void retry() {
        if (disposed || route == null || loading.get() || !loadFailed.get()) {
            return;
        }
        loadFailed.set(false);
        reload();
    }

    /** Sản phẩm không còn: báo rồi về danh sách (AC-009-13). */
    private void gone() {
        loading.set(false);
        toasts.show(MSG_NOT_FOUND, ToastKind.ERROR);
        back();
    }

    /**
     * "Thêm vào giỏ" với chữ đang có trong ô số lượng. {@link #busyProperty()} là {@code true} tới khi
     * {@link AddToCart} xong, nên bấm đúp không thêm hai lần. Thông báo về giỏ do {@link AddToCart} hiện; ở đây
     * chỉ phản ứng với kết quả: sản phẩm đã bị gỡ thì báo và về danh sách, hết hàng thì nạp lại để màn hiện
     * "Hết hàng" và bỏ ô số lượng.
     */
    void addToCart() {
        if (disposed || route == null || busy.get() || !inStock.get()) {
            return;
        }
        busy.set(true);
        addToCart.add(route.productId(), quantityText.get(), result -> {
            busy.set(false);
            if (disposed) {
                return;
            }
            switch (result) {
                case PRODUCT_GONE -> gone();
                case OUT_OF_STOCK -> reload();
                case ADDED, INVALID_QUANTITY, FAILED -> {
                    // Thông báo (nếu có) đã do AddToCart hiện; chỉ cần mở lại nút
                }
            }
        });
    }

    /** "Về danh sách sản phẩm": quay lại danh sách đã mở màn này, giữ bộ lọc, sắp xếp và trang. */
    void back() {
        if (disposed || route == null) {
            return;
        }
        navigator.navigateTo(route.back());
    }

    /** Hủy việc đang chờ: sau đó không callback nào của màn này chạy nữa. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        if (pending != null) {
            pending.cancel();
        }
    }

    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} khi lần nạp đầu của route này lỗi vì một lỗi lạ (màn hiện khối "Thử lại"). */
    ReadOnlyBooleanProperty loadFailedProperty() {
        return loadFailed;
    }

    /** Sản phẩm đang hiện; {@code null} tới lần nạp thành công đầu tiên. */
    ReadOnlyObjectProperty<ProductDetailView> productProperty() {
        return product;
    }

    /** Giá đã định dạng, ví dụ {@code 1.250.000 ₫}. */
    ReadOnlyStringProperty priceTextProperty() {
        return priceText;
    }

    /** "Còn hàng" hoặc "Hết hàng" (không có số tồn kho cụ thể, spec 002). */
    ReadOnlyStringProperty stockTextProperty() {
        return stockText;
    }

    ReadOnlyBooleanProperty inStockProperty() {
        return inStock;
    }

    /** Chữ trong ô số lượng, nguyên văn người dùng gõ; mặc định "1". */
    StringProperty quantityTextProperty() {
        return quantityText;
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }
}

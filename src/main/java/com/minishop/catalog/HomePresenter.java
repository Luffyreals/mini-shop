package com.minishop.catalog;

import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trạng thái và quyết định của trang chủ (QĐ5, QĐ11 của D3). Mỗi lần điều hướng tới {@link HomeRoute} có một
 * presenter mới ({@code prototype}); màn gọi {@link #load} một lần, và {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> {@link ProductService#home()} chạy qua {@link BackgroundTasks}; property chỉ đổi trên luồng
 * giao diện. Lỗi lạ đi vào {@link UnexpectedErrors} và màn giữ nội dung đang có. Sau {@link #dispose} không
 * callback nào đổi property hay điều hướng nữa.
 *
 * <p><b>Câu dẫn:</b> {@link #leadText(List)} nối tên danh mục theo thứ tự {@link HomeView} cung cấp (đã sắp theo
 * Collator tiếng Việt).
 *
 * <p>Presenter chỉ import {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class HomePresenter {

    static final String MSG_NOT_FOUND = "Không tìm thấy sản phẩm.";
    static final String PAYMENT_SENTENCE = "Trả tiền khi hàng tới tay.";

    private static final VndFormatter PRICE = new VndFormatter();

    private final ProductService productService;
    private final AddToCart addToCart;
    private final ShellPresenter shell;
    private final StorefrontPresenter storefront;
    private final Navigator navigator;
    private final BackgroundTasks tasks;
    private final Toasts toasts;
    private final UnexpectedErrors errors;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final ObjectProperty<ProductDetailView> featured = new SimpleObjectProperty<>();
    private final StringProperty featuredPriceText = new SimpleStringProperty("");
    private final ObservableList<ProductCardView> moreNew = FXCollections.observableArrayList();
    private final ObservableList<LowStockCardView> lowStock = FXCollections.observableArrayList();
    private final BooleanProperty hasLowStock = new SimpleBooleanProperty(false);
    private final StringProperty leadText = new SimpleStringProperty("");
    private final ObservableSet<Long> busyProducts = FXCollections.observableSet();
    private final IntegerProperty contentVersion = new SimpleIntegerProperty(0);

    private BackgroundTasks.Handle pending;
    private boolean disposed;

    HomePresenter(ProductService productService, AddToCart addToCart, ShellPresenter shell,
                  StorefrontPresenter storefront, Navigator navigator, BackgroundTasks tasks, Toasts toasts,
                  UnexpectedErrors errors) {
        this.productService = productService;
        this.addToCart = addToCart;
        this.shell = shell;
        this.storefront = storefront;
        this.navigator = navigator;
        this.tasks = tasks;
        this.toasts = toasts;
        this.errors = errors;
    }

    /** Nạp trang chủ ở nền. */
    void load() {
        reload();
    }

    private void reload() {
        if (pending != null) {
            pending.cancel();
        }
        loading.set(true);
        pending = tasks.submit(productService::home, this::show, this::failed);
    }

    private void show(HomeView view) {
        boolean firstLoad = !loaded.get();
        featured.set(view.featured());
        featuredPriceText.set(view.featured() == null ? "" : PRICE.format(view.featured().price()));
        moreNew.setAll(view.moreNew());
        lowStock.setAll(view.lowStock());
        hasLowStock.set(!view.lowStock().isEmpty());
        leadText.set(leadText(view.categories()));
        loaded.set(true);
        loading.set(false);
        if (firstLoad) {
            // Chỉ lần nạp đầu: lần nạp lại không được ghi đè chữ người dùng đang gõ
            shell.setSearchText("");
        }
        storefront.updateCategories(view.categories());
        // Cuối cùng: mọi property đã đúng, màn dựng lại một lần
        contentVersion.set(contentVersion.get() + 1);
    }

    private void failed(Throwable failure) {
        loading.set(false);
        errors.report(failure);
    }

    /**
     * Câu dẫn dưới "mini shop": tên danh mục nối bằng ", " và " và " trước tên cuối, rồi câu về thanh toán.
     * Một danh mục thì chỉ tên nó; không có danh mục thì chỉ câu về thanh toán.
     */
    static String leadText(List<CategoryOption> categories) {
        List<String> names = categories.stream()
                .map(CategoryOption::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        if (names.isEmpty()) {
            return PAYMENT_SENTENCE;
        }
        String joined;
        if (names.size() == 1) {
            joined = names.get(0);
        } else {
            joined = String.join(", ", names.subList(0, names.size() - 1)) + " và " + names.get(names.size() - 1);
        }
        return joined + ". " + PAYMENT_SENTENCE;
    }

    /**
     * "Thêm vào giỏ" (thẻ lớn) hoặc "+ Thêm" (thẻ nhỏ): thêm 1 sản phẩm. Sản phẩm bị khóa ({@link #busyProducts()})
     * tới khi việc xong, nên bấm đúp không thêm hai lần. Thông báo về giỏ do {@link AddToCart} hiện; ở đây chỉ phản
     * ứng với kết quả: sản phẩm đã bị gỡ thì báo và nạp lại, hết hàng thì nạp lại để thẻ hiện "Hết hàng".
     */
    void add(long productId) {
        if (disposed || !busyProducts.add(productId)) {
            return;
        }
        addToCart.add(productId, "1", result -> {
            busyProducts.remove(productId);
            if (disposed) {
                return;
            }
            switch (result) {
                case PRODUCT_GONE -> {
                    toasts.show(MSG_NOT_FOUND, ToastKind.ERROR);
                    reload();
                }
                case OUT_OF_STOCK -> reload();
                case ADDED, INVALID_QUANTITY, FAILED -> {
                    // Thông báo (nếu có) đã do AddToCart hiện; không còn gì để làm ở đây
                }
            }
        });
    }

    /** Mở chi tiết sản phẩm; nút "Về danh sách sản phẩm" ở đó về danh sách mọi sản phẩm. */
    void openDetail(long productId) {
        if (disposed) {
            return;
        }
        navigator.navigateTo(new ProductDetailRoute(productId, CatalogRoute.all()));
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

    /** {@code true} từ lần nạp thành công đầu tiên. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** Sản phẩm mới nhất; {@code null} khi chưa có sản phẩm nào đang bán (hoặc chưa nạp xong). */
    ReadOnlyObjectProperty<ProductDetailView> featuredProperty() {
        return featured;
    }

    /** Giá của sản phẩm nổi bật đã định dạng, rỗng khi không có. */
    ReadOnlyStringProperty featuredPriceTextProperty() {
        return featuredPriceText;
    }

    /** Tối đa 2 sản phẩm mới kế tiếp. */
    ObservableList<ProductCardView> moreNew() {
        return moreNew;
    }

    /** Tối đa 3 sản phẩm còn 1 đến 5 cái, ít nhất trước. */
    ObservableList<LowStockCardView> lowStock() {
        return lowStock;
    }

    ReadOnlyBooleanProperty hasLowStockProperty() {
        return hasLowStock;
    }

    ReadOnlyStringProperty leadTextProperty() {
        return leadText;
    }

    /** Id sản phẩm đang được thêm vào giỏ; nút thêm của chúng bị khóa. */
    ObservableSet<Long> busyProducts() {
        return busyProducts;
    }

    /**
     * Tăng một lần sau mỗi lần nạp thành công, khi mọi property khác đã đúng; màn nghe nó để dựng lại nội dung.
     */
    ReadOnlyIntegerProperty contentVersionProperty() {
        return contentVersion;
    }
}

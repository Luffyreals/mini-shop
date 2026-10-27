package com.minishop.catalog;

import com.minishop.common.PageNav;
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

import java.util.Objects;

/**
 * Trạng thái và quyết định của màn danh sách sản phẩm (QĐ3, QĐ4, QĐ11 của D3). Mỗi lần điều hướng tới một
 * {@link CatalogRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần, và {@link #dispose}
 * khi bị thay.
 *
 * <p><b>Route là nguồn sự thật:</b> {@link #selectCategory}, {@link #selectSort}, {@link #goToPage} và
 * {@link #clearFilters} không đổi màn tại chỗ mà điều hướng tới route mới (đổi bộ lọc thì về trang 1, đổi trang
 * thì giữ bộ lọc). Route mới trùng route đang hiện thì không làm gì; vì vậy chọn lại danh mục hay cách sắp xếp
 * đang có ở trang 1 không đổi gì, còn ở trang sau thì về trang 1.
 *
 * <p><b>Luồng:</b> {@code ProductService.search} chạy qua {@link BackgroundTasks}; property chỉ đổi trên luồng
 * giao diện. Lỗi lạ đi vào {@link UnexpectedErrors} và màn giữ nội dung đang có; nếu đó là lần nạp đầu thì
 * {@link #loadFailedProperty()} bật và {@link #retry()} nạp lại (QĐ12). Sau {@link #dispose} không callback nào đổi
 * property hay điều hướng nữa.
 *
 * <p>Presenter chỉ import {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class CatalogPresenter {

    static final String MSG_NOT_FOUND = "Không tìm thấy sản phẩm.";

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
    private final BooleanProperty loadFailed = new SimpleBooleanProperty(false);
    private final ObservableList<ProductCardView> cards = FXCollections.observableArrayList();
    private final StringProperty resultText = new SimpleStringProperty("");
    private final BooleanProperty empty = new SimpleBooleanProperty(false);
    private final ObservableList<CategoryOption> categories = FXCollections.observableArrayList();
    private final ObjectProperty<Long> selectedCategoryId = new SimpleObjectProperty<>();
    private final StringProperty sortKey = new SimpleStringProperty(ProductSort.NEWEST.key());
    private final ObjectProperty<PageNav> pageNav = new SimpleObjectProperty<>();
    private final ObservableSet<Long> busyProducts = FXCollections.observableSet();
    private final IntegerProperty contentVersion = new SimpleIntegerProperty(0);

    private CatalogRoute route = CatalogRoute.all();
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    CatalogPresenter(ProductService productService, AddToCart addToCart, ShellPresenter shell,
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

    /** Nhận route của màn: đặt danh mục và cách sắp xếp đang chọn ngay, rồi nạp danh sách ở nền. */
    void load(CatalogRoute newRoute) {
        this.route = Objects.requireNonNull(newRoute, "route");
        selectedCategoryId.set(route.categoryId());
        sortKey.set(route.sortKey());
        reload();
    }

    /** Route của màn này (để màn so với route trước khi dựng lại, và để quay về từ màn chi tiết). */
    CatalogRoute route() {
        return route;
    }

    private void reload() {
        if (pending != null) {
            pending.cancel();
        }
        ProductSearchParams params = new ProductSearchParams(route.query(),
                route.categoryId() == null ? null : String.valueOf(route.categoryId()),
                route.sortKey(), String.valueOf(route.page()));
        loading.set(true);
        pending = tasks.submit(() -> productService.search(params), this::show, this::failed);
    }

    private void show(ProductListView view) {
        boolean firstLoad = !loaded.get();
        cards.setAll(view.products());
        categories.setAll(view.categories());
        resultText.set("Tìm thấy " + view.totalElements() + " sản phẩm");
        empty.set(view.products().isEmpty());
        pageNav.set(PageNav.of(view.filter().page(), view.totalPages()));
        loadFailed.set(false);
        loaded.set(true);
        loading.set(false);
        if (firstLoad) {
            // Chỉ lần nạp đầu: lần nạp lại (vd. sau "Hết hàng") không được ghi đè chữ người dùng đang gõ
            shell.setSearchText(view.filter().q());
        }
        storefront.updateCategories(view.categories());
        // Cuối cùng: mọi property đã đúng, màn dựng lại một lần
        contentVersion.set(contentVersion.get() + 1);
    }

    private void failed(Throwable failure) {
        loading.set(false);
        if (!loaded.get()) {
            loadFailed.set(true);   // lần nạp đầu lỗi: màn hiện khối lỗi thay vì để trống
        }
        errors.report(failure);
    }

    /**
     * "Thử lại" sau một lỗi lạ ở lần nạp đầu (QĐ12): tắt {@link #loadFailedProperty()} rồi nạp lại đúng route. Bỏ
     * qua khi đã {@link #dispose}, khi đang nạp hoặc khi chưa có lỗi.
     */
    void retry() {
        if (disposed || loading.get() || !loadFailed.get()) {
            return;
        }
        loadFailed.set(false);
        reload();
    }

    /** Chọn một danh mục ({@code null} là mọi danh mục). */
    void selectCategory(Long categoryId) {
        navigateIfChanged(route.withCategory(categoryId));
    }

    /** Chọn cách sắp xếp ({@code newest}, {@code price_asc}, {@code price_desc}). */
    void selectSort(String key) {
        navigateIfChanged(route.withSort(key));
    }

    void goToPage(int page) {
        navigateIfChanged(route.withPage(Math.max(page, 1)));
    }

    /** "Xóa bộ lọc": về danh sách không lọc (luôn điều hướng, để dựng lại màn). */
    void clearFilters() {
        navigator.navigateTo(CatalogRoute.all());
    }

    /** Mở chi tiết sản phẩm; nút "Về danh sách sản phẩm" ở đó quay lại đúng route này. */
    void openDetail(long productId) {
        navigator.navigateTo(new ProductDetailRoute(productId, route));
    }

    private void navigateIfChanged(CatalogRoute target) {
        if (!target.equals(route)) {
            navigator.navigateTo(target);
        }
    }

    /**
     * "+ Thêm" trên một thẻ: thêm 1 sản phẩm vào giỏ. Sản phẩm bị khóa ({@link #busyProducts()}) tới khi việc xong,
     * nên bấm đúp không thêm hai lần. Thông báo về giỏ do {@link AddToCart} hiện; ở đây chỉ phản ứng với kết quả:
     * sản phẩm đã bị gỡ thì báo và nạp lại, hết hàng thì nạp lại để thẻ hiện "Hết hàng".
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

    /** {@code true} từ lần nạp thành công đầu tiên; màn dùng nó để biết đã có gì để hiện chưa. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /**
     * Tăng một lần sau mỗi lần nạp thành công, khi mọi property khác đã đúng. Màn nghe nó để dựng lại thẻ và pill
     * (các property khác đổi lần lượt, và nạp lại cùng kết quả không đổi property nào).
     */
    ReadOnlyIntegerProperty contentVersionProperty() {
        return contentVersion;
    }

    ObservableList<ProductCardView> cards() {
        return cards;
    }

    ReadOnlyStringProperty resultTextProperty() {
        return resultText;
    }

    /** {@code true} khi lần nạp gần nhất không có sản phẩm nào (kể cả trang vượt cuối). */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty;
    }

    ObservableList<CategoryOption> categories() {
        return categories;
    }

    /** Id danh mục đang chọn, {@code null} là mọi danh mục; đặt ngay khi {@link #load}, trước khi có dữ liệu. */
    ReadOnlyObjectProperty<Long> selectedCategoryIdProperty() {
        return selectedCategoryId;
    }

    ReadOnlyStringProperty sortKeyProperty() {
        return sortKey;
    }

    ReadOnlyObjectProperty<PageNav> pageNavProperty() {
        return pageNav;
    }

    /** Id sản phẩm đang được thêm vào giỏ; nút "+ Thêm" của chúng bị khóa. */
    ObservableSet<Long> busyProducts() {
        return busyProducts;
    }
}

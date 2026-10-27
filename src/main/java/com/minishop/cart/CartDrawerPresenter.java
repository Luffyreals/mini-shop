package com.minishop.cart;

import com.minishop.catalog.CatalogRoute;
import com.minishop.catalog.ProductDetailRoute;
import com.minishop.common.NotFoundException;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DrawerHost;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Trạng thái và quyết định của drawer giỏ hàng (QĐ1, QĐ6, QĐ7 của D4). Mỗi lần mở drawer có một presenter mới
 * ({@code prototype}); {@link CartDrawer.Opener} gọi {@link #load} một lần và {@link #dispose} khi drawer đóng.
 *
 * <p><b>Luồng:</b> mọi lời gọi {@link CartService} chạy qua {@link BackgroundTasks}; property chỉ đổi trên luồng
 * giao diện. Chủ giỏ ({@link CurrentCartOwner#get()}) được đọc <b>trên luồng giao diện, ngay trước khi gửi việc</b>
 * (AC-004-18, BR-009-4). Một lần ghi (sửa, xóa) gồm hai bước: ghi, rồi nạp lại {@link CartService#view} cho cùng chủ
 * giỏ.
 *
 * <p><b>Khóa dòng:</b> trong lúc một dòng đang ghi, id của nó nằm trong {@link #writingIds()} tới khi lần nạp lại
 * sau đó xong, nên màn khóa mọi điều khiển của dòng đó và bấm + nhanh nhiều lần không gửi chồng. Mỗi lần nạp có một
 * số thế hệ; chỉ kết quả của lần nạp mới nhất được áp dụng (lần nạp cũ hơn bị bỏ), và khi áp dụng thì mọi dòng đã
 * gửi nạp lại ở thế hệ không mới hơn đều được mở khóa. Nhờ vậy hai dòng ghi gần nhau không dòng nào bị khóa mãi.
 *
 * <p><b>Sau {@link #dispose}:</b> không property nào đổi và không nạp lại nữa. Việc ghi đã gửi thì vẫn chạy tới cùng
 * (hủy callback không hủy transaction), nên khi nó xong vẫn hiện toast kết quả và làm mới số trên biểu tượng giỏ.
 *
 * <p>Presenter chỉ import {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class CartDrawerPresenter {

    static final int MAX_QUANTITY = CartService.MAX_LINE_QUANTITY;
    static final String MSG_UPDATED = "Đã cập nhật số lượng.";
    static final String MSG_REMOVED = "Đã xóa sản phẩm khỏi giỏ hàng.";
    static final String MSG_OUT_OF_STOCK = AddToCartPresenter.MSG_OUT_OF_STOCK;
    static final String MSG_INVALID_QUANTITY = AddToCartPresenter.MSG_INVALID_QUANTITY;
    static final String MSG_MAX = AddToCartPresenter.MSG_MAX;
    static final String MSG_EMPTY = "Giỏ hàng của bạn đang trống.";
    static final String MSG_NOT_ORDERABLE =
            "Vui lòng xóa hoặc giảm số lượng các sản phẩm không đủ hàng trước khi đặt hàng.";
    static final String LABEL_DISCONTINUED = "Sản phẩm đã ngừng bán";
    static final String LABEL_OUT_OF_STOCK = "Hết hàng";
    /** Thành tiền của dòng không mua được. */
    static final String NO_LINE_TOTAL = "—";

    private static final VndFormatter PRICE = new VndFormatter();

    private final CartService cartService;
    private final CurrentCartOwner currentOwner;
    private final BackgroundTasks tasks;
    private final Toasts toasts;
    private final CartBadge badge;
    private final UnexpectedErrors errors;
    private final Navigator navigator;
    private final DrawerHost drawerHost;
    private final CheckoutEntry checkoutEntry;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final ObservableList<CartLineView> lines = FXCollections.observableArrayList();
    private final StringProperty totalText = new SimpleStringProperty(PRICE.format(BigDecimal.ZERO));
    private final BooleanProperty empty = new SimpleBooleanProperty(false);
    private final BooleanProperty orderable = new SimpleBooleanProperty(false);
    private final BooleanProperty showNotOrderableNotice = new SimpleBooleanProperty(false);
    private final ObservableSet<Long> writingIds = FXCollections.observableSet();

    /** Dòng đã ghi xong và đang chờ lần nạp lại: id → thế hệ của lần nạp đó. */
    private final Map<Long, Long> awaitingReload = new HashMap<>();
    private final List<BackgroundTasks.Handle> reloads = new ArrayList<>();
    private long latestGeneration;
    private boolean disposed;

    CartDrawerPresenter(CartService cartService, CurrentCartOwner currentOwner, BackgroundTasks tasks, Toasts toasts,
                        CartBadge badge, UnexpectedErrors errors, Navigator navigator, DrawerHost drawerHost,
                        CheckoutEntry checkoutEntry) {
        this.cartService = cartService;
        this.currentOwner = currentOwner;
        this.tasks = tasks;
        this.toasts = toasts;
        this.badge = badge;
        this.errors = errors;
        this.navigator = navigator;
        this.drawerHost = drawerHost;
        this.checkoutEntry = checkoutEntry;
    }

    // ------------------------------------------------------------------ nạp

    /** Nạp giỏ của chủ giỏ hiện tại ở nền. Gọi một lần khi drawer mở. */
    void load() {
        if (disposed) {
            return;
        }
        reload(currentOwner.get(), null);
    }

    /**
     * Gửi việc nạp {@code owner}. {@code writtenId} (nếu có) là dòng vừa ghi xong: nó được mở khóa khi lần nạp này
     * (hoặc lần nạp mới hơn) được áp dụng. Đăng ký thế hệ <em>trước</em> khi gửi việc, vì bản chạy đồng bộ của
     * {@link BackgroundTasks} gọi callback ngay trong {@code submit}.
     */
    private void reload(CartOwner owner, Long writtenId) {
        long generation = ++latestGeneration;
        if (writtenId != null) {
            awaitingReload.put(writtenId, generation);
        }
        loading.set(true);
        BackgroundTasks.Handle handle = tasks.submit(
                () -> cartService.view(owner),
                view -> viewLoaded(generation, view),
                failure -> viewFailed(generation, failure));
        if (handle != null) {
            reloads.add(handle);
        }
    }

    private void viewLoaded(long generation, CartView view) {
        if (disposed || generation != latestGeneration) {
            return;
        }
        lines.setAll(view.lines());
        totalText.set(PRICE.format(view.total()));
        empty.set(view.isEmpty());
        orderable.set(view.isOrderable());
        showNotOrderableNotice.set(!view.isEmpty() && !view.isOrderable());
        loaded.set(true);
        loading.set(false);
        unlockUpTo(generation);
    }

    private void viewFailed(long generation, Throwable failure) {
        if (disposed || generation != latestGeneration) {
            return;
        }
        loading.set(false);
        unlockUpTo(generation);
        errors.report(failure);
    }

    private void unlockUpTo(long generation) {
        awaitingReload.entrySet().removeIf(entry -> {
            if (entry.getValue() <= generation) {
                writingIds.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ sửa và xóa

    /** Nút +: tăng một. Không làm gì khi đã là 99 (service cũng từ chối số ngoài 1..99) hoặc dòng đang ghi. */
    void increase(long productId) {
        CartLineView line = find(productId);
        if (line == null || line.quantity() >= MAX_QUANTITY) {
            return;
        }
        update(productId, line.quantity() + 1);
    }

    /** Nút −: giảm một. Không làm gì khi đã là 1; muốn bỏ dòng thì dùng {@link #remove}. */
    void decrease(long productId) {
        CartLineView line = find(productId);
        if (line == null || line.quantity() <= 1) {
            return;
        }
        update(productId, line.quantity() - 1);
    }

    /**
     * Enter trong ô số lượng, với <em>chữ thô</em> trong ô. Chữ sai ({@link CartInput#quantity}) thì hiện "Số lượng
     * không hợp lệ." và không gọi service (AC-004-7).
     *
     * @return {@code true} nếu đã gửi việc ghi; {@code false} nếu chữ sai hoặc dòng đang ghi (màn trả ô về số
     *         hiện tại)
     */
    boolean submitQuantity(long productId, String rawQuantity) {
        if (disposed || writingIds.contains(productId)) {
            return false;
        }
        OptionalInt quantity = CartInput.quantity(rawQuantity);
        if (quantity.isEmpty()) {
            toasts.show(MSG_INVALID_QUANTITY, ToastKind.ERROR);
            return false;
        }
        update(productId, quantity.getAsInt());
        return true;
    }

    private void update(long productId, int quantity) {
        if (disposed || !writingIds.add(productId)) {
            return;
        }
        CartOwner owner = currentOwner.get();
        tasks.submit(
                () -> cartService.update(owner, productId, quantity),
                change -> {
                    show(change);
                    badge.refresh();
                    afterWrite(owner, productId);
                },
                failure -> {
                    if (failure instanceof NotFoundException) {
                        // Sản phẩm vừa bị ẩn: không toast, nạp lại để dòng thành "Sản phẩm đã ngừng bán"
                        badge.refresh();
                        afterWrite(owner, productId);
                    } else {
                        failWrite(productId, failure);
                    }
                });
    }

    /** Toast của kết quả sửa (bảng QĐ6). */
    private void show(CartChange change) {
        switch (change.outcome()) {
            case OK -> toasts.show(MSG_UPDATED, ToastKind.SUCCESS);
            case CAPPED_AT_STOCK -> toasts.show(AddToCartPresenter.stockMessage(change.quantity()), ToastKind.INFO);
            case CAPPED_AT_MAX -> toasts.show(MSG_MAX, ToastKind.INFO);
            case OUT_OF_STOCK -> toasts.show(MSG_OUT_OF_STOCK, ToastKind.ERROR);
            case NOT_IN_CART -> {
                // Dòng đã mất (ví dụ xóa ở nơi khác): không báo, nạp lại là dòng biến mất
            }
        }
    }

    /** "Xóa": idempotent ở service, nên xóa dòng đã mất cũng không lỗi (AC-004-12). */
    void remove(long productId) {
        if (disposed || !writingIds.add(productId)) {
            return;
        }
        CartOwner owner = currentOwner.get();
        tasks.submit(
                () -> {
                    cartService.remove(owner, productId);
                    return Boolean.TRUE;
                },
                done -> {
                    toasts.show(MSG_REMOVED, ToastKind.SUCCESS);
                    badge.refresh();
                    afterWrite(owner, productId);
                },
                failure -> failWrite(productId, failure));
    }

    /** Ghi xong: nạp lại cho cùng chủ giỏ (một việc nền không bao giờ trộn hai phiên). Đã đóng thì thôi. */
    private void afterWrite(CartOwner owner, long productId) {
        if (disposed) {
            return;
        }
        reload(owner, productId);
    }

    private void failWrite(long productId, Throwable failure) {
        if (!disposed) {
            writingIds.remove(productId);
        }
        errors.report(failure);
    }

    // ------------------------------------------------------------------ điều hướng

    /**
     * "Đặt hàng": đóng drawer rồi giao cho {@link CheckoutEntry} (đòi đăng nhập nếu là khách, QĐ7). Không làm gì
     * khi còn dòng đang ghi ({@link #writingIds()}): màn Thanh toán có thể đọc giỏ cũ rồi báo sai "Giá sản phẩm vừa
     * thay đổi…". Đóng drawer bằng Esc giữa lúc ghi thì không chặn được: màn Thanh toán có thể hiện tóm tắt cũ, và lần
     * xác nhận kế tiếp tự sửa (nhận tổng mới từ {@code PriceChanged}).
     */
    void checkout() {
        if (disposed || !orderable.get() || !writingIds.isEmpty()) {
            return;
        }
        drawerHost.close();
        checkoutEntry.open();
    }

    /** "Tiếp tục mua sắm" (giỏ trống): đóng drawer rồi mở danh sách "Hàng mới". */
    void continueShopping() {
        if (disposed) {
            return;
        }
        drawerHost.close();
        navigator.navigateTo(CatalogRoute.all());
    }

    /** Bấm tên sản phẩm: đóng drawer rồi mở chi tiết. Dòng đã ngừng bán không có chi tiết nên không làm gì. */
    void openProduct(long productId) {
        CartLineView line = find(productId);
        if (disposed || line == null || line.isDiscontinued()) {
            return;
        }
        drawerHost.close();
        navigator.navigateTo(new ProductDetailRoute(productId, CatalogRoute.all()));
    }

    /** Nút "Đóng". Esc và bấm ra ngoài do {@link DrawerHost} xử lý. */
    void close() {
        drawerHost.close();
    }

    /** Hủy việc nạp đang chờ; sau đó không property nào đổi nữa. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        reloads.forEach(BackgroundTasks.Handle::cancel);
        reloads.clear();
    }

    // ------------------------------------------------------------------ chữ hiển thị

    /** Nhãn của dòng: "Sản phẩm đã ngừng bán" / "Hết hàng" / "Chỉ còn {n} sản phẩm"; {@code null} khi bình thường. */
    String labelFor(CartLineView line) {
        return switch (line.status()) {
            case DISCONTINUED -> LABEL_DISCONTINUED;
            case OUT_OF_STOCK -> LABEL_OUT_OF_STOCK;
            case LOW_STOCK -> "Chỉ còn " + line.availableStock() + " sản phẩm";
            case AVAILABLE -> null;
        };
    }

    /** Hậu tố class của nhãn ({@code line-label-gone|out|low}); {@code null} khi dòng không có nhãn. */
    String labelStyleFor(CartLineView line) {
        return switch (line.status()) {
            case DISCONTINUED -> "gone";
            case OUT_OF_STOCK -> "out";
            case LOW_STOCK -> "low";
            case AVAILABLE -> null;
        };
    }

    String unitPriceText(CartLineView line) {
        return PRICE.format(line.unitPrice());
    }

    /** Thành tiền của dòng, hoặc "—" khi dòng không mua được. */
    String lineTotalText(CartLineView line) {
        return line.lineTotal() == null ? NO_LINE_TOTAL : PRICE.format(line.lineTotal());
    }

    private CartLineView find(long productId) {
        for (CartLineView line : lines) {
            if (line.productId() == productId) {
                return line;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ property

    /** {@code true} trong lúc đang nạp (lần đầu hoặc sau một lần ghi). */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lần nạp thành công đầu tiên; trước đó màn chưa biết giỏ trống hay không. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** Các dòng đang hiện (thay cả danh sách sau mỗi lần nạp). */
    ObservableList<CartLineView> lines() {
        return lines;
    }

    /** Tổng cộng đã định dạng ({@code 2.649.000 ₫}); chỉ cộng các dòng mua được. */
    ReadOnlyStringProperty totalTextProperty() {
        return totalText;
    }

    /** Giỏ trống (chỉ có nghĩa sau khi {@link #loadedProperty()} là {@code true}). */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty;
    }

    /** Có thể đặt hàng: không trống và mọi dòng đặt được (AC-005-1). */
    ReadOnlyBooleanProperty orderableProperty() {
        return orderable;
    }

    /** Hiện câu "Vui lòng xóa hoặc giảm số lượng…": giỏ không trống nhưng không đặt được. */
    ReadOnlyBooleanProperty showNotOrderableNoticeProperty() {
        return showNotOrderableNotice;
    }

    /** Id các dòng đang ghi; màn khóa điều khiển của những dòng này. Chỉ đọc trên luồng giao diện. */
    ObservableSet<Long> writingIds() {
        return writingIds;
    }
}

package com.minishop.cart;

import com.minishop.catalog.AddToCart;
import com.minishop.catalog.AddToCartResult;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * "Thêm vào giỏ" cho các màn hình cửa hàng (QĐ2 của D3). {@code catalog} định nghĩa {@link AddToCart}, lớp này cài
 * đặt nó: cùng kiểu đảo phụ thuộc với {@code user.LoginHook}, nên {@code catalog} không phụ thuộc {@code cart}.
 *
 * <p><b>Luồng:</b> {@link #add} chạy trên luồng giao diện. Số lượng được phân tích trước; sai thì báo ngay và không
 * gọi service. Chủ giỏ được đọc <b>trên luồng giao diện, trước khi gửi việc</b> (BR-009-4), việc nền chỉ gọi
 * {@link CartService#add} rồi callback (trên luồng giao diện) hiện thông báo, làm mới số trên biểu tượng giỏ và gọi
 * {@code onFinished} đúng một lần.
 *
 * <p>Không import {@code javafx}: toast đi qua {@link Toasts}, luồng qua {@link BackgroundTasks}.
 */
@Component
class AddToCartPresenter implements AddToCart {

    static final String MSG_ADDED = "Đã thêm vào giỏ hàng.";
    static final String MSG_OUT_OF_STOCK = "Sản phẩm này đã hết hàng.";
    static final String MSG_INVALID_QUANTITY = "Số lượng không hợp lệ.";
    static final String MSG_MAX = "Số lượng tối đa cho mỗi sản phẩm là " + CartService.MAX_LINE_QUANTITY + ".";

    private final CartService cartService;
    private final CurrentCartOwner currentOwner;
    private final BackgroundTasks tasks;
    private final Toasts toasts;
    private final CartBadgePresenter badge;
    private final UnexpectedErrors errors;

    AddToCartPresenter(CartService cartService, CurrentCartOwner currentOwner, BackgroundTasks tasks, Toasts toasts,
                       CartBadgePresenter badge, UnexpectedErrors errors) {
        this.cartService = cartService;
        this.currentOwner = currentOwner;
        this.tasks = tasks;
        this.toasts = toasts;
        this.badge = badge;
        this.errors = errors;
    }

    @Override
    public void add(long productId, String rawQuantity, Consumer<AddToCartResult> onFinished) {
        Objects.requireNonNull(onFinished, "onFinished");
        OptionalInt quantity = CartInput.quantity(rawQuantity);
        if (quantity.isEmpty()) {
            toasts.show(MSG_INVALID_QUANTITY, ToastKind.ERROR);
            onFinished.accept(AddToCartResult.INVALID_QUANTITY);
            return;
        }
        CartOwner owner = currentOwner.get();
        int wanted = quantity.getAsInt();
        tasks.submit(
                () -> cartService.add(owner, productId, wanted),
                change -> {
                    AddToCartResult result = show(change);
                    badge.refresh();
                    onFinished.accept(result);
                },
                failure -> {
                    AddToCartResult result;
                    if (failure instanceof NotFoundException) {
                        result = AddToCartResult.PRODUCT_GONE;
                    } else {
                        errors.report(failure);
                        result = AddToCartResult.FAILED;
                    }
                    badge.refresh();
                    onFinished.accept(result);
                });
    }

    /** Hiện toast của kết quả và trả về kết quả cho màn hình. */
    private AddToCartResult show(CartChange change) {
        switch (change.outcome()) {
            case OK -> toasts.show(MSG_ADDED, ToastKind.SUCCESS);
            case CAPPED_AT_STOCK -> toasts.show(stockMessage(change.quantity()), ToastKind.INFO);
            case CAPPED_AT_MAX -> toasts.show(MSG_MAX, ToastKind.INFO);
            case OUT_OF_STOCK -> {
                toasts.show(MSG_OUT_OF_STOCK, ToastKind.ERROR);
                return AddToCartResult.OUT_OF_STOCK;
            }
            case NOT_IN_CART -> {
                // Không thể xảy ra khi thêm; coi như đã xong, không hiện gì.
            }
        }
        return AddToCartResult.ADDED;
    }

    private static String stockMessage(int stock) {
        return "Chỉ còn " + stock + " sản phẩm trong kho.";
    }
}

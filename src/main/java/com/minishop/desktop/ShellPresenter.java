package com.minishop.desktop;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của thanh trên: số hiện ở badge giỏ, nhãn nút tài khoản, nút nào bấm được.
 * Không import {@code javafx.scene}; {@link MainWindow} chỉ gắn node vào các property này.
 * <p>
 * Thanh trên không biết tính năng nào (QĐ4). Mỗi tính năng gắn hành động của mình bằng
 * {@link #setAction(ShellAction, Runnable)} khi nó có màn; nút chưa có hành động thì bị khóa
 * (QĐ15), nên không có nút bấm mà không xảy ra gì. Các property chỉ được đổi trên luồng giao diện.
 */
@Component
public class ShellPresenter {

    /** Các nút bấm của thanh trên (ô tìm kiếm riêng, xem {@link #setSearchHandler}). */
    public enum ShellAction {
        STORE, NEW_ARRIVALS, CATEGORIES, CART, ACCOUNT
    }

    static final String GUEST_ACCOUNT_LABEL = "Đăng nhập";
    static final int MAX_BADGE = 99;

    private final IntegerProperty cartCount = new SimpleIntegerProperty(0);
    private final StringProperty accountLabel = new SimpleStringProperty(GUEST_ACCOUNT_LABEL);
    private final StringBinding cartBadgeText = Bindings.createStringBinding(
            () -> badgeText(cartCount.get()), cartCount);
    private final StringBinding cartAccessibleText = Bindings.createStringBinding(
            () -> "Giỏ hàng, " + cartCount.get() + " sản phẩm", cartCount);

    private final Map<ShellAction, ObjectProperty<Runnable>> handlers = new EnumMap<>(ShellAction.class);
    private final Map<ShellAction, BooleanBinding> enabled = new EnumMap<>(ShellAction.class);
    private final ObjectProperty<Consumer<String>> searchHandler = new SimpleObjectProperty<>();
    private final BooleanBinding searchEnabled = searchHandler.isNotNull();

    public ShellPresenter() {
        for (ShellAction action : ShellAction.values()) {
            ObjectProperty<Runnable> handler = new SimpleObjectProperty<>();
            handlers.put(action, handler);
            enabled.put(action, handler.isNotNull());
        }
    }

    /** Số hiện trên badge giỏ: số thật, hoặc {@code 99+} khi hơn 99. */
    static String badgeText(int count) {
        return count > MAX_BADGE ? MAX_BADGE + "+" : String.valueOf(Math.max(count, 0));
    }

    public IntegerProperty cartCountProperty() {
        return cartCount;
    }

    /** Đặt số sản phẩm trong giỏ; số âm tính là 0. */
    public void setCartCount(int count) {
        cartCount.set(Math.max(count, 0));
    }

    public StringBinding cartBadgeTextBinding() {
        return cartBadgeText;
    }

    public StringBinding cartAccessibleTextBinding() {
        return cartAccessibleText;
    }

    public StringProperty accountLabelProperty() {
        return accountLabel;
    }

    /** Đặt nhãn nút tài khoản; rỗng thì quay về "Đăng nhập". */
    public void setAccountLabel(String label) {
        accountLabel.set(label == null || label.isBlank() ? GUEST_ACCOUNT_LABEL : label);
    }

    /** Gắn (hoặc gỡ, khi {@code null}) hành động của một nút. Có hành động thì nút được bật. */
    public void setAction(ShellAction action, Runnable handler) {
        handlers.get(action).set(handler);
    }

    public BooleanBinding enabledBinding(ShellAction action) {
        return enabled.get(action);
    }

    /** Chạy hành động của nút; không làm gì nếu chưa gắn. */
    public void trigger(ShellAction action) {
        Runnable handler = handlers.get(action).get();
        if (handler != null) {
            handler.run();
        }
    }

    /** Gắn (hoặc gỡ) hàm nhận từ khóa khi người dùng nhấn Enter trong ô tìm kiếm. */
    public void setSearchHandler(Consumer<String> handler) {
        searchHandler.set(handler);
    }

    public BooleanBinding searchEnabledBinding() {
        return searchEnabled;
    }

    /** Gửi từ khóa cho hàm đã gắn; không làm gì nếu chưa gắn. */
    public void submitSearch(String text) {
        Consumer<String> handler = searchHandler.get();
        if (handler != null) {
            handler.accept(text == null ? "" : text);
        }
    }
}

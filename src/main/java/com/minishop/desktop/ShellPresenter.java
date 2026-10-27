package com.minishop.desktop;

import java.util.EnumMap;
import java.util.List;
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
import javafx.beans.value.ObservableStringValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của thanh trên: số hiện ở badge giỏ, nhãn nút tài khoản, nút nào bấm được.
 * Không import {@code javafx.scene}; {@link MainWindow} chỉ gắn node vào các property này.
 * <p>
 * Thanh trên không biết tính năng nào (QĐ4). Mỗi tính năng gắn hành động của mình bằng
 * {@link #setAction(ShellAction, Runnable)} khi nó có màn; nút chưa có hành động thì bị khóa
 * (QĐ15), nên không có nút bấm mà không xảy ra gì. Các property chỉ được đổi trên luồng giao diện.
 * <p>
 * Nút tài khoản có thể có thêm menu ({@link #accountMenuEntries()}): khi đã có mục thì bấm nút mở menu, khi
 * menu rỗng thì bấm nút chạy hành động {@link ShellAction#ACCOUNT}. Nút được bật khi có hành động
 * <b>hoặc</b> menu có mục. Nút "Danh mục" làm giống vậy với {@link #categoriesMenuEntries()}. Tính năng gắn
 * những thứ này trong {@link ShellContributor#contribute}.
 * <p>
 * Chữ trong ô tìm kiếm là {@link #searchTextProperty()}: màn danh sách đặt lại nó theo từ khóa đã chuẩn hóa.
 */
@Component
public class ShellPresenter {

    /** Các nút bấm của thanh trên (ô tìm kiếm riêng, xem {@link #setSearchHandler}). */
    public enum ShellAction {
        STORE, NEW_ARRIVALS, CATEGORIES, CART, ACCOUNT
    }

    static final String GUEST_ACCOUNT_LABEL = "Đăng nhập";
    static final int MAX_BADGE = 99;
    /** Nhãn nút tài khoản dài hơn mức này thì bị cắt và thêm "…", để thanh trên không vỡ ở cửa sổ 1024 px. */
    static final int MAX_ACCOUNT_LABEL = 24;

    private final IntegerProperty cartCount = new SimpleIntegerProperty(0);
    private final StringProperty accountLabel = new SimpleStringProperty(GUEST_ACCOUNT_LABEL);
    private final StringProperty accountAccessibleText = new SimpleStringProperty();
    private final StringBinding accountEffectiveAccessibleText = Bindings.createStringBinding(
            () -> {
                String text = accountAccessibleText.get();
                return text == null || text.isBlank() ? accountLabel.get() : text;
            }, accountAccessibleText, accountLabel);
    private final ObservableList<ShellMenuEntry> accountMenu = FXCollections.observableArrayList();
    private final ObservableList<ShellMenuEntry> categoriesMenu = FXCollections.observableArrayList();
    private final StringProperty searchText = new SimpleStringProperty("");
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
        enabled.put(ShellAction.ACCOUNT,
                handlers.get(ShellAction.ACCOUNT).isNotNull().or(Bindings.isNotEmpty(accountMenu)));
        enabled.put(ShellAction.CATEGORIES,
                handlers.get(ShellAction.CATEGORIES).isNotNull().or(Bindings.isNotEmpty(categoriesMenu)));
    }

    /** Cắt nhãn dài (theo code point) và thêm "…"; nhãn ngắn giữ nguyên. */
    static String shortenLabel(String label) {
        if (label.codePointCount(0, label.length()) <= MAX_ACCOUNT_LABEL) {
            return label;
        }
        int end = label.offsetByCodePoints(0, MAX_ACCOUNT_LABEL - 1);
        return label.substring(0, end).stripTrailing() + "…";
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

    /**
     * Đặt nhãn nút tài khoản; rỗng thì quay về "Đăng nhập"; dài hơn {@value #MAX_ACCOUNT_LABEL} ký tự thì bị
     * cắt và thêm "…" (đọc đủ chữ qua {@link #setAccountAccessibleText}).
     */
    public void setAccountLabel(String label) {
        accountLabel.set(label == null || label.isBlank() ? GUEST_ACCOUNT_LABEL : shortenLabel(label.strip()));
    }

    /**
     * Chữ cho trình đọc màn hình của nút tài khoản: {@link #setAccountAccessibleText} nếu có, không thì nhãn
     * nút. Chỉ đọc; luôn không rỗng.
     */
    public ObservableStringValue accountAccessibleTextProperty() {
        return accountEffectiveAccessibleText;
    }

    /** Đặt chữ cho trình đọc màn hình của nút tài khoản; {@code null} hoặc rỗng thì dùng nhãn nút. */
    public void setAccountAccessibleText(String text) {
        accountAccessibleText.set(text);
    }

    /**
     * Các mục của menu tài khoản, theo thứ tự hiện. Rỗng nghĩa là nút tài khoản chạy thẳng hành động
     * {@link ShellAction#ACCOUNT}. Chỉ đọc nội dung trên luồng giao diện; đổi bằng {@link #setAccountMenu}.
     */
    public ObservableList<ShellMenuEntry> accountMenuEntries() {
        return accountMenu;
    }

    /** Thay toàn bộ menu tài khoản; {@code null} hoặc rỗng thì gỡ menu. */
    public void setAccountMenu(List<ShellMenuEntry> entries) {
        accountMenu.setAll(entries == null ? List.of() : List.copyOf(entries));
    }

    /**
     * Các mục của menu "Danh mục", theo thứ tự hiện. Rỗng nghĩa là nút chạy thẳng hành động
     * {@link ShellAction#CATEGORIES}. Chỉ đọc nội dung trên luồng giao diện; đổi bằng {@link #setCategoriesMenu}.
     */
    public ObservableList<ShellMenuEntry> categoriesMenuEntries() {
        return categoriesMenu;
    }

    /** Thay toàn bộ menu danh mục; {@code null} hoặc rỗng thì gỡ menu. */
    public void setCategoriesMenu(List<ShellMenuEntry> entries) {
        categoriesMenu.setAll(entries == null ? List.of() : List.copyOf(entries));
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

    /** Chữ trong ô tìm kiếm, gắn hai chiều với ô nhập; không bao giờ {@code null}. Chỉ đổi trên luồng giao diện. */
    public StringProperty searchTextProperty() {
        return searchText;
    }

    /** Đặt chữ trong ô tìm kiếm (ví dụ từ khóa đã chuẩn hóa của màn danh sách); {@code null} thành rỗng. */
    public void setSearchText(String text) {
        searchText.set(text == null ? "" : text);
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

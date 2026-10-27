package com.minishop.desktop;

import com.minishop.desktop.ShellPresenter.ShellAction;
import java.util.List;
import java.util.Objects;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Controller của {@code ui/main-window.fxml}: chỉ gắn node vào {@link ShellPresenter}.
 * Là bean {@code prototype} vì mỗi lần {@code FXMLLoader.load} cần một controller mới.
 */
@Component
@Scope("prototype")
class MainWindow {

    /** Khoảng cách (px) giữa nút tài khoản và menu của nó. */
    private static final double MENU_GAP = 6;

    /** Phím tắt mở ô tìm kiếm (spec 009 mục 8). */
    private static final KeyCombination SEARCH_SHORTCUT =
            new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);

    private final ShellPresenter presenter;
    private final Navigator navigator;
    private final Toasts toasts;
    private final DrawerHost drawerHost;
    private final ObjectProvider<ShellContributor> contributors;
    private ContextMenu accountMenu;
    private ContextMenu categoriesMenu;

    @FXML private Pane shell;
    @FXML private Pane topBar;
    @FXML private Pane logo;
    @FXML private Button navStore;
    @FXML private Button navNew;
    @FXML private Button navCategories;
    @FXML private Pane searchBox;
    @FXML private TextField searchField;
    @FXML private Button cartButton;
    @FXML private Label cartBadge;
    @FXML private Button accountButton;
    @FXML private StackPane content;
    @FXML private StackPane drawerLayer;
    @FXML private StackPane toastLayer;

    public MainWindow(ShellPresenter presenter, Navigator navigator, Toasts toasts, DrawerHost drawerHost,
                      ObjectProvider<ShellContributor> contributors) {
        this.presenter = presenter;
        this.navigator = navigator;
        this.toasts = toasts;
        this.drawerHost = drawerHost;
        this.contributors = contributors;
    }

    @FXML
    private void initialize() {
        navigator.attach(content);
        drawerHost.attach(drawerLayer, shell);
        toasts.attach(toastLayer);

        bindButton(navStore, ShellAction.STORE);
        bindButton(navNew, ShellAction.NEW_ARRIVALS);
        bindButton(navCategories, ShellAction.CATEGORIES);
        bindButton(cartButton, ShellAction.CART);
        bindButton(accountButton, ShellAction.ACCOUNT);
        accountButton.setOnAction(event -> onAccountButton());
        navCategories.setOnAction(event -> onCategoriesButton());

        searchBox.disableProperty().bind(presenter.searchEnabledBinding().not());
        // Hai chiều nhưng không dùng bindBidirectional: khi màn đặt lại chữ (đã chuẩn hóa), TextField đưa con trỏ về
        // đầu, nên chiều presenter → ô tự đặt chữ rồi đưa con trỏ về cuối. Chữ người dùng gõ đi chiều ngược lại;
        // so bằng trước khi ghi nên hai listener không gọi nhau mãi.
        searchField.setText(presenter.searchTextProperty().get());
        presenter.searchTextProperty().addListener((observable, oldText, newText) -> {
            if (!Objects.equals(newText, searchField.getText())) {
                searchField.setText(newText);
                searchField.positionCaret(searchField.getLength());
            }
        });
        searchField.textProperty().addListener((observable, oldText, newText) -> presenter.setSearchText(newText));
        searchField.setOnAction(event -> presenter.submitSearch(searchField.getText()));
        registerSearchShortcut();

        cartBadge.textProperty().bind(presenter.cartBadgeTextBinding());
        cartButton.accessibleTextProperty().bind(presenter.cartAccessibleTextBinding());
        accountButton.textProperty().bind(presenter.accountLabelProperty());
        accountButton.accessibleTextProperty().bind(presenter.accountAccessibleTextProperty());

        // Mọi binding đã gắn: tính năng được đặt hành động, nhãn, menu, số giỏ (luồng giao diện, QĐ9).
        contributors.orderedStream().forEach(contributor -> contributor.contribute(presenter));
    }

    /** Menu rỗng thì chạy hành động của nút; có mục thì mở menu ngay dưới nút. */
    private void onAccountButton() {
        if (presenter.accountMenuEntries().isEmpty()) {
            presenter.trigger(ShellAction.ACCOUNT);
        } else {
            accountMenu = showMenu(accountMenu, accountButton, presenter.accountMenuEntries(), true, "account-menu");
        }
    }

    private void onCategoriesButton() {
        if (presenter.categoriesMenuEntries().isEmpty()) {
            presenter.trigger(ShellAction.CATEGORIES);
        } else {
            categoriesMenu = showMenu(categoriesMenu, navCategories, presenter.categoriesMenuEntries(), false, null);
        }
    }

    /**
     * Dựng menu mới mỗi lần mở (mục có thể đã đổi) ngay dưới {@code anchor}: canh mép phải của thẻ menu theo mép
     * phải của nút ({@code alignRight}) hoặc mép trái theo mép trái. {@code ContextMenu} có sẵn phím mũi tên,
     * Enter và Esc; Esc hoặc bấm ra ngoài thì menu đóng và focus về nút.
     *
     * @param previous menu cũ của nút này (đóng trước khi mở cái mới), có thể {@code null}
     * @param extraStyleClass class thêm ngoài {@code shell-menu}, hoặc {@code null}
     * @return menu vừa mở, để lần sau đóng; giữ nguyên {@code previous} nếu nút chưa có vị trí trên màn hình
     */
    private ContextMenu showMenu(ContextMenu previous, Button anchor, List<ShellMenuEntry> entries,
                                 boolean alignRight, String extraStyleClass) {
        if (previous != null) {
            previous.hide();
        }
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("shell-menu");
        if (extraStyleClass != null) {
            menu.getStyleClass().add(extraStyleClass);
        }
        for (ShellMenuEntry entry : entries) {
            MenuItem item = new MenuItem(entry.label());
            item.setId(entry.id());
            item.setOnAction(event -> entry.action().run());
            menu.getItems().add(item);
        }
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) {
            return previous;
        }
        // Cửa sổ popup rộng hơn thẻ menu nhìn thấy (skin chừa chỗ trong suốt cho bóng đổ), nên canh theo mép
        // thật của thẻ: layoutBounds không tính hiệu ứng bóng.
        menu.setOnShown(event -> {
            Node card = menu.getSkin() == null ? null : menu.getSkin().getNode();
            Bounds cardOnScreen = card == null ? null : card.localToScreen(card.getLayoutBounds());
            if (alignRight) {
                double cardRight = cardOnScreen == null ? menu.getWidth() : cardOnScreen.getMaxX() - menu.getX();
                menu.setX(bounds.getMaxX() - cardRight);
            } else {
                double cardLeft = cardOnScreen == null ? 0 : cardOnScreen.getMinX() - menu.getX();
                menu.setX(bounds.getMinX() - cardLeft);
            }
        });
        menu.show(anchor, bounds.getMinX(), bounds.getMaxY() + MENU_GAP);
        return menu;
    }

    /**
     * Ctrl+K (Cmd+K trên macOS) trên {@code Scene} chính: focus ô tìm kiếm và chọn hết chữ. {@code initialize()}
     * chạy trước khi có scene, nên đăng ký khi node được gắn vào scene; mỗi scene chỉ đăng ký một lần.
     */
    private void registerSearchShortcut() {
        searchField.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.getAccelerators().remove(SEARCH_SHORTCUT);
            }
            addSearchShortcut(newScene);
        });
        addSearchShortcut(searchField.getScene());
    }

    private void addSearchShortcut(Scene scene) {
        if (scene != null) {
            scene.getAccelerators().put(SEARCH_SHORTCUT, this::focusSearch);
        }
    }

    private void focusSearch() {
        if (searchField.isDisabled()) {
            return;
        }
        searchField.requestFocus();
        searchField.selectAll();
    }

    private void bindButton(Button button, ShellAction action) {
        button.disableProperty().bind(presenter.enabledBinding(action).not());
        button.setOnAction(event -> presenter.trigger(action));
    }

    /** Vùng nội dung giữa thanh trên; {@link Navigator} thay node ở đây. */
    StackPane content() {
        return content;
    }

    /** Lớp phủ cho {@link DrawerHost}: nằm trên thanh trên và nội dung, dưới lớp toast. */
    StackPane drawerLayer() {
        return drawerLayer;
    }

    /** Lớp phủ cho {@link Toasts}. Không chặn chuột ngoài các toast. */
    StackPane toastLayer() {
        return toastLayer;
    }
}

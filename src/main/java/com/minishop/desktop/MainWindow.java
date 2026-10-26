package com.minishop.desktop;

import com.minishop.desktop.ShellPresenter.ShellAction;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
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

    private final ShellPresenter presenter;
    private final Navigator navigator;
    private final Toasts toasts;
    private final ObjectProvider<ShellContributor> contributors;
    private ContextMenu accountMenu;

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
    @FXML private StackPane toastLayer;

    public MainWindow(ShellPresenter presenter, Navigator navigator, Toasts toasts,
                      ObjectProvider<ShellContributor> contributors) {
        this.presenter = presenter;
        this.navigator = navigator;
        this.toasts = toasts;
        this.contributors = contributors;
    }

    @FXML
    private void initialize() {
        navigator.attach(content);
        toasts.attach(toastLayer);

        bindButton(navStore, ShellAction.STORE);
        bindButton(navNew, ShellAction.NEW_ARRIVALS);
        bindButton(navCategories, ShellAction.CATEGORIES);
        bindButton(cartButton, ShellAction.CART);
        bindButton(accountButton, ShellAction.ACCOUNT);
        accountButton.setOnAction(event -> onAccountButton());

        searchBox.disableProperty().bind(presenter.searchEnabledBinding().not());
        searchField.setOnAction(event -> presenter.submitSearch(searchField.getText()));

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
            showAccountMenu();
        }
    }

    /**
     * Dựng menu mới mỗi lần mở (mục có thể đã đổi), mép phải thẳng với mép phải của nút. {@code ContextMenu}
     * có sẵn phím mũi tên, Enter và Esc; Esc hoặc bấm ra ngoài thì menu đóng và focus về nút.
     */
    private void showAccountMenu() {
        if (accountMenu != null) {
            accountMenu.hide();
        }
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("account-menu");
        for (ShellMenuEntry entry : presenter.accountMenuEntries()) {
            MenuItem item = new MenuItem(entry.label());
            item.setId(entry.id());
            item.setOnAction(event -> entry.action().run());
            menu.getItems().add(item);
        }
        Bounds bounds = accountButton.localToScreen(accountButton.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        // Cửa sổ popup rộng hơn thẻ menu nhìn thấy (skin chừa chỗ trong suốt cho bóng đổ), nên canh theo mép phải
        // thật của thẻ: layoutBounds không tính hiệu ứng bóng.
        menu.setOnShown(event -> {
            Node card = menu.getSkin() == null ? null : menu.getSkin().getNode();
            Bounds cardOnScreen = card == null ? null : card.localToScreen(card.getLayoutBounds());
            double cardRight = cardOnScreen == null ? menu.getWidth() : cardOnScreen.getMaxX() - menu.getX();
            menu.setX(bounds.getMaxX() - cardRight);
        });
        accountMenu = menu;
        menu.show(accountButton, bounds.getMinX(), bounds.getMaxY() + MENU_GAP);
    }

    private void bindButton(Button button, ShellAction action) {
        button.disableProperty().bind(presenter.enabledBinding(action).not());
        button.setOnAction(event -> presenter.trigger(action));
    }

    /** Vùng nội dung giữa thanh trên; {@link Navigator} thay node ở đây. */
    StackPane content() {
        return content;
    }

    /** Lớp phủ cho {@link Toasts}. Không chặn chuột ngoài các toast. */
    StackPane toastLayer() {
        return toastLayer;
    }
}

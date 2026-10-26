package com.minishop.desktop;

import com.minishop.desktop.ShellPresenter.ShellAction;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Controller của {@code ui/main-window.fxml}: chỉ gắn node vào {@link ShellPresenter}.
 * Là bean {@code prototype} vì mỗi lần {@code FXMLLoader.load} cần một controller mới.
 */
@Component
@Scope("prototype")
class MainWindow {

    private final ShellPresenter presenter;
    private final Navigator navigator;
    private final Toasts toasts;

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

    public MainWindow(ShellPresenter presenter, Navigator navigator, Toasts toasts) {
        this.presenter = presenter;
        this.navigator = navigator;
        this.toasts = toasts;
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

        searchBox.disableProperty().bind(presenter.searchEnabledBinding().not());
        searchField.setOnAction(event -> presenter.submitSearch(searchField.getText()));

        cartBadge.textProperty().bind(presenter.cartBadgeTextBinding());
        cartButton.accessibleTextProperty().bind(presenter.cartAccessibleTextBinding());
        accountButton.textProperty().bind(presenter.accountLabelProperty());
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

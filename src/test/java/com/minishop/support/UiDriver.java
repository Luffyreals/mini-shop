package com.minishop.support;

import com.minishop.catalog.CatalogRoute;
import com.minishop.catalog.ProductDetailRoute;
import com.minishop.catalog.ProductRepository;
import com.minishop.desktop.DrawerHost;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.user.AppSession;
import com.minishop.user.DemoAccounts;
import com.minishop.user.SessionListener;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real main window for the cart and order smoke tests ({@code @Tag("ui")}, D4 plan T3): lookups by node
 * id, firing buttons, waiting for background work, signing in and out through the real login dialog and account
 * menu, and putting the app back to a clean state between tests. Like the other UI tests it never creates a robot:
 * it only calls {@code fire()}, fires plain events on nodes, and reads state.
 *
 * <p>Only public API of the app is used, so the helper can live in {@code support} and serve tests of any feature
 * package.
 */
public final class UiDriver {

    private final Stage stage;
    private final TestShellApp app;

    public UiDriver(Stage stage, TestShellApp app) {
        this.stage = stage;
        this.app = app;
    }

    // ------------------------------------------------------------------ beans

    public <T> T bean(Class<T> type) {
        return app.context().getBean(type);
    }

    public Navigator navigator() {
        return bean(Navigator.class);
    }

    public ShellPresenter shell() {
        return bean(ShellPresenter.class);
    }

    public DrawerHost drawerHost() {
        return bean(DrawerHost.class);
    }

    public AppSession session() {
        return bean(AppSession.class);
    }

    /** Id of the active seed product with that exact name. */
    public long productId(String name) {
        return bean(ProductRepository.class).findAll().stream()
                .filter(product -> name.equals(product.getName()))
                .findFirst().orElseThrow().getId();
    }

    public int stockOf(String name) {
        return bean(ProductRepository.class).findAll().stream()
                .filter(product -> name.equals(product.getName()))
                .findFirst().orElseThrow().getStock();
    }

    // ------------------------------------------------------------------ lookups

    /** The node with that id anywhere in the main window, or {@code null}. */
    public Node node(String id) {
        return FxTestSupport.callOnFx(() -> stage.getScene().lookup("#" + id));
    }

    public Node nodeRequired(String id) {
        Node node = node(id);
        assertThat(node).as("node #" + id).isNotNull();
        return node;
    }

    /** The node with that id inside the content area, or {@code null}. */
    public Node inContent(String id) {
        return FxTestSupport.callOnFx(() -> {
            Node content = stage.getScene().lookup("#content");
            return content == null ? null : content.lookup("#" + id);
        });
    }

    public Node inContentRequired(String id) {
        Node node = inContent(id);
        assertThat(node).as("node #" + id + " in the content area").isNotNull();
        return node;
    }

    /** The first showing window other than the main one whose scene has the node, or {@code null}. */
    public Window windowWith(String id) {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(window -> window != stage && window.isShowing() && window.getScene() != null
                        && window.getScene().lookup("#" + id) != null)
                .findFirst().orElse(null));
    }

    public Node inWindow(Window window, String id) {
        return FxTestSupport.callOnFx(() -> window.getScene().lookup("#" + id));
    }

    /** True when the node exists, is managed, and it and every parent up to the scene root are visible. */
    public static boolean shown(Node node) {
        return FxTestSupport.callOnFx(() -> {
            if (node == null || !node.isManaged()) {
                return false;
            }
            for (Node current = node; current != null; current = current.getParent()) {
                if (!current.isVisible()) {
                    return false;
                }
            }
            return true;
        });
    }

    public boolean shownInScene(String id) {
        return shown(node(id));
    }

    public boolean shownInContent(String id) {
        return shown(inContent(id));
    }

    public String text(Node node) {
        return FxTestSupport.callOnFx(() -> {
            if (node instanceof Label label) {
                return label.getText();
            }
            if (node instanceof TextInputControl input) {
                return input.getText();
            }
            if (node instanceof Button button) {
                return button.getText();
            }
            return null;
        });
    }

    public String badgeText() {
        return text(nodeRequired("cartBadge"));
    }

    public List<String> toastTexts() {
        return FxTestSupport.callOnFx(() -> stage.getScene().getRoot().lookupAll(".toast-text").stream()
                .map(node -> ((Label) node).getText()).toList());
    }

    /** Every label text under the node (the node itself included). */
    public List<String> labelsUnder(Node root) {
        return FxTestSupport.callOnFx(() -> {
            List<String> texts = new ArrayList<>();
            collectLabels(root, texts);
            return texts;
        });
    }

    private static void collectLabels(Node node, List<String> texts) {
        if (node instanceof Label label && label.getText() != null) {
            texts.add(label.getText());
        }
        if (node instanceof Button button && button.getText() != null) {
            texts.add(button.getText());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectLabels(child, texts));
        }
    }

    public void assertNoErrorDialog() {
        assertThat(FxTestSupport.showingWindows()).as("no general error dialog")
                .extracting(Window::getScene)
                .noneMatch(scene -> scene != null && scene.lookup("#errorDialog") != null);
    }

    // ------------------------------------------------------------------ actions

    public void fire(String id) {
        FxTestSupport.runOnFx(() -> ((Button) nodeRequired(id)).fire());
    }

    public void fireIn(Window window, String id) {
        FxTestSupport.runOnFx(() -> ((Button) inWindow(window, id)).fire());
    }

    public void setText(String id, String value) {
        FxTestSupport.runOnFx(() -> ((TextInputControl) nodeRequired(id)).setText(value));
    }

    public void setTextIn(Window window, String id, String value) {
        FxTestSupport.runOnFx(() -> ((TextInputControl) inWindow(window, id)).setText(value));
    }

    /** Fires a key press at the node, so it goes through the scene filters and handlers like a real key. */
    public void pressKey(Node target, KeyCode code) {
        FxTestSupport.runOnFx(() -> Event.fireEvent(target,
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    public static MouseEvent clickOn(Node target) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, 1, false, false, false,
                false, true, false, false, false, false, true, new PickResult(target, 5, 5));
    }

    // ------------------------------------------------------------------ cart

    /**
     * Puts {@code quantity} of the product in the cart through the product detail screen (the real add button),
     * and waits until the badge shows {@code expectedBadge}.
     */
    public void addFromDetail(String productName, int quantity, int expectedBadge) {
        long id = productId(productName);
        FxTestSupport.runOnFx(() -> navigator().navigateTo(new ProductDetailRoute(id, CatalogRoute.all())));
        FxTestSupport.await("the detail of " + productName, () -> {
            Node label = inContent("productName");
            return label != null && productName.equals(text(label));
        });
        FxTestSupport.await("the add button to be usable", () -> {
            Node add = inContent("addToCart");
            return add != null && !FxTestSupport.callOnFx(add::isDisabled);
        });
        FxTestSupport.runOnFx(() -> ((TextInputControl) inContentRequired("quantityField"))
                .setText(Integer.toString(quantity)));
        FxTestSupport.runOnFx(() -> ((Button) inContentRequired("addToCart")).fire());
        FxTestSupport.await("the cart badge to show " + expectedBadge, () -> Integer.toString(expectedBadge)
                .equals(badgeText()));
        FxTestSupport.await("the add button to be usable again", () -> {
            Node add = inContent("addToCart");
            return add != null && !FxTestSupport.callOnFx(add::isDisabled);
        });
    }

    /** Fires the cart button and waits until the drawer has loaded (lines or the empty state). */
    public void openCart() {
        fire("cartButton");
        awaitDrawerLoaded();
    }

    public void awaitDrawerLoaded() {
        FxTestSupport.await("the cart drawer to load",
                () -> shownInScene("cartLines") || shownInScene("cartEmpty"));
    }

    public boolean drawerClosed() {
        return !FxTestSupport.callOnFx(() -> drawerHost().isShowing()) && node("cartDrawer") == null;
    }

    // ------------------------------------------------------------------ session

    private boolean loggedIn() {
        return FxTestSupport.callOnFx(() -> session().isLoggedIn());
    }

    private List<ContextMenu> showingMenus() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu && window.isShowing())
                .map(window -> (ContextMenu) window)
                .toList());
    }

    public void hideMenus() {
        FxTestSupport.runOnFx(() -> showingMenus().forEach(ContextMenu::hide));
    }

    /** Fires an item of the account menu (the user must be logged in) and hides the menu again. */
    public void fireAccountMenuItem(String itemId) {
        FxTestSupport.runOnFx(() -> ((Button) nodeRequired("accountButton")).fire());
        FxTestSupport.await("the account menu", () -> showingMenus().size() == 1);
        FxTestSupport.flushFx();
        MenuItem item = FxTestSupport.callOnFx(() -> showingMenus().get(0).getItems().stream()
                .filter(candidate -> itemId.equals(candidate.getId())).findFirst().orElseThrow());
        FxTestSupport.runOnFx(item::fire);
        // A ContextMenu left showing would swallow the next key events (see AccountMenuSmokeTest.hideMenus)
        hideMenus();
        FxTestSupport.flushFx();
    }

    /** Types the demo user's credentials into the open login dialog, submits it and waits until it is gone. */
    public void submitLoginDialog(String email, String password) {
        FxTestSupport.await("the login dialog", () -> windowWith("loginSubmit") != null);
        Window dialog = windowWith("loginSubmit");
        setTextIn(dialog, "loginEmail", email);
        setTextIn(dialog, "loginPassword", password);
        fireIn(dialog, "loginSubmit");
        FxTestSupport.await("the login to finish", () -> loggedIn() && windowWith("authDialog") == null);
    }

    /** Logs in as the demo customer through the real dialog, started from the account button. */
    public void logInAsUser() {
        logIn(DemoAccounts.USER_EMAIL, StandaloneApp.DEV_USER_PASSWORD, "Khách hàng mẫu");
    }

    /** Logs in as the demo administrator through the real dialog (an admin is also a customer, spec 005 Q5). */
    public void logInAsAdmin() {
        logIn(DemoAccounts.ADMIN_EMAIL, StandaloneApp.DEV_ADMIN_PASSWORD, "Quản trị viên");
    }

    private void logIn(String email, String password, String fullName) {
        fire("accountButton");
        submitLoginDialog(email, password);
        FxTestSupport.await("the account button to show " + fullName,
                () -> fullName.equals(text(node("accountButton"))));
        FxTestSupport.flushFx();
    }

    // ------------------------------------------------------------------ cleaning up

    /** Dismisses every toast on screen and in the queue (a click on a toast hides it). */
    public void dismissToasts() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                FxTestSupport.TIMEOUT_SECONDS);
        while (true) {
            List<Node> toasts = FxTestSupport.callOnFx(() -> {
                Node layer = stage.getScene().lookup("#toastLayer");
                return layer instanceof javafx.scene.layout.Pane pane ? List.copyOf(pane.getChildren())
                        : List.<Node>of();
            });
            if (toasts.isEmpty() || System.nanoTime() > deadline) {
                return;
            }
            FxTestSupport.runOnFx(() -> toasts.forEach(toast -> Event.fireEvent(toast, clickOn(toast))));
            FxTestSupport.flushFx();
        }
    }

    /**
     * Puts the app back to a guest on the home screen with an empty cart (guest and database), no drawer, no extra
     * window, no toast. Every test class calls it from {@code @AfterEach} so each test runs alone and in any order.
     */
    public void resetToHome() {
        FxTestSupport.runOnFx(() -> {
            drawerHost().close();
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage && window.isShowing()) {
                    window.hide();
                }
            }
        });
        if (loggedIn()) {
            fireAccountMenuItem("accountMenuLogout");
            FxTestSupport.await("the logout", () -> !loggedIn());
        }
        FxTestSupport.runOnFx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage && window.isShowing()) {
                    window.hide();
                }
            }
        });
        // Carts of every user are emptied directly (test H2); orders stay, they only add rows to the history
        new JdbcTemplate(bean(DataSource.class)).update("delete from cart_items");
        FxTestSupport.runOnFx(() -> {
            // A logout clears the guest cart and refreshes the badge; no session is opened here. Same @Order as
            // AppSession, so the guest cart is cleared before the badge reads it
            app.context().getBeanProvider(SessionListener.class).orderedStream()
                    .forEach(listener -> listener.sessionChanged(null, null));
            shell().setSearchText("");
            navigator().goHome();
        });
        FxTestSupport.await("the home screen", () -> inContent("homeScreen") != null);
        FxTestSupport.await("the empty cart badge", () -> "0".equals(badgeText()));
        // The drawer slides out for a moment before its nodes leave the layer
        FxTestSupport.await("the drawer nodes to leave", this::drawerClosed);
        dismissToasts();
        FxTestSupport.flushFx();
    }
}

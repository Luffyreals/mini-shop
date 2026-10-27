package com.minishop.admin;

import com.minishop.support.FxTestSupport;
import com.minishop.support.UiDriver;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small helpers shared by the admin smoke tests ({@code @Tag("ui")}): waiting for admin screens and toasts, firing
 * toggle buttons (sidebar items and filter pills are {@code ToggleButton}s, which {@link UiDriver#fire} cannot cast),
 * the confirm dialog, and reading the account menu. Like {@link UiDriver} it never creates a robot.
 */
final class AdminSmoke {

    private final UiDriver ui;

    AdminSmoke(UiDriver ui) {
        this.ui = ui;
    }

    UiDriver ui() {
        return ui;
    }

    // ------------------------------------------------------------------ waiting

    void awaitScreen(String screenId) {
        FxTestSupport.await("the screen #" + screenId, () -> ui.inContent(screenId) != null);
    }

    /** Waits until the toast is on screen. */
    void awaitToast(String text) {
        FxTestSupport.await("the toast \"" + text + "\"", () -> ui.toastTexts().contains(text));
    }

    void awaitText(String what, String id, String expected) {
        FxTestSupport.await(what + " to read \"" + expected + "\"", () -> {
            Node node = ui.inContent(id);
            return node != null && expected.equals(ui.text(node));
        });
    }

    void awaitShown(String id) {
        FxTestSupport.await("#" + id + " to be shown", () -> ui.shownInContent(id));
    }

    void awaitGone(String id) {
        FxTestSupport.await("#" + id + " to be gone", () -> ui.inContent(id) == null || !ui.shownInContent(id));
    }

    // ------------------------------------------------------------------ opening the admin area

    /** The admin (already logged in) opens "Quản trị" from the account menu and waits for the loaded dashboard. */
    void openAdminArea() {
        ui.fireAccountMenuItem("accountMenuAdmin");
        awaitDashboardLoaded();
    }

    void awaitDashboardLoaded() {
        awaitScreen("adminDashboardScreen");
        FxTestSupport.await("the dashboard counts", () -> ui.shownInContent("adminCounts")
                && ui.inContent("adminCount-PENDING") != null);
    }

    void goTo(String sectionKey, String screenId) {
        fireToggle("adminNav-" + sectionKey);
        awaitScreen(screenId);
    }

    /**
     * The node with that id in the content area, waiting for it to appear. The content of a scroll pane only joins
     * the scene once its skin exists, so a node inside an admin screen can be missing for a moment after the screen
     * itself is found.
     */
    Node node(String id) {
        FxTestSupport.await("the node #" + id, () -> ui.inContent(id) != null);
        return ui.inContentRequired(id);
    }

    // ------------------------------------------------------------------ firing

    /** Fires a {@code Button} or {@code ToggleButton} found in the content area. */
    void fireInContent(String id) {
        Node target = node(id);
        FxTestSupport.runOnFx(() -> ((ButtonBase) target).fire());
    }

    void fireToggle(String id) {
        FxTestSupport.await("the toggle #" + id, () -> ui.node(id) != null);
        FxTestSupport.runOnFx(() -> ((ButtonBase) ui.nodeRequired(id)).fire());
    }

    /** Presses Enter on a node that is a keyboard-activatable row or tile (not a button). */
    void activate(String id) {
        ui.pressKey(node(id), KeyCode.ENTER);
    }

    // ------------------------------------------------------------------ focus

    /**
     * What a mouse click on a button does: the button takes the focus, then it fires. Both happen in one FX task, so
     * when the click replaces the screen the focused node leaves the scene, exactly like a real click on a sidebar
     * item or a filter pill.
     */
    void click(String id) {
        FxTestSupport.await("the button #" + id, () -> ui.node(id) != null);
        FxTestSupport.runOnFx(() -> {
            Node target = ui.nodeRequired(id);
            target.requestFocus();
            ((ButtonBase) target).fire();
        });
    }

    /** Gives the node the keyboard focus (what a click on it does), then waits for the FX thread. */
    void focus(String id) {
        FxTestSupport.await("the node #" + id, () -> ui.node(id) != null);
        FxTestSupport.runOnFx(() -> ui.nodeRequired(id).requestFocus());
    }

    /** The scene's focus owner, or {@code null}. */
    Node focusOwner() {
        return FxTestSupport.callOnFx(() -> ui.nodeRequired("content").getScene().getFocusOwner());
    }

    /** "ClassName#id" of the focus owner, for failure messages and exact checks. */
    String focusOwnerId() {
        Node owner = focusOwner();
        return owner == null ? null : FxTestSupport.callOnFx(owner::getId);
    }

    /** True when the focus owner is the node with that id or one of its descendants. */
    boolean focusIsInside(String ancestorId) {
        return FxTestSupport.callOnFx(() -> {
            Node ancestor = ui.node(ancestorId);
            for (Node current = focusOwner(); current != null; current = current.getParent()) {
                if (current == ancestor) {
                    return ancestor != null;
                }
            }
            return false;
        });
    }

    private String describeFocus() {
        Node owner = focusOwner();
        return owner == null ? "nothing" : FxTestSupport.callOnFx(() -> owner.getClass().getSimpleName() + "#"
                + owner.getId());
    }

    /**
     * Waits until the focus is inside the admin screen's own content (not in its sidebar and not in the window's top
     * bar), then checks it stays there once everything queued has run. The focus must never be left on "Cửa hàng"
     * or another top bar control after the screen was replaced.
     */
    void awaitFocusInScreen(String screenId) {
        try {
            FxTestSupport.await("the focus to be inside #" + screenId + " but not in its sidebar",
                    () -> focusIsInside(screenId) && !focusIsInside("adminSidebar"));
        } catch (AssertionError timeout) {
            throw new AssertionError(timeout.getMessage() + "; the focus is on " + describeFocus(), timeout);
        }
        FxTestSupport.flushFx();
        FxTestSupport.flushFx();
        assertThat(focusIsInside("topBar")).as("the focus is not in the top bar (it is on %s)", describeFocus())
                .isFalse();
        assertThat(focusIsInside(screenId)).as("the focus is inside #%s (it is on %s)", screenId, describeFocus())
                .isTrue();
        assertThat(focusIsInside("adminSidebar")).as("the focus is not in the sidebar (it is on %s)", describeFocus())
                .isFalse();
    }

    /** Presses and releases a key on the node (a Space key press fires a button on release). */
    void typeKey(Node target, KeyCode code) {
        FxTestSupport.runOnFx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
        });
    }

    void typeInContent(String id, String value) {
        Node target = node(id);
        FxTestSupport.runOnFx(() -> ((TextInputControl) target).setText(value));
    }

    @SuppressWarnings("unchecked")
    void selectCategory(String name) {
        ComboBox<com.minishop.catalog.CategoryOption> combo =
                (ComboBox<com.minishop.catalog.CategoryOption>) node("productCategoryField");
        FxTestSupport.await("the category options to load", () -> !FxTestSupport.callOnFx(
                () -> combo.getItems().isEmpty()));
        FxTestSupport.runOnFx(() -> {
            combo.getItems().stream().filter(option -> name.equals(option.name())).findFirst()
                    .ifPresentOrElse(option -> combo.setValue(option),
                            () -> {
                                throw new AssertionError("no category option named " + name);
                            });
        });
    }

    boolean isSelected(String id) {
        FxTestSupport.await("the toggle #" + id, () -> ui.node(id) != null);
        return FxTestSupport.callOnFx(() -> ((ToggleButton) ui.nodeRequired(id)).isSelected());
    }

    String toggleText(String id) {
        return FxTestSupport.callOnFx(() -> ((ToggleButton) ui.nodeRequired(id)).getText());
    }

    String text(String id) {
        return ui.text(node(id));
    }

    // ------------------------------------------------------------------ confirm dialog

    Window awaitConfirm(String expectedMessage) {
        FxTestSupport.await("the confirm dialog", () -> ui.windowWith("confirmDialog") != null);
        Window dialog = ui.windowWith("confirmDialog");
        assertThat(ui.text(ui.inWindow(dialog, "confirmMessage"))).isEqualTo(expectedMessage);
        return dialog;
    }

    void dismissConfirm(Window dialog) {
        ui.fireIn(dialog, "confirmDismiss");
        FxTestSupport.await("the confirm dialog to close", () -> ui.windowWith("confirmDialog") == null);
        FxTestSupport.flushFx();
    }

    void acceptConfirm(Window dialog) {
        ui.fireIn(dialog, "confirmAccept");
        FxTestSupport.await("the confirm dialog to close", () -> ui.windowWith("confirmDialog") == null);
    }

    void assertNoConfirm() {
        FxTestSupport.flushFx();
        assertThat(ui.windowWith("confirmDialog")).as("no confirm dialog").isNull();
    }

    // ------------------------------------------------------------------ account menu

    private static List<ContextMenu> showingMenus() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu && window.isShowing())
                .map(window -> (ContextMenu) window)
                .toList());
    }

    /** Opens the account menu (the user must be logged in), reads the item ids in order and closes it again. */
    List<String> accountMenuItemIds() {
        ui.fire("accountButton");
        FxTestSupport.await("the account menu", () -> showingMenus().size() == 1);
        FxTestSupport.flushFx();
        List<String> ids = FxTestSupport.callOnFx(() -> showingMenus().get(0).getItems().stream()
                .map(MenuItem::getId).toList());
        ui.hideMenus();
        FxTestSupport.flushFx();
        return ids;
    }

    // ------------------------------------------------------------------ session

    /** Logs the current user out through the account menu and waits for the guest state. */
    void logOut() {
        ui.fireAccountMenuItem("accountMenuLogout");
        FxTestSupport.await("the logout", () -> !FxTestSupport.callOnFx(() -> ui.session().isLoggedIn()));
    }
}

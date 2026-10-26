package com.minishop.user;

import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import javafx.application.Application;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account menu of the top bar on the real JavaFX toolkit (AC-009-8, AC-009-10; QĐ9, QĐ10 of the D2 plan). The
 * session is signed in on the FX thread (this test is in the {@code user} package, so it may call the
 * package-private {@code AppSession.signIn}), the account button is fired, and the menu that opens is inspected.
 * No robot: nothing moves the mouse or presses a key.
 *
 * <p>It also measures where the menu card is drawn: the visible right edge of the card must line up with the right
 * edge of the account button (the popup window is wider than the card, to leave room for its shadow, and
 * {@code MainWindow.showAccountMenu} compensates).
 */
@Tag("ui")
class AccountMenuSmokeTest {

    /** Largest accepted difference, in pixels, between the right edge of the menu card and of the button. */
    private static final double ALIGNMENT_TOLERANCE = 1.0;

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (app != null && app.context() != null && app.context().isActive()) {
            FxToolkit.cleanupApplication((Application) app);
        }
        FxTestSupport.hideAllWindows();
    }

    /** Every test starts as a guest with nothing open and prepares what it needs; this puts the app back. */
    @AfterEach
    void backToAGuestWithNothingOpen() {
        hideMenus();
        closeAuthDialogs();
        signOut();
        FxTestSupport.flushFx();
    }

    // ------------------------------------------------------------------ helpers

    private static void closeAuthDialogs() {
        FxTestSupport.runOnFx(() -> List.copyOf(Window.getWindows()).stream()
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window.getScene().lookup("#authClose") != null)
                .forEach(window -> ((Button) window.getScene().lookup("#authClose")).fire()));
        FxTestSupport.await("the dialog to close", () -> !authDialogShowing());
    }

    private static Button accountButton() {
        return (Button) FxTestSupport.callOnFx(() -> mainStage.getScene().lookup("#accountButton"));
    }

    private static AppSession session() {
        return app.context().getBean(AppSession.class);
    }

    private static void signInAs(String email) {
        User user = app.context().getBean(UserRepository.class).findByEmail(email).orElseThrow();
        CurrentUser current = new CurrentUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
        FxTestSupport.runOnFx(() -> session().signIn(current));
    }

    private static void signOut() {
        FxTestSupport.runOnFx(() -> session().signOut());
    }

    private static List<ContextMenu> showingMenus() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu && window.isShowing())
                .map(window -> (ContextMenu) window)
                .toList());
    }

    private static void hideMenus() {
        FxTestSupport.runOnFx(() -> showingMenus().forEach(ContextMenu::hide));
    }

    private static ContextMenu openAccountMenu() {
        FxTestSupport.runOnFx(() -> accountButton().fire());
        FxTestSupport.await("the account menu", () -> showingMenus().size() == 1);
        // Let the popup lay out and the "shown" handler of MainWindow move it before anything is measured.
        FxTestSupport.flushFx();
        FxTestSupport.flushFx();
        return showingMenus().get(0);
    }

    private static List<String> itemIds(ContextMenu menu) {
        return FxTestSupport.callOnFx(() -> menu.getItems().stream().map(MenuItem::getId).toList());
    }

    private static List<String> itemTexts(ContextMenu menu) {
        return FxTestSupport.callOnFx(() -> menu.getItems().stream().map(MenuItem::getText).toList());
    }

    private static boolean authDialogShowing() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .anyMatch(window -> window.isShowing() && window.getScene() != null
                        && window.getScene().lookup("#authDialog") != null));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void signIn_asAUser_showsTheNameOnTheAccountButton() {
        signInAs(DemoAccounts.USER_EMAIL);

        Button button = accountButton();

        assertThat(FxTestSupport.callOnFx(button::getText)).isEqualTo("Khách hàng mẫu");
        assertThat(FxTestSupport.callOnFx(button::getAccessibleText)).isEqualTo("Xin chào, Khách hàng mẫu");
        assertThat(FxTestSupport.callOnFx(button::isDisabled)).isFalse();
    }

    @Test
    void firingTheAccountButton_asAUser_opensAMenuWithOnlyLogoutAndNotTheLoginDialog() {
        signInAs(DemoAccounts.USER_EMAIL);
        ContextMenu menu = openAccountMenu();

        assertThat(itemIds(menu)).containsExactly("accountMenuLogout");
        assertThat(itemTexts(menu)).containsExactly("Đăng xuất");
        assertThat(authDialogShowing()).as("a logged-in user does not get the login dialog").isFalse();
    }

    @Test
    void theMenuCard_isDrawnWithItsRightEdgeUnderTheRightEdgeOfTheAccountButton() {
        signInAs(DemoAccounts.USER_EMAIL);
        ContextMenu menu = openAccountMenu();

        Bounds[] measured = FxTestSupport.callOnFx(() -> {
            Button button = accountButton();
            Node card = menu.getSkin().getNode();
            return new Bounds[] {
                    button.localToScreen(button.getBoundsInLocal()),
                    card.localToScreen(card.getLayoutBounds())};
        });
        Bounds button = measured[0];
        Bounds card = measured[1];
        double delta = card.getMaxX() - button.getMaxX();

        assertThat(Math.abs(delta))
                .as("right edge of the visible menu card (%.2f) minus right edge of #accountButton (%.2f) = %.2f px",
                        card.getMaxX(), button.getMaxX(), delta)
                .isLessThanOrEqualTo(ALIGNMENT_TOLERANCE);
        assertThat(card.getMinY()).as("the card is below the button").isGreaterThanOrEqualTo(button.getMaxY());
    }

    @Test
    void firingTheLogoutItem_returnsToAGuestWithAnEmptyContentAndAToast() {
        signInAs(DemoAccounts.USER_EMAIL);
        ContextMenu menu = openAccountMenu();
        MenuItem logout = FxTestSupport.callOnFx(() -> menu.getItems().get(0));

        FxTestSupport.runOnFx(logout::fire);
        FxTestSupport.flushFx();

        assertThat(FxTestSupport.callOnFx(() -> accountButton().getText())).isEqualTo("Đăng nhập");
        assertThat(session().isLoggedIn()).isFalse();
        assertThat(FxTestSupport.callOnFx(() -> ((StackPane) mainStage.getScene().lookup("#content"))
                .getChildren().size())).as("the content area is empty (the D2 home)").isZero();
        List<String> toasts = FxTestSupport.callOnFx(() -> mainStage.getScene().getRoot().lookupAll(".toast-text").stream()
                .map(node -> ((Label) node).getText()).toList());
        assertThat(toasts).contains("Bạn đã đăng xuất.");
        assertThat(FxTestSupport.callOnFx(() -> ((Label) mainStage.getScene().lookup("#cartBadge")).getText()))
                .isEqualTo("0");
        hideMenus();
    }

    @Test
    void afterLogout_theAccountButtonOpensTheLoginDialogAgainAndHasNoMenu() {
        FxTestSupport.runOnFx(() -> accountButton().fire());

        FxTestSupport.await("the login dialog", AccountMenuSmokeTest::authDialogShowing);
        assertThat(showingMenus()).as("a guest has no menu").isEmpty();
    }

    @Test
    void signIn_asAnAdmin_getsAMenuWithOnlyLogoutBecauseNoFeatureAttachedAnAdminEntryYet() {
        signInAs(DemoAccounts.ADMIN_EMAIL);

        ContextMenu menu = openAccountMenu();

        assertThat(FxTestSupport.callOnFx(() -> accountButton().getText())).isEqualTo("Quản trị viên");
        assertThat(itemTexts(menu)).as("no \"Quản trị\" and no dead entry in D2").containsExactly("Đăng xuất");
        assertThat(itemIds(menu)).containsExactly("accountMenuLogout");
        hideMenus();
        signOut();
    }

    @Test
    void firingTheAccountButtonTwice_buildsAFreshMenuAndKeepsOnlyOneOpen() {
        signInAs(DemoAccounts.USER_EMAIL);
        openAccountMenu();

        ContextMenu second = openAccountMenu();

        assertThat(showingMenus()).hasSize(1).containsExactly(second);
        hideMenus();
        signOut();
    }
}

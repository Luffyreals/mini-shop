package com.minishop.user;

import com.minishop.support.FxTestSupport;
import com.minishop.support.StandaloneApp;
import com.minishop.support.TestShellApp;
import javafx.application.Application;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testfx.api.FxToolkit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login and registration dialog on the real JavaFX toolkit (AC-009-6; QĐ8 of the D2 plan): the real app is
 * started, the dialog is opened by firing the account button, filled by setting text, and driven by firing buttons.
 * No robot, so nothing moves the mouse or presses a key. One application serves the whole class; each test opens
 * what it needs itself and {@code @AfterEach} closes the dialog and logs out, so any test runs alone.
 */
@Tag("ui")
class AuthDialogSmokeTest {

    private static final String WRONG_CREDENTIALS = "Email hoặc mật khẩu không đúng.";
    private static final List<String> DIALOG_IDS = List.of("authDialog", "authTitle", "authNotice", "authClose",
            "loginEmail", "loginPassword", "loginError", "loginSubmit", "toRegister",
            "regFullName", "regEmail", "regPassword", "regConfirmPassword",
            "regFullNameError", "regEmailError", "regPasswordError", "regConfirmPasswordError",
            "regSubmit", "toLogin");

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

    /** Every test starts as a guest with no dialog or menu showing; this puts the app back after it. */
    @AfterEach
    void backToAGuestWithNothingOpen() {
        hideContextMenus();
        FxTestSupport.runOnFx(() -> {
            Stage open = dialog();
            if (open != null) {
                ((Button) open.getScene().lookup("#authClose")).fire();
            }
        });
        FxTestSupport.await("the dialog to close", () -> dialog() == null);
        FxTestSupport.runOnFx(() -> app.context().getBean(AppSession.class).signOut());
        FxTestSupport.flushFx();
    }

    // ------------------------------------------------------------------ lookups

    private static Button accountButton() {
        return (Button) FxTestSupport.callOnFx(() -> mainStage.getScene().lookup("#accountButton"));
    }

    private static Stage dialog() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> window.getScene() != null && window.getScene().lookup("#authDialog") != null)
                .map(window -> (Stage) window)
                .findFirst()
                .orElse(null));
    }

    /** Fires the account button as a guest and waits for the login dialog. */
    private static Stage openDialog() {
        FxTestSupport.runOnFx(() -> accountButton().fire());
        FxTestSupport.await("the login dialog", () -> dialog() != null);
        return dialog();
    }

    private static <T extends Node> T node(Stage dialog, String id) {
        @SuppressWarnings("unchecked")
        T found = (T) FxTestSupport.callOnFx(() -> dialog.getScene().lookup("#" + id));
        assertThat(found).as("node #" + id).isNotNull();
        return found;
    }

    private static String text(Stage dialog, String id) {
        return FxTestSupport.callOnFx(() -> ((Label) dialog.getScene().lookup("#" + id)).getText());
    }

    /** True when the node and every parent up to the scene root are visible (a hidden form hides its children). */
    private static boolean visible(Stage dialog, String id) {
        return FxTestSupport.callOnFx(() -> {
            for (Node current = dialog.getScene().lookup("#" + id); current != null; current = current.getParent()) {
                if (!current.isVisible()) {
                    return false;
                }
            }
            return true;
        });
    }

    private static void hideContextMenus() {
        FxTestSupport.runOnFx(() -> List.copyOf(Window.getWindows()).stream()
                .filter(window -> window instanceof ContextMenu)
                .forEach(Window::hide));
    }

    private static String fieldText(Stage dialog, String id) {
        return FxTestSupport.callOnFx(() -> ((TextField) dialog.getScene().lookup("#" + id)).getText());
    }

    private static void type(Stage dialog, String id, String value) {
        FxTestSupport.runOnFx(() -> ((TextField) dialog.getScene().lookup("#" + id)).setText(value));
    }

    private static void fire(Stage dialog, String id) {
        FxTestSupport.runOnFx(() -> ((Button) dialog.getScene().lookup("#" + id)).fire());
    }

    private static boolean isBusy(Stage dialog) {
        return FxTestSupport.callOnFx(() -> dialog.getScene().lookup("#loginSubmit").isDisabled());
    }

    /** The real login runs on a background thread; wait until the submit button is enabled again. */
    private static void awaitWorkDone(Stage dialog) {
        FxTestSupport.await("the login to finish", () -> !isBusy(dialog));
        FxTestSupport.flushFx();
    }

    private static void assertNoErrorDialog() {
        assertThat(FxTestSupport.showingWindows()).as("no general error dialog")
                .extracting(Window::getScene)
                .noneMatch(scene -> scene != null && scene.lookup("#errorDialog") != null);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void accountButton_forAGuest_isEnabledAndShowsLogin() {
        Button button = accountButton();

        assertThat(FxTestSupport.callOnFx(button::isDisabled)).isFalse();
        assertThat(button.getText()).isEqualTo("Đăng nhập");
        assertThat(dialog()).as("no dialog is showing before the button is fired").isNull();
    }

    @Test
    void firingTheAccountButton_opensTheLoginDialogWithEveryNode() {
        Stage dialog = openDialog();

        for (String id : DIALOG_IDS) {
            assertThat(FxTestSupport.callOnFx(() -> dialog.getScene().lookup("#" + id))).as("#" + id).isNotNull();
        }
        assertThat(text(dialog, "authTitle")).isEqualTo("Đăng nhập");
        assertThat(((Button) node(dialog, "loginSubmit")).getText()).isEqualTo("Đăng nhập");
        Node password = node(dialog, "loginPassword");
        assertThat(password).isInstanceOf(PasswordField.class);
        assertThat(dialog.getModality()).isEqualTo(Modality.APPLICATION_MODAL);
        assertThat(dialog.getOwner()).as("owned by the main window").isSameAs(mainStage);
        assertThat(visible(dialog, "loginError")).as("no error yet").isFalse();
        assertThat(visible(dialog, "authNotice")).as("no notice yet").isFalse();
        assertThat(visible(dialog, "regFullName")).as("the registration form is hidden").isFalse();
        assertNoErrorDialog();
    }

    @Test
    void wrongPassword_showsTheGenericSentenceClearsThePasswordAndKeepsTheEmail() {
        Stage dialog = openDialog();
        type(dialog, "loginEmail", DemoAccounts.USER_EMAIL);
        type(dialog, "loginPassword", "not-the-password-1");

        fire(dialog, "loginSubmit");
        awaitWorkDone(dialog);

        assertThat(visible(dialog, "loginError")).isTrue();
        assertThat(text(dialog, "loginError")).isEqualTo(WRONG_CREDENTIALS);
        assertThat(fieldText(dialog, "loginPassword")).isEmpty();
        assertThat(fieldText(dialog, "loginEmail")).isEqualTo(DemoAccounts.USER_EMAIL);
        assertThat(dialog()).as("the dialog stays open").isNotNull();
        assertNoErrorDialog();
    }

    @Test
    void emptyPassword_showsTheSameSentenceWithoutLoggingIn() {
        Stage dialog = openDialog();

        fire(dialog, "loginSubmit");
        FxTestSupport.flushFx();

        assertThat(text(dialog, "loginError")).isEqualTo(WRONG_CREDENTIALS);
        assertThat(FxTestSupport.callOnFx(() -> accountButton().getText())).isEqualTo("Đăng nhập");
        assertNoErrorDialog();
    }

    @Test
    void switchingToRegister_showsTheRegistrationFormAndAnEmptySubmitShowsTheFirstError() {
        Stage dialog = openDialog();

        fire(dialog, "toRegister");
        FxTestSupport.flushFx();

        assertThat(text(dialog, "authTitle")).isEqualTo("Đăng ký");
        assertThat(visible(dialog, "regFullName")).isTrue();
        assertThat(visible(dialog, "loginEmail")).isFalse();
        assertThat(visible(dialog, "loginError")).as("the old login error is gone").isFalse();

        fire(dialog, "regSubmit");
        FxTestSupport.flushFx();

        assertThat(text(dialog, "regFullNameError")).isEqualTo("Vui lòng nhập họ và tên.");
        assertThat(text(dialog, "regEmailError")).isEqualTo("Vui lòng nhập email.");
        assertThat(text(dialog, "regPasswordError")).isEqualTo("Mật khẩu phải có từ 8 đến 64 ký tự.");
        assertThat(fieldText(dialog, "regPassword")).isEmpty();
        assertThat(fieldText(dialog, "regConfirmPassword")).isEmpty();
        assertNoErrorDialog();

        fire(dialog, "toLogin");
        FxTestSupport.flushFx();
        assertThat(text(dialog, "authTitle")).isEqualTo("Đăng nhập");
    }

    @Test
    void registerMode_inTheMinimumMainWindow_fitsInsideTheMainWindowAndKeepsTheSubmitButtonVisible() {
        double originalWidth = FxTestSupport.callOnFx(mainStage::getWidth);
        double originalHeight = FxTestSupport.callOnFx(mainStage::getHeight);
        FxTestSupport.runOnFx(() -> {
            mainStage.setWidth(1024);
            mainStage.setHeight(700);
        });
        try {
            FxTestSupport.await("the main window to be 1024 x 700",
                    () -> FxTestSupport.callOnFx(() -> mainStage.getHeight() == 700 && mainStage.getWidth() == 1024));
            Stage dialog = openDialog();

            fire(dialog, "toRegister");
            settle(dialog);

            assertThat(visible(dialog, "regFullName")).isTrue();
            assertFitsInside(dialog, "after switching to register mode");

            fire(dialog, "regSubmit");
            settle(dialog);

            assertThat(text(dialog, "regFullNameError")).isEqualTo("Vui lòng nhập họ và tên.");
            assertFitsInside(dialog, "after an empty submit showed the errors");
            assertNoErrorDialog();
        } finally {
            FxTestSupport.runOnFx(() -> {
                mainStage.setWidth(originalWidth);
                mainStage.setHeight(originalHeight);
            });
        }
    }

    /** Waits until the dialog has stopped resizing (the stage is fitted to its content in a later pulse). */
    private static void settle(Stage dialog) {
        double last = -1;
        int stableRounds = 0;
        for (int round = 0; round < 100 && stableRounds < 3; round++) {
            FxTestSupport.flushFx();
            double height = FxTestSupport.callOnFx(dialog::getHeight);
            stableRounds = height == last ? stableRounds + 1 : 0;
            last = height;
        }
        assertThat(stableRounds).as("the dialog stopped resizing").isGreaterThanOrEqualTo(3);
    }

    private static void assertFitsInside(Stage dialog, String when) {
        double dialogHeight = FxTestSupport.callOnFx(dialog::getHeight);
        double mainHeight = FxTestSupport.callOnFx(mainStage::getHeight);
        assertThat(dialogHeight).as("dialog height %s (main window %.0f)", when, mainHeight)
                .isLessThanOrEqualTo(mainHeight);

        Bounds submit = FxTestSupport.callOnFx(() -> {
            Node button = dialog.getScene().lookup("#regSubmit");
            return button.localToScene(button.getBoundsInLocal());
        });
        double sceneHeight = FxTestSupport.callOnFx(() -> dialog.getScene().getHeight());
        double sceneWidth = FxTestSupport.callOnFx(() -> dialog.getScene().getWidth());
        assertThat(submit.getMinX()).as("#regSubmit left edge %s", when).isGreaterThanOrEqualTo(0);
        assertThat(submit.getMinY()).as("#regSubmit top edge %s", when).isGreaterThanOrEqualTo(0);
        assertThat(submit.getMaxX()).as("#regSubmit right edge %s", when).isLessThanOrEqualTo(sceneWidth);
        assertThat(submit.getMaxY()).as("#regSubmit bottom edge %s (scene height %.1f)", when, sceneHeight)
                .isLessThanOrEqualTo(sceneHeight);
    }

    @Test
    void closeButton_closesTheDialog_andReopeningStartsWithEmptyFields() {
        Stage dialog = openDialog();
        type(dialog, "loginEmail", "typed@example.com");

        fire(dialog, "authClose");
        FxTestSupport.flushFx();

        assertThat(dialog()).as("the dialog is closed").isNull();

        Stage reopened = openDialog();
        assertThat(fieldText(reopened, "loginEmail")).isEmpty();
        assertThat(fieldText(reopened, "loginPassword")).isEmpty();
        assertThat(visible(reopened, "loginError")).isFalse();
        assertThat(text(reopened, "authTitle")).isEqualTo("Đăng nhập");
    }

    @Test
    void correctPassword_closesTheDialogAndTheAccountButtonShowsTheName() {
        Stage dialog = openDialog();
        type(dialog, "loginEmail", "  User@MiniShop.LOCAL ");
        type(dialog, "loginPassword", StandaloneApp.DEV_USER_PASSWORD);

        fire(dialog, "loginSubmit");
        FxTestSupport.await("the account button to show the name",
                () -> FxTestSupport.callOnFx(() -> accountButton().getText()).equals("Khách hàng mẫu"));
        FxTestSupport.await("the dialog to close", () -> dialog() == null);

        ConfigurableApplicationContext context = app.context();
        assertThat(context.getBean(AppSession.class).currentUser())
                .hasValueSatisfying(user -> {
                    assertThat(user.email()).isEqualTo(DemoAccounts.USER_EMAIL);
                    assertThat(user.role()).isEqualTo(Role.USER);
                });
        assertThat(FxTestSupport.callOnFx(() -> accountButton().isDisabled())).isFalse();
        assertNoErrorDialog();
    }

    @Test
    void afterTheLogin_firingTheAccountButtonDoesNotOpenTheLoginDialogAgain() {
        User user = app.context().getBean(UserRepository.class).findByEmail(DemoAccounts.USER_EMAIL).orElseThrow();
        CurrentUser current = new CurrentUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
        FxTestSupport.runOnFx(() -> app.context().getBean(AppSession.class).signIn(current));

        FxTestSupport.runOnFx(() -> accountButton().fire());
        FxTestSupport.flushFx();

        assertThat(dialog()).isNull();
    }
}

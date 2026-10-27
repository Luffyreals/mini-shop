package com.minishop.catalog;

import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Nguồn ảnh" entry in the home screen's footer on the real JavaFX toolkit (QĐ11 of the D6 plan): the real app
 * with an in-memory H2 database, so the link, the {@code CreditsDialog} bean and the focus hand-back are the ones a
 * user gets. No robot: the link gets focus first and is then fired (a mouse click would focus it first), and Esc is a
 * plain key event sent to the dialog's button.
 */
@Tag("ui")
class HomeCreditsSmokeTest {

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (app != null && app.context() != null && app.context().isActive()) {
            FxToolkit.cleanupApplication((Application) app);
        }
        FxTestSupport.hideAllWindows();
    }

    @AfterEach
    void backToTheHomeScreen() {
        ui.resetToHome();
    }

    // ------------------------------------------------------------------ helpers

    private static Node creditsLink() {
        // The footer is hidden until the home data has loaded, and a hidden node cannot take the focus: waiting for
        // the node to exist is not enough (focusing it early is a silent no-op, so the dialog would remember the
        // top-bar button as the opener and give the focus back there).
        FxTestSupport.await("the credits link in the home footer to be shown",
                () -> UiDriver.shown(ui.inContent("creditsLink")));
        return ui.inContentRequired("creditsLink");
    }

    private static void focusAndFire(Node link) {
        FxTestSupport.runOnFx(() -> {
            link.requestFocus();
            assertThat(link.getScene().getFocusOwner()).as("the link took the focus").isSameAs(link);
            ((Button) link).fire();
        });
    }

    private static Window awaitCreditsDialog() {
        FxTestSupport.await("the credits dialog", () -> ui.windowWith("creditsDialog") != null);
        return ui.windowWith("creditsDialog");
    }

    private static Node focusOwner() {
        return FxTestSupport.callOnFx(() -> mainStage.getScene().getFocusOwner());
    }

    // ------------------------------------------------------------------ the link

    @Test
    void homeFooter_hasTheCreditsLink_withItsTextAndAccessibleText() {
        Node link = creditsLink();

        assertThat(ui.inContent("homeFooter")).isNotNull();
        assertThat(ui.text(link)).isEqualTo("Nguồn ảnh");
        assertThat(FxTestSupport.<String>callOnFx(link::getAccessibleText)).isEqualTo("Nguồn ảnh và giấy phép");
        assertThat(FxTestSupport.<Boolean>callOnFx(link::isFocusTraversable)).isTrue();
        assertThat(UiDriver.shown(link)).isTrue();
    }

    @Test
    void creditsLink_whenFired_opensTheCreditsDialogOwnedByTheMainWindow() {
        focusAndFire(creditsLink());

        Window dialog = awaitCreditsDialog();
        assertThat(ui.inWindow(dialog, "creditsTitle")).isNotNull();
        assertThat(ui.text(ui.inWindow(dialog, "creditsTitle"))).isEqualTo("Nguồn ảnh và giấy phép");
        assertThat(FxTestSupport.<Node>callOnFx(() -> dialog.getScene().getFocusOwner()))
                .as("focus starts on Đóng").isSameAs(ui.inWindow(dialog, "creditsClose"));
        ui.assertNoErrorDialog();
    }

    @Test
    void creditsLink_firedTwice_keepsOneDialog() {
        Node link = creditsLink();
        focusAndFire(link);
        awaitCreditsDialog();

        FxTestSupport.runOnFx(((Button) link)::fire);
        FxTestSupport.flushFx();

        long dialogs = FxTestSupport.showingWindows().stream()
                .filter(window -> window.getScene() != null && window.getScene().lookup("#creditsDialog") != null)
                .count();
        assertThat(dialogs).isEqualTo(1);
    }

    // ------------------------------------------------------------------ closing

    @Test
    void escape_inTheCreditsDialog_closesItAndPutsTheFocusBackOnTheCreditsLink() {
        Node link = creditsLink();
        focusAndFire(link);
        Window dialog = awaitCreditsDialog();

        ui.pressKey(ui.inWindow(dialog, "creditsClose"), KeyCode.ESCAPE);

        FxTestSupport.await("the credits dialog to close", () -> ui.windowWith("creditsDialog") == null);
        FxTestSupport.await("the focus to return to the credits link", () -> focusOwner() == link);
        assertThat(focusOwner()).isSameAs(link);
    }

    @Test
    void closeButton_inTheCreditsDialog_closesItAndPutsTheFocusBackOnTheCreditsLink() {
        Node link = creditsLink();
        focusAndFire(link);
        Window dialog = awaitCreditsDialog();

        ui.fireIn(dialog, "creditsClose");

        FxTestSupport.await("the credits dialog to close", () -> ui.windowWith("creditsDialog") == null);
        FxTestSupport.await("the focus to return to the credits link", () -> focusOwner() == link);
        assertThat(focusOwner()).isSameAs(link);
    }
}

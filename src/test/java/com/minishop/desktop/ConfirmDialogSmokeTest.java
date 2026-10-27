package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import javafx.application.Application;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared confirm dialog on the real JavaFX toolkit (QĐ11 of the D4 plan): the real app is started (so the dialog
 * gets its themed, owned, application-modal window from {@link ModalDialogs}); the tests open the dialog through the
 * {@link ConfirmDialog} bean and drive it by firing its buttons and pressing keys on them. No robot.
 *
 * <p>It checks the promises that make it safe for destructive actions: the "keep" button has the focus, Esc and the
 * "keep" button close without calling anything, and the confirm button calls {@code onConfirm} exactly once.
 */
@Tag("ui")
class ConfirmDialogSmokeTest {

    private static final String MESSAGE = "Bạn có chắc muốn hủy đơn hàng này?";
    private static final String CONFIRM = "Hủy đơn hàng";
    private static final String DISMISS = "Giữ đơn hàng";

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

    @AfterEach
    void closeStrayDialogs() {
        FxTestSupport.runOnFx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != mainStage && window.isShowing()) {
                    window.hide();
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Stage open(AtomicInteger confirmed) {
        ConfirmDialog dialog = app.context().getBean(ConfirmDialog.class);
        Stage stage = FxTestSupport.callOnFx(() -> dialog.show(MESSAGE, CONFIRM, DISMISS, confirmed::incrementAndGet));
        FxTestSupport.flushFx();
        return stage;
    }

    private static Node node(Stage stage, String id) {
        Node node = FxTestSupport.callOnFx(() -> stage.getScene().lookup("#" + id));
        assertThat(node).as("node #" + id).isNotNull();
        return node;
    }

    private static void fire(Stage stage, String id) {
        FxTestSupport.runOnFx(() -> ((Button) node(stage, id)).fire());
    }

    private static void press(Node target, KeyCode code) {
        FxTestSupport.runOnFx(() -> Event.fireEvent(target,
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private static boolean showing(Stage stage) {
        return FxTestSupport.callOnFx(stage::isShowing);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void ask_showsTheQuestionAndBothButtonsWithTheGivenLabels_andFocusesTheDismissButton() {
        AtomicInteger confirmed = new AtomicInteger();

        Stage stage = open(confirmed);

        assertThat(showing(stage)).isTrue();
        assertThat(node(stage, "confirmDialog")).isNotNull();
        assertThat(((Label) node(stage, "confirmMessage")).getText()).isEqualTo(MESSAGE);
        assertThat(((Button) node(stage, "confirmAccept")).getText()).isEqualTo(CONFIRM);
        assertThat(((Button) node(stage, "confirmDismiss")).getText()).isEqualTo(DISMISS);
        assertThat(FxTestSupport.callOnFx(() -> stage.getScene().getFocusOwner()))
                .as("the safe button has the focus").isSameAs(node(stage, "confirmDismiss"));
        assertThat(confirmed.get()).isZero();
    }

    @Test
    void escape_closesTheDialogWithoutCallingOnConfirm() {
        AtomicInteger confirmed = new AtomicInteger();
        Stage stage = open(confirmed);

        press(node(stage, "confirmDismiss"), KeyCode.ESCAPE);

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        assertThat(confirmed.get()).isZero();
    }

    @Test
    void dismissButton_closesTheDialogWithoutCallingOnConfirm() {
        AtomicInteger confirmed = new AtomicInteger();
        Stage stage = open(confirmed);

        fire(stage, "confirmDismiss");

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        assertThat(confirmed.get()).isZero();
    }

    @Test
    void acceptButton_closesTheDialogAndCallsOnConfirmExactlyOnce() {
        AtomicInteger confirmed = new AtomicInteger();
        Stage stage = open(confirmed);

        fire(stage, "confirmAccept");
        // A second click on the (closing) button must not repeat the action
        FxTestSupport.runOnFx(() -> ((Button) stage.getScene().lookup("#confirmAccept")).fire());

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        assertThat(confirmed.get()).isEqualTo(1);
    }

    @Test
    void enter_onTheFocusedDismissButton_doesNotConfirm() {
        AtomicInteger confirmed = new AtomicInteger();
        Stage stage = open(confirmed);

        press(node(stage, "confirmDismiss"), KeyCode.ENTER);

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        assertThat(confirmed.get()).as("Enter never confirms for the user while the safe button has focus").isZero();
    }

    @Test
    void enter_onTheAcceptButton_confirmsOnce() {
        AtomicInteger confirmed = new AtomicInteger();
        Stage stage = open(confirmed);

        press(node(stage, "confirmAccept"), KeyCode.ENTER);

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        assertThat(confirmed.get()).isEqualTo(1);
    }

    @Test
    void closingTheWindowFromOutside_doesNotCallOnConfirm() {
        AtomicInteger confirmed = new AtomicInteger();
        Stage stage = open(confirmed);

        FxTestSupport.runOnFx(stage::hide);

        assertThat(showing(stage)).isFalse();
        assertThat(confirmed.get()).isZero();
    }
}

package com.minishop.desktop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.support.FxTestSupport;
import com.minishop.support.LogCapture;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorDialog} on the real JavaFX toolkit (AC-009-12, QĐ6, QĐ11): the dialog shows the message with the
 * close button focused, runs {@code onClosed} once, and an exception thrown on the FX thread reaches it through the
 * app's {@link UncaughtErrorHandler}. Nothing here uses a robot: the dialog is closed by firing its button.
 */
@Tag("ui")
class ErrorDialogSmokeTest {

    private static Stage mainStage;

    private Thread.UncaughtExceptionHandler previousHandler;

    @BeforeAll
    static void startToolkit() {
        mainStage = FxTestSupport.startToolkit();
    }

    @AfterAll
    static void closeWindows() {
        FxTestSupport.hideAllWindows();
    }

    @BeforeEach
    void showMainStage() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        FxTestSupport.runOnFx(() -> {
            mainStage.setScene(new Scene(new StackPane(new Label("main")), 400, 300));
            mainStage.setTitle("main");
            mainStage.show();
        });
    }

    @AfterEach
    void restore() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        FxTestSupport.hideAllWindows();
    }

    /** The showing dialog stages (those whose scene has {@code #errorDialog}). */
    private static List<Stage> dialogs() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> window.getScene() != null && window.getScene().lookup("#errorDialog") != null)
                .map(window -> (Stage) window)
                .toList());
    }

    private static String textOf(Stage dialog) {
        return FxTestSupport.callOnFx(() -> ((Label) dialog.getScene().lookup("#errorMessage")).getText());
    }

    private static void closeByButton(Stage dialog) {
        FxTestSupport.runOnFx(() -> ((Button) dialog.getScene().lookup("#errorCloseButton")).fire());
    }

    @Test
    void show_displaysTheMessageWithTheCloseButtonFocused() {
        Stage dialog = FxTestSupport.callOnFx(() -> ErrorDialog.show("Mini Shop đang chạy ở một cửa sổ khác.", null));

        assertThat(dialogs()).containsExactly(dialog);
        assertThat(textOf(dialog)).isEqualTo("Mini Shop đang chạy ở một cửa sổ khác.");
        FxTestSupport.runOnFx(() -> {
            Scene scene = dialog.getScene();
            Button close = (Button) scene.lookup("#errorCloseButton");
            assertThat(close.getText()).isEqualTo(ErrorDialog.CLOSE_LABEL);
            assertThat(scene.getFocusOwner()).as("the close button has the focus").isSameAs(close);
            assertThat(dialog.getTitle()).isEqualTo(MiniShopApp.TITLE);
            assertThat(dialog.getOwner()).as("centred on the window that was open").isSameAs(mainStage);
            assertThat(scene.getStylesheets()).anyMatch(css -> css.endsWith(Theme.STYLESHEET));
        });
    }

    @Test
    void show_whenTheCloseButtonIsFired_hidesTheDialogAndRunsOnClosedOnce() {
        AtomicInteger closed = new AtomicInteger();
        Stage dialog = FxTestSupport.callOnFx(() -> ErrorDialog.show("Lỗi", closed::incrementAndGet));

        closeByButton(dialog);
        FxTestSupport.runOnFx(dialog::hide);
        FxTestSupport.runOnFx(dialog::close);

        assertThat(dialog.isShowing()).isFalse();
        assertThat(closed).as("onClosed runs once however often the stage is hidden").hasValue(1);
        assertThat(mainStage.isShowing()).as("the main window stays").isTrue();
    }

    @Test
    void show_whenTheStageIsHiddenDirectly_stillRunsOnClosed() {
        AtomicInteger closed = new AtomicInteger();
        Stage dialog = FxTestSupport.callOnFx(() -> ErrorDialog.show("Lỗi", closed::incrementAndGet));

        FxTestSupport.runOnFx(dialog::hide);

        assertThat(closed).hasValue(1);
    }

    @Test
    void uncaughtExceptionOnTheFxThread_showsTheGenericErrorDialogAndKeepsTheMainWindow() {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtErrorHandler(new ErrorReporter(ErrorDialog::showLater)));
        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(ErrorReporter.class))) {
            Platform.runLater(() -> {
                throw new IllegalStateException("something nobody expected");
            });
            FxTestSupport.await("the error dialog", () -> dialogs().size() == 1);

            Stage dialog = dialogs().getFirst();
            assertThat(textOf(dialog)).isEqualTo(ErrorReporter.MESSAGE).isEqualTo("Đã có lỗi xảy ra. Vui lòng thử lại.");
            assertThat(mainStage.isShowing()).as("the main window stays open").isTrue();

            List<ILoggingEvent> errors = log.events(Level.ERROR);
            assertThat(errors).as("one ERROR event per error").hasSize(1);
            assertThat(errors.getFirst().getFormattedMessage()).contains("JavaFX Application Thread");

            closeByButton(dialog);
            assertThat(dialogs()).isEmpty();
        }
    }

    @Test
    void uncaughtExceptions_whileADialogIsOpen_showOnlyOneDialogButLogEach() {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtErrorHandler(new ErrorReporter(ErrorDialog::showLater)));
        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(ErrorReporter.class))) {
            for (int i = 0; i < 3; i++) {
                int number = i;
                Platform.runLater(() -> {
                    throw new IllegalStateException("error " + number);
                });
            }
            FxTestSupport.await("the error dialog", () -> dialogs().size() == 1);
            FxTestSupport.await("three log events", () -> log.events(Level.ERROR).size() == 3);
            FxTestSupport.flushFx();

            assertThat(dialogs()).hasSize(1);
            closeByButton(dialogs().getFirst());
        }
    }

    @Test
    void uncaughtException_afterTheDialogWasClosed_showsANewDialog() {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtErrorHandler(new ErrorReporter(ErrorDialog::showLater)));
        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(ErrorReporter.class))) {
            Platform.runLater(() -> {
                throw new IllegalStateException("first");
            });
            FxTestSupport.await("the first dialog", () -> dialogs().size() == 1);
            closeByButton(dialogs().getFirst());
            FxTestSupport.await("the first dialog to close", () -> dialogs().isEmpty());

            Platform.runLater(() -> {
                throw new IllegalStateException("second");
            });
            FxTestSupport.await("the second dialog", () -> dialogs().size() == 1);

            assertThat(log.events(Level.ERROR)).hasSize(2);
        }
    }
}

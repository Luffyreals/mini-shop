package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link LoadError} (QĐ12 of the D6 plan) on the real JavaFX toolkit: the block's ids and text, and that its button
 * runs the retry action exactly once per press. No window is needed, only the toolkit to build controls.
 */
@Tag("ui")
class LoadErrorSmokeTest {

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    @Test
    void create_buildsTheBlockWithIdsFromThePrefix() {
        VBox block = FxTestSupport.callOnFx(() -> LoadError.create("orders", () -> { }));

        assertThat(block.getId()).isEqualTo("ordersLoadError");
        assertThat(block.getStyleClass()).contains("empty-state", "load-error");
        Button retry = FxTestSupport.callOnFx(() -> LoadError.retryButton(block));
        assertThat(retry.getId()).isEqualTo("ordersRetry");
    }

    @Test
    void create_showsTheSameSentenceAsTheErrorDialogAndARetryButton() {
        VBox block = FxTestSupport.callOnFx(() -> LoadError.create("adminOrders", () -> { }));

        Label message = (Label) block.getChildren().get(0);
        assertThat(message.getText()).isEqualTo(ErrorReporter.MESSAGE).isEqualTo("Đã có lỗi xảy ra. Vui lòng thử lại.");
        assertThat(message.isWrapText()).isTrue();
        Button retry = FxTestSupport.callOnFx(() -> LoadError.retryButton(block));
        assertThat(retry.getText()).isEqualTo(LoadError.RETRY_TEXT).isEqualTo("Thử lại");
        assertThat(retry.getId()).isEqualTo("adminOrdersRetry");
    }

    @Test
    void retryButton_fire_callsOnRetryExactlyOncePerPress() {
        AtomicInteger calls = new AtomicInteger();
        VBox block = FxTestSupport.callOnFx(() -> LoadError.create("orders", calls::incrementAndGet));
        Button retry = FxTestSupport.callOnFx(() -> LoadError.retryButton(block));

        assertThat(calls.get()).as("nothing runs before the press").isZero();
        FxTestSupport.runOnFx(retry::fire);
        assertThat(calls.get()).isEqualTo(1);
        FxTestSupport.runOnFx(retry::fire);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retryButton_enter_callsOnRetryOnce_andIsConsumed() {
        AtomicInteger calls = new AtomicInteger();
        VBox block = FxTestSupport.callOnFx(() -> LoadError.create("orders", calls::incrementAndGet));
        Button retry = FxTestSupport.callOnFx(() -> LoadError.retryButton(block));
        KeyEvent enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
        KeyEvent tab = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false);

        AtomicInteger reachedTheBlock = new AtomicInteger();
        FxTestSupport.runOnFx(() -> block.addEventHandler(KeyEvent.KEY_PRESSED,
                event -> reachedTheBlock.incrementAndGet()));

        FxTestSupport.runOnFx(() -> Event.fireEvent(retry, enter));
        assertThat(calls.get()).as("Enter presses the focused button, like Space").isEqualTo(1);
        assertThat(reachedTheBlock.get()).as("Enter is consumed, it does not bubble to the screen").isZero();

        FxTestSupport.runOnFx(() -> Event.fireEvent(retry, tab));
        assertThat(calls.get()).as("other keys do nothing").isEqualTo(1);
        assertThat(reachedTheBlock.get()).as("other keys bubble on as before").isEqualTo(1);
    }

    @Test
    void retryButton_ofAnUnrelatedBox_isRejected() {
        VBox other = FxTestSupport.callOnFx(() -> new VBox(new Label("x")));

        assertThatIllegalArgumentException().isThrownBy(() -> LoadError.retryButton(other));
    }

    @Test
    void create_rejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> LoadError.create(null, () -> { }));
        assertThatNullPointerException().isThrownBy(() -> LoadError.create("orders", null));
        assertThatNullPointerException().isThrownBy(() -> LoadError.retryButton(null));
    }
}

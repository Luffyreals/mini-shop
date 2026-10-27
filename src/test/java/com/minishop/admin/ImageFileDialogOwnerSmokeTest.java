package com.minishop.admin;

import com.minishop.support.FxTestSupport;
import javafx.scene.Scene;
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

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ImageFileDialog.pickOwner} on the real JavaFX toolkit (QĐ6 of D5): the owner of the OS file chooser is a
 * showing {@link Stage}, never a {@code ContextMenu} or another popup that happens to be open. The method is private
 * (the real chooser cannot be driven without a robot), so the test reaches it by reflection; no
 * {@code FileChooser} is ever opened. Which stage wins when several are showing depends on window focus, which a
 * headless run cannot control, so only "a showing Stage, not a popup" is asserted.
 */
@Tag("ui")
class ImageFileDialogOwnerSmokeTest {

    private static Stage mainStage;
    private static Method pickOwner;

    @BeforeAll
    static void startToolkit() throws NoSuchMethodException {
        mainStage = FxTestSupport.startToolkit();
        pickOwner = ImageFileDialog.class.getDeclaredMethod("pickOwner");
        pickOwner.setAccessible(true);
    }

    @AfterAll
    static void closeWindows() {
        FxTestSupport.hideAllWindows();
    }

    @AfterEach
    void hideEverything() {
        FxTestSupport.hideAllWindows();
    }

    private static Window pickOwner() {
        return FxTestSupport.callOnFx(() -> (Window) pickOwner.invoke(null));
    }

    private static StackPane showMainStage() {
        StackPane root = new StackPane(new Label("main"));
        FxTestSupport.runOnFx(() -> {
            mainStage.setScene(new Scene(root, 400, 300));
            mainStage.show();
        });
        return root;
    }

    @Test
    void pickOwner_withAnOpenContextMenu_returnsAShowingStage_notThePopup() {
        StackPane root = showMainStage();
        ContextMenu menu = FxTestSupport.callOnFx(() -> {
            ContextMenu popup = new ContextMenu(new MenuItem("Một mục"));
            popup.show(root, 100, 100);
            return popup;
        });
        FxTestSupport.await("the context menu to show", () -> FxTestSupport.callOnFx(menu::isShowing));
        assertThat(FxTestSupport.showingWindows()).contains(menu);

        Window owner = pickOwner();

        assertThat(owner).isInstanceOf(Stage.class).isNotInstanceOf(ContextMenu.class);
        assertThat(FxTestSupport.callOnFx(owner::isShowing)).isTrue();
    }

    @Test
    void pickOwner_withOneShowingStage_returnsThatStage() {
        showMainStage();

        assertThat(pickOwner()).isSameAs(mainStage);
    }

    @Test
    void pickOwner_withNoShowingStage_returnsNull_soTheChooserHasNoOwner() {
        showMainStage();
        FxTestSupport.hideAllWindows();

        assertThat(pickOwner()).isNull();
    }
}

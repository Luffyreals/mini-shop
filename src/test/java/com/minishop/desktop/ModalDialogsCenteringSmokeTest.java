package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Dialogs open centred on their owner window, not on the screen (QĐ14 of the D6 plan), and stay inside the visible
 * part of the screen. The dialog is built with {@link ModalDialogs#create} around a plain card of a known size; the
 * owner is a plain stage placed relative to the primary screen's visible bounds, so the test works on any screen of
 * about 1024x600 or more. No robot.
 */
@Tag("ui")
class ModalDialogsCenteringSmokeTest {

    private static final double TOLERANCE = 1.0;

    private Rectangle2D screen;
    private Stage owner;
    private Stage dialog;
    private Region card;

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    @BeforeEach
    void readScreen() {
        FxTestSupport.hideAllWindows();
        screen = FxTestSupport.callOnFx(() -> Screen.getPrimary().getVisualBounds());
    }

    @AfterEach
    void closeWindows() {
        FxTestSupport.hideAllWindows();
    }

    // ------------------------------------------------------------------ helpers

    private void showOwner(double x, double y, double sceneWidth, double sceneHeight) {
        FxTestSupport.runOnFx(() -> {
            owner = new Stage();
            owner.setScene(new Scene(new VBox(), sceneWidth, sceneHeight));
            owner.setX(x);
            owner.setY(y);
            owner.show();
        });
        FxTestSupport.flushFx();
    }

    private void showDialog(double cardWidth, double cardHeight) {
        FxTestSupport.runOnFx(() -> {
            card = new VBox();
            card.getStyleClass().addAll("card", "dialog-card");
            card.setMinSize(cardWidth, cardHeight);
            card.setPrefSize(cardWidth, cardHeight);
            card.setMaxSize(cardWidth, cardHeight);
            dialog = ModalDialogs.create(card);
            dialog.show();
            dialog.sizeToScene();
        });
        FxTestSupport.flushFx();
    }

    private double dialogCentreX() {
        return FxTestSupport.callOnFx(() -> dialog.getX() + dialog.getWidth() / 2);
    }

    private double dialogCentreY() {
        return FxTestSupport.callOnFx(() -> dialog.getY() + dialog.getHeight() / 2);
    }

    private double ownerCentreX() {
        return FxTestSupport.callOnFx(() -> owner.getX() + owner.getWidth() / 2);
    }

    private double ownerCentreY() {
        return FxTestSupport.callOnFx(() -> owner.getY() + owner.getHeight() / 2);
    }

    private void assertCentredOnOwner() {
        assertThat(dialogCentreX()).as("dialog centre x").isCloseTo(ownerCentreX(), within(TOLERANCE));
        assertThat(dialogCentreY()).as("dialog centre y").isCloseTo(ownerCentreY(), within(TOLERANCE));
    }

    private void assertInsideVisibleBounds() {
        double x = FxTestSupport.callOnFx(() -> dialog.getX());
        double y = FxTestSupport.callOnFx(() -> dialog.getY());
        double width = FxTestSupport.callOnFx(() -> dialog.getWidth());
        double height = FxTestSupport.callOnFx(() -> dialog.getHeight());
        assertThat(x).as("left edge").isGreaterThanOrEqualTo(screen.getMinX() - TOLERANCE);
        assertThat(y).as("top edge").isGreaterThanOrEqualTo(screen.getMinY() - TOLERANCE);
        assertThat(x + width).as("right edge").isLessThanOrEqualTo(screen.getMaxX() + TOLERANCE);
        assertThat(y + height).as("bottom edge").isLessThanOrEqualTo(screen.getMaxY() + TOLERANCE);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void show_ownerNotInTheScreenCentre_dialogCentreEqualsOwnerCentre() {
        showOwner(screen.getMinX() + 70, screen.getMinY() + 30, 640, 400);

        showDialog(360, 180);

        assertCentredOnOwner();
        assertInsideVisibleBounds();
    }

    @Test
    void show_afterTheOwnerMoved_dialogFollowsTheOwnerAtThatMoment() {
        showOwner(screen.getMinX() + 40, screen.getMinY() + 20, 640, 400);
        FxTestSupport.runOnFx(() -> {
            owner.setX(screen.getMinX() + 130);
            owner.setY(screen.getMinY() + 60);
        });
        FxTestSupport.flushFx();

        showDialog(360, 180);

        assertCentredOnOwner();
    }

    @Test
    void sizeToScene_afterTheContentGrows_dialogIsStillCentred() {
        showOwner(screen.getMinX() + 70, screen.getMinY() + 30, 640, 400);
        showDialog(360, 160);
        double heightBefore = FxTestSupport.callOnFx(() -> dialog.getHeight());

        FxTestSupport.runOnFx(() -> {
            card.setMinHeight(280);
            card.setPrefHeight(280);
            card.setMaxHeight(280);
            dialog.sizeToScene();
        });
        FxTestSupport.await("the dialog to grow", () -> FxTestSupport.callOnFx(() -> dialog.getHeight()) > heightBefore);
        FxTestSupport.flushFx();

        assertCentredOnOwner();
        assertInsideVisibleBounds();
    }

    @Test
    void show_ownerAtTheTopLeftEdge_dialogBiggerThanTheOwnerStaysInsideTheVisibleBounds() {
        showOwner(screen.getMinX(), screen.getMinY(), 400, 260);

        showDialog(640, 400);

        assertInsideVisibleBounds();
    }

    @Test
    void show_ownerAtTheBottomRightEdge_dialogBiggerThanTheOwnerStaysInsideTheVisibleBounds() {
        showOwner(screen.getMinX(), screen.getMinY(), 400, 260);
        FxTestSupport.runOnFx(() -> {
            owner.setX(screen.getMaxX() - owner.getWidth());
            owner.setY(screen.getMaxY() - owner.getHeight());
        });
        FxTestSupport.flushFx();

        showDialog(640, 400);

        assertInsideVisibleBounds();
    }

    @Test
    void sizeToScene_ownerAtTheBottomEdge_growingDialogIsPushedBackInsideTheVisibleBounds() {
        showOwner(screen.getMinX() + 200, screen.getMinY(), 500, 260);
        FxTestSupport.runOnFx(() -> owner.setY(screen.getMaxY() - owner.getHeight()));
        FxTestSupport.flushFx();
        showDialog(400, 120);

        FxTestSupport.runOnFx(() -> {
            card.setMinHeight(420);
            card.setPrefHeight(420);
            card.setMaxHeight(420);
            dialog.sizeToScene();
        });
        FxTestSupport.flushFx();

        assertInsideVisibleBounds();
    }
}

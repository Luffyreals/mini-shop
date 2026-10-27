package com.minishop.catalog;

import com.minishop.desktop.ImageLoader;
import com.minishop.support.FxTestSupport;
import com.minishop.support.ManualBackgroundTasks;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The click rules of {@link CatalogScreen.ProductCard} (D3 review fixes): a click opens the detail page only when it
 * is a primary click, it did not land on the add button or inside it, and the product is not busy. Real nodes and
 * plain {@link MouseEvent} objects with a {@link PickResult}; no robot, no window. The toolkit is needed to build
 * controls.
 */
@Tag("ui")
class CatalogScreenTest {

    private static final long ID = 7;

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
    }

    /** A primary {@code MOUSE_CLICKED} whose pick target is {@code target}; shared with {@link StorefrontSmokeTest}. */
    static MouseEvent clickOn(Node target) {
        return click(target, MouseButton.PRIMARY);
    }

    static MouseEvent click(Node target, MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, button, 1, false, false, false, false,
                button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY, false, false, true,
                new PickResult(target, 5, 5));
    }

    private static final class Fixture {
        final ObservableSet<Long> busy = FXCollections.observableSet();
        final List<Long> opened = new ArrayList<>();
        final List<Long> added = new ArrayList<>();
        final CatalogScreen.ProductCard card;

        Fixture(boolean inStock) {
            ImageLoader images = new ImageLoader(ManualBackgroundTasks.honoringCancel());
            ProductCardView view = new ProductCardView(ID, "Áo thun", "Thời trang", new BigDecimal("250000"), inStock,
                    null);
            card = new CatalogScreen.ProductCard(view, images, busy, opened::add, added::add);
            // A scene and one CSS pass give the button its skin, so it has child nodes (its text)
            new Scene(card, 300, 400).getRoot().applyCss();
        }

        Button addButton() {
            return (Button) card.lookup("#addButton-" + ID);
        }

        Node name() {
            return card.lookup(".product-card-name");
        }

        Node image() {
            return card.getChildren().get(0);
        }
    }

    // ------------------------------------------------------------------ isInsideButton

    @Test
    void isInsideButton_whenThePickTargetIsTheAddButton_isTrue() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            assertThat(CatalogScreen.isInsideButton(clickOn(f.addButton()), f.card)).isTrue();
        });
    }

    @Test
    void isInsideButton_whenThePickTargetIsAChildOfTheAddButton_isTrue() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);
            Node text = f.addButton().lookup(".text");
            assertThat(text).as("the button's text node").isNotNull().isNotSameAs(f.addButton());

            assertThat(CatalogScreen.isInsideButton(clickOn(text), f.card)).isTrue();
        });
    }

    @Test
    void isInsideButton_whenThePickTargetIsTheNameImageOrTheCardItself_isFalse() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);
            ImageView imageView = (ImageView) ((Parent) f.image()).getChildrenUnmodifiable().stream()
                    .filter(ImageView.class::isInstance).findFirst().orElseThrow();

            assertThat(CatalogScreen.isInsideButton(clickOn(f.name()), f.card)).isFalse();
            assertThat(CatalogScreen.isInsideButton(clickOn(f.image()), f.card)).isFalse();
            assertThat(CatalogScreen.isInsideButton(clickOn(imageView), f.card)).isFalse();
            assertThat(CatalogScreen.isInsideButton(clickOn(f.card), f.card)).isFalse();
        });
    }

    @Test
    void isInsideButton_forAPickTargetOutsideTheBoundary_stopsAtTheBoundary() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            // The card is the boundary, so a button above it is not looked at
            assertThat(CatalogScreen.isInsideButton(clickOn(f.name()), f.name())).isFalse();
            assertThat(CatalogScreen.isInsideButton(clickOn(new Pane()), f.card)).isFalse();
        });
    }

    // ------------------------------------------------------------------ the card's click handler

    @Test
    void cardClick_onTheName_opensTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            Event.fireEvent(f.card, clickOn(f.name()));

            assertThat(f.opened).containsExactly(ID);
            assertThat(f.added).isEmpty();
        });
    }

    @Test
    void cardClick_onTheImage_opensTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            Event.fireEvent(f.card, clickOn(f.image()));

            assertThat(f.opened).containsExactly(ID);
        });
    }

    @Test
    void cardClick_onTheAddButton_doesNotOpenTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            Event.fireEvent(f.card, clickOn(f.addButton()));

            assertThat(f.opened).isEmpty();
        });
    }

    @Test
    void cardClick_onTheBusyDisabledAddButton_doesNotOpenTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);
            f.busy.add(ID);
            assertThat(f.addButton().isDisabled()).as("busy locks the button").isTrue();

            // A disabled button does not run its own handler, so the click reaches the card: the guard stops it
            Event.fireEvent(f.card, clickOn(f.addButton()));

            assertThat(f.opened).isEmpty();
            assertThat(f.added).isEmpty();
        });
    }

    @Test
    void cardClick_whileTheProductIsBusy_doesNotOpenTheDetailPageEvenOnTheName() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);
            f.busy.add(ID);

            Event.fireEvent(f.card, clickOn(f.name()));

            assertThat(f.opened).isEmpty();

            f.busy.remove(ID);
            Event.fireEvent(f.card, clickOn(f.name()));

            assertThat(f.opened).containsExactly(ID);
        });
    }

    @Test
    void cardClick_withTheSecondaryButton_doesNotOpenTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            Event.fireEvent(f.card, click(f.name(), MouseButton.SECONDARY));

            assertThat(f.opened).isEmpty();
        });
    }

    @Test
    void cardClick_onASoldOutCardName_opensTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(false);
            assertThat(f.addButton()).as("sold out has no add button").isNull();

            Event.fireEvent(f.card, clickOn(f.name()));

            assertThat(f.opened).containsExactly(ID);
        });
    }

    @Test
    void addButtonAction_callsOnAddOnceAndDoesNotOpenTheDetailPage() {
        FxTestSupport.runOnFx(() -> {
            Fixture f = new Fixture(true);

            f.addButton().fire();

            assertThat(f.added).containsExactly(ID);
            assertThat(f.opened).isEmpty();
        });
    }
}

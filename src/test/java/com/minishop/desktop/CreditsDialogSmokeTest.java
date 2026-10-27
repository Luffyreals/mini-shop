package com.minishop.desktop;

import com.minishop.support.FxTestSupport;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Nguồn ảnh và giấy phép" dialog on the real JavaFX toolkit (QĐ11 of the D6 plan). The dialog is built directly
 * with a recording {@link ExternalLinks}, so no Spring context starts and no browser can open; the owner is a plain
 * stage with one button standing in for the home screen's "Nguồn ảnh" link. Nodes are looked up by {@code getId()}
 * because the image ids contain dots, which a CSS selector cannot match. No robot: only {@code fire()} and plain
 * key events.
 */
@Tag("ui")
class CreditsDialogSmokeTest {

    private static final class RecordingLinks implements ExternalLinks {
        final List<String> opened = new CopyOnWriteArrayList<>();

        @Override
        public void open(String httpsUrl) {
            opened.add(httpsUrl);
        }
    }

    private static Credits credits;

    private RecordingLinks links;
    private CreditsDialog dialog;
    private Stage owner;
    private Button openerButton;

    @BeforeAll
    static void startToolkit() {
        FxTestSupport.startToolkit();
        credits = Credits.load();
    }

    @BeforeEach
    void openOwner() {
        links = new RecordingLinks();
        dialog = new CreditsDialog(links);
        FxTestSupport.runOnFx(() -> {
            openerButton = new Button("Nguồn ảnh");
            openerButton.setId("openerButton");
            owner = new Stage();
            owner.setScene(new Scene(new VBox(openerButton), 800, 500));
            owner.setX(60);
            owner.setY(40);
            owner.show();
            openerButton.requestFocus();
        });
        FxTestSupport.flushFx();
    }

    @AfterEach
    void closeWindows() {
        FxTestSupport.hideAllWindows();
    }

    // ------------------------------------------------------------------ helpers

    private void show() {
        FxTestSupport.runOnFx(dialog::show);
        FxTestSupport.flushFx();
    }

    private static Stage dialogStage() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(window -> window instanceof Stage && window.isShowing() && window.getScene() != null
                        && window.getScene().lookup("#creditsDialog") != null)
                .map(window -> (Stage) window).findFirst().orElse(null));
    }

    private static Stage dialogStageRequired() {
        Stage stage = dialogStage();
        assertThat(stage).as("the credits dialog window").isNotNull();
        return stage;
    }

    private static long creditsWindowCount() {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window.getScene().lookup("#creditsDialog") != null).count());
    }

    /** Every node with an id inside the dialog, including the content of the scroll pane. */
    private static Map<String, Node> nodesById(Stage stage) {
        return FxTestSupport.callOnFx(() -> {
            Map<String, Node> byId = new LinkedHashMap<>();
            collect(stage.getScene().getRoot(), byId);
            return byId;
        });
    }

    private static void collect(Node node, Map<String, Node> byId) {
        if (node.getId() != null) {
            byId.put(node.getId(), node);
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), byId);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, byId);
            }
        }
    }

    private static void press(Node target, KeyCode code) {
        FxTestSupport.runOnFx(() -> Event.fireEvent(target,
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private static boolean showing(Stage stage) {
        return FxTestSupport.callOnFx(stage::isShowing);
    }

    // ------------------------------------------------------------------ content

    @Test
    void show_listsEveryImageTheFontAndTheIcon() {
        show();

        Map<String, Node> nodes = nodesById(dialogStageRequired());
        List<String> imageIds = nodes.keySet().stream().filter(id -> id.startsWith("credit-image-")).toList();
        assertThat(imageIds).hasSize(21).containsExactlyElementsOf(
                credits.images().stream().map(credit -> "credit-image-" + credit.file()).toList());
        assertThat(nodes).containsKeys("creditsDialog", "creditsTitle", "creditsIntro", "creditsScroll",
                "creditsFont", "creditsIcon", "creditsClose");
        assertThat(((Label) nodes.get("creditsTitle")).getText()).isEqualTo(CreditsDialog.TITLE);
        assertThat(((Label) nodes.get("creditsIntro")).getText()).isEqualTo(credits.intro());
        assertThat(((Button) nodes.get("creditsClose")).getText()).isEqualTo("Đóng");
    }

    @Test
    void show_everyEntryHasLicenseAndSourceNodes_aLinkOnlyWhenTheUrlIsHttps() {
        show();

        Map<String, Node> nodes = nodesById(dialogStageRequired());
        List<Credits.Credit> all = new ArrayList<>(credits.images());
        all.addAll(credits.fonts());
        all.addAll(credits.icons());
        for (Credits.Credit credit : all) {
            Node license = nodes.get("credit-license-" + credit.file());
            Node source = nodes.get("credit-source-" + credit.file());
            assertThat(license).as("license of " + credit.file()).isNotNull();
            assertThat(source).as("source of " + credit.file()).isNotNull();
            assertThat(license instanceof Hyperlink).as("license link of " + credit.file())
                    .isEqualTo(FxExternalLinks.isHttps(credit.licenseUrl()) && !credit.license().isEmpty());
            assertThat(source instanceof Hyperlink).as("source link of " + credit.file())
                    .isEqualTo(FxExternalLinks.isHttps(credit.sourceUrl()) && !credit.source().isEmpty());
        }
        assertThat(nodes.get("credit-source-app-icon.svg")).as("the icon has no source link").isNotInstanceOf(Hyperlink.class);
    }

    @Test
    void show_cardIsNotTallerThanTheOwnerWindow() {
        show();

        Stage stage = dialogStageRequired();
        assertThat(FxTestSupport.<Double>callOnFx(stage::getHeight)).isLessThanOrEqualTo(owner.getHeight());
    }

    // ------------------------------------------------------------------ focus and closing

    @Test
    void show_putsTheFocusOnTheCloseButton() {
        show();

        Stage stage = dialogStageRequired();
        assertThat(FxTestSupport.callOnFx(() -> stage.getScene().getFocusOwner()))
                .isSameAs(nodesById(stage).get("creditsClose"));
    }

    @Test
    void escape_closesTheDialogAndReturnsTheFocusToTheOpener() {
        show();
        Stage stage = dialogStageRequired();

        press(nodesById(stage).get("creditsClose"), KeyCode.ESCAPE);

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        FxTestSupport.flushFx();
        assertThat(FxTestSupport.callOnFx(() -> owner.getScene().getFocusOwner())).isSameAs(openerButton);
    }

    @Test
    void closeButton_closesTheDialogAndReturnsTheFocusToTheOpener() {
        show();
        Stage stage = dialogStageRequired();

        FxTestSupport.runOnFx(() -> ((Button) nodesById(stage).get("creditsClose")).fire());

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        FxTestSupport.flushFx();
        assertThat(FxTestSupport.callOnFx(() -> owner.getScene().getFocusOwner())).isSameAs(openerButton);
    }

    @Test
    void enterOnTheCloseButton_closesTheDialog() {
        show();
        Stage stage = dialogStageRequired();

        press(nodesById(stage).get("creditsClose"), KeyCode.ENTER);

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
    }

    @Test
    void windowCloseRequest_closesTheDialogWithoutOpeningALink() {
        show();
        Stage stage = dialogStageRequired();

        FxTestSupport.runOnFx(() -> Event.fireEvent(stage,
                new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));

        FxTestSupport.await("the dialog to close", () -> !showing(stage));
        assertThat(links.opened).isEmpty();
    }

    // ------------------------------------------------------------------ one window at a time

    @Test
    void show_whileAlreadyShowing_keepsTheSameSingleWindow() {
        show();
        Stage first = dialogStageRequired();
        long windowsBefore = FxTestSupport.showingWindows().size();

        show();

        assertThat(creditsWindowCount()).isEqualTo(1);
        assertThat(FxTestSupport.showingWindows()).hasSize((int) windowsBefore);
        assertThat(dialogStageRequired()).isSameAs(first);
    }

    @Test
    void show_afterClosing_opensAFreshWindow() {
        show();
        Stage first = dialogStageRequired();
        press(nodesById(first).get("creditsClose"), KeyCode.ESCAPE);
        FxTestSupport.await("the dialog to close", () -> !showing(first));

        show();

        assertThat(creditsWindowCount()).isEqualTo(1);
        assertThat(showing(dialogStageRequired())).isTrue();
        assertThat(FxTestSupport.callOnFx(() -> dialogStageRequired().getScene().getFocusOwner()))
                .isSameAs(nodesById(dialogStageRequired()).get("creditsClose"));
    }

    // ------------------------------------------------------------------ links

    @Test
    void sourceLink_callsExternalLinksWithItsUrl() {
        show();
        Credits.Credit credit = credits.images().stream()
                .filter(candidate -> FxExternalLinks.isHttps(candidate.sourceUrl())).findFirst().orElseThrow();
        Hyperlink link = (Hyperlink) nodesById(dialogStageRequired()).get("credit-source-" + credit.file());

        FxTestSupport.runOnFx(link::fire);

        assertThat(links.opened).containsExactly(credit.sourceUrl());
        assertThat(FxTestSupport.<Boolean>callOnFx(link::isVisited)).as("a link never shows as visited").isFalse();
        assertThat(showing(dialogStageRequired())).as("opening a link keeps the dialog").isTrue();
    }

    @Test
    void licenseLink_callsExternalLinksWithTheLicenseUrl() {
        show();
        Credits.Credit credit = credits.images().stream()
                .filter(candidate -> FxExternalLinks.isHttps(candidate.licenseUrl())).findFirst().orElseThrow();
        Hyperlink link = (Hyperlink) nodesById(dialogStageRequired()).get("credit-license-" + credit.file());

        FxTestSupport.runOnFx(link::fire);

        assertThat(links.opened).containsExactly(credit.licenseUrl());
    }

    @Test
    void links_haveAnAccessibleTextThatSaysTheyOpenTheBrowser() {
        show();
        Credits.Credit credit = credits.images().stream()
                .filter(candidate -> FxExternalLinks.isHttps(candidate.sourceUrl())).findFirst().orElseThrow();
        Hyperlink link = (Hyperlink) nodesById(dialogStageRequired()).get("credit-source-" + credit.file());

        assertThat(FxTestSupport.<String>callOnFx(link::getAccessibleText))
                .isEqualTo(credit.source() + CreditsDialog.LINK_SUFFIX);
    }
}

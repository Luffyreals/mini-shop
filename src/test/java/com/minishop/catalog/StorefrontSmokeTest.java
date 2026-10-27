package com.minishop.catalog;

import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.user.SessionListener;
import javafx.application.Application;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storefront on the real JavaFX toolkit (AC-009-1, AC-009-3, AC-009-13, AC-002-1; D3): the real app is started
 * with an in-memory H2 database and the seed, and the tests drive it by firing buttons, calling the navigator and the
 * shell's search handler, and looking up nodes. No robot. Background work runs on real virtual threads, so every
 * expectation waits (with {@link FxTestSupport#await}) for the node it needs instead of sleeping.
 *
 * <p>One application serves the whole class. {@code @AfterEach} goes back to the home screen with an empty search
 * box and an empty guest cart and closes stray dialogs, so each test runs alone and in any order.
 */
@Tag("ui")
class StorefrontSmokeTest {

    private static final int SEED_PRODUCTS = 21;
    private static final int PAGE_SIZE = 12;

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

    /** Every test leaves the app on a fresh home screen, with no search text, no guest cart and no extra window. */
    @AfterEach
    void backToTheHomeScreen() {
        FxTestSupport.runOnFx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != mainStage && window.isShowing()) {
                    window.hide();
                }
            }
            // A logout clears the guest cart and refreshes the badge; no session is ever opened here
            app.context().getBeansOfType(SessionListener.class).values()
                    .forEach(listener -> listener.sessionChanged(null, null));
            shell().setSearchText("");
            navigator().goHome();
        });
        FxTestSupport.await("the home screen", () -> inContent("homeScreen") != null);
        FxTestSupport.await("the empty cart badge", () -> "0".equals(badgeText()));
        FxTestSupport.flushFx();
    }

    // ------------------------------------------------------------------ helpers

    private static Navigator navigator() {
        return app.context().getBean(Navigator.class);
    }

    private static ShellPresenter shell() {
        return app.context().getBean(ShellPresenter.class);
    }

    private static Node content() {
        return FxTestSupport.callOnFx(() -> mainStage.getScene().lookup("#content"));
    }

    /** The node with that id inside the content area, or {@code null}. */
    private static Node inContent(String id) {
        return FxTestSupport.callOnFx(() -> {
            Node content = mainStage.getScene().lookup("#content");
            return content == null ? null : content.lookup("#" + id);
        });
    }

    private static Node inContentRequired(String id) {
        Node node = inContent(id);
        assertThat(node).as("node #" + id).isNotNull();
        return node;
    }

    private static String badgeText() {
        return FxTestSupport.callOnFx(() -> ((Label) mainStage.getScene().lookup("#cartBadge")).getText());
    }

    private static String labelText(String id) {
        return FxTestSupport.callOnFx(() -> {
            Node node = inContent(id);
            return node instanceof Label label ? label.getText() : null;
        });
    }

    private static void fire(String id) {
        FxTestSupport.runOnFx(() -> ((Button) inContentRequired(id)).fire());
    }

    private static void fireTopBar(String id) {
        FxTestSupport.runOnFx(() -> ((Button) mainStage.getScene().lookup("#" + id)).fire());
    }

    /** True when the node is managed and it and every parent up to the scene root are visible. */
    private static boolean shown(String id) {
        return FxTestSupport.callOnFx(() -> {
            Node node = inContent(id);
            for (Node current = node; current != null; current = current.getParent()) {
                if (!current.isVisible() || (current == node && !current.isManaged())) {
                    return false;
                }
            }
            return inContent(id) != null;
        });
    }

    private static List<Node> productCards() {
        return FxTestSupport.callOnFx(() -> {
            Node grid = inContent("productGrid");
            return grid == null ? List.<Node>of() : List.copyOf(grid.lookupAll(".product-card"));
        });
    }

    private static List<String> productCardNames() {
        return FxTestSupport.callOnFx(() -> {
            List<String> names = new ArrayList<>();
            for (Node card : productCards()) {
                for (Node name : card.lookupAll(".product-card-name")) {
                    names.add(((Label) name).getText());
                }
            }
            return names;
        });
    }

    private static List<String> labelsUnder(String id) {
        return FxTestSupport.callOnFx(() -> {
            List<String> texts = new ArrayList<>();
            collectLabels(inContentRequired(id), texts);
            return texts;
        });
    }

    private static void collectLabels(Node node, List<String> texts) {
        if (node instanceof Label label && label.getText() != null) {
            texts.add(label.getText());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectLabels(child, texts));
        }
    }

    private static void awaitCatalog(String resultText, int cards) {
        FxTestSupport.await("the catalog to show \"" + resultText + "\" with " + cards + " cards",
                () -> resultText.equals(labelText("resultCount")) && productCards().size() == cards);
    }

    private static void assertNoErrorDialog() {
        assertThat(FxTestSupport.showingWindows()).as("no general error dialog")
                .extracting(Window::getScene)
                .noneMatch(scene -> scene != null && scene.lookup("#errorDialog") != null);
    }

    private static Product newestProduct() {
        return app.context().getBean(ProductRepository.class).findAll().stream()
                .filter(Product::isActive)
                .max(Comparator.comparing(Product::getCreatedAt).thenComparing(Product::getId))
                .orElseThrow();
    }

    private static Product anInStockProduct() {
        return app.context().getBean(ProductRepository.class).findAll().stream()
                .filter(Product::isActive)
                .filter(product -> product.getStock() >= 2)
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void start_showsTheHomeScreenWithTheNewestProductAndNoLowStockBlock() {
        Product newest = newestProduct();

        FxTestSupport.await("the featured card", () -> inContent("featuredCard") != null);

        assertThat(inContent("homeScreen")).isNotNull();
        assertThat(labelsUnder("featuredCard")).as("the newest product is featured").contains(newest.getName());
        assertThat(shown("lowStockBlock")).as("the seed has no stock of 1 to 5").isFalse();
        assertThat(shown("moreNew")).isTrue();
        assertThat(inContent("deliveryCard")).isNotNull();
        assertNoErrorDialog();
    }

    @Test
    void newArrivalsButton_showsTwelveOfTwentyOneProductsThenNineOnTheNextPage() {
        FxTestSupport.await("the home screen to load", () -> inContent("featuredCard") != null);

        fireTopBar("navNew");
        awaitCatalog("Tìm thấy " + SEED_PRODUCTS + " sản phẩm", PAGE_SIZE);

        assertThat(inContent("catalogScreen")).isNotNull();
        assertThat(productCards()).hasSize(PAGE_SIZE);

        fire("pageNext");
        awaitCatalog("Tìm thấy " + SEED_PRODUCTS + " sản phẩm", SEED_PRODUCTS - PAGE_SIZE);

        assertThat(productCards()).hasSize(9);
        assertNoErrorDialog();
    }

    @Test
    void search_findsByNameIgnoringCaseAndSpaces_butNotWithoutAccents_andClearFiltersBringsEverythingBack() {
        FxTestSupport.runOnFx(() -> shell().submitSearch("  áo THUN "));
        FxTestSupport.await("the search result", () -> productCardNames().contains("Áo thun cotton cổ tròn"));

        assertThat(inContent("catalogScreen")).isNotNull();
        assertThat(productCardNames()).contains("Áo thun cotton cổ tròn");
        assertThat(productCards().size()).isLessThan(SEED_PRODUCTS);

        // Not plain "ao": it is a substring of "Tạ tay cao su 5 kg", so it would match. "ao thun" has no accent-free match.
        FxTestSupport.runOnFx(() -> shell().submitSearch("ao thun"));
        FxTestSupport.await("the empty state", () -> shown("emptyState"));

        assertThat(productCards()).isEmpty();
        assertThat(labelsUnder("emptyState")).contains("Không tìm thấy sản phẩm phù hợp.");

        fire("clearFilters");
        awaitCatalog("Tìm thấy " + SEED_PRODUCTS + " sản phẩm", PAGE_SIZE);

        assertThat(shown("emptyState")).isFalse();
        assertNoErrorDialog();
    }

    @Test
    void addToCart_asAGuestFromTheDetailScreen_putsTheQuantityOnTheCartBadge() {
        Product product = anInStockProduct();

        FxTestSupport.runOnFx(() -> navigator().navigateTo(new ProductDetailRoute(product.getId(), CatalogRoute.all())));
        FxTestSupport.await("the product detail", () -> product.getName().equals(labelText("productName")));
        FxTestSupport.runOnFx(() -> ((TextField) inContentRequired("quantityField")).setText("2"));

        fire("addToCart");
        FxTestSupport.await("the cart badge to show 2", () -> "2".equals(badgeText()));

        assertThat(badgeText()).isEqualTo("2");
        assertThat(FxTestSupport.callOnFx(() -> mainStage.getScene().lookup("#cartButton").getAccessibleText()))
                .isEqualTo("Giỏ hàng, 2 sản phẩm");
        FxTestSupport.await("the add button to be usable again", () -> !inContentRequired("addToCart").isDisabled());
        assertNoErrorDialog();
    }

    @Test
    void cardClick_onTheAddButtonArea_staysOnTheCatalog_butOnTheNameOpensTheDetail() {
        // Plain MouseEvent objects with a pick result, fired on the card; no robot. The busy (disabled) button case
        // needs a click that reaches the card through a disabled button, which is the same pick target as here.
        fireTopBar("navNew");
        awaitCatalog("Tìm thấy " + SEED_PRODUCTS + " sản phẩm", PAGE_SIZE);
        Node card = FxTestSupport.callOnFx(() -> productCards().stream()
                .filter(candidate -> candidate.lookup("#addButton-" + candidate.getId().substring("productCard-".length())) != null)
                .findFirst().orElseThrow());
        Node addButton = FxTestSupport.callOnFx(() ->
                card.lookup("#addButton-" + card.getId().substring("productCard-".length())));
        Node name = FxTestSupport.callOnFx(() -> card.lookup(".product-card-name"));

        FxTestSupport.runOnFx(() -> Event.fireEvent(card, CatalogScreenTest.clickOn(addButton)));
        FxTestSupport.flushFx();

        assertThat(inContent("catalogScreen")).as("still on the catalog").isNotNull();
        assertThat(inContent("productDetailScreen")).isNull();

        FxTestSupport.runOnFx(() -> Event.fireEvent(card, CatalogScreenTest.clickOn(name)));

        FxTestSupport.await("the product detail", () -> inContent("productDetailScreen") != null);
        assertNoErrorDialog();
    }

    @Test
    void detailOfAMissingProduct_goesBackToTheCatalogWithoutAnErrorDialog() {
        FxTestSupport.runOnFx(() -> navigator().navigateTo(new ProductDetailRoute(999_999, CatalogRoute.all())));

        FxTestSupport.await("the catalog after a missing product", () -> inContent("catalogScreen") != null);
        awaitCatalog("Tìm thấy " + SEED_PRODUCTS + " sản phẩm", PAGE_SIZE);

        assertThat(inContent("productDetailScreen")).isNull();
        assertNoErrorDialog();
    }

    @Test
    void storeButton_fromTheDetailScreen_returnsToTheHomeScreen() {
        Product product = anInStockProduct();
        FxTestSupport.runOnFx(() -> navigator().navigateTo(new ProductDetailRoute(product.getId(), CatalogRoute.all())));
        FxTestSupport.await("the product detail", () -> inContent("productDetailScreen") != null);

        fireTopBar("navStore");

        FxTestSupport.await("the home screen", () -> inContent("homeScreen") != null);
        assertThat(inContent("productDetailScreen")).isNull();
        assertThat(content()).isNotNull();
        assertNoErrorDialog();
    }
}

package com.minishop.admin;

import com.minishop.support.AdminTestData;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import javafx.application.Application;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testfx.api.FxToolkit;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Where the keyboard focus ends up in the admin area, on the real JavaFX toolkit (D5 focus fixes): after a click on a
 * sidebar item, a filter pill, the product search or a saved category edit, the screen is replaced and the focus
 * must be inside the new screen's content, never on a control of the window's top bar ("Cửa hàng" and the others;
 * JavaFX moves the focus to the first control of the window when the focused node leaves the scene). It also
 * measures that scrolling a focused field into view keeps its label visible. No robot: a click is the button taking
 * the focus and firing, in one FX task ({@link AdminSmoke#click}).
 *
 * <p>Data this class creates has names starting with {@code Admin test } and is removed after each test.
 */
@Tag("ui")
class AdminFocusSmokeTest {

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;
    private static AdminSmoke smoke;
    private static JdbcTemplate jdbc;
    private static AdminTestData data;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
        smoke = new AdminSmoke(ui);
        jdbc = new JdbcTemplate(ui.bean(DataSource.class));
        data = new AdminTestData(jdbc, null, Path.of("target", "test-uploads").toAbsolutePath().normalize());
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
        data.cleanUp();
    }

    // ------------------------------------------------------------------ helpers

    private static void openAdmin() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
    }

    /** Clicks the control and waits until the screen with that id is a different instance (the new screen). */
    private static void clickAndAwaitNewScreen(String controlId, String screenId) {
        Node before = ui.inContent(screenId);
        smoke.click(controlId);
        FxTestSupport.await("a new #" + screenId, () -> {
            Node now = ui.inContent(screenId);
            return now != null && now != before;
        });
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        if (text == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ sidebar click

    @Test
    void sidebarClick_toEachSection_leavesTheFocusInsideTheNewScreen_neverInTheTopBar() {
        openAdmin();

        // The dashboard is last so that it too is opened from another screen
        String[][] sections = {
                {"orders", "adminOrdersScreen"},
                {"products", "adminProductsScreen"},
                {"categories", "adminCategoriesScreen"},
                {"dashboard", "adminDashboardScreen"},
        };
        for (String[] section : sections) {
            clickAndAwaitNewScreen("adminNav-" + section[0], section[1]);

            smoke.awaitFocusInScreen(section[1]);

            String focused = smoke.focusOwnerId();
            switch (section[0]) {
                case "orders" -> assertThat(startsWithAny(focused, "adminOrderRow-", "orderFilter-"))
                        .as("orders: the first row, or the selected pill when there are none (was %s)", focused)
                        .isTrue();
                case "products" -> assertThat(focused).as("products: the search field").isEqualTo("productSearchField");
                case "categories" -> assertThat(focused).as("categories: \"Thêm danh mục\"").isEqualTo("addCategory");
                case "dashboard" -> assertThat(startsWithAny(focused, "adminCount-"))
                        .as("dashboard: the first count tile (was %s)", focused).isTrue();
                default -> throw new AssertionError(section[0]);
            }
        }
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebarClick_onTheItemOfTheOpenScreen_alsoLeavesTheFocusInsideTheReloadedScreen() {
        openAdmin();
        for (String[] section : new String[][] {{"products", "adminProductsScreen"},
                {"categories", "adminCategoriesScreen"}}) {
            smoke.goTo(section[0], section[1]);
            smoke.awaitFocusInScreen(section[1]);

            clickAndAwaitNewScreen("adminNav-" + section[0], section[1]);

            smoke.awaitFocusInScreen(section[1]);
        }
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ orders filter pill

    @Test
    void orderFilterPill_leavesTheFocusInsideTheNewScreen_neverInTheTopBar() {
        openAdmin();
        smoke.goTo("orders", "adminOrdersScreen");
        FxTestSupport.await("the pills", () -> ui.shownInContent("adminOrderFilters")
                && ui.inContent("orderFilter-PENDING") != null);
        smoke.awaitFocusInScreen("adminOrdersScreen");

        clickAndAwaitNewScreen("orderFilter-PENDING", "adminOrdersScreen");

        smoke.awaitFocusInScreen("adminOrdersScreen");
        assertThat(startsWithAny(smoke.focusOwnerId(), "adminOrderRow-", "orderFilter-"))
                .as("the first row, or the selected pill when no order is pending (was %s)", smoke.focusOwnerId())
                .isTrue();

        clickAndAwaitNewScreen("orderFilter-all", "adminOrdersScreen");

        smoke.awaitFocusInScreen("adminOrdersScreen");
        ui.assertNoErrorDialog();
    }

    @Test
    void orderFilterPill_withNoOrderInThatStatus_putsTheFocusOnTheSelectedPill() {
        openAdmin();
        smoke.goTo("orders", "adminOrdersScreen");
        FxTestSupport.await("the pills", () -> ui.shownInContent("adminOrderFilters"));
        String status = emptyStatus();

        clickAndAwaitNewScreen("orderFilter-" + status, "adminOrdersScreen");

        FxTestSupport.await("the empty state", () -> ui.shownInContent("adminOrdersEmpty"));
        smoke.awaitFocusInScreen("adminOrdersScreen");
        assertThat(smoke.focusOwnerId()).isEqualTo("orderFilter-" + status);
        ui.assertNoErrorDialog();
    }

    /** A status with no orders in the shared test database; the test fails (not skips) when every status is used. */
    private static String emptyStatus() {
        for (String status : new String[] {"CANCELLED", "DELIVERED", "SHIPPING", "CONFIRMED", "PENDING"}) {
            Integer count = jdbc.queryForObject("select count(*) from orders where status = ?", Integer.class, status);
            if (count != null && count == 0) {
                return status;
            }
        }
        throw new AssertionError("every order status has orders, so no empty filter can be tested");
    }

    // ------------------------------------------------------------------ product search

    @Test
    void productSearch_withNoMatch_leavesTheFocusInTheSearchField() {
        openAdmin();
        smoke.goTo("products", "adminProductsScreen");
        FxTestSupport.await("the product list", () -> ui.shownInContent("productRows"));
        smoke.typeInContent("productSearchField", "Admin test zzz-nothing-" + UUID.randomUUID());

        clickAndAwaitNewScreen("productSearchButton", "adminProductsScreen");

        FxTestSupport.await("the empty state", () -> ui.shownInContent("productsEmpty"));
        smoke.awaitFocusInScreen("adminProductsScreen");
        assertThat(smoke.focusOwnerId()).isEqualTo("productSearchField");
        ui.assertNoErrorDialog();
    }

    @Test
    void productSearch_withAMatch_leavesTheFocusInTheSearchField() {
        long category = data.createCategory("Focus");
        long id = data.createProduct(category, "Findable", 50_000, 5, true);
        String name = data.productName(id);
        openAdmin();
        smoke.goTo("products", "adminProductsScreen");
        FxTestSupport.await("the product list", () -> ui.shownInContent("productRows"));
        smoke.typeInContent("productSearchField", name);

        clickAndAwaitNewScreen("productSearchButton", "adminProductsScreen");

        FxTestSupport.await("the matching row", () -> ui.inContent("productRow-" + id) != null);
        smoke.awaitFocusInScreen("adminProductsScreen");
        assertThat(smoke.focusOwnerId()).isEqualTo("productSearchField");
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ saving a category edit

    @Test
    void categoryEditSave_returnsToTheListWithTheFocusOnAddCategory_neverInTheTopBar() {
        long id = data.createCategory("Rename");
        openAdmin();
        smoke.goTo("categories", "adminCategoriesScreen");
        FxTestSupport.await("the row", () -> ui.inContent("categoryRow-" + id) != null);
        ui.dismissToasts();

        smoke.click("editCategory-" + id);
        smoke.awaitScreen("categoryFormScreen");
        String renamed = AdminTestData.NAME_PREFIX + "Renamed " + UUID.randomUUID().toString().substring(0, 6);
        smoke.awaitText("the prefilled name", "categoryNameField", data.categoryName(id));
        smoke.typeInContent("categoryNameField", renamed);
        smoke.click("saveCategory");

        smoke.awaitToast("Đã cập nhật danh mục " + renamed + ".");
        smoke.awaitScreen("adminCategoriesScreen");
        FxTestSupport.await("the renamed row", () -> ui.inContent("categoryRow-" + id) != null);
        smoke.awaitFocusInScreen("adminCategoriesScreen");
        assertThat(smoke.focusOwnerId()).isEqualTo("addCategory");
        assertThat(data.categoryName(id)).isEqualTo(renamed);
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ scrolling a focused field into view

    private static void layOut(ScrollPane scroll) {
        FxTestSupport.runOnFx(() -> {
            scroll.applyCss();
            scroll.layout();
        });
    }

    /**
     * Waits until the loaded form has been laid out taller than the viewport. {@code stockCurrent} being shown only
     * means the data arrived: the layout pass (and the window resize to 700) may still be pending, and a
     * {@code setVvalue(1)} made while the content is still short is reset to 0 by the scroll pane when the layout
     * grows (seen as a 251 px content in a 576 px viewport), which left the name field unscrolled.
     */
    private static void awaitScrollable(ScrollPane scroll) {
        FxTestSupport.await("the form to be laid out taller than the viewport", () -> FxTestSupport.callOnFx(() -> {
            scroll.applyCss();
            scroll.layout();
            return scroll.getViewportBounds().getHeight() > 0
                    && scroll.getContent().getLayoutBounds().getHeight() > scroll.getViewportBounds().getHeight();
        }));
    }

    /** Top of the scroll pane's viewport in scene coordinates. */
    private static double viewportTop(ScrollPane scroll) {
        return FxTestSupport.callOnFx(() -> scroll.localToScene(0, 0).getY() + scroll.getInsets().getTop());
    }

    private static double viewportBottom(ScrollPane scroll) {
        return FxTestSupport.callOnFx(() -> viewportTop(scroll) + scroll.getViewportBounds().getHeight());
    }

    private static Bounds sceneBounds(Node node) {
        return FxTestSupport.callOnFx(() -> node.localToScene(node.getBoundsInLocal()));
    }

    /** The label of the field: the sibling {@code Label} whose {@code labelFor} is the field. */
    private static Label labelOf(Node field) {
        return FxTestSupport.callOnFx(() -> {
            Parent parent = field.getParent();
            for (Node sibling : parent.getChildrenUnmodifiable()) {
                if (sibling instanceof Label label && label.getLabelFor() == field) {
                    return label;
                }
            }
            throw new AssertionError("no label points at the field " + field.getId());
        });
    }

    @Test
    void scrollIntoView_fromBelow_keepsTheFieldsLabelInsideTheViewportWithAMargin() {
        long category = data.createCategory("Scroll");
        long id = data.createProduct(category, "Tall form", 80_000, 10, true);
        double width = FxTestSupport.callOnFx(mainStage::getWidth);
        double height = FxTestSupport.callOnFx(mainStage::getHeight);
        // The smallest window the app allows, so the edit form is taller than the viewport (stacked layout)
        FxTestSupport.runOnFx(() -> {
            mainStage.setWidth(1024);
            mainStage.setHeight(700);
        });
        try {
            openAdmin();
            FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(AdminProductFormRoute.edit(id,
                    AdminProductsRoute.first())));
            smoke.awaitScreen("productFormScreen");
            FxTestSupport.await("the form to load", () -> ui.shownInContent("stockCurrent"));
            Node name = smoke.node("productNameField");
            AdminScreen screen = (AdminScreen) ui.inContentRequired("productFormScreen");
            ScrollPane scroll = screen.bodyScroll();
            Label label = labelOf(name);
            awaitScrollable(scroll);

            // The form focuses the name field when it opens, and focusing the owner again changes nothing: move the
            // focus elsewhere first, then scroll to the very bottom so that the name field and its label are above
            FxTestSupport.runOnFx(smoke.node("saveProduct")::requestFocus);
            FxTestSupport.runOnFx(() -> scroll.setVvalue(1));
            layOut(scroll);
            assertThat(sceneBounds(name).getMinY()).as("precondition: the name field is above the viewport")
                    .isLessThan(viewportTop(scroll));
            assertThat(sceneBounds(label).getMinY()).as("precondition: its label is above the viewport")
                    .isLessThan(viewportTop(scroll));

            FxTestSupport.runOnFx(name::requestFocus);
            layOut(scroll);

            double labelTop = sceneBounds(label).getMinY();
            double fieldBottom = sceneBounds(name).getMaxY();
            assertThat(labelTop).as("the label's top is inside the viewport").isGreaterThanOrEqualTo(
                    viewportTop(scroll) - 0.5);
            assertThat(fieldBottom).as("the field is inside the viewport").isLessThanOrEqualTo(
                    viewportBottom(scroll) + 0.5);
            double vvalue = FxTestSupport.callOnFx(scroll::getVvalue);
            if (vvalue > 0) {
                assertThat(labelTop - viewportTop(scroll)).as("a 12 px margin above the label")
                        .isCloseTo(12, within(1.5));
            }
            ui.assertNoErrorDialog();
        } finally {
            FxTestSupport.runOnFx(() -> {
                mainStage.setWidth(width);
                mainStage.setHeight(height);
            });
        }
    }

    @Test
    void scrollIntoView_fromAbove_keepsTheFocusedFieldInsideTheViewport() {
        long category = data.createCategory("ScrollDown");
        long id = data.createProduct(category, "Tall form", 80_000, 10, true);
        double width = FxTestSupport.callOnFx(mainStage::getWidth);
        double height = FxTestSupport.callOnFx(mainStage::getHeight);
        FxTestSupport.runOnFx(() -> {
            mainStage.setWidth(1024);
            mainStage.setHeight(700);
        });
        try {
            openAdmin();
            FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(AdminProductFormRoute.edit(id,
                    AdminProductsRoute.first())));
            smoke.awaitScreen("productFormScreen");
            FxTestSupport.await("the form to load", () -> ui.shownInContent("stockCurrent"));
            Node delta = smoke.node("stockDeltaField");
            ScrollPane scroll = ((AdminScreen) ui.inContentRequired("productFormScreen")).bodyScroll();
            awaitScrollable(scroll);

            FxTestSupport.runOnFx(() -> scroll.setVvalue(0));
            layOut(scroll);
            assertThat(sceneBounds(delta).getMaxY()).as("precondition: the stock field is below the viewport")
                    .isGreaterThan(viewportBottom(scroll));

            FxTestSupport.runOnFx(delta::requestFocus);
            layOut(scroll);

            assertThat(sceneBounds(delta).getMaxY()).as("the field is inside the viewport")
                    .isLessThanOrEqualTo(viewportBottom(scroll) + 0.5);
            assertThat(sceneBounds(labelOf(delta)).getMinY()).as("and so is its label")
                    .isGreaterThanOrEqualTo(viewportTop(scroll) - 0.5);
            ui.assertNoErrorDialog();
        } finally {
            FxTestSupport.runOnFx(() -> {
                mainStage.setWidth(width);
                mainStage.setHeight(height);
            });
        }
    }
}

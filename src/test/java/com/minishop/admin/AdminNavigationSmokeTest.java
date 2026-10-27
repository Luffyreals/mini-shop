package com.minishop.admin;

import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import javafx.application.Application;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entering and leaving the admin area on the real JavaFX toolkit (AC-009-8, AC-009-10, AC-006-4; D5): the real app
 * with an in-memory database and the seed. It signs in through the real login dialog and the account menu and
 * drives the sidebar by firing its toggle buttons. No robot. Every test starts as a guest on the home screen and
 * {@link UiDriver#resetToHome()} puts the app back.
 */
@Tag("ui")
class AdminNavigationSmokeTest {

    private static final String[][] SECTIONS = {
            {"dashboard", "adminDashboardScreen", "Tổng quan"},
            {"orders", "adminOrdersScreen", "Đơn hàng"},
            {"products", "adminProductsScreen", "Sản phẩm"},
            {"categories", "adminCategoriesScreen", "Danh mục"},
    };

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;
    private static AdminSmoke smoke;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
        smoke = new AdminSmoke(ui);
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

    // ------------------------------------------------------------------ who sees "Quản trị" (AC-009-8)

    @Test
    void accountButton_asAGuest_opensTheLoginDialogAndNoMenu() {
        ui.fire("accountButton");

        FxTestSupport.await("the login dialog", () -> ui.windowWith("loginSubmit") != null);
        assertThat(ui.inContent("adminSidebar")).isNull();
        assertThat(ui.inContent("adminDashboardScreen")).isNull();
        ui.assertNoErrorDialog();
    }

    @Test
    void accountMenu_forACustomer_hasOrdersAndLogoutButNoAdminEntry() {
        ui.logInAsUser();

        assertThat(smoke.accountMenuItemIds()).containsExactly("accountMenuOrders", "accountMenuLogout");
        assertThat(ui.inContent("adminSidebar")).isNull();
    }

    @Test
    void accountMenu_forAnAdmin_hasTheAdminEntryBetweenOrdersAndLogout() {
        ui.logInAsAdmin();

        assertThat(smoke.accountMenuItemIds())
                .containsExactly("accountMenuOrders", "accountMenuAdmin", "accountMenuLogout");
    }

    @Test
    void accountMenu_afterAnAdminLogsOutAndACustomerLogsIn_hasNoAdminEntryAnyMore() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);

        ui.logInAsUser();

        assertThat(smoke.accountMenuItemIds()).containsExactly("accountMenuOrders", "accountMenuLogout");
        assertThat(ui.inContent("adminSidebar")).isNull();
    }

    // ------------------------------------------------------------------ reaching every section (AC-006-4)

    @Test
    void adminEntry_opensTheDashboardWithTheSidebarAndTheFiveStatusTiles() {
        ui.logInAsAdmin();

        smoke.openAdminArea();

        assertThat(ui.text(ui.inContentRequired("dashboardTitle"))).isEqualTo("Tổng quan");
        assertThat(ui.labelsUnder(ui.inContentRequired("adminSidebar"))).containsExactly("Quản trị");
        assertThat(List.of(SECTIONS).stream().map(section -> smoke.toggleText("adminNav-" + section[0])).toList())
                .containsExactly("Tổng quan", "Đơn hàng", "Sản phẩm", "Danh mục");
        assertThat(smoke.isSelected("adminNav-dashboard")).isTrue();
        assertThat(List.of("PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED"))
                .allSatisfy(status -> assertThat(ui.inContent("adminCount-" + status))
                        .as("tile " + status).isNotNull());
        assertThat(ui.labelsUnder(ui.inContentRequired("adminCounts")))
                .contains("Chờ xử lý", "Đã xác nhận", "Đang giao", "Đã giao", "Đã hủy");
        assertThat(ui.shownInContent("revenueValue")).isTrue();
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_eachItemOpensItsScreenAndMarksOnlyItself() {
        ui.logInAsAdmin();
        smoke.openAdminArea();

        // Leave the dashboard first so every item, the dashboard too, is reached from another screen
        for (String[] section : new String[][] {SECTIONS[1], SECTIONS[2], SECTIONS[3], SECTIONS[0]}) {
            smoke.goTo(section[0], section[1]);

            for (String[] other : SECTIONS) {
                assertThat(smoke.isSelected("adminNav-" + other[0]))
                        .as("%s selected while on %s", other[0], section[0])
                        .isEqualTo(other[0].equals(section[0]));
            }
            assertThat(ui.inContent("adminSidebar")).as("the sidebar on " + section[0]).isNotNull();
        }
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_firingTheItemOfTheOpenScreen_keepsItSelectedAndReloadsTheScreen() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("categories", "adminCategoriesScreen");

        smoke.fireToggle("adminNav-categories");
        smoke.awaitScreen("adminCategoriesScreen");

        assertThat(smoke.isSelected("adminNav-categories")).isTrue();
        FxTestSupport.await("the category list to reload", () -> ui.shownInContent("categoryRows"));
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_everyItemIsATabStop_andNoItemBelongsToAToggleGroup() {
        ui.logInAsAdmin();
        smoke.openAdminArea();

        for (String[] section : SECTIONS) {
            ToggleButton item = (ToggleButton) ui.nodeRequired("adminNav-" + section[0]);
            assertThat(FxTestSupport.callOnFx(item::isFocusTraversable))
                    .as("%s is focus-traversable", section[0]).isTrue();
            assertThat(FxTestSupport.callOnFx(item::isDisabled)).as("%s is enabled", section[0]).isFalse();
            assertThat(FxTestSupport.callOnFx(item::getToggleGroup))
                    .as("%s has no toggle group (a group would make Tab skip the unselected items)", section[0])
                    .isNull();
        }
    }

    @Test
    void sidebar_firingEveryItemTwice_alwaysLeavesExactlyTheShownSectionSelected() {
        ui.logInAsAdmin();
        smoke.openAdminArea();

        for (String[] section : new String[][] {SECTIONS[1], SECTIONS[2], SECTIONS[3], SECTIONS[0]}) {
            smoke.goTo(section[0], section[1]);
            assertOnlySelected(section[0]);

            // Firing the item of the open screen must not deselect it, not even for a moment after the click
            smoke.fireToggle("adminNav-" + section[0]);
            assertOnlySelected(section[0]);
            smoke.awaitScreen(section[1]);
            FxTestSupport.flushFx();
            assertOnlySelected(section[0]);
        }
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_enterOnAFocusedItem_opensItsScreenAndMarksOnlyIt() {
        ui.logInAsAdmin();
        smoke.openAdminArea();

        for (String[] section : new String[][] {SECTIONS[1], SECTIONS[2], SECTIONS[3], SECTIONS[0]}) {
            smoke.focus("adminNav-" + section[0]);

            ui.pressKey(ui.nodeRequired("adminNav-" + section[0]), KeyCode.ENTER);

            smoke.awaitScreen(section[1]);
            assertOnlySelected(section[0]);
        }
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_enterOnTheItemOfTheOpenScreen_keepsItSelected() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("products", "adminProductsScreen");
        smoke.focus("adminNav-products");

        ui.pressKey(ui.nodeRequired("adminNav-products"), KeyCode.ENTER);

        assertOnlySelected("products");
        smoke.awaitScreen("adminProductsScreen");
        FxTestSupport.flushFx();
        assertOnlySelected("products");
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_spaceOnAFocusedItem_opensItsScreenAndMarksOnlyIt() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.focus("adminNav-categories");

        smoke.typeKey(ui.nodeRequired("adminNav-categories"), KeyCode.SPACE);

        smoke.awaitScreen("adminCategoriesScreen");
        assertOnlySelected("categories");
        ui.assertNoErrorDialog();
    }

    @Test
    void sidebar_arrowKeys_onlyMoveTheFocus_theySelectNothingAndOpenNoScreen() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.focus("adminNav-dashboard");

        // Up and down move along the sidebar; left and right may leave it (the nearest node on that side), which is
        // fine: what matters is that no arrow selects an item or opens a screen
        for (KeyCode arrow : new KeyCode[] {KeyCode.DOWN, KeyCode.DOWN, KeyCode.UP, KeyCode.RIGHT, KeyCode.LEFT}) {
            ui.pressKey(smoke.focusOwner(), arrow);
            FxTestSupport.flushFx();

            assertOnlySelected("dashboard");
            assertThat(ui.inContent("adminDashboardScreen")).as("still on the dashboard after %s", arrow).isNotNull();
            assertThat(ui.inContent("adminOrdersScreen")).as("no other screen after %s", arrow).isNull();
            assertThat(ui.inContent("adminProductsScreen")).as("no other screen after %s", arrow).isNull();
            assertThat(ui.inContent("adminCategoriesScreen")).as("no other screen after %s", arrow).isNull();
            if (arrow == KeyCode.DOWN || arrow == KeyCode.UP) {
                assertThat(smoke.focusIsInside("adminSidebar")).as("the focus stays in the sidebar after %s", arrow)
                        .isTrue();
            }
        }
        ui.assertNoErrorDialog();
    }

    /** Exactly the item of {@code selectedKey} is selected, whichever sidebar instance the lookup finds. */
    private static void assertOnlySelected(String selectedKey) {
        for (String[] other : SECTIONS) {
            assertThat(smoke.isSelected("adminNav-" + other[0]))
                    .as("%s selected while %s is the section shown", other[0], selectedKey)
                    .isEqualTo(other[0].equals(selectedKey));
        }
    }

    @Test
    void sidebar_onEveryScreenOfTheAreaTheShopButtonOfTheTopBarStillGoesToTheStorefront() {
        ui.logInAsAdmin();
        smoke.openAdminArea();

        ui.fire("navStore");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        assertThat(ui.inContent("adminSidebar")).isNull();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ logout from an admin screen (AC-009-10)

    @Test
    void logout_whileOnTheDashboard_returnsToTheHomeScreenWithAToast() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        ui.dismissToasts();

        ui.fireAccountMenuItem("accountMenuLogout");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        assertThat(ui.inContent("adminDashboardScreen")).isNull();
        assertThat(ui.inContent("adminSidebar")).isNull();
        smoke.awaitToast("Bạn đã đăng xuất.");
        assertThat(ui.text(ui.nodeRequired("accountButton"))).isEqualTo("Đăng nhập");
        ui.assertNoErrorDialog();
    }

    @Test
    void logout_whileOnAnAdminForm_returnsToTheHomeScreenAndNoLateLoadBringsTheAdminScreenBack() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("categories", "adminCategoriesScreen");
        FxTestSupport.await("the category list", () -> ui.shownInContent("addCategory"));
        smoke.fireInContent("addCategory");
        smoke.awaitScreen("categoryFormScreen");
        ui.dismissToasts();

        ui.fireAccountMenuItem("accountMenuLogout");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        FxTestSupport.flushFx();
        assertThat(ui.inContent("categoryFormScreen")).isNull();
        assertThat(ui.inContent("adminSidebar")).isNull();
        assertThat(FxTestSupport.callOnFx(() -> ui.session().isLoggedIn())).isFalse();
        ui.assertNoErrorDialog();
    }

    @Test
    void logout_whileTheProductListIsOpen_returnsToTheHomeScreen() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("products", "adminProductsScreen");

        ui.fireAccountMenuItem("accountMenuLogout");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        assertThat(ui.inContent("adminProductsScreen")).isNull();
        ui.assertNoErrorDialog();
    }

    @Test
    void adminRoute_openedByAGuest_isRefusedByTheServiceAndSendsTheGuestHome() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);

        // A guest who somehow navigates to an admin route gets a refusal from the service, never the data (AC-009-9)
        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(new AdminCategoriesRoute()));

        FxTestSupport.await("the guest to be sent home", () -> ui.inContent("homeScreen") != null);
        smoke.awaitToast("Bạn không có quyền truy cập trang này.");
        assertThat(ui.inContent("categoryRows")).isNull();
        assertThat(ui.inContent("adminSidebar")).isNull();
        ui.assertNoErrorDialog();
    }
}

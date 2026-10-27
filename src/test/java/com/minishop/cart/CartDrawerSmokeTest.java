package com.minishop.cart;

import com.minishop.common.VndFormatter;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import javafx.application.Application;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cart drawer on the real JavaFX toolkit (AC-004-9 to -12, -22, -23, AC-009-1; D4): the real app with an
 * in-memory H2 database and the seed; the tests act as a guest (no login needed, AC-004-23) and drive the drawer by
 * firing buttons, pressing keys on nodes and looking up nodes. No robot. Background work runs on real virtual
 * threads, so every expectation waits for the node or text it needs instead of sleeping.
 *
 * <p>One application serves the whole class; {@link UiDriver#resetToHome()} runs after each test.
 */
@Tag("ui")
class CartDrawerSmokeTest {

    private static final String KEYBOARD = "Bàn phím cơ cỡ nhỏ";
    private static final String T_SHIRT = "Áo thun cotton cổ tròn";
    private static final VndFormatter MONEY = new VndFormatter();

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
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

    // ------------------------------------------------------------------ helpers

    private static void awaitToast(String text) {
        FxTestSupport.await("the toast \"" + text + "\"", () -> ui.toastTexts().contains(text));
    }

    private static String money(long amount) {
        return MONEY.format(BigDecimal.valueOf(amount));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void cartButton_isEnabled_andFiringItOpensTheDrawerWithTheEmptyStateForAGuest() {
        assertThat(FxTestSupport.callOnFx(() -> ui.nodeRequired("cartButton").isDisabled()))
                .as("D4 attached the cart button").isFalse();

        ui.openCart();

        assertThat(ui.drawerClosed()).isFalse();
        assertThat(ui.node("drawerScrim")).isNotNull();
        assertThat(ui.text(ui.nodeRequired("cartDrawerTitle"))).isEqualTo("Giỏ hàng");
        assertThat(ui.shownInScene("cartEmpty")).isTrue();
        assertThat(ui.labelsUnder(ui.nodeRequired("cartEmpty"))).contains("Giỏ hàng của bạn đang trống.",
                "Tiếp tục mua sắm");
        assertThat(ui.shownInScene("checkoutButton")).as("no checkout button on an empty cart").isFalse();
        ui.assertNoErrorDialog();
    }

    @Test
    void continueShopping_fromTheEmptyDrawer_closesTheDrawerAndOpensTheCatalog() {
        ui.openCart();

        ui.fire("continueShopping");

        FxTestSupport.await("the catalog screen", () -> ui.inContent("catalogScreen") != null);
        FxTestSupport.await("the drawer to close", ui::drawerClosed);
        ui.assertNoErrorDialog();
    }

    @Test
    void drawer_afterAGuestAddedTwoProducts_showsTheLinesTheLineTotalsAndTheTotal() {
        ui.addFromDetail(KEYBOARD, 1, 1);
        ui.addFromDetail(T_SHIRT, 2, 3);
        long keyboard = ui.productId(KEYBOARD);
        long shirt = ui.productId(T_SHIRT);

        ui.openCart();

        assertThat(ui.node("cartLine-" + keyboard)).isNotNull();
        assertThat(ui.node("cartLine-" + shirt)).isNotNull();
        assertThat(ui.labelsUnder(ui.nodeRequired("cartLine-" + keyboard))).contains(KEYBOARD, money(1_250_000));
        assertThat(ui.text(ui.nodeRequired("lineTotal-" + shirt))).isEqualTo(money(298_000));
        assertThat(ui.text(ui.nodeRequired("qtyField-" + shirt))).isEqualTo("2");
        assertThat(ui.text(ui.nodeRequired("cartTotal"))).isEqualTo(money(1_548_000));
        assertThat(ui.shownInScene("checkoutButton")).isTrue();
        assertThat(ui.shownInScene("cartEmpty")).isFalse();
        assertThat(ui.drawerClosed()).isFalse();
        ui.assertNoErrorDialog();
    }

    @Test
    void plusButton_raisesTheQuantityTheLineTotalTheTotalAndTheBadge() {
        ui.addFromDetail(T_SHIRT, 1, 1);
        long shirt = ui.productId(T_SHIRT);
        ui.openCart();
        ui.dismissToasts();

        ui.fire("qtyPlus-" + shirt);

        FxTestSupport.await("the badge to show 2", () -> "2".equals(ui.badgeText()));
        FxTestSupport.await("the line to show 2",
                () -> ui.node("qtyField-" + shirt) != null && "2".equals(ui.text(ui.node("qtyField-" + shirt))));
        assertThat(ui.text(ui.nodeRequired("lineTotal-" + shirt))).isEqualTo(money(298_000));
        assertThat(ui.text(ui.nodeRequired("cartTotal"))).isEqualTo(money(298_000));
        awaitToast("Đã cập nhật số lượng.");
        ui.assertNoErrorDialog();
    }

    @Test
    void minusButton_isDisabledAtOne_andEnabledAboveOne() {
        ui.addFromDetail(T_SHIRT, 2, 2);
        long shirt = ui.productId(T_SHIRT);
        ui.openCart();
        assertThat(FxTestSupport.callOnFx(() -> ui.nodeRequired("qtyMinus-" + shirt).isDisabled())).isFalse();

        ui.fire("qtyMinus-" + shirt);

        FxTestSupport.await("the badge to show 1", () -> "1".equals(ui.badgeText()));
        FxTestSupport.await("the line to show 1 with the minus button locked",
                () -> ui.node("qtyField-" + shirt) != null && "1".equals(ui.text(ui.node("qtyField-" + shirt)))
                        && FxTestSupport.callOnFx(() -> ui.node("qtyMinus-" + shirt).isDisabled()));
    }

    @Test
    void quantityField_withTextThatIsNotANumber_toastsAndPutsTheOldNumberBackWithoutWriting() {
        ui.addFromDetail(T_SHIRT, 1, 1);
        long shirt = ui.productId(T_SHIRT);
        ui.openCart();
        ui.dismissToasts();
        Node field = ui.nodeRequired("qtyField-" + shirt);

        ui.setText("qtyField-" + shirt, "abc");
        ui.pressKey(field, KeyCode.ENTER);

        awaitToast("Số lượng không hợp lệ.");
        assertThat(ui.text(ui.nodeRequired("qtyField-" + shirt))).isEqualTo("1");
        assertThat(ui.badgeText()).isEqualTo("1");
        ui.assertNoErrorDialog();
    }

    @Test
    void quantityField_aboveTheStock_isCappedAtTheStockWithTheStockToast() {
        ui.addFromDetail(KEYBOARD, 1, 1);
        long keyboard = ui.productId(KEYBOARD);
        ui.openCart();
        ui.dismissToasts();
        Node field = ui.nodeRequired("qtyField-" + keyboard);

        ui.setText("qtyField-" + keyboard, "20");
        ui.pressKey(field, KeyCode.ENTER);

        awaitToast("Chỉ còn 8 sản phẩm trong kho.");
        FxTestSupport.await("the badge to show the stock", () -> "8".equals(ui.badgeText()));
        FxTestSupport.await("the field to show the stock", () -> ui.node("qtyField-" + keyboard) != null
                && "8".equals(ui.text(ui.node("qtyField-" + keyboard))));
        assertThat(ui.text(ui.nodeRequired("cartTotal"))).isEqualTo(money(8 * 1_250_000L));
    }

    @Test
    void removeLine_removesTheLineShowsTheEmptyStateAndZerosTheBadge() {
        ui.addFromDetail(T_SHIRT, 1, 1);
        long shirt = ui.productId(T_SHIRT);
        ui.openCart();
        ui.dismissToasts();

        ui.fire("removeLine-" + shirt);

        FxTestSupport.await("the empty state", () -> ui.shownInScene("cartEmpty"));
        assertThat(ui.node("cartLine-" + shirt)).isNull();
        assertThat(ui.shownInScene("checkoutButton")).isFalse();
        FxTestSupport.await("the badge to show 0", () -> "0".equals(ui.badgeText()));
        awaitToast("Đã xóa sản phẩm khỏi giỏ hàng.");
        ui.assertNoErrorDialog();
    }

    @Test
    void escape_closesTheDrawerAndReturnsTheFocusToTheCartButton() {
        Button cartButton = (Button) ui.nodeRequired("cartButton");
        FxTestSupport.runOnFx(cartButton::requestFocus);
        assertThat(FxTestSupport.callOnFx(() -> mainStage.getScene().getFocusOwner())).isSameAs(cartButton);
        ui.openCart();
        assertThat(ui.drawerClosed()).isFalse();

        ui.pressKey(ui.nodeRequired("cartDrawer"), KeyCode.ESCAPE);

        assertThat(FxTestSupport.callOnFx(() -> ui.drawerHost().isShowing())).isFalse();
        FxTestSupport.await("the drawer nodes to leave", ui::drawerClosed);
        assertThat(FxTestSupport.callOnFx(() -> mainStage.getScene().getFocusOwner()))
                .as("focus goes back to #cartButton").isSameAs(cartButton);
        assertThat(FxTestSupport.callOnFx(cartButton::isDisabled)).as("the shell is usable again").isFalse();
    }

    @Test
    void closeButtonAndAClickOnTheScrim_bothCloseTheDrawer() {
        ui.openCart();
        ui.fire("cartDrawerClose");
        FxTestSupport.await("the drawer to close with the Đóng button", ui::drawerClosed);

        ui.openCart();
        Node scrim = ui.nodeRequired("drawerScrim");
        FxTestSupport.runOnFx(() -> Event.fireEvent(scrim, UiDriver.clickOn(scrim)));

        FxTestSupport.await("the drawer to close with a click outside", ui::drawerClosed);
        ui.assertNoErrorDialog();
    }

    @Test
    void whileTheDrawerIsOpen_theShellBehindIsDisabled_andItIsEnabledAgainAfterClosing() {
        ui.openCart();

        assertThat(FxTestSupport.callOnFx(() -> ui.nodeRequired("topBar").isDisabled())).isTrue();
        assertThat(FxTestSupport.callOnFx(() -> ui.nodeRequired("content").isDisabled())).isTrue();

        FxTestSupport.runOnFx(() -> ui.drawerHost().close());

        assertThat(FxTestSupport.callOnFx(() -> ui.nodeRequired("topBar").isDisabled())).isFalse();
        assertThat(FxTestSupport.callOnFx(() -> ui.nodeRequired("content").isDisabled())).isFalse();
    }

    @Test
    void addToCartToast_hasAViewCartButtonThatOpensTheDrawer() {
        ui.addFromDetail(T_SHIRT, 1, 1);
        FxTestSupport.await("the \"Xem giỏ hàng\" button of the toast", () -> ui.node("toastAction") != null);
        assertThat(ui.toastTexts()).contains("Đã thêm vào giỏ hàng.");
        assertThat(ui.text(ui.nodeRequired("toastAction"))).isEqualTo("Xem giỏ hàng");
        assertThat(ui.drawerClosed()).isTrue();

        ui.fire("toastAction");

        FxTestSupport.await("the drawer to open", () -> ui.node("cartDrawer") != null && ui.shownInScene("cartLines"));
        assertThat(ui.node("cartLine-" + ui.productId(T_SHIRT))).isNotNull();
        FxTestSupport.await("the toast to be dismissed by its button", () -> ui.node("toastAction") == null);
        ui.assertNoErrorDialog();
    }
}

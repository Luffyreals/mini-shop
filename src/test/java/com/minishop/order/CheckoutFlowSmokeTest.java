package com.minishop.order;

import com.minishop.common.VndFormatter;
import com.minishop.support.FxTestSupport;
import com.minishop.support.StandaloneApp;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import com.minishop.user.DemoAccounts;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkout, order history, order detail and cancel on the real JavaFX toolkit (AC-009-4, -10, -13; AC-004-15 to -17
 * through the "Đặt hàng" path; AC-005-2, -3, -7, -9, -20, -23, -24, -27, -32; D4): the real app with an in-memory H2
 * database and the seed. The tests sign in through the real login dialog and the account menu, and drive the screens
 * by firing buttons, pressing keys on nodes and looking up nodes. No robot. Every test starts as a guest on the
 * home screen with empty carts and may place orders freely, because this class owns its application and database.
 */
@Tag("ui")
class CheckoutFlowSmokeTest {

    private static final String MOUSE = "Chuột không dây văn phòng";
    private static final long MOUSE_PRICE = 190_000;
    private static final Pattern BANNER = Pattern.compile("Đặt hàng thành công\\. Mã đơn của bạn là #(\\d+)\\.");
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

    private static String money(long amount) {
        return MONEY.format(BigDecimal.valueOf(amount));
    }

    /** Waits for the checkout screen with its summary loaded to {@code total}. */
    private static void awaitCheckout(long total) {
        FxTestSupport.await("the checkout screen with a total of " + money(total), () -> {
            Node totalLabel = ui.inContent("checkoutTotal");
            return ui.inContent("checkoutScreen") != null && totalLabel != null
                    && money(total).equals(ui.text(totalLabel))
                    && ui.inContent("phoneField") != null && ui.inContent("confirmOrder") != null
                    && ui.shownInContent("phoneField");
        });
    }

    private static void fillCheckout(String phone, String address, String note) {
        FxTestSupport.runOnFx(() -> {
            ((TextInputControl) ui.inContentRequired("phoneField")).setText(phone);
            ((TextInputControl) ui.inContentRequired("addressField")).setText(address);
            ((TextInputControl) ui.inContentRequired("noteField")).setText(note);
        });
    }

    private static long idFromBanner() {
        Matcher matcher = BANNER.matcher(ui.text(ui.inContentRequired("orderBanner")));
        assertThat(matcher.matches()).as("the banner text").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /** The logged-in user buys one mouse through the real screens and ends on the detail of the new order. */
    private static long placeOneMouseOrder() {
        ui.addFromDetail(MOUSE, 1, 1);
        ui.openCart();
        ui.fire("checkoutButton");
        awaitCheckout(MOUSE_PRICE);
        fillCheckout("0912345678", "12 Nguyễn Trãi, Quận 1, TP.HCM", "");
        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("confirmOrder")).fire());
        FxTestSupport.await("the order detail with the banner", () -> ui.inContent("orderDetailScreen") != null
                && ui.inContent("orderBanner") != null && ui.shownInContent("orderBanner"));
        return idFromBanner();
    }

    private static void awaitOrderStatus(String label) {
        FxTestSupport.await("the order status " + label, () -> {
            Node status = ui.inContent("orderStatus");
            return status != null && label.equals(ui.text(status));
        });
    }

    private static void awaitToast(String text) {
        FxTestSupport.await("the toast \"" + text + "\"", () -> ui.toastTexts().contains(text));
    }

    // ------------------------------------------------------------------ checkout

    @Test
    void checkoutButton_asAGuestWithAFilledCart_asksToLogInThenOpensTheCheckoutWithTheMergedCart() {
        ui.addFromDetail(MOUSE, 2, 2);
        ui.openCart();

        ui.fire("checkoutButton");

        FxTestSupport.await("the drawer to close", ui::drawerClosed);
        FxTestSupport.await("the login dialog", () -> ui.windowWith("loginSubmit") != null);
        assertThat(ui.inContent("checkoutScreen")).as("not before the login").isNull();

        ui.submitLoginDialog(DemoAccounts.USER_EMAIL, StandaloneApp.DEV_USER_PASSWORD);

        awaitCheckout(2 * MOUSE_PRICE);
        assertThat(ui.labelsUnder(ui.inContentRequired("summaryLines"))).contains(MOUSE);
        assertThat(ui.text(ui.inContentRequired("receiverNameField"))).isEqualTo("Khách hàng mẫu");
        assertThat(ui.text(ui.inContentRequired("phoneField"))).as("the seed user has no phone").isEmpty();
        assertThat(ui.text(ui.inContentRequired("paymentMethod"))).isEqualTo("Thanh toán khi nhận hàng (COD)");
        assertThat(FxTestSupport.callOnFx(() -> ui.inContentRequired("confirmOrder").isDisabled())).isFalse();
        FxTestSupport.await("the first empty field to have the focus", () -> FxTestSupport.callOnFx(
                () -> mainStage.getScene().getFocusOwner()) == ui.inContent("phoneField"));
        ui.assertNoErrorDialog();
    }

    @Test
    void checkoutButton_whenTheLoginDialogIsClosedWithoutLoggingIn_staysOnTheScreenItWasOn() {
        ui.addFromDetail(MOUSE, 1, 1);
        ui.openCart();
        ui.fire("checkoutButton");
        FxTestSupport.await("the login dialog", () -> ui.windowWith("authClose") != null);

        ui.fireIn(ui.windowWith("authClose"), "authClose");

        FxTestSupport.await("the dialog to close", () -> ui.windowWith("authDialog") == null);
        FxTestSupport.flushFx();
        assertThat(FxTestSupport.callOnFx(() -> ui.session().isLoggedIn())).isFalse();
        assertThat(ui.inContent("checkoutScreen")).isNull();
        assertThat(ui.inContent("productDetailScreen")).as("still on the screen before the cart").isNotNull();
        ui.assertNoErrorDialog();
    }

    @Test
    void confirmOrder_withEmptyPhoneAndAddress_showsTheErrorsUnderTheFieldsAndPlacesNothing() {
        ui.logInAsUser();
        int stockBefore = ui.stockOf(MOUSE);
        ui.addFromDetail(MOUSE, 1, 1);
        ui.openCart();
        ui.fire("checkoutButton");
        awaitCheckout(MOUSE_PRICE);

        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("confirmOrder")).fire());

        assertThat(ui.text(ui.inContentRequired("phoneError"))).isEqualTo("Vui lòng nhập số điện thoại.");
        assertThat(ui.text(ui.inContentRequired("addressError"))).isEqualTo("Vui lòng nhập địa chỉ giao hàng.");
        assertThat(ui.inContent("checkoutScreen")).isNotNull();
        assertThat(ui.inContent("orderDetailScreen")).isNull();
        FxTestSupport.await("the focus on the first field with an error", () -> FxTestSupport.callOnFx(
                () -> mainStage.getScene().getFocusOwner()) == ui.inContent("phoneField"));
        assertThat(ui.stockOf(MOUSE)).isEqualTo(stockBefore);
        assertThat(ui.badgeText()).as("the cart is kept").isEqualTo("1");
        ui.assertNoErrorDialog();
    }

    @Test
    void confirmOrder_withAValidForm_opensTheNewOrderWithTheBannerTakesStockAndEmptiesTheCart() {
        ui.logInAsUser();
        int stockBefore = ui.stockOf(MOUSE);
        ui.addFromDetail(MOUSE, 1, 1);
        ui.openCart();
        ui.fire("checkoutButton");
        awaitCheckout(MOUSE_PRICE);
        fillCheckout("0912 345 678", "12 Nguyễn Trãi, Quận 1, TP.HCM", "<b>giao giờ hành chính</b>");

        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("confirmOrder")).fire());

        FxTestSupport.await("the order detail with the banner", () -> ui.inContent("orderDetailScreen") != null
                && ui.inContent("orderBanner") != null && ui.shownInContent("orderBanner"));
        long id = idFromBanner();
        FxTestSupport.await("the title", () -> ui.inContent("orderTitle") != null
                && ("Đơn hàng #" + id).equals(ui.text(ui.inContent("orderTitle"))));
        awaitOrderStatus("Chờ xử lý");
        assertThat(ui.labelsUnder(ui.inContentRequired("orderDetailScreen")))
                .contains("0912345678", "<b>giao giờ hành chính</b>", "Khách hàng mẫu", MOUSE,
                        "Thanh toán khi nhận hàng (COD)");
        assertThat(ui.text(ui.inContentRequired("orderTotal"))).isEqualTo(money(MOUSE_PRICE));
        assertThat(ui.shownInContent("cancelOrder")).as("a PENDING order can be cancelled").isTrue();
        FxTestSupport.await("the badge to show 0", () -> "0".equals(ui.badgeText()));
        assertThat(ui.stockOf(MOUSE)).isEqualTo(stockBefore - 1);

        ui.openCart();
        assertThat(ui.shownInScene("cartEmpty")).as("the cart was emptied by the order").isTrue();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ order history, detail, cancel

    @Test
    void ordersMenuItem_listsThePlacedOrder_andOpeningItShowsTheDetailWithoutTheBanner() {
        ui.logInAsUser();
        long id = placeOneMouseOrder();

        ui.fireAccountMenuItem("accountMenuOrders");

        FxTestSupport.await("the order list with the new order",
                () -> ui.inContent("ordersScreen") != null && ui.inContent("orderRow-" + id) != null);
        assertThat(ui.labelsUnder(ui.inContentRequired("orderRow-" + id))).contains("#" + id, "Chờ xử lý",
                money(MOUSE_PRICE));
        assertThat(ui.shownInContent("ordersEmpty")).isFalse();

        ui.pressKey(ui.inContentRequired("orderRow-" + id), KeyCode.ENTER);

        FxTestSupport.await("the order detail", () -> ui.inContent("orderDetailScreen") != null
                && ui.inContent("orderTitle") != null && ("Đơn hàng #" + id).equals(ui.text(ui.inContent("orderTitle"))));
        assertThat(ui.shownInContent("orderBanner")).as("the banner is only for the order just placed").isFalse();

        ui.fire("backToOrders");
        FxTestSupport.await("the order list again", () -> ui.inContent("orderRow-" + id) != null);
        ui.assertNoErrorDialog();
    }

    @Test
    void ordersMenuItem_forACustomerWithoutOrders_showsTheEmptyStateWithAWayToTheCatalog() {
        ui.logInAsAdmin();

        ui.fireAccountMenuItem("accountMenuOrders");

        FxTestSupport.await("the empty order list", () -> ui.inContent("ordersScreen") != null
                && ui.shownInContent("ordersEmpty"));
        assertThat(ui.labelsUnder(ui.inContentRequired("ordersEmpty"))).contains("Bạn chưa có đơn hàng nào.",
                "Bắt đầu mua sắm");

        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("startShopping")).fire());

        FxTestSupport.await("the catalog", () -> ui.inContent("catalogScreen") != null);
        ui.assertNoErrorDialog();
    }

    @Test
    void cancelOrder_asksToConfirm_keepsTheOrderOnDismiss_andOnConfirmCancelsItAndRestoresStock() {
        ui.logInAsUser();
        int stockBefore = ui.stockOf(MOUSE);
        long id = placeOneMouseOrder();
        assertThat(ui.stockOf(MOUSE)).isEqualTo(stockBefore - 1);
        ui.fireAccountMenuItem("accountMenuOrders");
        FxTestSupport.await("the order list", () -> ui.inContent("orderRow-" + id) != null);
        ui.pressKey(ui.inContentRequired("orderRow-" + id), KeyCode.ENTER);
        FxTestSupport.await("the order detail", () -> ui.shownInContent("cancelOrder"));
        ui.dismissToasts();

        // Keep the order: nothing changes
        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("cancelOrder")).fire());
        FxTestSupport.await("the confirm dialog", () -> ui.windowWith("confirmDialog") != null);
        Window dialog = ui.windowWith("confirmDialog");
        assertThat(ui.text(ui.inWindow(dialog, "confirmMessage"))).isEqualTo("Bạn có chắc muốn hủy đơn hàng này?");
        assertThat(ui.text(ui.inWindow(dialog, "confirmAccept"))).isEqualTo("Hủy đơn hàng");
        ui.fireIn(dialog, "confirmDismiss");
        FxTestSupport.await("the dialog to close", () -> ui.windowWith("confirmDialog") == null);
        FxTestSupport.flushFx();
        assertThat(ui.text(ui.inContentRequired("orderStatus"))).isEqualTo("Chờ xử lý");
        assertThat(ui.shownInContent("cancelOrder")).isTrue();
        assertThat(ui.stockOf(MOUSE)).as("dismissing changes nothing").isEqualTo(stockBefore - 1);

        // Confirm: the order is cancelled and the stock comes back
        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("cancelOrder")).fire());
        FxTestSupport.await("the confirm dialog again", () -> ui.windowWith("confirmDialog") != null);
        ui.fireIn(ui.windowWith("confirmDialog"), "confirmAccept");

        awaitOrderStatus("Đã hủy");
        FxTestSupport.await("the cancel button to go", () -> !ui.shownInContent("cancelOrder"));
        awaitToast("Đã hủy đơn hàng #" + id + ".");
        assertThat(ui.stockOf(MOUSE)).as("the stock is restored once").isEqualTo(stockBefore);
        ui.assertNoErrorDialog();
    }

    @Test
    void orderDetail_ofAnOrderThatDoesNotExist_toastsAndGoesBackToTheOrderListWithoutAnErrorDialog() {
        ui.logInAsUser();

        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(new OrderDetailRoute(999_999, null, false)));

        FxTestSupport.await("the order list", () -> ui.inContent("ordersScreen") != null);
        assertThat(ui.inContent("orderDetailScreen")).isNull();
        awaitToast("Không tìm thấy đơn hàng.");
        ui.assertNoErrorDialog();
    }

    @Test
    void orderDetail_ofAnotherUsersOrder_isNotFound_evenForAnAdmin() {
        ui.logInAsUser();
        long id = placeOneMouseOrder();
        ui.fireAccountMenuItem("accountMenuLogout");
        FxTestSupport.await("the logout", () -> !FxTestSupport.callOnFx(() -> ui.session().isLoggedIn()));
        ui.logInAsAdmin();
        ui.dismissToasts();

        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(new OrderDetailRoute(id, null, false)));

        FxTestSupport.await("the order list", () -> ui.inContent("ordersScreen") != null);
        assertThat(ui.inContent("orderDetailScreen")).isNull();
        awaitToast("Không tìm thấy đơn hàng.");
        assertThat(ui.inContent("orderRow-" + id)).as("the admin's list does not show the user's order").isNull();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logout_whileOnTheOrderDetail_returnsToTheHomeScreenWithAToast() {
        ui.logInAsUser();
        placeOneMouseOrder();
        ui.dismissToasts();

        ui.fireAccountMenuItem("accountMenuLogout");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        assertThat(ui.inContent("orderDetailScreen")).isNull();
        awaitToast("Bạn đã đăng xuất.");
        assertThat(ui.badgeText()).isEqualTo("0");
        assertThat(ui.text(ui.nodeRequired("accountButton"))).isEqualTo("Đăng nhập");
    }

    @Test
    void logout_whileOnTheCheckoutScreen_returnsToTheHomeScreen() {
        ui.logInAsUser();
        ui.addFromDetail(MOUSE, 1, 1);
        ui.openCart();
        ui.fire("checkoutButton");
        awaitCheckout(MOUSE_PRICE);

        ui.fireAccountMenuItem("accountMenuLogout");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        assertThat(ui.inContent("checkoutScreen")).isNull();
        FxTestSupport.await("the badge of a guest", () -> "0".equals(ui.badgeText()));
        ui.assertNoErrorDialog();
    }
}

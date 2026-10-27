package com.minishop.admin;

import com.minishop.common.VndFormatter;
import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import com.minishop.user.DemoAccounts;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testfx.api.FxToolkit;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin order flow of AC-006-52 on the real JavaFX toolkit (also AC-006-6, -38 to -45, AC-009-13; D5): a customer
 * places an order through the real checkout, the admin finds it on the dashboard, opens it and walks it through
 * the statuses, the customer sees the result, and the revenue grows. A second path cancels orders and checks that
 * the stock comes back (AC-006-42 to -44). The real app, an in-memory database and the seed; the screens are driven
 * by firing buttons, pressing keys on nodes and looking up nodes. No robot.
 *
 * <p>The class owns its application and database, so its orders may stay behind (every test ends with its orders in
 * a final status); the checks compare with the database, never with absolute numbers.
 */
@Tag("ui")
class AdminOrderFlowSmokeTest {

    private static final String MOUSE = "Chuột không dây văn phòng";
    private static final long MOUSE_PRICE = 190_000;
    private static final Pattern BANNER = Pattern.compile("Đặt hàng thành công\\. Mã đơn của bạn là #(\\d+)\\.");
    private static final VndFormatter MONEY = new VndFormatter();

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;
    private static AdminSmoke smoke;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(TestShellApp::new);
        FxTestSupport.await("the main window", () -> mainStage.isShowing());
        ui = new UiDriver(mainStage, app);
        smoke = new AdminSmoke(ui);
        jdbc = new JdbcTemplate(ui.bean(DataSource.class));
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

    private static long deliveredRevenue() {
        return jdbc.queryForObject("select coalesce(sum(total_amount), 0) from orders where status = 'DELIVERED'",
                BigDecimal.class).longValueExact();
    }

    private static int ordersWithStatus(String status) {
        return jdbc.queryForObject("select count(*) from orders where status = ?", Integer.class, status);
    }

    private static String statusInDb(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private static void awaitCheckout(long total) {
        FxTestSupport.await("the checkout screen with a total of " + money(total), () -> {
            Node totalLabel = ui.inContent("checkoutTotal");
            return ui.inContent("checkoutScreen") != null && totalLabel != null
                    && money(total).equals(ui.text(totalLabel))
                    && ui.inContent("phoneField") != null && ui.inContent("confirmOrder") != null
                    && ui.shownInContent("phoneField");
        });
    }

    /** The logged-in customer buys one mouse through the real screens and ends on the detail of the new order. */
    private static long placeOneMouseOrder() {
        ui.addFromDetail(MOUSE, 1, 1);
        ui.openCart();
        ui.fire("checkoutButton");
        awaitCheckout(MOUSE_PRICE);
        FxTestSupport.runOnFx(() -> {
            ((TextInputControl) ui.inContentRequired("phoneField")).setText("0912345678");
            ((TextInputControl) ui.inContentRequired("addressField")).setText("12 Nguyễn Trãi, Quận 1, TP.HCM");
        });
        FxTestSupport.runOnFx(() -> ((Button) ui.inContentRequired("confirmOrder")).fire());
        FxTestSupport.await("the order detail with the banner", () -> ui.inContent("orderDetailScreen") != null
                && ui.inContent("orderBanner") != null && ui.shownInContent("orderBanner"));
        Matcher matcher = BANNER.matcher(ui.text(ui.inContentRequired("orderBanner")));
        assertThat(matcher.matches()).as("the banner text").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static void openOrderFromTheList(long id) {
        smoke.goTo("orders", "adminOrdersScreen");
        FxTestSupport.await("the order in the list", () -> ui.inContent("adminOrderRow-" + id) != null);
        smoke.activate("adminOrderRow-" + id);
        smoke.awaitScreen("adminOrderDetailScreen");
        smoke.awaitText("the order title", "adminOrderTitle", "Đơn hàng #" + id);
        smoke.awaitText("the order status", "adminOrderStatus", "Chờ xử lý");
    }

    private static void changeStatus(long id, String target, String newLabel, String expectedToast) {
        ui.dismissToasts();
        smoke.fireInContent("statusAction-" + target);
        smoke.awaitText("the status after " + target, "adminOrderStatus", newLabel);
        smoke.awaitToast(expectedToast);
        assertThat(statusInDb(id)).isEqualTo(target);
    }

    // ------------------------------------------------------------------ AC-006-52

    @Test
    void adminWalksACustomersOrderToDelivered_theCustomerSeesIt_andTheRevenueGrows() {
        long revenueBefore = deliveredRevenue();
        ui.logInAsUser();
        long id = placeOneMouseOrder();
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        ui.logInAsAdmin();

        // Dashboard: the pending tile counts it, the latest-orders table shows it
        smoke.openAdminArea();
        int pending = ordersWithStatus("PENDING");
        assertThat(pending).isGreaterThanOrEqualTo(1);
        FxTestSupport.await("the pending tile to count " + pending, () -> ui.labelsUnder(
                ui.inContentRequired("adminCount-PENDING")).containsAll(List.of("Chờ xử lý",
                Integer.toString(pending))));
        assertThat(ui.inContent("latestOrder-" + id)).as("the new order is among the latest").isNotNull();
        assertThat(ui.labelsUnder(ui.inContentRequired("latestOrder-" + id)))
                .contains("#" + id, "Khách hàng mẫu", DemoAccounts.USER_EMAIL, money(MOUSE_PRICE), "Chờ xử lý");

        // The pending tile opens the order list filtered to PENDING
        smoke.activate("adminCount-PENDING");
        smoke.awaitScreen("adminOrdersScreen");
        FxTestSupport.await("the PENDING pill to be selected", () -> ui.node("orderFilter-PENDING") != null
                && smoke.isSelected("orderFilter-PENDING"));
        FxTestSupport.await("the order in the filtered list", () -> ui.inContent("adminOrderRow-" + id) != null);
        assertThat(smoke.isSelected("orderFilter-all")).isFalse();

        // Open it: the full detail (AC-006-40)
        smoke.activate("adminOrderRow-" + id);
        smoke.awaitScreen("adminOrderDetailScreen");
        smoke.awaitText("the order title", "adminOrderTitle", "Đơn hàng #" + id);
        smoke.awaitText("the order status", "adminOrderStatus", "Chờ xử lý");
        smoke.awaitText("the account", "adminOrderAccount", "Khách hàng mẫu (" + DemoAccounts.USER_EMAIL + ")");
        assertThat(smoke.text("adminOrderPhone")).isEqualTo("0912345678");
        assertThat(smoke.text("adminOrderAddress")).isEqualTo("12 Nguyễn Trãi, Quận 1, TP.HCM");
        assertThat(ui.labelsUnder(ui.inContentRequired("adminOrderLines"))).contains(MOUSE);
        assertThat(smoke.text("adminOrderTotal")).isEqualTo(money(MOUSE_PRICE));
        smoke.node("statusAction-CONFIRMED");
        smoke.node("statusAction-CANCELLED");
        assertThat(ui.inContent("statusAction-DELIVERED")).as("no jump to DELIVERED from PENDING").isNull();
        assertThat(ui.shownInContent("adminOrderFinal")).isFalse();

        // PENDING -> CONFIRMED -> SHIPPING without questions
        changeStatus(id, "CONFIRMED", "Đã xác nhận", "Đã chuyển đơn hàng #" + id + " sang trạng thái Đã xác nhận.");
        smoke.assertNoConfirm();
        smoke.node("statusAction-SHIPPING");
        changeStatus(id, "SHIPPING", "Đang giao", "Đã chuyển đơn hàng #" + id + " sang trạng thái Đang giao.");
        smoke.assertNoConfirm();
        FxTestSupport.await("the DELIVERED button", () -> ui.inContent("statusAction-DELIVERED") != null);

        // SHIPPING -> DELIVERED asks first; "Quay lại" changes nothing
        String question = "Đánh dấu đơn hàng #" + id + " đã giao? Không thể hoàn tác.";
        smoke.fireInContent("statusAction-DELIVERED");
        Window dialog = smoke.awaitConfirm(question);
        assertThat(ui.text(ui.inWindow(dialog, "confirmDismiss"))).isEqualTo("Quay lại");
        smoke.dismissConfirm(dialog);
        assertThat(smoke.text("adminOrderStatus")).isEqualTo("Đang giao");
        assertThat(statusInDb(id)).isEqualTo("SHIPPING");
        assertThat(deliveredRevenue()).isEqualTo(revenueBefore);

        ui.dismissToasts();
        smoke.fireInContent("statusAction-DELIVERED");
        smoke.acceptConfirm(smoke.awaitConfirm(question));
        smoke.awaitText("the status", "adminOrderStatus", "Đã giao");
        smoke.awaitToast("Đã chuyển đơn hàng #" + id + " sang trạng thái Đã giao.");
        FxTestSupport.await("the final-state sentence", () -> ui.shownInContent("adminOrderFinal"));
        assertThat(smoke.text("adminOrderFinal")).isEqualTo("Đơn hàng đã ở trạng thái cuối.");
        assertThat(ui.inContent("statusAction-DELIVERED")).isNull();
        assertThat(ui.inContent("statusAction-CANCELLED")).as("a delivered order cannot be cancelled").isNull();
        assertThat(statusInDb(id)).isEqualTo("DELIVERED");

        // Back to the dashboard: the revenue grew by the order total
        assertThat(deliveredRevenue()).isEqualTo(revenueBefore + MOUSE_PRICE);
        smoke.goTo("dashboard", "adminDashboardScreen");
        FxTestSupport.await("the revenue to read " + money(revenueBefore + MOUSE_PRICE), () -> {
            Node revenue = ui.inContent("revenueValue");
            return revenue != null && money(revenueBefore + MOUSE_PRICE).equals(ui.text(revenue));
        });

        // The customer sees "Đã giao" and no cancel button
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        ui.logInAsUser();
        ui.fireAccountMenuItem("accountMenuOrders");
        FxTestSupport.await("the customer's order list", () -> ui.inContent("orderRow-" + id) != null);
        assertThat(ui.labelsUnder(ui.inContentRequired("orderRow-" + id))).contains("#" + id, "Đã giao");
        ui.pressKey(ui.inContentRequired("orderRow-" + id), javafx.scene.input.KeyCode.ENTER);
        FxTestSupport.await("the customer's order detail", () -> ui.inContent("orderStatus") != null
                && "Đã giao".equals(ui.text(ui.inContent("orderStatus"))));
        assertThat(ui.shownInContent("cancelOrder")).isFalse();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ AC-006-42 to -44

    @Test
    void adminCancelOfAConfirmedOrder_asksFirst_keepsItOnDismiss_andOnConfirmRestoresTheStock() {
        ui.logInAsUser();
        int stockBefore = ui.stockOf(MOUSE);
        long id = placeOneMouseOrder();
        assertThat(ui.stockOf(MOUSE)).as("the order took one").isEqualTo(stockBefore - 1);
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        ui.logInAsAdmin();
        smoke.openAdminArea();
        openOrderFromTheList(id);
        changeStatus(id, "CONFIRMED", "Đã xác nhận", "Đã chuyển đơn hàng #" + id + " sang trạng thái Đã xác nhận.");
        FxTestSupport.await("the cancel button", () -> ui.inContent("statusAction-CANCELLED") != null);
        String question = "Hủy đơn hàng #" + id + " và hoàn lại tồn kho?";

        // "Giữ đơn hàng": nothing changes
        smoke.fireInContent("statusAction-CANCELLED");
        Window dialog = smoke.awaitConfirm(question);
        assertThat(ui.text(ui.inWindow(dialog, "confirmDismiss"))).isEqualTo("Giữ đơn hàng");
        smoke.dismissConfirm(dialog);
        assertThat(smoke.text("adminOrderStatus")).isEqualTo("Đã xác nhận");
        assertThat(statusInDb(id)).isEqualTo("CONFIRMED");
        assertThat(ui.stockOf(MOUSE)).isEqualTo(stockBefore - 1);

        // Confirm: cancelled, stock back exactly once
        ui.dismissToasts();
        smoke.fireInContent("statusAction-CANCELLED");
        smoke.acceptConfirm(smoke.awaitConfirm(question));
        smoke.awaitText("the status", "adminOrderStatus", "Đã hủy");
        smoke.awaitToast("Đã hủy đơn hàng #" + id + " và hoàn lại tồn kho.");
        FxTestSupport.await("the final-state sentence", () -> ui.shownInContent("adminOrderFinal"));
        assertThat(statusInDb(id)).isEqualTo("CANCELLED");
        assertThat(ui.stockOf(MOUSE)).as("the stock is restored once").isEqualTo(stockBefore);
        ui.assertNoErrorDialog();
    }

    @Test
    void adminCancelOfAPendingOrder_restoresTheStockAndTheCustomerSeesItCancelled() {
        ui.logInAsUser();
        int stockBefore = ui.stockOf(MOUSE);
        long id = customerOrdersThenAdminOpensTheArea();
        assertThat(ui.stockOf(MOUSE)).isEqualTo(stockBefore - 1);
        openOrderFromTheList(id);
        ui.dismissToasts();

        smoke.fireInContent("statusAction-CANCELLED");
        smoke.acceptConfirm(smoke.awaitConfirm("Hủy đơn hàng #" + id + " và hoàn lại tồn kho?"));

        smoke.awaitText("the status", "adminOrderStatus", "Đã hủy");
        smoke.awaitToast("Đã hủy đơn hàng #" + id + " và hoàn lại tồn kho.");
        assertThat(ui.stockOf(MOUSE)).isEqualTo(stockBefore);

        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        ui.logInAsUser();
        ui.fireAccountMenuItem("accountMenuOrders");
        FxTestSupport.await("the order in the customer's list", () -> ui.inContent("orderRow-" + id) != null);
        assertThat(ui.labelsUnder(ui.inContentRequired("orderRow-" + id))).contains("Đã hủy");
        ui.assertNoErrorDialog();
    }

    /** The customer (already logged in) places an order, logs out, and the admin logs in and opens the area. */
    private static long customerOrdersThenAdminOpensTheArea() {
        long id = placeOneMouseOrder();
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        ui.logInAsAdmin();
        smoke.openAdminArea();
        return id;
    }

    // ------------------------------------------------------------------ list filters (AC-006-38, -39)

    @Test
    void orderList_filteredByAStatusWithNoOrders_showsTheEmptySentence_andAllShowsTheOrdersAgain() {
        assertThat(ordersWithStatus("SHIPPING")).as("no order is left in SHIPPING by another test").isZero();
        ui.logInAsAdmin();
        smoke.openAdminArea();
        smoke.goTo("orders", "adminOrdersScreen");

        smoke.fireToggle("orderFilter-SHIPPING");

        FxTestSupport.await("the empty sentence", () -> ui.shownInContent("adminOrdersEmpty")
                && "Không có đơn hàng nào ở trạng thái Đang giao.".equals(ui.text(ui.inContent("adminOrdersEmptyText"))));
        assertThat(smoke.isSelected("orderFilter-SHIPPING")).isTrue();
        assertThat(smoke.isSelected("orderFilter-all")).isFalse();

        smoke.fireToggle("orderFilter-all");

        int total = jdbc.queryForObject("select count(*) from orders", Integer.class);
        FxTestSupport.await("the list to show again", () -> ui.shownInContent("adminOrdersEmpty") == (total == 0)
                && (total == 0 || ui.shownInContent("adminOrderRows")));
        assertThat(smoke.isSelected("orderFilter-all")).isTrue();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ not found (AC-009-13)

    @Test
    void orderDetail_ofAnOrderThatDoesNotExist_toastsAndGoesBackToTheOrderListWithoutAnErrorDialog() {
        ui.logInAsAdmin();
        smoke.openAdminArea();
        ui.dismissToasts();

        FxTestSupport.runOnFx(() -> ui.navigator().navigateTo(new AdminOrderDetailRoute(999_999,
                AdminOrdersRoute.all())));

        smoke.awaitScreen("adminOrdersScreen");
        smoke.awaitToast("Không tìm thấy đơn hàng.");
        assertThat(ui.inContent("adminOrderDetailScreen")).isNull();
        ui.assertNoErrorDialog();
    }

    // ------------------------------------------------------------------ logout from an order screen (AC-009-10)

    @Test
    void logout_whileOnTheAdminOrderDetail_returnsToTheHomeScreen() {
        ui.logInAsUser();
        long id = placeOneMouseOrder();
        smoke.logOut();
        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        ui.logInAsAdmin();
        smoke.openAdminArea();
        openOrderFromTheList(id);
        ui.dismissToasts();

        ui.fireAccountMenuItem("accountMenuLogout");

        FxTestSupport.await("the home screen", () -> ui.inContent("homeScreen") != null);
        assertThat(ui.inContent("adminOrderDetailScreen")).isNull();
        smoke.awaitToast("Bạn đã đăng xuất.");
        assertThat(statusInDb(id)).as("the order is untouched").isEqualTo("PENDING");
        // Leave nothing pending behind for the other tests
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", id);
        jdbc.update("update products set stock = stock + 1 where name = ?", MOUSE);
        ui.assertNoErrorDialog();
    }
}

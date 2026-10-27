package com.minishop.order;

import com.minishop.support.FxTestSupport;
import com.minishop.support.TestShellApp;
import com.minishop.support.UiDriver;
import com.minishop.user.DemoAccounts;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testfx.api.FxToolkit;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-screen error block of "Đơn hàng của tôi" on the real JavaFX toolkit (AC-009-12, QĐ12 of the D6 plan): a real
 * database failure cannot be provoked in a running app, so the real {@link OrderService} bean is wrapped by a Mockito
 * delegate that throws once on the next {@code listOrders} when a test asks for it and forwards every other call to
 * the real (transactional) bean. The failure then travels the real path: background task, presenter, error dialog,
 * screen. No robot: buttons are fired and nodes looked up.
 */
@Tag("ui")
class OrderHistoryLoadErrorSmokeTest {

    /** Makes the next {@code listOrders} throw once; counts every {@code listOrders} call. */
    static final AtomicBoolean FAIL_NEXT_LIST = new AtomicBoolean();
    static final AtomicInteger LIST_CALLS = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    static class FlakyOrderService {

        @Bean
        static BeanPostProcessor flakyOrderServicePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof OrderService real)) {
                        return bean;
                    }
                    Answer<Object> forward = AdditionalAnswers.delegatesTo(real);
                    return Mockito.mock(OrderService.class, invocation -> {
                        if ("listOrders".equals(invocation.getMethod().getName())) {
                            LIST_CALLS.incrementAndGet();
                            if (FAIL_NEXT_LIST.compareAndSet(true, false)) {
                                throw new IllegalStateException("test: the database is down");
                            }
                        }
                        return forward.answer(invocation);
                    });
                }
            };
        }
    }

    private static Thread.UncaughtExceptionHandler previousHandler;
    private static TestShellApp app;
    private static Stage mainStage;
    private static UiDriver ui;

    @BeforeAll
    static void startApp() throws Exception {
        mainStage = FxTestSupport.startToolkit();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        app = (TestShellApp) FxToolkit.setupApplication(() -> new TestShellApp(FlakyOrderService.class));
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
        FAIL_NEXT_LIST.set(false);
        ui.resetToHome();
    }

    // ------------------------------------------------------------------ helpers

    /** One pending order for the demo customer, inserted directly (the test database belongs to this class). */
    private static long insertOrderOfTheDemoCustomer() {
        JdbcTemplate jdbc = new JdbcTemplate(ui.bean(DataSource.class));
        jdbc.update("insert into orders (user_id, status, receiver_name, phone, shipping_address, payment_method,"
                + " total_amount) select id, 'PENDING', 'Nguyễn Văn An', '0912345678', '12 Lê Lợi, Quận 1, TP.HCM',"
                + " 'COD', 1250000 from users where email = ?", DemoAccounts.USER_EMAIL);
        Long id = jdbc.queryForObject("select max(o.id) from orders o join users u on u.id = o.user_id"
                + " where u.email = ?", Long.class, DemoAccounts.USER_EMAIL);
        assertThat(id).isNotNull();
        return id;
    }

    private static boolean errorDialogShowing() {
        return ui.windowWith("errorDialog") != null;
    }

    // ------------------------------------------------------------------ the test

    // Lặp lại vì lỗi nạp có thể về trước khi nội dung ScrollPane vào Scene: khi đó focus vào "Thử lại" chỉ đến
    // được nhờ listener scene của trang (OrderHistoryScreen). Không có listener, khoảng 6/10 lần chạy thì hỏng
    @RepeatedTest(5)
    void ordersScreen_afterAnUnexpectedLoadFailure_showsTheErrorBlockAndTheDialog_andRetryShowsTheList() {
        long orderId = insertOrderOfTheDemoCustomer();
        ui.logInAsUser();
        FAIL_NEXT_LIST.set(true);
        int callsBefore = LIST_CALLS.get();

        ui.fireAccountMenuItem("accountMenuOrders");

        FxTestSupport.await("the orders screen with the load error block",
                () -> ui.inContent("ordersScreen") != null && ui.inContent("ordersLoadError") != null
                        && ui.shownInContent("ordersLoadError"));
        FxTestSupport.await("the general error dialog", OrderHistoryLoadErrorSmokeTest::errorDialogShowing);
        assertThat(LIST_CALLS.get()).as("one failed read").isEqualTo(callsBefore + 1);
        assertThat(ui.labelsUnder(ui.inContentRequired("ordersLoadError")))
                .contains("Đã có lỗi xảy ra. Vui lòng thử lại.", "Thử lại");
        assertThat(ui.shownInContent("orderRows")).as("no table behind the error block").isFalse();
        assertThat(ui.shownInContent("ordersEmpty")).as("an empty state would be a lie").isFalse();
        assertThat(ui.shownInContent("ordersRetry")).isTrue();

        Window dialog = ui.windowWith("errorDialog");
        assertThat(ui.text(ui.inWindow(dialog, "errorMessage"))).isEqualTo("Đã có lỗi xảy ra. Vui lòng thử lại.");
        ui.fireIn(dialog, "errorCloseButton");
        FxTestSupport.await("the error dialog to close", () -> !errorDialogShowing());
        assertThat(ui.shownInContent("ordersLoadError")).as("the block stays after the dialog").isTrue();
        FxTestSupport.await("the focus on Thử lại", () -> FxTestSupport.callOnFx(
                () -> mainStage.getScene().getFocusOwner()) == ui.inContent("ordersRetry"));

        ui.fire("ordersRetry");

        FxTestSupport.await("the list after the retry", () -> ui.inContent("orderRow-" + orderId) != null
                && !ui.shownInContent("ordersLoadError"));
        assertThat(LIST_CALLS.get()).as("exactly one more read").isEqualTo(callsBefore + 2);
        Node row = ui.inContentRequired("orderRow-" + orderId);
        assertThat(ui.labelsUnder(row)).contains("#" + orderId, "Chờ xử lý");
        assertThat(ui.shownInContent("orderRows")).isTrue();
        assertThat(errorDialogShowing()).as("a retry that works raises no second dialog").isFalse();
    }
}

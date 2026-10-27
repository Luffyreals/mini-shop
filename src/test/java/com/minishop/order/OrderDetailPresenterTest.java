package com.minishop.order;

import com.minishop.common.NotFoundException;
import com.minishop.desktop.ConfirmDialog;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.support.ManualBackgroundTasks;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OrderDetailPresenter}: the order detail and the customer's own cancel (AC-005-23, -24, -27, -28, AC-009-13;
 * QĐ2, QĐ9, QĐ11 of the D4 plan). No JavaFX toolkit and no Spring: the order service, navigator, toasts, confirm
 * dialog, error reporter and session are mocks. The confirm dialog mock only records the question; a test decides
 * whether the user "confirms" by running the captured action.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderDetailPresenterTest {

    private static final long USER_ID = 3L;
    private static final CurrentUser USER = new CurrentUser(USER_ID, "user@minishop.local", "Khách hàng mẫu", Role.USER);
    private static final long ORDER_ID = 42L;
    private static final Instant PLACED = Instant.parse("2026-10-27T05:30:00Z");
    private static final OrdersRoute BACK_TO_PAGE_TWO = new OrdersRoute(2);

    @Mock
    private OrderService orders;
    @Mock
    private AppSession session;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private ConfirmDialog confirmDialog;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(session.currentUser()).thenReturn(Optional.of(USER));
    }

    private OrderDetailPresenter presenter() {
        return new OrderDetailPresenter(orders, session, direct, navigator, toasts, confirmDialog, errors);
    }

    private OrderDetailPresenter presenter(ManualBackgroundTasks manual) {
        return new OrderDetailPresenter(orders, session, manual, navigator, toasts, confirmDialog, errors);
    }

    // ------------------------------------------------------------------ test data

    private static OrderDetailView pending(String note) {
        return new OrderDetailView(ORDER_ID, PLACED, "Chờ xác nhận", "pending", "Thanh toán khi nhận hàng (COD)",
                "Nguyễn Văn An", "0912345678", "12 Lê Lợi, Quận 1, TP.HCM", note,
                List.of(new OrderLineView("Bàn phím cơ cỡ nhỏ", new BigDecimal("1250000"), 2, new BigDecimal("2500000")),
                        new OrderLineView("Chuột không dây văn phòng", new BigDecimal("190000"), 1,
                                new BigDecimal("190000"))),
                new BigDecimal("2690000"), true);
    }

    private static OrderDetailView cancelled() {
        return new OrderDetailView(ORDER_ID, PLACED, "Đã hủy", "cancelled", "Thanh toán khi nhận hàng (COD)",
                "Nguyễn Văn An", "0912345678", "12 Lê Lợi, Quận 1, TP.HCM", null,
                List.of(new OrderLineView("Bàn phím cơ cỡ nhỏ", new BigDecimal("1250000"), 2, new BigDecimal("2500000"))),
                new BigDecimal("2500000"), false);
    }

    private static OrderDetailView shipping() {
        return new OrderDetailView(ORDER_ID, PLACED, "Đang giao", "shipping", "Thanh toán khi nhận hàng (COD)",
                "Nguyễn Văn An", "0912345678", "12 Lê Lợi, Quận 1, TP.HCM", null,
                List.of(new OrderLineView("Bàn phím cơ cỡ nhỏ", new BigDecimal("1250000"), 2, new BigDecimal("2500000"))),
                new BigDecimal("2500000"), false);
    }

    private static OrderDetailRoute route(boolean justPlaced) {
        return new OrderDetailRoute(ORDER_ID, BACK_TO_PAGE_TWO, justPlaced);
    }

    private OrderDetailPresenter loaded(OrderDetailView view, boolean justPlaced) {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(view);
        OrderDetailPresenter presenter = presenter();
        presenter.load(route(justPlaced));
        return presenter;
    }

    private Runnable confirmedAction(OrderDetailPresenter presenter) {
        presenter.requestCancel();
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(eq("Bạn có chắc muốn hủy đơn hàng này?"), eq("Hủy đơn hàng"), eq("Giữ đơn hàng"),
                action.capture());
        return action.getValue();
    }

    // ------------------------------------------------------------------ loading (AC-005-23)

    @Test
    void load_showsTheTitleDateTotalAndLinesWithTheSnapshotPrices() {
        OrderDetailPresenter presenter = loaded(pending("Giao giờ hành chính"), false);

        verify(orders).getOrder(USER_ID, ORDER_ID);
        assertThat(presenter.titleTextProperty().get()).isEqualTo("Đơn hàng #42");
        assertThat(presenter.dateTextProperty().get()).isEqualTo("27/10/2026 12:30");
        assertThat(presenter.totalTextProperty().get()).isEqualTo("2.690.000 ₫");
        assertThat(presenter.lines()).containsExactly(
                new OrderDetailPresenter.LineRow("Bàn phím cơ cỡ nhỏ", "1.250.000 ₫", "2", "2.500.000 ₫"),
                new OrderDetailPresenter.LineRow("Chuột không dây văn phòng", "190.000 ₫", "1", "190.000 ₫"));
        assertThat(presenter.orderProperty().get().receiverName()).isEqualTo("Nguyễn Văn An");
        assertThat(presenter.orderProperty().get().phone()).isEqualTo("0912345678");
        assertThat(presenter.orderProperty().get().shippingAddress()).isEqualTo("12 Lê Lợi, Quận 1, TP.HCM");
        assertThat(presenter.orderProperty().get().note()).isEqualTo("Giao giờ hành chính");
        assertThat(presenter.orderProperty().get().paymentMethodLabel()).isEqualTo("Thanh toán khi nhận hàng (COD)");
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(toasts, navigator, errors);
    }

    @Test
    void load_ofAnOrderWithoutANote_exposesANullNoteSoTheScreenCanHideTheRow() {
        OrderDetailPresenter presenter = loaded(pending(null), false);

        assertThat(presenter.orderProperty().get().note()).isNull();
        assertThat(presenter.lines()).hasSize(2);
        verifyNoInteractions(errors);
    }

    @Test
    void load_readsTheUserWhenCalled_notWhenTheBackgroundWorkRuns() {
        when(orders.getOrder(anyLong(), anyLong())).thenReturn(pending(null));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        OrderDetailPresenter presenter = presenter(manual);

        presenter.load(route(false));
        when(session.currentUser()).thenReturn(Optional.empty());
        manual.run(0);

        verify(orders).getOrder(USER_ID, ORDER_ID);
    }

    @Test
    void load_whilePending_isLoadingAndHasNoOrderYet() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        OrderDetailPresenter presenter = presenter(manual);

        presenter.load(route(false));

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.orderProperty().get()).isNull();
        assertThat(presenter.cancellableProperty().get()).isFalse();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.orderProperty().get()).isNotNull();
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter().load(null));
    }

    @Test
    void load_asAGuest_neverCallsTheServiceAndGoesHome() {
        when(session.currentUser()).thenReturn(Optional.empty());
        OrderDetailPresenter presenter = presenter();

        presenter.load(route(false));

        verifyNoInteractions(orders);
        verify(navigator).goHome();
    }

    // ------------------------------------------------------------------ banner (question 1)

    @Test
    void bannerText_whenTheOrderWasJustPlaced_namesTheOrderNumber() {
        OrderDetailPresenter presenter = loaded(pending(null), true);

        assertThat(presenter.bannerTextProperty().get()).isEqualTo("Đặt hàng thành công. Mã đơn của bạn là #42.");
    }

    @Test
    void bannerText_whenOpenedFromTheList_isEmpty() {
        OrderDetailPresenter presenter = loaded(pending(null), false);

        assertThat(presenter.bannerTextProperty().get()).isEmpty();
    }

    // ------------------------------------------------------------------ cancellable (AC-005-27, -28)

    @Test
    void cancellable_followsTheViewOfThePendingOrder() {
        assertThat(loaded(pending(null), false).cancellableProperty().get()).isTrue();
    }

    @Test
    void cancellable_isFalseForAnOrderThatIsNoLongerPending() {
        OrderDetailPresenter presenter = loaded(shipping(), false);

        assertThat(presenter.cancellableProperty().get()).isFalse();
    }

    @Test
    void requestCancel_onAnOrderThatCannotBeCancelled_asksNothing() {
        OrderDetailPresenter presenter = loaded(shipping(), false);

        presenter.requestCancel();

        verifyNoInteractions(confirmDialog);
        verify(orders, never()).cancelByCustomer(anyLong(), anyLong());
    }

    @Test
    void requestCancel_beforeTheOrderIsLoaded_asksNothing() {
        presenter().requestCancel();

        verifyNoInteractions(confirmDialog, orders);
    }

    // ------------------------------------------------------------------ the confirm step

    @Test
    void requestCancel_onlyAsksTheQuestion_theServiceIsNotCalledUntilTheUserConfirms() {
        OrderDetailPresenter presenter = loaded(pending(null), false);

        presenter.requestCancel();

        verify(confirmDialog).ask(eq("Bạn có chắc muốn hủy đơn hàng này?"), eq("Hủy đơn hàng"), eq("Giữ đơn hàng"),
                any(Runnable.class));
        verify(orders, never()).cancelByCustomer(anyLong(), anyLong());
        verifyNoInteractions(toasts);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void requestCancel_whenTheUserKeepsTheOrder_nothingIsCalled() {
        OrderDetailPresenter presenter = loaded(pending(null), false);

        presenter.requestCancel(); // the dialog's dismiss button runs nothing

        verify(orders, never()).cancelByCustomer(anyLong(), anyLong());
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void confirmedCancel_callsCancelByCustomerExactlyOnceWithTheUserAndTheOrder() {
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = loaded(pending(null), false);
        Runnable onConfirm = confirmedAction(presenter);

        onConfirm.run();

        verify(orders, times(1)).cancelByCustomer(USER_ID, ORDER_ID);
    }

    // ------------------------------------------------------------------ cancel outcomes (AC-005-27, -28, AC-009-13)

    @Test
    void cancel_whenCancelled_toastsSuccessAndReloadsTheOrderWithoutTheCancelButton() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null), cancelled());
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = presenter();
        presenter.load(route(false));

        confirmedAction(presenter).run();

        verify(toasts).show("Đã hủy đơn hàng #42.", ToastKind.SUCCESS);
        verify(orders, times(2)).getOrder(USER_ID, ORDER_ID);
        assertThat(presenter.cancellableProperty().get()).isFalse();
        assertThat(presenter.orderProperty().get().statusLabel()).isEqualTo("Đã hủy");
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(errors, navigator);
    }

    @Test
    void cancel_ofAJustPlacedOrder_dropsTheJustPlacedBannerAfterTheReload() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null), cancelled());
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = presenter();
        presenter.load(route(true));
        assertThat(presenter.bannerTextProperty().get()).isNotEmpty();

        confirmedAction(presenter).run();

        assertThat(presenter.bannerTextProperty().get()).isEmpty();
    }

    @Test
    void cancel_whenTheOrderCannotBeCancelledAnymore_toastsAnErrorAndReloads() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null), shipping());
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.NOT_CANCELLABLE);
        OrderDetailPresenter presenter = presenter();
        presenter.load(route(false));

        confirmedAction(presenter).run();

        verify(toasts).show("Đơn hàng này không thể hủy nữa.", ToastKind.ERROR);
        verify(orders, times(2)).getOrder(USER_ID, ORDER_ID);
        assertThat(presenter.cancellableProperty().get()).isFalse();
        assertThat(presenter.orderProperty().get().statusLabel()).isEqualTo("Đang giao");
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(toasts, never()).show(eq("Đã hủy đơn hàng #42."), any());
    }

    @Test
    void load_whenTheOrderIsNotFound_toastsAndGoesBackToTheListPageItCameFrom() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenThrow(new NotFoundException("Order not found"));
        OrderDetailPresenter presenter = presenter();

        presenter.load(route(false));

        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verify(navigator).navigateTo(BACK_TO_PAGE_TWO);
        verifyNoInteractions(errors);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.orderProperty().get()).isNull();
    }

    @Test
    void load_whenTheOrderBelongsToSomeoneElse_looksExactlyLikeNotFound() {
        // The service answers an order of another user with NotFoundException (IDOR rule); the presenter shows the same text.
        when(orders.getOrder(USER_ID, 99L)).thenThrow(new NotFoundException("Order not found"));
        OrderDetailPresenter presenter = presenter();

        presenter.load(new OrderDetailRoute(99L, null, false));

        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verify(navigator).navigateTo(OrdersRoute.first());
    }

    @Test
    void cancel_whenTheOrderIsNotFound_toastsUnlocksAndGoesBackToTheList() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenThrow(new NotFoundException("Order not found"));
        OrderDetailPresenter presenter = presenter();
        presenter.load(route(false));

        confirmedAction(presenter).run();

        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verify(navigator).navigateTo(BACK_TO_PAGE_TWO);
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_whenTheServiceFailsUnexpectedly_reportsOnceAndShowsNoToast() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(orders.getOrder(USER_ID, ORDER_ID)).thenThrow(failure);
        OrderDetailPresenter presenter = presenter();

        presenter.load(route(false));

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
        assertThat(presenter.loadingProperty().get()).isFalse();
    }

    @Test
    void cancel_whenTheServiceFailsUnexpectedly_reportsOnceAndUnlocksTheButton() {
        RuntimeException failure = new IllegalStateException("lock timeout");
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenThrow(failure);
        OrderDetailPresenter presenter = presenter();
        presenter.load(route(false));

        confirmedAction(presenter).run();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.cancellableProperty().get()).as("still cancellable, nothing changed").isTrue();
        verifyNoInteractions(toasts, navigator);
    }

    // ------------------------------------------------------------------ busy (AC-005-29 to -31)

    @Test
    void cancel_whilePending_isBusyAndALaterConfirmationSendsNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null), cancelled());
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = presenter(manual);
        presenter.load(route(false));
        manual.run(0);
        presenter.requestCancel();
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(any(), any(), any(), action.capture());

        action.getValue().run();
        assertThat(presenter.busyProperty().get()).isTrue();
        action.getValue().run();
        presenter.requestCancel();

        assertThat(manual.submittedCount()).as("the load and one cancel").isEqualTo(2);
        verify(confirmDialog, times(1)).ask(any(), any(), any(), any());
    }

    @Test
    void cancel_staysBusyUntilTheOrderWasReloaded() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null), cancelled());
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = presenter(manual);
        presenter.load(route(false));
        manual.run(0);
        presenter.requestCancel();
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(any(), any(), any(), action.capture());
        action.getValue().run();

        manual.run(1); // the cancel is done, the reload is queued
        assertThat(presenter.busyProperty().get()).as("no stale button between the cancel and the reload").isTrue();
        assertThat(presenter.cancellableProperty().get()).isTrue();

        manual.run(2); // the reload arrives
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.cancellableProperty().get()).isFalse();
    }

    @Test
    void cancel_readsTheUserWhenConfirmed_notWhenTheBackgroundWorkRuns() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        when(orders.cancelByCustomer(anyLong(), anyLong())).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = presenter(manual);
        presenter.load(route(false));
        manual.run(0);

        presenter.cancel();
        when(session.currentUser()).thenReturn(Optional.empty());
        manual.run(1);

        verify(orders).cancelByCustomer(USER_ID, ORDER_ID);
    }

    @Test
    void cancel_whenTheSessionBecameAGuest_goesHomeWithoutCallingTheService() {
        OrderDetailPresenter presenter = loaded(pending(null), false);
        when(session.currentUser()).thenReturn(Optional.empty());

        presenter.cancel();

        verify(orders, never()).cancelByCustomer(anyLong(), anyLong());
        verify(navigator).goHome();
    }

    // ------------------------------------------------------------------ back

    @Test
    void back_opensTheListPageThatLedHere() {
        OrderDetailPresenter presenter = loaded(pending(null), false);

        presenter.back();

        verify(navigator).navigateTo(BACK_TO_PAGE_TWO);
    }

    @Test
    void back_afterJustPlacing_opensTheFirstPage() {
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        OrderDetailPresenter presenter = presenter();
        presenter.load(new OrderDetailRoute(ORDER_ID, OrdersRoute.first(), true));

        presenter.back();

        verify(navigator).navigateTo(OrdersRoute.first());
    }

    @Test
    void back_beforeLoad_doesNothing() {
        presenter().back();

        verifyNoInteractions(navigator);
    }

    // ------------------------------------------------------------------ dispose

    @Test
    void dispose_cancelsThePendingLoadSoItsAnswerChangesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        OrderDetailPresenter presenter = presenter(manual);
        presenter.load(route(false));

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.orderProperty().get()).isNull();
        assertThat(presenter.titleTextProperty().get()).isEmpty();
    }

    @Test
    void dispose_whileTheCancelRuns_stillToastsTheResultButDoesNotReload() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenReturn(CancelResult.CANCELLED);
        OrderDetailPresenter presenter = presenter(manual);
        presenter.load(route(false));
        manual.run(0);
        presenter.cancel();

        presenter.dispose();
        manual.runAll();

        verify(toasts).show("Đã hủy đơn hàng #42.", ToastKind.SUCCESS);
        assertThat(manual.submittedCount()).as("no reload after dispose").isEqualTo(2);
        assertThat(presenter.cancellableProperty().get()).isTrue();
        verifyNoInteractions(navigator);
    }

    @Test
    void dispose_whileTheCancelRuns_aNotFoundIsSilentAndAnUnexpectedFailureIsReported() {
        ManualBackgroundTasks notFound = ManualBackgroundTasks.honoringCancel();
        when(orders.getOrder(USER_ID, ORDER_ID)).thenReturn(pending(null));
        when(orders.cancelByCustomer(USER_ID, ORDER_ID)).thenThrow(new NotFoundException("gone"));
        OrderDetailPresenter first = presenter(notFound);
        first.load(route(false));
        notFound.run(0);
        first.cancel();
        first.dispose();
        notFound.runAll();
        verifyNoInteractions(toasts, navigator, errors);

        RuntimeException failure = new IllegalStateException("lock timeout");
        ManualBackgroundTasks unexpected = ManualBackgroundTasks.honoringCancel();
        org.mockito.Mockito.doThrow(failure).when(orders).cancelByCustomer(USER_ID, ORDER_ID);
        OrderDetailPresenter second = presenter(unexpected);
        second.load(route(false));
        unexpected.run(0);
        second.cancel();
        second.dispose();
        unexpected.runAll();

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void afterDispose_commandsDoNothing() {
        OrderDetailPresenter presenter = loaded(pending(null), false);
        presenter.dispose();

        presenter.requestCancel();
        presenter.cancel();
        presenter.back();

        verifyNoInteractions(confirmDialog, navigator);
        verify(orders, never()).cancelByCustomer(anyLong(), anyLong());
    }

    @Test
    void dispose_calledTwice_isHarmless() {
        OrderDetailPresenter presenter = loaded(pending(null), false);

        presenter.dispose();
        presenter.dispose();

        assertThat(presenter.lines()).hasSize(2);
    }
}

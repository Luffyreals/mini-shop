package com.minishop.admin;

import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.ConfirmDialog;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.order.AdminOrderDetail;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderLineView;
import com.minishop.order.StatusAction;
import com.minishop.order.StatusChangeResult;
import com.minishop.support.ManualBackgroundTasks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminOrderDetailPresenter}: the admin order detail and the status change (AC-006-40 to -45; AC-009-10 to -13;
 * QĐ5, QĐ9, QĐ10 of the D5 plan). No JavaFX toolkit and no Spring. The confirm dialog mock only records the question;
 * a test decides whether the admin "confirms" by running the captured action.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOrderDetailPresenterTest {

    private static final long ORDER_ID = 42L;
    private static final Instant PLACED = Instant.parse("2026-10-27T05:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-28T08:00:00Z");
    private static final AdminOrdersRoute BACK = new AdminOrdersRoute("SHIPPING", 2);
    private static final AdminOrderDetailRoute ROUTE = new AdminOrderDetailRoute(ORDER_ID, BACK);

    private static final StatusAction CONFIRM = new StatusAction("CONFIRMED", "Xác nhận đơn", null, false);
    private static final StatusAction SHIP = new StatusAction("SHIPPING", "Bắt đầu giao hàng", null, false);
    private static final StatusAction DELIVER = new StatusAction("DELIVERED", "Xác nhận đã giao",
            "Xác nhận đơn hàng đã được giao thành công?", false);
    private static final StatusAction CANCEL = new StatusAction("CANCELLED", "Hủy đơn",
            "Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", true);

    @Mock
    private OrderAdminService service;
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
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Đang giao", "shipping", "Giao giờ hành chính",
                List.of(DELIVER, CANCEL)));
    }

    private static AdminOrderDetail detail(String statusLabel, String statusKey, String note,
                                           List<StatusAction> actions) {
        return new AdminOrderDetail(ORDER_ID, PLACED, UPDATED, statusLabel, statusKey,
                "Thanh toán khi nhận hàng (COD)", "Nguyễn Văn An", "an@example.com", "Trần Thị Bình", "0912345678",
                "12 Lê Lợi, Quận 1, TP.HCM", note,
                List.of(new OrderLineView("Bàn phím cơ cỡ nhỏ", new BigDecimal("1250000"), 2,
                                new BigDecimal("2500000")),
                        new OrderLineView("Chuột không dây", new BigDecimal("190000"), 1, new BigDecimal("190000"))),
                new BigDecimal("2690000"), actions);
    }

    private AdminOrderDetailPresenter presenter(BackgroundTasks tasks) {
        return new AdminOrderDetailPresenter(service, tasks, navigator, toasts, confirmDialog,
                new AdminFailures(toasts, navigator, errors));
    }

    private AdminOrderDetailPresenter loaded() {
        AdminOrderDetailPresenter presenter = presenter(direct);
        presenter.load(ROUTE);
        return presenter;
    }

    private Runnable confirmedAction(String message, String confirmLabel, String dismissLabel) {
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(eq(message), eq(confirmLabel), eq(dismissLabel), action.capture());
        return action.getValue();
    }

    // ------------------------------------------------------------------ load (AC-006-40)

    @Test
    void load_showsEveryFieldOfTheDetail() {
        AdminOrderDetailPresenter presenter = loaded();

        AdminOrderDetailPresenter.Detail detail = presenter.detailProperty().get();
        assertThat(presenter.titleTextProperty().get()).isEqualTo("Đơn hàng #42");
        assertThat(detail.id()).isEqualTo(42L);
        assertThat(detail.statusLabel()).isEqualTo("Đang giao");
        assertThat(detail.statusKey()).isEqualTo("shipping");
        assertThat(detail.receiverName()).isEqualTo("Trần Thị Bình");
        assertThat(detail.phone()).isEqualTo("0912345678");
        assertThat(detail.address()).isEqualTo("12 Lê Lợi, Quận 1, TP.HCM");
        assertThat(detail.note()).isEqualTo("Giao giờ hành chính");
        assertThat(detail.hasNote()).isTrue();
        assertThat(detail.paymentText()).isEqualTo("Thanh toán khi nhận hàng (COD)");
        assertThat(detail.createdText()).isEqualTo("27/10/2026 12:30");
        assertThat(detail.updatedText()).isEqualTo("28/10/2026 15:00");
        assertThat(detail.accountText()).isEqualTo("Nguyễn Văn An (an@example.com)");
        assertThat(detail.totalText()).isEqualTo("2.690.000 ₫");
        assertThat(detail.lines()).containsExactly(
                new AdminOrderDetailPresenter.LineRow("Bàn phím cơ cỡ nhỏ", "1.250.000 ₫", "2", "2.500.000 ₫"),
                new AdminOrderDetailPresenter.LineRow("Chuột không dây", "190.000 ₫", "1", "190.000 ₫"));
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(service, times(1)).getOrder(ORDER_ID);
        verifyNoInteractions(toasts, errors, navigator, confirmDialog);
    }

    @Test
    void load_exposesTheActionsExactlyAsTheServiceGaveThem_inTheSameOrder() {
        AdminOrderDetailPresenter presenter = loaded();

        assertThat(presenter.detailProperty().get().actions()).containsExactly(DELIVER, CANCEL);
        assertThat(presenter.detailProperty().get().finalStatus()).isFalse();
    }

    @Test
    void load_withANullNote_hidesTheNoteRow() {
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Đang giao", "shipping", null, List.of(DELIVER)));

        AdminOrderDetailPresenter.Detail detail = loaded().detailProperty().get();

        assertThat(detail.hasNote()).isFalse();
        assertThat(detail.note()).isEmpty();
    }

    @Test
    void load_withABlankNote_hidesTheNoteRow() {
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Đang giao", "shipping", "   ", List.of(DELIVER)));

        assertThat(loaded().detailProperty().get().hasNote()).isFalse();
    }

    @Test
    void load_ofAFinalOrder_hasNoActionsAndIsMarkedFinal() {
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Đã giao", "delivered", null, List.of()));

        AdminOrderDetailPresenter.Detail detail = loaded().detailProperty().get();

        assertThat(detail.actions()).isEmpty();
        assertThat(detail.finalStatus()).isTrue();
    }

    @Test
    void load_whilePending_hasTheTitleAtOnce_isLoading_andHasNoDetailYet() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);

        presenter.load(ROUTE);

        assertThat(presenter.titleTextProperty().get()).isEqualTo("Đơn hàng #42");
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.detailProperty().get()).isNull();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.detailProperty().get()).isNotNull();
    }

    @Test
    void load_whenTheOrderIsGone_toastsNotFound_andGoesBackToTheListWithTheSameFilterAndPage() {
        when(service.getOrder(ORDER_ID)).thenThrow(new NotFoundException("gone"));

        AdminOrderDetailPresenter presenter = loaded();

        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.detailProperty().get()).isNull();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_asANonAdmin_toastsAndGoesHome() {
        when(service.getOrder(ORDER_ID)).thenThrow(new AccessDeniedException());

        loaded();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void load_withAnUnexpectedFailure_reportsOnce() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.getOrder(ORDER_ID)).thenThrow(failure);

        loaded();

        verify(errors, times(1)).report(failure);
        verify(navigator, never()).navigateTo(any());
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter(direct).load(null));
    }

    // ------------------------------------------------------------------ confirm dialogs (AC-006-42 to -45, QĐ9)

    @Test
    void runAction_withAConfirmMessage_onlyOpensTheDialog_theServiceIsNotCalledYet() {
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(DELIVER);

        verify(confirmDialog).ask(eq("Xác nhận đơn hàng đã được giao thành công?"), eq("Xác nhận đã giao"),
                eq("Quay lại"), any(Runnable.class));
        verify(service, never()).changeStatus(anyLong(), anyString());
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(toasts);
    }

    @Test
    void runAction_cancel_opensTheDialogWithTheActionsOwnTextAndTheKeepOrderButton() {
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(CANCEL);

        verify(confirmDialog).ask(eq("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại."), eq("Hủy đơn"),
                eq("Giữ đơn hàng"), any(Runnable.class));
        verify(service, never()).changeStatus(anyLong(), anyString());
    }

    @Test
    void runAction_whenTheAdminDismissesTheDialog_nothingIsCalled() {
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(DELIVER);   // the captured action is never run

        verify(service, never()).changeStatus(anyLong(), anyString());
        verify(service, times(1)).getOrder(ORDER_ID);
    }

    @Test
    void confirmedAction_callsChangeStatusExactlyOnceWithTheTargetStatus() {
        when(service.changeStatus(ORDER_ID, "DELIVERED")).thenReturn(new StatusChangeResult.Changed("Đã giao"));
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(DELIVER);

        confirmedAction("Xác nhận đơn hàng đã được giao thành công?", "Xác nhận đã giao", "Quay lại").run();

        verify(service, times(1)).changeStatus(ORDER_ID, "DELIVERED");
    }

    @Test
    void runAction_withoutAConfirmMessage_callsTheServiceAtOnce_withoutADialog() {
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Chờ xử lý", "pending", null, List.of(CONFIRM, CANCEL)));
        when(service.changeStatus(ORDER_ID, "CONFIRMED")).thenReturn(new StatusChangeResult.Changed("Đã xác nhận"));
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(CONFIRM);

        verify(service, times(1)).changeStatus(ORDER_ID, "CONFIRMED");
        verifyNoInteractions(confirmDialog);
    }

    @Test
    void runAction_withABlankConfirmMessage_isTreatedAsNoConfirmation() {
        StatusAction blank = new StatusAction("CONFIRMED", "Xác nhận đơn", "  ", false);
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Chờ xử lý", "pending", null, List.of(blank)));
        when(service.changeStatus(ORDER_ID, "CONFIRMED")).thenReturn(new StatusChangeResult.Changed("Đã xác nhận"));

        loaded().runAction(blank);

        verify(service).changeStatus(ORDER_ID, "CONFIRMED");
        verifyNoInteractions(confirmDialog);
    }

    @Test
    void runAction_forAnActionThatIsNotInTheCurrentList_orNull_isIgnored() {
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(CONFIRM);   // the order is SHIPPING: CONFIRM is not offered
        presenter.runAction(null);

        verify(service, never()).changeStatus(anyLong(), anyString());
        verifyNoInteractions(confirmDialog);
    }

    @Test
    void runAction_beforeTheOrderIsLoaded_isIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);

        presenter.runAction(DELIVER);

        verifyNoInteractions(confirmDialog);
        assertThat(manual.submittedCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the four results (QĐ5)

    @Test
    void result_changed_toastsSuccessWithTheNewLabel_thenReloadsInPlace() {
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Chờ xử lý", "pending", null, List.of(CONFIRM)))
                .thenReturn(detail("Đã xác nhận", "confirmed", null, List.of(SHIP, CANCEL)));
        when(service.changeStatus(ORDER_ID, "CONFIRMED")).thenReturn(new StatusChangeResult.Changed("Đã xác nhận"));
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(CONFIRM);

        verify(toasts).show("Đã chuyển đơn hàng #42 sang trạng thái Đã xác nhận.", ToastKind.SUCCESS);
        verify(service, times(2)).getOrder(ORDER_ID);
        assertThat(presenter.detailProperty().get().statusLabel()).isEqualTo("Đã xác nhận");
        assertThat(presenter.detailProperty().get().actions()).containsExactly(SHIP, CANCEL);
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(navigator, never()).navigateTo(any());
        verifyNoInteractions(errors);
    }

    @Test
    void result_cancelled_toastsSuccessWithTheStockRestoredText_thenReloads() {
        when(service.changeStatus(ORDER_ID, "CANCELLED")).thenReturn(new StatusChangeResult.Cancelled());
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(toasts).show("Đã hủy đơn hàng #42 và hoàn lại tồn kho.", ToastKind.SUCCESS);
        verify(service, times(2)).getOrder(ORDER_ID);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void result_notAllowed_toastsAnErrorWithBothLabels_thenReloadsTheCurrentState() {
        when(service.changeStatus(ORDER_ID, "CANCELLED"))
                .thenReturn(new StatusChangeResult.NotAllowed("Đã giao", "Đã hủy"));
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(toasts).show("Không thể chuyển đơn hàng #42 từ Đã giao sang Đã hủy.", ToastKind.ERROR);
        verify(service, times(2)).getOrder(ORDER_ID);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void result_invalidStatus_toastsAnError_thenReloads() {
        when(service.changeStatus(ORDER_ID, "DELIVERED")).thenReturn(new StatusChangeResult.InvalidStatus());
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(DELIVER);

        confirmedAction("Xác nhận đơn hàng đã được giao thành công?", "Xác nhận đã giao", "Quay lại").run();

        verify(toasts).show("Trạng thái không hợp lệ.", ToastKind.ERROR);
        verify(service, times(2)).getOrder(ORDER_ID);
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    @Test
    void changeStatus_whenTheOrderIsGone_toastsNotFound_andGoesBackWithTheSameFilterAndPage() {
        when(service.changeStatus(anyLong(), anyString())).thenThrow(new NotFoundException("gone"));
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void changeStatus_asANonAdmin_toastsAndGoesHome() {
        when(service.changeStatus(anyLong(), anyString())).thenThrow(new AccessDeniedException());
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void changeStatus_withAnUnexpectedFailure_reportsOnce_unlocks_andReloadsTheOrderOnce() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.changeStatus(anyLong(), anyString())).thenThrow(failure);
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(service, times(2)).getOrder(ORDER_ID);   // the load and one reload: the real state may have changed
        verify(toasts, never()).show(any(), any());
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void changeStatus_withAnUnexpectedFailure_unlocksAtOnce_andShowsTheReloadedOrderWhenItArrives() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Chờ xử lý", "pending", null, List.of(CONFIRM)))
                .thenReturn(detail("Đã xác nhận", "confirmed", null, List.of()));
        when(service.changeStatus(ORDER_ID, "CONFIRMED")).thenThrow(failure);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);
        manual.run(0);
        presenter.runAction(CONFIRM);

        manual.run(1);   // the failing change

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(manual.submittedCount()).isEqualTo(3);
        assertThat(presenter.detailProperty().get().statusLabel()).isEqualTo("Chờ xử lý");

        manual.run(2);   // the reload

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.detailProperty().get().statusLabel()).isEqualTo("Đã xác nhận");
    }

    @Test
    void changeStatus_whenTheOrderIsGone_doesNotReload() {
        when(service.changeStatus(anyLong(), anyString())).thenThrow(new NotFoundException("gone"));
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(service, times(1)).getOrder(ORDER_ID);   // the load only
        verifyNoInteractions(errors);
    }

    @Test
    void changeStatus_whenAccessIsDenied_doesNotReload() {
        when(service.changeStatus(anyLong(), anyString())).thenThrow(new AccessDeniedException());
        AdminOrderDetailPresenter presenter = loaded();
        presenter.runAction(CANCEL);

        confirmedAction("Bạn có chắc muốn hủy đơn hàng này? Tồn kho sẽ được hoàn lại.", "Hủy đơn", "Giữ đơn hàng")
                .run();

        verify(service, times(1)).getOrder(ORDER_ID);   // the load only
        verifyNoInteractions(errors);
    }

    @Test
    void changeStatus_whenTheReloadAfterwardsFails_unlocks_andReportsThatFailure() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Chờ xử lý", "pending", null, List.of(CONFIRM)))
                .thenThrow(failure);
        when(service.changeStatus(ORDER_ID, "CONFIRMED")).thenReturn(new StatusChangeResult.Changed("Đã xác nhận"));
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(CONFIRM);

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
    }

    // ------------------------------------------------------------------ busy guard

    @Test
    void whileAChangeRuns_busyIsTrue_andASecondClickSendsNothing_untilTheReloadFinishes() {
        when(service.changeStatus(ORDER_ID, "CANCELLED")).thenReturn(new StatusChangeResult.Cancelled());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);
        manual.run(0);

        presenter.runAction(CANCEL);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(any(), any(), any(), action.capture());
        action.getValue().run();

        assertThat(presenter.busyProperty().get()).isTrue();
        // a second click while writing: no new dialog, and a second confirm sends nothing
        presenter.runAction(CANCEL);
        presenter.runAction(DELIVER);
        action.getValue().run();
        verify(confirmDialog, times(1)).ask(any(), any(), any(), any(Runnable.class));
        assertThat(manual.submittedCount()).isEqualTo(2);   // the load and one change

        manual.run(1);   // the change is done: toast and reload queued, still busy
        assertThat(presenter.busyProperty().get()).isTrue();
        presenter.runAction(DELIVER);
        verify(confirmDialog, times(1)).ask(any(), any(), any(), any(Runnable.class));

        manual.run(2);   // the reload finished

        assertThat(presenter.busyProperty().get()).isFalse();
        verify(service, times(1)).changeStatus(ORDER_ID, "CANCELLED");
    }

    // ------------------------------------------------------------------ back

    @Test
    void back_goesToTheListWithTheFilterAndPageTheAdminCameFrom() {
        loaded().back();

        verify(navigator).navigateTo(BACK);
    }

    @Test
    void back_whenOpenedWithoutABackRoute_goesToTheFullList() {
        AdminOrderDetailPresenter presenter = presenter(direct);
        presenter.load(new AdminOrderDetailRoute(ORDER_ID, null));

        presenter.back();

        verify(navigator).navigateTo(AdminOrdersRoute.all());
    }

    // ------------------------------------------------------------------ dispose (AC-009-10, QĐ10)

    @Test
    void dispose_cancelsTheLoad_soNothingChangesWhenItArrives() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.detailProperty().get()).isNull();
    }

    @Test
    void dispose_doesNotCancelAChangeInFlight_lateSuccessStillToasts_butDoesNotReloadOrNavigate() {
        when(service.changeStatus(ORDER_ID, "CANCELLED")).thenReturn(new StatusChangeResult.Cancelled());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);
        manual.run(0);
        presenter.runAction(CANCEL);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(any(), any(), any(), action.capture());
        action.getValue().run();

        presenter.dispose();
        manual.run(1);

        assertThat(manual.handle(1).isCancelled()).isFalse();
        verify(toasts).show("Đã hủy đơn hàng #42 và hoàn lại tồn kho.", ToastKind.SUCCESS);
        assertThat(manual.submittedCount()).isEqualTo(2);   // no reload
        verify(navigator, never()).navigateTo(any());
        verify(navigator, never()).goHome();
    }

    @Test
    void dispose_lateRefusal_stillToastsTheError() {
        when(service.changeStatus(ORDER_ID, "CANCELLED")).thenReturn(new StatusChangeResult.NotAllowed("Đã giao", "Đã hủy"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);
        manual.run(0);
        presenter.runAction(CANCEL);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog).ask(any(), any(), any(), action.capture());
        action.getValue().run();

        presenter.dispose();
        manual.run(1);

        verify(toasts).show("Không thể chuyển đơn hàng #42 từ Đã giao sang Đã hủy.", ToastKind.ERROR);
        assertThat(manual.submittedCount()).isEqualTo(2);
    }

    @Test
    void dispose_lateBusinessFailures_areIgnored_butAnUnexpectedOneIsStillReported() {
        when(service.changeStatus(ORDER_ID, "CANCELLED")).thenThrow(new NotFoundException("gone"));
        IllegalStateException boom = new IllegalStateException("boom");
        when(service.changeStatus(ORDER_ID, "DELIVERED")).thenThrow(boom);
        ManualBackgroundTasks first = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter notFound = presenter(first);
        notFound.load(ROUTE);
        first.run(0);
        ManualBackgroundTasks second = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter unexpected = presenter(second);
        unexpected.load(ROUTE);
        second.run(0);
        notFound.runAction(CANCEL);
        unexpected.runAction(DELIVER);
        ArgumentCaptor<Runnable> actions = ArgumentCaptor.forClass(Runnable.class);
        verify(confirmDialog, times(2)).ask(any(), any(), any(), actions.capture());
        actions.getAllValues().get(0).run();
        actions.getAllValues().get(1).run();

        notFound.dispose();
        unexpected.dispose();
        first.run(1);
        second.run(1);

        verifyNoInteractions(toasts);
        verify(errors, times(1)).report(boom);
        assertThat(second.submittedCount()).isEqualTo(2);   // a disposed screen does not reload after the failure
        verify(navigator, never()).goHome();
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void afterDispose_everyAction_doesNothing() {
        AdminOrderDetailPresenter presenter = loaded();
        presenter.dispose();

        presenter.runAction(DELIVER);
        presenter.back();

        verifyNoInteractions(navigator, confirmDialog);
        verify(service, never()).changeStatus(anyLong(), anyString());
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    @Test
    void load_firstLoadFailsUnexpectedly_setsLoadFailed_reportsOnce_andStopsLoading() {
        IllegalStateException failure = new IllegalStateException("database is down");
        when(service.getOrder(ORDER_ID)).thenThrow(failure);

        AdminOrderDetailPresenter presenter = loaded();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.detailProperty().get()).isNull();
        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void load_whenTheOrderIsGone_keepsTheOldPath_andLoadFailedStaysFalse() {
        when(service.getOrder(ORDER_ID)).thenThrow(new NotFoundException("gone"));

        assertThat(loaded().loadFailedProperty().get()).isFalse();

        verify(navigator).navigateTo(BACK);
        verifyNoInteractions(errors);
    }

    @Test
    void load_asANonAdmin_keepsTheOldPath_andLoadFailedStaysFalse() {
        when(service.getOrder(ORDER_ID)).thenThrow(new AccessDeniedException());

        assertThat(loaded().loadFailedProperty().get()).isFalse();

        verify(navigator).goHome();
        verifyNoInteractions(errors);
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsTheSameOrderAgain_clearsLoadFailed_andShowsIt() {
        when(service.getOrder(ORDER_ID)).thenThrow(new IllegalStateException("down"))
                .thenReturn(detail("Đang giao", "shipping", null, List.of(DELIVER, CANCEL)));
        AdminOrderDetailPresenter presenter = loaded();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.detailProperty().get()).isNotNull();
        assertThat(presenter.detailProperty().get().statusLabel()).isEqualTo("Đang giao");
        verify(service, times(2)).getOrder(ORDER_ID);
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryFindsTheOrderGone_toastsAndGoesBack_loadFailedStaysFalse() {
        when(service.getOrder(ORDER_ID)).thenThrow(new IllegalStateException("down"))
                .thenThrow(new NotFoundException("gone"));
        AdminOrderDetailPresenter presenter = loaded();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verify(navigator, times(1)).navigateTo(BACK);
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        IllegalStateException failure = new IllegalStateException("down");
        when(service.getOrder(ORDER_ID)).thenThrow(failure);
        AdminOrderDetailPresenter presenter = loaded();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        when(service.getOrder(ORDER_ID)).thenThrow(new IllegalStateException("down"))
                .thenReturn(detail("Đang giao", "shipping", null, List.of(DELIVER)));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrderDetailPresenter presenter = presenter(manual);
        presenter.load(ROUTE);
        manual.run(0);
        presenter.retry();

        presenter.retry();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadFailedProperty().get()).isFalse();
    }

    @Test
    void retry_afterDispose_doesNothing() {
        when(service.getOrder(ORDER_ID)).thenThrow(new IllegalStateException("down"));
        AdminOrderDetailPresenter presenter = loaded();
        presenter.dispose();

        presenter.retry();

        verify(service, times(1)).getOrder(ORDER_ID);
        assertThat(presenter.detailProperty().get()).isNull();
    }

    @Test
    void retry_beforeAnyLoad_orWhenNothingFailed_doesNothing() {
        presenter(direct).retry();
        verifyNoInteractions(service);

        AdminOrderDetailPresenter presenter = loaded();
        presenter.retry();

        verify(service, times(1)).getOrder(ORDER_ID);
    }

    @Test
    void reload_failsAfterAStatusChange_keepsTheDetail_andLoadFailedStaysFalse() {
        IllegalStateException failure = new IllegalStateException("down");
        when(service.getOrder(ORDER_ID)).thenReturn(detail("Chờ xử lý", "pending", null, List.of(CONFIRM, CANCEL)))
                .thenThrow(failure);
        when(service.changeStatus(ORDER_ID, "CONFIRMED")).thenReturn(new StatusChangeResult.Changed("Đã xác nhận"));
        AdminOrderDetailPresenter presenter = loaded();

        presenter.runAction(CONFIRM);

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.detailProperty().get()).isNotNull();
        assertThat(presenter.detailProperty().get().statusLabel()).isEqualTo("Chờ xử lý");
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.busyProperty().get()).isFalse();
        verify(errors, times(1)).report(failure);
    }
}

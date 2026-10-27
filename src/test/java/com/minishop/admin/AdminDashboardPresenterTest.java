package com.minishop.admin;

import com.minishop.catalog.LowStockRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.common.AccessDeniedException;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderDashboard;
import com.minishop.order.StatusCount;
import com.minishop.support.ManualBackgroundTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminDashboardPresenter}: the "Tổng quan" screen (AC-006-6, -7, -8, AC-009-10, -11, -12; QĐ8 of the D5
 * plan). No JavaFX toolkit and no Spring: both admin services, the navigator, toasts and the error reporter are mocks;
 * {@link AdminFailures} is the real class on top of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDashboardPresenterTest {

    private static final Instant PLACED = Instant.parse("2026-10-27T05:30:00Z");

    @Mock
    private OrderAdminService orderAdminService;
    @Mock
    private ProductAdminService productAdminService;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    private static List<StatusCount> counts() {
        return List.of(new StatusCount("PENDING", "Chờ xử lý", 4), new StatusCount("CONFIRMED", "Đã xác nhận", 3),
                new StatusCount("SHIPPING", "Đang giao", 2), new StatusCount("DELIVERED", "Đã giao", 7),
                new StatusCount("CANCELLED", "Đã hủy", 0));
    }

    private static AdminOrderRow order(long id, String status, String key) {
        return new AdminOrderRow(id, PLACED, "Nguyễn Văn An", "an@example.com", "Trần Thị Bình",
                new BigDecimal("1250000"), status, key);
    }

    private static OrderDashboard dashboard() {
        return new OrderDashboard(counts(), new BigDecimal("12345000"),
                List.of(order(11, "Chờ xử lý", "pending"), order(10, "Đã giao", "delivered")));
    }

    @BeforeEach
    void setUp() {
        when(orderAdminService.dashboard()).thenReturn(dashboard());
        when(productAdminService.lowStock()).thenReturn(
                List.of(new LowStockRow(5, "Bàn phím cơ", 0), new LowStockRow(6, "Chuột không dây", 3)));
    }

    private AdminDashboardPresenter presenter() {
        return new AdminDashboardPresenter(orderAdminService, productAdminService, direct, navigator,
                new AdminFailures(toasts, navigator, errors));
    }

    private AdminDashboardPresenter presenter(ManualBackgroundTasks manual) {
        return new AdminDashboardPresenter(orderAdminService, productAdminService, manual, navigator,
                new AdminFailures(toasts, navigator, errors));
    }

    private AdminDashboardPresenter loaded() {
        AdminDashboardPresenter presenter = presenter();
        presenter.load();
        return presenter;
    }

    // ------------------------------------------------------------------ the five counts (AC-006-6)

    @Test
    void load_showsTheFiveCountsInEnumOrderWithLabelsAndNumbers() {
        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.counts()).extracting(AdminDashboardPresenter.CountTile::status)
                .containsExactly("PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED");
        assertThat(presenter.counts()).extracting(AdminDashboardPresenter.CountTile::label)
                .containsExactly("Chờ xử lý", "Đã xác nhận", "Đang giao", "Đã giao", "Đã hủy");
        assertThat(presenter.counts()).extracting(AdminDashboardPresenter.CountTile::countText)
                .containsExactly("4", "3", "2", "7", "0");
    }

    @Test
    void load_onlyThePendingTileIsTheLead() {
        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.counts()).filteredOn(AdminDashboardPresenter.CountTile::lead)
                .extracting(AdminDashboardPresenter.CountTile::status).containsExactly("PENDING");
    }

    @Test
    void load_eachTileHasAnAccessibleTextWithLabelAndCount() {
        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.counts().get(0).accessibleText()).isEqualTo("Chờ xử lý: 4 đơn hàng");
        assertThat(presenter.counts().get(4).accessibleText()).isEqualTo("Đã hủy: 0 đơn hàng");
    }

    @Test
    void load_formatsTheDeliveredRevenueAsVnd() {
        assertThat(loaded().revenueTextProperty().get()).isEqualTo("12.345.000 ₫");
    }

    @Test
    void load_withNoDeliveredOrders_showsZeroRevenue() {
        when(orderAdminService.dashboard()).thenReturn(new OrderDashboard(counts(), BigDecimal.ZERO, List.of()));

        assertThat(loaded().revenueTextProperty().get()).isEqualTo("0 ₫");
    }

    // ------------------------------------------------------------------ low stock (AC-006-7)

    @Test
    void load_listsLowStockWithTheStockTextInTheServiceOrder() {
        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.lowStock()).containsExactly(
                new AdminDashboardPresenter.LowStockItem(5, "Bàn phím cơ", "Tồn kho: 0", true,
                        "Bàn phím cơ, Tồn kho: 0"),
                new AdminDashboardPresenter.LowStockItem(6, "Chuột không dây", "Tồn kho: 3", false,
                        "Chuột không dây, Tồn kho: 3"));
        assertThat(presenter.lowStockEmptyProperty().get()).isFalse();
    }

    @Test
    void load_withNothingLowOnStock_setsTheEmptyFlag() {
        when(productAdminService.lowStock()).thenReturn(List.of());

        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.lowStock()).isEmpty();
        assertThat(presenter.lowStockEmptyProperty().get()).isTrue();
        assertThat(presenter.latestEmptyProperty().get()).isFalse();
    }

    // ------------------------------------------------------------------ latest orders (AC-006-8)

    @Test
    void load_listsTheLatestOrdersWithFormattedTextAndTheirStatusKey() {
        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.latest()).containsExactly(
                new AdminDashboardPresenter.LatestOrder(11, "#11", "27/10/2026 12:30", "Nguyễn Văn An",
                        "an@example.com", "1.250.000 ₫", "Chờ xử lý", "pending",
                        "Đơn hàng #11, 27/10/2026 12:30, Nguyễn Văn An, 1.250.000 ₫, Chờ xử lý"),
                new AdminDashboardPresenter.LatestOrder(10, "#10", "27/10/2026 12:30", "Nguyễn Văn An",
                        "an@example.com", "1.250.000 ₫", "Đã giao", "delivered",
                        "Đơn hàng #10, 27/10/2026 12:30, Nguyễn Văn An, 1.250.000 ₫, Đã giao"));
        assertThat(presenter.latestEmptyProperty().get()).isFalse();
    }

    @Test
    void load_withNoOrders_setsTheEmptyFlagAndKeepsTheCountsAtZero() {
        List<StatusCount> zero = List.of(new StatusCount("PENDING", "Chờ xử lý", 0),
                new StatusCount("CONFIRMED", "Đã xác nhận", 0), new StatusCount("SHIPPING", "Đang giao", 0),
                new StatusCount("DELIVERED", "Đã giao", 0), new StatusCount("CANCELLED", "Đã hủy", 0));
        when(orderAdminService.dashboard()).thenReturn(new OrderDashboard(zero, BigDecimal.ZERO, List.of()));

        AdminDashboardPresenter presenter = loaded();

        assertThat(presenter.latest()).isEmpty();
        assertThat(presenter.latestEmptyProperty().get()).isTrue();
        assertThat(presenter.counts()).hasSize(5).extracting(AdminDashboardPresenter.CountTile::countText)
                .containsOnly("0");
    }

    // ------------------------------------------------------------------ loading flow

    @Test
    void load_callsEachServiceExactlyOnce_inOneBackgroundTask() {
        loaded();

        verify(orderAdminService, times(1)).dashboard();
        verify(productAdminService, times(1)).lowStock();
        assertThat(direct.submittedCount()).isEqualTo(1);
        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void load_whilePending_isLoadingAndNothingIsLoadedYet_thenFlipsTogether() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminDashboardPresenter presenter = presenter(manual);

        presenter.load();

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.counts()).isEmpty();
        assertThat(presenter.revenueTextProperty().get()).isEmpty();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.counts()).hasSize(5);
    }

    @Test
    void load_whileALoadIsPending_isIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminDashboardPresenter presenter = presenter(manual);

        presenter.load();
        presenter.load();

        assertThat(manual.submittedCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ failures (AC-009-9, -12)

    @Test
    void load_asANonAdmin_toastsAndGoesHome_withoutChangingTheProperties() {
        when(orderAdminService.dashboard()).thenThrow(new AccessDeniedException());
        AdminDashboardPresenter presenter = presenter();

        presenter.load();

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.counts()).isEmpty();
        verifyNoInteractions(errors);
    }

    @Test
    void load_whenTheSecondServiceFails_nothingIsShown_andTheErrorIsReportedOnce() {
        IllegalStateException failure = new IllegalStateException("database is down");
        when(productAdminService.lowStock()).thenThrow(failure);
        AdminDashboardPresenter presenter = presenter();

        presenter.load();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.counts()).isEmpty();
        assertThat(presenter.latest()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(toasts);
    }

    @Test
    void load_afterAFailure_canBeRetried() {
        when(orderAdminService.dashboard()).thenThrow(new IllegalStateException("once")).thenReturn(dashboard());
        AdminDashboardPresenter presenter = presenter();

        presenter.load();
        assertThat(presenter.loadFailedProperty().get()).isTrue();
        presenter.load();

        assertThat(presenter.loadedProperty().get()).isTrue();
        // Nạp thành công luôn tắt khối lỗi, kể cả khi không đi qua retry()
        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.counts()).hasSize(5);
    }

    // ------------------------------------------------------------------ navigation (AC-006-6, -7, -8)

    @Test
    void openStatus_opensTheOrderListFilteredByThatStatusOnPageOne() {
        loaded().openStatus("PENDING");

        verify(navigator).navigateTo(new AdminOrdersRoute("PENDING", 1));
    }

    @Test
    void openAllOrders_opensTheUnfilteredList() {
        loaded().openAllOrders();

        verify(navigator).navigateTo(AdminOrdersRoute.all());
    }

    @Test
    void openProduct_opensTheEditFormWithTheFirstProductsPageAsBack() {
        loaded().openProduct(5L);

        verify(navigator).navigateTo(AdminProductFormRoute.edit(5L, AdminProductsRoute.first()));
    }

    @Test
    void openOrder_opensTheDetailWithTheFullListAsBack() {
        loaded().openOrder(11L);

        verify(navigator).navigateTo(new AdminOrderDetailRoute(11L, AdminOrdersRoute.all()));
    }

    // ------------------------------------------------------------------ dispose (AC-009-10)

    @Test
    void dispose_whileLoading_cancelsTheLoad_soNothingChangesWhenTheResultArrives() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminDashboardPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.counts()).isEmpty();
        assertThat(presenter.revenueTextProperty().get()).isEmpty();
    }

    @Test
    void dispose_whileLoading_andALateFailureArrives_nothingIsToastedOrNavigated() {
        when(orderAdminService.dashboard()).thenThrow(new AccessDeniedException());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminDashboardPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        verifyNoInteractions(toasts, errors);
        verify(navigator, never()).goHome();
    }

    @Test
    void afterDispose_load_andEveryNavigation_doNothing() {
        AdminDashboardPresenter presenter = presenter();
        presenter.dispose();

        presenter.load();
        presenter.openStatus("PENDING");
        presenter.openAllOrders();
        presenter.openProduct(1L);
        presenter.openOrder(1L);

        assertThat(direct.submittedCount()).isZero();
        verifyNoInteractions(navigator, orderAdminService, productAdminService);
    }

    @Test
    void dispose_twice_isHarmless() {
        AdminDashboardPresenter presenter = loaded();

        presenter.dispose();
        presenter.dispose();

        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    @Test
    void load_firstLoadFailsUnexpectedly_setsLoadFailed_reportsOnce_andStopsLoading() {
        IllegalStateException failure = new IllegalStateException("database is down");
        when(orderAdminService.dashboard()).thenThrow(failure);
        AdminDashboardPresenter presenter = presenter();

        presenter.load();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void load_asANonAdmin_keepsTheOldPath_andLoadFailedStaysFalse() {
        when(orderAdminService.dashboard()).thenThrow(new AccessDeniedException());
        AdminDashboardPresenter presenter = presenter();

        presenter.load();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verifyNoInteractions(errors);
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsAgain_clearsLoadFailed_andShowsTheContent() {
        when(orderAdminService.dashboard()).thenThrow(new IllegalStateException("down")).thenReturn(dashboard());
        AdminDashboardPresenter presenter = presenter();
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.counts()).hasSize(5);
        verify(orderAdminService, times(2)).dashboard();
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        IllegalStateException failure = new IllegalStateException("down");
        when(orderAdminService.dashboard()).thenThrow(failure);
        AdminDashboardPresenter presenter = presenter();
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whenTheRetryIsRefusedForNotBeingAnAdmin_goesHome_andLoadFailedStaysFalse() {
        when(orderAdminService.dashboard()).thenThrow(new IllegalStateException("down"))
                .thenThrow(new AccessDeniedException());
        AdminDashboardPresenter presenter = presenter();
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        when(orderAdminService.dashboard()).thenThrow(new IllegalStateException("down")).thenReturn(dashboard());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminDashboardPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.retry();

        presenter.retry();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadFailedProperty().get()).isFalse();
    }

    @Test
    void retry_afterDispose_doesNothing() {
        when(orderAdminService.dashboard()).thenThrow(new IllegalStateException("down"));
        AdminDashboardPresenter presenter = presenter();
        presenter.load();
        presenter.dispose();

        presenter.retry();

        verify(orderAdminService, times(1)).dashboard();
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void retry_beforeAnyLoad_orWhenNothingFailed_doesNothing() {
        presenter().retry();
        verifyNoInteractions(orderAdminService, productAdminService);

        AdminDashboardPresenter presenter = loaded();
        presenter.retry();

        verify(orderAdminService, times(1)).dashboard();
    }

    @Test
    void load_failsAfterContentWasShown_keepsTheContent_andLoadFailedStaysFalse() {
        AdminDashboardPresenter presenter = loaded();
        IllegalStateException failure = new IllegalStateException("down");
        when(orderAdminService.dashboard()).thenThrow(failure);

        presenter.load();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.counts()).hasSize(5);
        verify(errors, times(1)).report(failure);
    }
}

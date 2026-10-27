package com.minishop.admin;

import com.minishop.common.AccessDeniedException;
import com.minishop.common.PageNav;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.order.AdminOrderPage;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.StatusFilterOption;
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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminOrdersPresenter}: the admin order list with the status filter and paging (AC-006-38, -39; AC-009-10 to
 * -12; F5 of the D5 plan). No JavaFX toolkit and no Spring: the service, navigator and toasts are mocks;
 * {@link AdminFailures} is the real class on top of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOrdersPresenterTest {

    private static final Instant PLACED = Instant.parse("2026-10-27T05:30:00Z");

    @Mock
    private OrderAdminService service;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(service.listOrders(any(), any())).thenReturn(page(null, "Tất cả", 1, 3, 45,
                List.of(order(12, "Chờ xử lý", "pending"), order(11, "Đã giao", "delivered"))));
    }

    private static AdminOrderRow order(long id, String statusLabel, String statusKey) {
        return new AdminOrderRow(id, PLACED, "Nguyễn Văn An", "an@example.com", "Trần Thị Bình",
                new BigDecimal("2690000"), statusLabel, statusKey);
    }

    private static List<StatusFilterOption> filters(String selected) {
        return List.of(new StatusFilterOption(null, "Tất cả", selected == null),
                new StatusFilterOption("PENDING", "Chờ xử lý", "PENDING".equals(selected)),
                new StatusFilterOption("CONFIRMED", "Đã xác nhận", "CONFIRMED".equals(selected)),
                new StatusFilterOption("SHIPPING", "Đang giao", "SHIPPING".equals(selected)),
                new StatusFilterOption("DELIVERED", "Đã giao", "DELIVERED".equals(selected)),
                new StatusFilterOption("CANCELLED", "Đã hủy", "CANCELLED".equals(selected)));
    }

    private static AdminOrderPage page(String status, String statusLabel, int page, int totalPages, long total,
                                       List<AdminOrderRow> rows) {
        return new AdminOrderPage(rows, status, statusLabel, filters(status), page, totalPages, total);
    }

    private AdminOrdersPresenter presenter() {
        return new AdminOrdersPresenter(service, direct, navigator, new AdminFailures(toasts, navigator, errors));
    }

    private AdminOrdersPresenter loaded(AdminOrdersRoute route) {
        AdminOrdersPresenter presenter = presenter();
        presenter.load(route);
        return presenter;
    }

    // ------------------------------------------------------------------ load (AC-006-38)

    @Test
    void load_passesTheStatusAndThePageAsStringsToTheService_exactlyOnce() {
        loaded(new AdminOrdersRoute("PENDING", 2));

        verify(service, times(1)).listOrders("PENDING", "2");
    }

    @Test
    void load_withNoFilter_passesANullStatus() {
        loaded(AdminOrdersRoute.all());

        verify(service).listOrders(null, "1");
    }

    @Test
    void load_showsTheSixFilterPillsFromTheService_withTheAppliedOneSelected() {
        when(service.listOrders(any(), any())).thenReturn(page("SHIPPING", "Đang giao", 1, 1, 1,
                List.of(order(12, "Đang giao", "shipping"))));

        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("SHIPPING", 1));

        assertThat(presenter.filters()).extracting(AdminOrdersPresenter.FilterOption::label)
                .containsExactly("Tất cả", "Chờ xử lý", "Đã xác nhận", "Đang giao", "Đã giao", "Đã hủy");
        assertThat(presenter.filters()).extracting(AdminOrdersPresenter.FilterOption::value)
                .containsExactly(null, "PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED");
        assertThat(presenter.filters()).filteredOn(AdminOrdersPresenter.FilterOption::selected)
                .extracting(AdminOrdersPresenter.FilterOption::value).containsExactly("SHIPPING");
    }

    @Test
    void load_showsTheRowsWithFormattedDateTotalAndStatus() {
        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());

        assertThat(presenter.rows()).containsExactly(
                new AdminOrdersPresenter.OrderRow(12, "#12", "27/10/2026 12:30", "Nguyễn Văn An", "an@example.com",
                        "Trần Thị Bình", "2.690.000 ₫", "Chờ xử lý", "pending",
                        "Đơn hàng #12, 27/10/2026 12:30, Nguyễn Văn An, 2.690.000 ₫, Chờ xử lý"),
                new AdminOrdersPresenter.OrderRow(11, "#11", "27/10/2026 12:30", "Nguyễn Văn An", "an@example.com",
                        "Trần Thị Bình", "2.690.000 ₫", "Đã giao", "delivered",
                        "Đơn hàng #11, 27/10/2026 12:30, Nguyễn Văn An, 2.690.000 ₫, Đã giao"));
        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.emptyTextProperty().get()).isEqualTo("Chưa có đơn hàng nào.");
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void load_pageNavFollowsThePageTheServiceReturned_notTheRequestedOne() {
        when(service.listOrders("PENDING", "9")).thenReturn(page("PENDING", "Chờ xử lý", 1, 2, 25,
                List.of(order(12, "Chờ xử lý", "pending"))));

        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("PENDING", 9));

        assertThat(presenter.pageNavProperty().get()).isEqualTo(PageNav.of(1, 2));
    }

    @Test
    void load_withNoOrdersAtAll_showsTheFirstEmptyMessage() {
        when(service.listOrders(any(), any())).thenReturn(page(null, "Tất cả", 1, 0, 0, List.of()));

        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.emptyTextProperty().get()).isEqualTo("Chưa có đơn hàng nào.");
        assertThat(presenter.rows()).isEmpty();
        assertThat(presenter.filters()).hasSize(6);
    }

    @Test
    void load_withNoOrdersInTheFilteredStatus_showsTheMessageWithThatStatusLabel() {
        when(service.listOrders(any(), any())).thenReturn(page("SHIPPING", "Đang giao", 1, 0, 0, List.of()));

        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("SHIPPING", 1));

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.emptyTextProperty().get()).isEqualTo("Không có đơn hàng nào ở trạng thái Đang giao.");
    }

    @Test
    void load_whilePending_isLoadingAndNotLoaded_andASecondLoadIsIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrdersPresenter presenter = new AdminOrdersPresenter(service, manual, navigator,
                new AdminFailures(toasts, navigator, errors));

        presenter.load(AdminOrdersRoute.all());
        presenter.load(AdminOrdersRoute.all());

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.pageNavProperty().get()).isNull();
        assertThat(manual.submittedCount()).isEqualTo(1);

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void load_asANonAdmin_toastsAndGoesHome() {
        when(service.listOrders(any(), any())).thenThrow(new AccessDeniedException());

        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());

        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void load_withAnUnexpectedFailure_reportsItOnce() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(service.listOrders(any(), any())).thenThrow(failure);

        loaded(AdminOrdersRoute.all());

        verify(errors, times(1)).report(failure);
        verify(toasts, never()).show(any(), any());
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter().load(null));
    }

    // ------------------------------------------------------------------ filter and paging (AC-006-38, -39)

    @Test
    void filter_toAnotherStatus_navigatesToThatStatusOnPageOne() {
        when(service.listOrders(any(), any())).thenReturn(page("PENDING", "Chờ xử lý", 3, 5, 90,
                List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("PENDING", 3));

        presenter.filter("SHIPPING");

        verify(navigator).navigateTo(new AdminOrdersRoute("SHIPPING", 1));
    }

    @Test
    void filter_toAll_navigatesToTheUnfilteredList() {
        when(service.listOrders(any(), any())).thenReturn(page("PENDING", "Chờ xử lý", 3, 5, 90,
                List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("PENDING", 3));

        presenter.filter(null);

        verify(navigator).navigateTo(AdminOrdersRoute.all());
    }

    @Test
    void filter_toTheStatusAlreadyApplied_doesNothing_evenOnALaterPage() {
        when(service.listOrders(any(), any())).thenReturn(page("PENDING", "Chờ xử lý", 3, 5, 90,
                List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("PENDING", 3));

        presenter.filter("PENDING");

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void filter_toAllWhileAllIsApplied_doesNothing() {
        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());

        presenter.filter(null);

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void filter_usesTheStatusTheServiceAppliedNotTheRawRoute_soAnUnknownStatusBehavesAsAll() {
        // the route carries an unknown status; the service treats it as "Tất cả" and says so
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("NO_SUCH", 1));

        presenter.filter(null);
        presenter.filter("PENDING");

        verify(navigator, times(1)).navigateTo(any());
        verify(navigator).navigateTo(new AdminOrdersRoute("PENDING", 1));
    }

    @Test
    void filter_beforeTheFirstLoadFinished_doesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrdersPresenter presenter = new AdminOrdersPresenter(service, manual, navigator,
                new AdminFailures(toasts, navigator, errors));
        presenter.load(AdminOrdersRoute.all());

        presenter.filter("PENDING");
        presenter.goToPage(2);
        presenter.openOrder(12L);

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void goToPage_navigatesToThatPage_keepingTheFilter() {
        when(service.listOrders(any(), any())).thenReturn(page("PENDING", "Chờ xử lý", 1, 5, 90,
                List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("PENDING", 1));

        presenter.goToPage(3);

        verify(navigator).navigateTo(new AdminOrdersRoute("PENDING", 3));
    }

    @Test
    void goToPage_theCurrentPageOrBelowOne_doesNothing() {
        when(service.listOrders(any(), any())).thenReturn(page(null, "Tất cả", 2, 5, 90,
                List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute(null, 2));

        presenter.goToPage(2);
        presenter.goToPage(0);
        presenter.goToPage(-1);

        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void goToPage_comparesWithThePageTheServiceReturned_whenTheRequestedOneWasPastTheEnd() {
        // asked for page 9, the service answered page 1
        when(service.listOrders("PENDING", "9")).thenReturn(page("PENDING", "Chờ xử lý", 1, 2, 25,
                List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("PENDING", 9));

        presenter.goToPage(1);
        presenter.goToPage(2);

        verify(navigator, times(1)).navigateTo(any());
        verify(navigator).navigateTo(new AdminOrdersRoute("PENDING", 2));
    }

    // ------------------------------------------------------------------ open an order keeps filter and page

    @Test
    void openOrder_opensTheDetailWithTheCurrentFilterAndPageAsBack() {
        when(service.listOrders(any(), any())).thenReturn(page("SHIPPING", "Đang giao", 2, 4, 70,
                List.of(order(12, "Đang giao", "shipping"))));
        AdminOrdersPresenter presenter = loaded(new AdminOrdersRoute("SHIPPING", 2));

        presenter.openOrder(12L);

        verify(navigator).navigateTo(new AdminOrderDetailRoute(12L, new AdminOrdersRoute("SHIPPING", 2)));
    }

    @Test
    void openOrder_withNoFilter_hasTheUnfilteredListAsBack() {
        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());

        presenter.openOrder(11L);

        verify(navigator).navigateTo(new AdminOrderDetailRoute(11L, AdminOrdersRoute.all()));
    }

    // ------------------------------------------------------------------ dispose (AC-009-10)

    @Test
    void dispose_cancelsTheLoad_soNothingChangesWhenTheResultArrives() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrdersPresenter presenter = new AdminOrdersPresenter(service, manual, navigator,
                new AdminFailures(toasts, navigator, errors));
        presenter.load(AdminOrdersRoute.all());

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.rows()).isEmpty();
        assertThat(presenter.filters()).isEmpty();
    }

    @Test
    void dispose_lateFailure_toastsNothingAndNavigatesNowhere() {
        when(service.listOrders(any(), any())).thenThrow(new AccessDeniedException());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrdersPresenter presenter = new AdminOrdersPresenter(service, manual, navigator,
                new AdminFailures(toasts, navigator, errors));
        presenter.load(AdminOrdersRoute.all());

        presenter.dispose();
        manual.runAll();

        verifyNoInteractions(toasts, errors, navigator);
    }

    @Test
    void afterDispose_everyAction_doesNothing() {
        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());
        presenter.dispose();

        presenter.load(new AdminOrdersRoute("PENDING", 1));
        presenter.filter("PENDING");
        presenter.goToPage(2);
        presenter.openOrder(12L);

        verifyNoInteractions(navigator);
        verify(service, times(1)).listOrders(any(), any());
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    private AdminOrdersPresenter presenter(ManualBackgroundTasks manual) {
        return new AdminOrdersPresenter(service, manual, navigator, new AdminFailures(toasts, navigator, errors));
    }

    @Test
    void load_firstLoadFailsUnexpectedly_setsLoadFailed_reportsOnce_andStopsLoading() {
        IllegalStateException failure = new IllegalStateException("database is down");
        when(service.listOrders(any(), any())).thenThrow(failure);
        AdminOrdersPresenter presenter = presenter();

        presenter.load(new AdminOrdersRoute("PENDING", 2));

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void load_asANonAdmin_keepsTheOldPath_andLoadFailedStaysFalse() {
        when(service.listOrders(any(), any())).thenThrow(new AccessDeniedException());
        AdminOrdersPresenter presenter = presenter();

        presenter.load(AdminOrdersRoute.all());

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verifyNoInteractions(errors);
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsTheRequestedRouteAgain_clearsLoadFailed_andShowsTheRows() {
        when(service.listOrders("PENDING", "2")).thenThrow(new IllegalStateException("down"))
                .thenReturn(page("PENDING", "Chờ xử lý", 2, 3, 25, List.of(order(12, "Chờ xử lý", "pending"))));
        AdminOrdersPresenter presenter = presenter();
        presenter.load(new AdminOrdersRoute("PENDING", 2));

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.rows()).hasSize(1);
        verify(service, times(2)).listOrders("PENDING", "2");
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        IllegalStateException failure = new IllegalStateException("down");
        when(service.listOrders(any(), any())).thenThrow(failure);
        AdminOrdersPresenter presenter = presenter();
        presenter.load(AdminOrdersRoute.all());

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        when(service.listOrders(any(), any())).thenThrow(new IllegalStateException("down"))
                .thenReturn(page(null, "Tất cả", 1, 1, 0, List.of()));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AdminOrdersPresenter presenter = presenter(manual);
        presenter.load(AdminOrdersRoute.all());
        manual.run(0);
        presenter.retry();

        presenter.retry();

        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadFailedProperty().get()).isFalse();
    }

    @Test
    void retry_afterDispose_doesNothing() {
        when(service.listOrders(any(), any())).thenThrow(new IllegalStateException("down"));
        AdminOrdersPresenter presenter = presenter();
        presenter.load(AdminOrdersRoute.all());
        presenter.dispose();

        presenter.retry();

        verify(service, times(1)).listOrders(any(), any());
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void retry_beforeAnyLoad_orWhenNothingFailed_doesNothing() {
        presenter().retry();
        verifyNoInteractions(service);

        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());
        presenter.retry();

        verify(service, times(1)).listOrders(any(), any());
    }

    @Test
    void load_failsAfterRowsWereShown_keepsThem_andLoadFailedStaysFalse() {
        AdminOrdersPresenter presenter = loaded(AdminOrdersRoute.all());
        IllegalStateException failure = new IllegalStateException("down");
        when(service.listOrders(any(), any())).thenThrow(failure);

        presenter.load(new AdminOrdersRoute("PENDING", 1));

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.rows()).hasSize(2);
        verify(errors, times(1)).report(failure);
    }
}

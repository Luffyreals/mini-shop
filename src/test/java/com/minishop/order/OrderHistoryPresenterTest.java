package com.minishop.order;

import com.minishop.catalog.CatalogRoute;
import com.minishop.common.PageNav;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.support.ManualBackgroundTasks;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OrderHistoryPresenter}: the "Đơn hàng của tôi" list (AC-005-20 to -22, AC-009-4; QĐ2 of the D4 plan). No
 * JavaFX toolkit and no Spring: the order service, navigator, error reporter and session are mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderHistoryPresenterTest {

    private static final long USER_ID = 3L;
    private static final CurrentUser USER = new CurrentUser(USER_ID, "user@minishop.local", "Khách hàng mẫu", Role.USER);

    /** 27/10/2026 05:30 UTC = 12:30 in Vietnam. */
    private static final Instant NOON_IN_VIETNAM = Instant.parse("2026-10-27T05:30:00Z");
    /** 27/10/2026 18:00 UTC = 01:00 on the next day in Vietnam. */
    private static final Instant NEXT_DAY_IN_VIETNAM = Instant.parse("2026-10-27T18:00:00Z");

    @Mock
    private OrderService orders;
    @Mock
    private AppSession session;
    @Mock
    private Navigator navigator;
    @Mock
    private UnexpectedErrors errors;

    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();

    @BeforeEach
    void setUp() {
        when(session.currentUser()).thenReturn(Optional.of(USER));
    }

    private OrderHistoryPresenter presenter() {
        return new OrderHistoryPresenter(orders, session, direct, navigator, errors);
    }

    private static OrderSummaryView summary(long id, Instant at, String total, String label, String key) {
        return new OrderSummaryView(id, at, new BigDecimal(total), label, key);
    }

    private static OrderListView page(int page, int totalPages, long totalElements, OrderSummaryView... orders) {
        return new OrderListView(List.of(orders), page, totalPages, totalElements);
    }

    // ------------------------------------------------------------------ loading

    @Test
    void load_passesTheRoutePageAsTextAndTheIdOfTheSessionUser() {
        when(orders.listOrders(USER_ID, "3")).thenReturn(page(3, 4, 35));
        OrderHistoryPresenter presenter = presenter();

        presenter.load(new OrdersRoute(3));

        verify(orders).listOrders(USER_ID, "3");
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
    }

    @Test
    void load_readsTheUserWhenCalled_notWhenTheBackgroundWorkRuns() {
        when(orders.listOrders(anyLong(), anyString())).thenReturn(page(1, 1, 0));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        OrderHistoryPresenter presenter = new OrderHistoryPresenter(orders, session, manual, navigator, errors);

        presenter.load(OrdersRoute.first());
        when(session.currentUser()).thenReturn(Optional.empty());
        manual.run(0);

        verify(orders).listOrders(USER_ID, "1");
    }

    @Test
    void load_whilePending_isLoadingAndNotLoadedYet() {
        when(orders.listOrders(anyLong(), anyString())).thenReturn(page(1, 1, 0));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        OrderHistoryPresenter presenter = new OrderHistoryPresenter(orders, session, manual, navigator, errors);

        presenter.load(OrdersRoute.first());

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.pageNavProperty().get()).isNull();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void load_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter().load(null));
    }

    @Test
    void load_asAGuest_neverCallsTheServiceAndGoesHome() {
        when(session.currentUser()).thenReturn(Optional.empty());
        OrderHistoryPresenter presenter = presenter();

        presenter.load(OrdersRoute.first());

        verifyNoInteractions(orders);
        verify(navigator).goHome();
    }

    @Test
    void load_whenTheServiceFails_reportsOnceAndStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(orders.listOrders(anyLong(), anyString())).thenThrow(failure);
        OrderHistoryPresenter presenter = presenter();

        presenter.load(OrdersRoute.first());

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    // ------------------------------------------------------------------ rows (AC-005-20)

    @Test
    void rows_showIdDateInVietnamTimeTotalAndStatusLabelInTheOrderOfTheService() {
        when(orders.listOrders(USER_ID, "1")).thenReturn(page(1, 1, 2,
                summary(43, NEXT_DAY_IN_VIETNAM, "190000", "Đã hủy", "cancelled"),
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        OrderHistoryPresenter presenter = presenter();

        presenter.load(OrdersRoute.first());

        assertThat(presenter.rows()).containsExactly(
                new OrderHistoryPresenter.OrderRow(43, "#43", "28/10/2026 01:00", "190.000 ₫", "Đã hủy", "cancelled",
                        "Đơn hàng #43, 28/10/2026 01:00, 190.000 ₫, Đã hủy"),
                new OrderHistoryPresenter.OrderRow(42, "#42", "27/10/2026 12:30", "1.250.000 ₫", "Chờ xác nhận",
                        "pending", "Đơn hàng #42, 27/10/2026 12:30, 1.250.000 ₫, Chờ xác nhận"));
        assertThat(presenter.emptyProperty().get()).isFalse();
    }

    @Test
    void rows_areReplacedByTheNextLoad() {
        when(orders.listOrders(USER_ID, "1")).thenReturn(page(1, 1, 1,
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        OrderHistoryPresenter presenter = presenter();
        presenter.load(OrdersRoute.first());
        when(orders.listOrders(USER_ID, "2")).thenReturn(page(2, 2, 11,
                summary(7, NOON_IN_VIETNAM, "50000", "Đã giao", "delivered")));

        presenter.load(new OrdersRoute(2));

        assertThat(presenter.rows()).extracting(OrderHistoryPresenter.OrderRow::id).containsExactly(7L);
    }

    // ------------------------------------------------------------------ paging (AC-005-21)

    @Test
    void pageNav_followsThePageTheServiceReturned() {
        when(orders.listOrders(USER_ID, "2")).thenReturn(page(2, 3, 25,
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        OrderHistoryPresenter presenter = presenter();

        presenter.load(new OrdersRoute(2));

        assertThat(presenter.pageNavProperty().get()).isEqualTo(PageNav.of(2, 3));
    }

    @Test
    void pageNav_whenThePageWasPastTheEnd_showsPageOneAsReturnedByTheService() {
        when(orders.listOrders(USER_ID, "9")).thenReturn(page(1, 3, 25,
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        OrderHistoryPresenter presenter = presenter();

        presenter.load(new OrdersRoute(9));

        assertThat(presenter.pageNavProperty().get().current()).isEqualTo(1);
        assertThat(presenter.pageNavProperty().get()).isEqualTo(PageNav.of(1, 3));
        assertThat(presenter.rows()).hasSize(1);
    }

    @Test
    void goToPage_opensTheOrdersRouteOfThatPage() {
        OrderHistoryPresenter presenter = presenter();

        presenter.goToPage(3);

        verify(navigator).navigateTo(new OrdersRoute(3));
    }

    @Test
    void goToPage_belowOne_isIgnored() {
        OrderHistoryPresenter presenter = presenter();

        presenter.goToPage(0);
        presenter.goToPage(-2);

        verifyNoInteractions(navigator);
    }

    // ------------------------------------------------------------------ empty (AC-005-22)

    @Test
    void load_ofAUserWithNoOrders_isEmpty() {
        when(orders.listOrders(USER_ID, "1")).thenReturn(page(1, 0, 0));
        OrderHistoryPresenter presenter = presenter();

        presenter.load(OrdersRoute.first());

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.rows()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void startShopping_opensTheNewestProducts() {
        presenter().startShopping();

        verify(navigator).navigateTo(CatalogRoute.all());
    }

    // ------------------------------------------------------------------ opening an order

    @Test
    void openOrder_keepsTheCurrentPageAsTheWayBack() {
        when(orders.listOrders(USER_ID, "2")).thenReturn(page(2, 3, 25,
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        OrderHistoryPresenter presenter = presenter();
        presenter.load(new OrdersRoute(2));

        presenter.openOrder(42);

        verify(navigator).navigateTo(new OrderDetailRoute(42, new OrdersRoute(2), false));
    }

    @Test
    void openOrder_afterThePastTheEndFallback_goesBackToPageOne() {
        when(orders.listOrders(USER_ID, "9")).thenReturn(page(1, 3, 25,
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        OrderHistoryPresenter presenter = presenter();
        presenter.load(new OrdersRoute(9));

        presenter.openOrder(42);

        verify(navigator).navigateTo(new OrderDetailRoute(42, OrdersRoute.first(), false));
    }

    @Test
    void openOrder_beforeAnythingLoaded_backsToPageOne() {
        presenter().openOrder(42);

        verify(navigator).navigateTo(new OrderDetailRoute(42, OrdersRoute.first(), false));
    }

    // ------------------------------------------------------------------ dispose

    @Test
    void dispose_cancelsThePendingLoadSoItsAnswerChangesNothing() {
        when(orders.listOrders(anyLong(), anyString())).thenReturn(page(1, 1, 1,
                summary(42, NOON_IN_VIETNAM, "1250000", "Chờ xác nhận", "pending")));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        OrderHistoryPresenter presenter = new OrderHistoryPresenter(orders, session, manual, navigator, errors);
        presenter.load(OrdersRoute.first());

        presenter.dispose();
        manual.runAll();

        assertThat(manual.handle(0).isCancelled()).isTrue();
        assertThat(presenter.rows()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void afterDispose_navigationCommandsDoNothing() {
        OrderHistoryPresenter presenter = presenter();
        presenter.dispose();

        presenter.goToPage(2);
        presenter.openOrder(42);
        presenter.startShopping();

        verifyNoInteractions(navigator);
        verify(orders, never()).listOrders(anyLong(), anyString());
    }

    @Test
    void dispose_beforeLoad_andTwice_isHarmless() {
        OrderHistoryPresenter presenter = presenter();

        presenter.dispose();
        presenter.dispose();

        assertThat(presenter.rows()).isEmpty();
    }
}

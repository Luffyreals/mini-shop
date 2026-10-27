package com.minishop.cart;

import com.minishop.catalog.AddToCartResult;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AddToCartPresenter}: the "add to cart" shared by the storefront screens (AC-004-7, AC-009-3, BR-009-4; QĐ2
 * of the D3 plan). No JavaFX toolkit and no Spring: the cart service, toasts, badge, error reporter and session are
 * mocks, the owner provider is real so a guest and a signed-in user take the same path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddToCartPresenterTest {

    private static final long PRODUCT = 7L;
    private static final CurrentUser USER = new CurrentUser(3L, "user@minishop.local", "Khách hàng mẫu", Role.USER);
    private static final CartOwner.Customer USER_CART = new CartOwner.Customer(3L);

    @Mock
    private CartService cartService;
    @Mock
    private AppSession session;
    @Mock
    private Toasts toasts;
    @Mock
    private CartBadgePresenter badge;
    @Mock
    private UnexpectedErrors errors;

    private final RecordingGuestCartStore store = new RecordingGuestCartStore();
    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();
    private final List<AddToCartResult> results = new ArrayList<>();
    private CurrentCartOwner currentOwner;

    @BeforeEach
    void setUp() {
        currentOwner = new CurrentCartOwner(session, store);
        when(session.currentUser()).thenReturn(Optional.empty());
    }

    private AddToCartPresenter presenter(BackgroundTasks tasks) {
        return new AddToCartPresenter(cartService, currentOwner, tasks, toasts, badge, errors);
    }

    private Consumer<AddToCartResult> recorder() {
        return results::add;
    }

    private void cartReturns(CartChange.Outcome outcome, int quantity) {
        when(cartService.add(any(), anyLong(), anyInt())).thenReturn(new CartChange(outcome, quantity));
    }

    // ------------------------------------------------------------------ one row per outcome

    @Test
    void add_whenOk_showsTheSuccessToastAndReportsAdded() {
        cartReturns(CartChange.Outcome.OK, 2);

        presenter(direct).add(PRODUCT, "2", recorder());

        verify(toasts).show("Đã thêm vào giỏ hàng.", ToastKind.SUCCESS);
        assertThat(results).containsExactly(AddToCartResult.ADDED);
    }

    @Test
    void add_whenCappedAtStock_showsTheStockToastAndStillReportsAdded() {
        cartReturns(CartChange.Outcome.CAPPED_AT_STOCK, 4);

        presenter(direct).add(PRODUCT, "9", recorder());

        verify(toasts).show("Chỉ còn 4 sản phẩm trong kho.", ToastKind.INFO);
        assertThat(results).containsExactly(AddToCartResult.ADDED);
    }

    @Test
    void add_whenCappedAtMax_showsTheMaximumToastAndStillReportsAdded() {
        cartReturns(CartChange.Outcome.CAPPED_AT_MAX, 99);

        presenter(direct).add(PRODUCT, "50", recorder());

        verify(toasts).show("Số lượng tối đa cho mỗi sản phẩm là 99.", ToastKind.INFO);
        assertThat(results).containsExactly(AddToCartResult.ADDED);
    }

    @Test
    void add_whenOutOfStock_showsAnErrorToastAndReportsOutOfStock() {
        cartReturns(CartChange.Outcome.OUT_OF_STOCK, 0);

        presenter(direct).add(PRODUCT, "1", recorder());

        verify(toasts).show("Sản phẩm này đã hết hàng.", ToastKind.ERROR);
        assertThat(results).containsExactly(AddToCartResult.OUT_OF_STOCK);
    }

    @Test
    void add_whenTheProductIsNotFound_showsNoToastAndReportsProductGone() {
        doThrow(new NotFoundException("Product 7")).when(cartService).add(any(), anyLong(), anyInt());

        presenter(direct).add(PRODUCT, "1", recorder());

        verifyNoInteractions(toasts, errors);
        assertThat(results).containsExactly(AddToCartResult.PRODUCT_GONE);
    }

    @Test
    void add_whenTheServiceFailsUnexpectedly_reportsOnceShowsNoToastAndReportsFailed() {
        RuntimeException failure = new IllegalStateException("database is down");
        doThrow(failure).when(cartService).add(any(), anyLong(), anyInt());

        presenter(direct).add(PRODUCT, "1", recorder());

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts);
        assertThat(results).containsExactly(AddToCartResult.FAILED);
        assertThat(direct.unhandled()).isEmpty();
    }

    @Test
    void add_asksTheServiceForTheParsedQuantityOfTheGivenProduct() {
        cartReturns(CartChange.Outcome.OK, 12);

        presenter(direct).add(PRODUCT, " 12 ", recorder());

        verify(cartService).add(new CartOwner.Guest(store), PRODUCT, 12);
    }

    // ------------------------------------------------------------------ invalid quantity (AC-004-7)

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100", "abc", "", "  ", "1.5", "1,5", "+1", "007x", "1e1", "２"})
    void add_withAnInvalidQuantity_neverTouchesTheCartTheOwnerTheTasksOrTheBadge(String raw) {
        BackgroundTasks tasks = mock(BackgroundTasks.class);
        CurrentCartOwner owner = mock(CurrentCartOwner.class);
        AddToCartPresenter presenter = new AddToCartPresenter(cartService, owner, tasks, toasts, badge, errors);

        presenter.add(PRODUCT, raw, recorder());

        verifyNoInteractions(cartService, owner, tasks, badge, errors);
        verify(toasts).show("Số lượng không hợp lệ.", ToastKind.ERROR);
        assertThat(results).containsExactly(AddToCartResult.INVALID_QUANTITY);
    }

    @ParameterizedTest
    @NullSource
    void add_withANullQuantity_isInvalidAndTouchesNothing(String raw) {
        BackgroundTasks tasks = mock(BackgroundTasks.class);
        CurrentCartOwner owner = mock(CurrentCartOwner.class);
        AddToCartPresenter presenter = new AddToCartPresenter(cartService, owner, tasks, toasts, badge, errors);

        presenter.add(PRODUCT, raw, recorder());

        verifyNoInteractions(cartService, owner, tasks, badge, errors);
        verify(toasts).show("Số lượng không hợp lệ.", ToastKind.ERROR);
        assertThat(results).containsExactly(AddToCartResult.INVALID_QUANTITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "99", "01", " 5 "})
    void add_withTheBoundaryQuantities_reachesTheService(String raw) {
        cartReturns(CartChange.Outcome.OK, 1);

        presenter(direct).add(PRODUCT, raw, recorder());

        verify(cartService).add(any(), anyLong(), anyInt());
        assertThat(results).containsExactly(AddToCartResult.ADDED);
    }

    @Test
    void add_withAnInvalidQuantity_doesNotRefreshTheBadge() {
        presenter(direct).add(PRODUCT, "0", recorder());

        verify(badge, never()).refresh();
    }

    // ------------------------------------------------------------------ owner is read before the work runs (BR-009-4)

    @Test
    void add_readsTheOwnerWhenCalled_notWhenTheBackgroundWorkRuns() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        cartReturns(CartChange.Outcome.OK, 1);
        when(session.currentUser()).thenReturn(Optional.of(USER));
        AddToCartPresenter presenter = presenter(manual);

        presenter.add(PRODUCT, "1", recorder());
        // The user logs out while the work is still waiting
        when(session.currentUser()).thenReturn(Optional.empty());
        manual.run(0);

        verify(cartService).add(USER_CART, PRODUCT, 1);
        verify(cartService, never()).add(new CartOwner.Guest(store), PRODUCT, 1);
    }

    @Test
    void add_aGuestWhoLogsInBeforeTheWorkRuns_stillAddsToTheGuestCart() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        cartReturns(CartChange.Outcome.OK, 1);
        AddToCartPresenter presenter = presenter(manual);

        presenter.add(PRODUCT, "1", recorder());
        when(session.currentUser()).thenReturn(Optional.of(USER));
        manual.run(0);

        verify(cartService).add(new CartOwner.Guest(store), PRODUCT, 1);
        verify(cartService, never()).add(USER_CART, PRODUCT, 1);
    }

    @Test
    void add_beforeTheWorkRuns_nothingHappensYet() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        AddToCartPresenter presenter = presenter(manual);

        presenter.add(PRODUCT, "1", recorder());

        assertThat(manual.submittedCount()).isEqualTo(1);
        verifyNoInteractions(cartService, toasts, badge);
        assertThat(results).isEmpty();
    }

    // ------------------------------------------------------------------ badge and callback

    @Test
    void add_refreshesTheBadgeAfterEveryAddThatReachedTheService() {
        cartReturns(CartChange.Outcome.OK, 1);
        AddToCartPresenter presenter = presenter(direct);

        presenter.add(PRODUCT, "1", recorder());

        verify(badge, times(1)).refresh();
    }

    @Test
    void add_refreshesTheBadgeAlsoWhenOutOfStockGoneOrFailed() {
        AddToCartPresenter presenter = presenter(direct);
        cartReturns(CartChange.Outcome.OUT_OF_STOCK, 0);
        presenter.add(PRODUCT, "1", recorder());
        doThrow(new NotFoundException("gone")).when(cartService).add(any(), anyLong(), anyInt());
        presenter.add(PRODUCT, "1", recorder());
        doThrow(new IllegalStateException("boom")).when(cartService).add(any(), anyLong(), anyInt());
        presenter.add(PRODUCT, "1", recorder());

        verify(badge, times(3)).refresh();
    }

    @Test
    void add_showsTheToastBeforeRefreshingTheBadgeAndThenCallsOnFinished() {
        cartReturns(CartChange.Outcome.OK, 1);
        List<String> order = new ArrayList<>();
        doAnswer(invocation -> order.add("toast")).when(toasts).show(any(), any());
        doAnswer(invocation -> order.add("badge")).when(badge).refresh();

        presenter(direct).add(PRODUCT, "1", result -> order.add("finished"));

        assertThat(order).containsExactly("toast", "badge", "finished");
    }

    @Test
    void add_callsOnFinishedExactlyOnce_forEveryKindOfOutcome() {
        AddToCartPresenter presenter = presenter(direct);
        for (CartChange.Outcome outcome : CartChange.Outcome.values()) {
            cartReturns(outcome, 1);
            presenter.add(PRODUCT, "1", recorder());
        }
        doThrow(new NotFoundException("gone")).when(cartService).add(any(), anyLong(), anyInt());
        presenter.add(PRODUCT, "1", recorder());
        doThrow(new IllegalStateException("boom")).when(cartService).add(any(), anyLong(), anyInt());
        presenter.add(PRODUCT, "1", recorder());
        presenter.add(PRODUCT, "x", recorder());

        assertThat(results).hasSize(CartChange.Outcome.values().length + 3);
    }

    @Test
    void add_whenTheCartSaysNotInCart_isTreatedAsAddedWithoutAToast() {
        cartReturns(CartChange.Outcome.NOT_IN_CART, 0);

        presenter(direct).add(PRODUCT, "1", recorder());

        verifyNoInteractions(toasts);
        assertThat(results).containsExactly(AddToCartResult.ADDED);
    }

    @Test
    void add_withANullCallback_throwsNullPointerExceptionBeforeDoingAnything() {
        AddToCartPresenter presenter = presenter(direct);

        assertThatNullPointerException().isThrownBy(() -> presenter.add(PRODUCT, "1", null));

        verifyNoInteractions(cartService, toasts, badge);
    }

    @Test
    void add_whenTheCallbackThrows_theFailureIsNotSwallowedByThePresenter() {
        // DirectBackgroundTasks records what the real implementation hands to the uncaught handler.
        cartReturns(CartChange.Outcome.OK, 1);

        presenter(direct).add(PRODUCT, "1", result -> {
            throw new IllegalStateException("screen bug");
        });

        assertThat(direct.unhandled()).hasSize(1);
        verifyNoInteractions(errors);
    }

    // ------------------------------------------------------------------ guests (AC-009-3)

    @Test
    void add_asAGuest_usesTheGuestCartWithoutLoggingIn() {
        cartReturns(CartChange.Outcome.OK, 2);

        presenter(direct).add(PRODUCT, "2", recorder());

        verify(cartService).add(new CartOwner.Guest(store), PRODUCT, 2);
        assertThat(results).containsExactly(AddToCartResult.ADDED);
        verify(toasts).show("Đã thêm vào giỏ hàng.", ToastKind.SUCCESS);
        // reading the owner must not create a store entry; only the cart service writes
        assertThat(store.getOrCreateCalls()).isZero();
    }

    @Test
    void add_asASignedInUser_usesTheDatabaseCartOfThatUser() {
        when(session.currentUser()).thenReturn(Optional.of(USER));
        cartReturns(CartChange.Outcome.OK, 1);

        presenter(direct).add(PRODUCT, "1", recorder());

        verify(cartService).add(USER_CART, PRODUCT, 1);
    }

    @Test
    void add_serviceIsCalledBeforeTheBadgeRefresh() {
        cartReturns(CartChange.Outcome.OK, 1);

        presenter(direct).add(PRODUCT, "1", recorder());

        InOrder order = inOrder(cartService, badge);
        order.verify(cartService).add(any(), anyLong(), anyInt());
        order.verify(badge).refresh();
    }
}

package com.minishop.cart;

import com.minishop.catalog.CatalogRoute;
import com.minishop.catalog.ProductDetailRoute;
import com.minishop.common.NotFoundException;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.DrawerHost;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CartDrawerPresenter}: the state and decisions of the cart drawer (AC-004-7, -9 to -12, -14, -18, -22, -23,
 * AC-005-1; QĐ1, QĐ6, QĐ7 of the D4 plan). No JavaFX toolkit and no Spring: the cart service, toasts, badge, error
 * reporter, navigator, drawer host and checkout entry are mocks; the owner provider is real so a guest and a signed-in
 * user take the same path. {@link DirectBackgroundTasks} runs everything at once; {@link ManualBackgroundTasks} is
 * used where the state <b>while</b> the work is pending matters.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartDrawerPresenterTest {

    private static final CurrentUser USER = new CurrentUser(3L, "user@minishop.local", "Khách hàng mẫu", Role.USER);
    private static final CartOwner.Customer USER_CART = new CartOwner.Customer(3L);
    private static final VndFormatter PRICE = new VndFormatter();

    private static final long KEYBOARD = 11L;
    private static final long MOUSE = 12L;

    @Mock
    private CartService cartService;
    @Mock
    private AppSession session;
    @Mock
    private Toasts toasts;
    @Mock
    private CartBadge badge;
    @Mock
    private UnexpectedErrors errors;
    @Mock
    private Navigator navigator;
    @Mock
    private DrawerHost drawerHost;
    @Mock
    private CheckoutEntry checkoutEntry;

    private final RecordingGuestCartStore store = new RecordingGuestCartStore();
    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();
    private CurrentCartOwner currentOwner;
    private CartOwner.Guest guestCart;

    @BeforeEach
    void setUp() {
        currentOwner = new CurrentCartOwner(session, store);
        guestCart = new CartOwner.Guest(store);
        when(session.currentUser()).thenReturn(Optional.empty());
    }

    private void logIn() {
        when(session.currentUser()).thenReturn(Optional.of(USER));
    }

    private void logOut() {
        when(session.currentUser()).thenReturn(Optional.empty());
    }

    private CartDrawerPresenter presenter(BackgroundTasks tasks) {
        return new CartDrawerPresenter(cartService, currentOwner, tasks, toasts, badge, errors, navigator,
                drawerHost, checkoutEntry);
    }

    private CartDrawerPresenter presenter() {
        return presenter(direct);
    }

    // ------------------------------------------------------------------ test data

    private static CartLineView line(long id, int quantity, CartLineStatus status, int stock, long unitPrice) {
        boolean purchasable = status == CartLineStatus.AVAILABLE || status == CartLineStatus.LOW_STOCK;
        BigDecimal price = BigDecimal.valueOf(unitPrice);
        return new CartLineView(id, "Sản phẩm " + id, "/images/products/p" + id + ".jpg", price, quantity,
                purchasable ? price.multiply(BigDecimal.valueOf(quantity)) : null, status, stock);
    }

    private static CartLineView keyboard(int quantity) {
        return line(KEYBOARD, quantity, CartLineStatus.AVAILABLE, 8, 1_250_000);
    }

    private static CartLineView mouse(int quantity) {
        return line(MOUSE, quantity, CartLineStatus.AVAILABLE, 100, 190_000);
    }

    private static CartView view(CartLineView... lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (CartLineView l : lines) {
            if (l.lineTotal() != null) {
                total = total.add(l.lineTotal());
            }
        }
        return new CartView(List.of(lines), total);
    }

    private void cartOfUserHolds(CartView... views) {
        logIn();
        if (views.length == 1) {
            when(cartService.view(USER_CART)).thenReturn(views[0]);
        } else {
            when(cartService.view(USER_CART)).thenReturn(views[0], java.util.Arrays.copyOfRange(views, 1, views.length));
        }
    }

    /** A loaded drawer of a signed-in user, ready for a write. */
    private CartDrawerPresenter loadedPresenter(CartView first, CartView... later) {
        CartView[] all = new CartView[1 + later.length];
        all[0] = first;
        System.arraycopy(later, 0, all, 1, later.length);
        cartOfUserHolds(all);
        CartDrawerPresenter presenter = presenter();
        presenter.load();
        return presenter;
    }

    // ------------------------------------------------------------------ loading (AC-004-9, -18, -23)

    @Test
    void load_asAGuest_readsTheGuestCartWithoutLoggingIn() {
        when(cartService.view(guestCart)).thenReturn(view(keyboard(2)));
        CartDrawerPresenter presenter = presenter();

        presenter.load();

        verify(cartService).view(guestCart);
        assertThat(presenter.lines()).hasSize(1);
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(store.getOrCreateCalls()).as("reading never creates a store entry").isZero();
        assertThat(direct.unhandled()).isEmpty();
    }

    @Test
    void load_asAUser_readsTheDatabaseCartOfTheSignedInUser() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), mouse(3)));

        verify(cartService).view(USER_CART);
        assertThat(presenter.lines()).extracting(CartLineView::productId).containsExactly(KEYBOARD, MOUSE);
    }

    @Test
    void load_readsTheCartOwnerWhenCalled_notWhenTheBackgroundWorkRuns() {
        logIn();
        when(cartService.view(any())).thenReturn(view(keyboard(1)));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartDrawerPresenter presenter = presenter(manual);

        presenter.load();
        logOut();
        manual.run(0);

        verify(cartService).view(USER_CART);
        verify(cartService, never()).view(guestCart);
    }

    @Test
    void load_whilePending_isLoadingAndNotLoadedYet() {
        when(cartService.view(any())).thenReturn(view(keyboard(1)));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartDrawerPresenter presenter = presenter(manual);

        presenter.load();

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.lines()).isEmpty();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
    }

    @Test
    void load_showsTheTotalFormattedAsVnd() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2), mouse(1)));

        assertThat(presenter.totalTextProperty().get())
                .isEqualTo(PRICE.format(new BigDecimal("2690000")))
                .isEqualTo("2.690.000 ₫");
    }

    @Test
    void totalText_beforeTheFirstLoad_isZeroVnd() {
        assertThat(presenter().totalTextProperty().get()).isEqualTo("0 ₫");
    }

    @Test
    void load_ofAnEmptyCart_isEmptyAndNotOrderableWithoutANotice() {
        CartDrawerPresenter presenter = loadedPresenter(view());

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.orderableProperty().get()).isFalse();
        assertThat(presenter.showNotOrderableNoticeProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.totalTextProperty().get()).isEqualTo("0 ₫");
    }

    @Test
    void load_ofAnOrderableCart_isOrderableWithoutANotice() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), mouse(2)));

        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.orderableProperty().get()).isTrue();
        assertThat(presenter.showNotOrderableNoticeProperty().get()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CartLineStatus.class, names = "AVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void load_ofACartWithALineThatCannotBeOrdered_isNotOrderableAndShowsTheNotice(CartLineStatus status) {
        CartLineView bad = line(MOUSE, 5, status, status == CartLineStatus.OUT_OF_STOCK ? 0 : 3, 190_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), bad));

        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.orderableProperty().get()).isFalse();
        assertThat(presenter.showNotOrderableNoticeProperty().get()).isTrue();
    }

    @Test
    void load_theTotalCountsOnlyTheLinesThatCanBeBought() {
        CartLineView gone = line(MOUSE, 4, CartLineStatus.DISCONTINUED, 50, 190_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), gone));

        assertThat(presenter.totalTextProperty().get()).isEqualTo("1.250.000 ₫");
        assertThat(presenter.lines()).hasSize(2);
    }

    @Test
    void load_neverWritesToTheCart_noToastNoBadgeRefresh() {
        loadedPresenter(view(keyboard(1)));

        verify(cartService, never()).update(any(), anyLong(), anyInt());
        verify(cartService, never()).remove(any(), anyLong());
        verifyNoInteractions(toasts, badge);
    }

    @Test
    void load_whenTheServiceFails_reportsTheErrorOnceAndStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(cartService.view(any())).thenThrow(failure);
        CartDrawerPresenter presenter = presenter();

        presenter.load();

        verify(errors).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    // ------------------------------------------------------------------ labels (AC-004-14)

    @Test
    void labelFor_usesTheThreeSentencesOfTheSpec() {
        CartDrawerPresenter presenter = presenter();

        assertThat(presenter.labelFor(line(1, 1, CartLineStatus.DISCONTINUED, 5, 100)))
                .isEqualTo("Sản phẩm đã ngừng bán");
        assertThat(presenter.labelFor(line(1, 1, CartLineStatus.OUT_OF_STOCK, 0, 100))).isEqualTo("Hết hàng");
        assertThat(presenter.labelFor(line(1, 9, CartLineStatus.LOW_STOCK, 3, 100))).isEqualTo("Chỉ còn 3 sản phẩm");
        assertThat(presenter.labelFor(line(1, 1, CartLineStatus.AVAILABLE, 50, 100))).isNull();
    }

    @Test
    void labelStyleFor_followsTheStatus() {
        CartDrawerPresenter presenter = presenter();

        assertThat(presenter.labelStyleFor(line(1, 1, CartLineStatus.DISCONTINUED, 5, 100))).isEqualTo("gone");
        assertThat(presenter.labelStyleFor(line(1, 1, CartLineStatus.OUT_OF_STOCK, 0, 100))).isEqualTo("out");
        assertThat(presenter.labelStyleFor(line(1, 9, CartLineStatus.LOW_STOCK, 3, 100))).isEqualTo("low");
        assertThat(presenter.labelStyleFor(line(1, 1, CartLineStatus.AVAILABLE, 50, 100))).isNull();
    }

    @Test
    void lineTexts_formatPricesAndShowADashForALineThatCannotBeBought() {
        CartDrawerPresenter presenter = presenter();
        CartLineView ok = keyboard(2);
        CartLineView gone = line(MOUSE, 2, CartLineStatus.DISCONTINUED, 5, 190_000);

        assertThat(presenter.unitPriceText(ok)).isEqualTo("1.250.000 ₫");
        assertThat(presenter.lineTotalText(ok)).isEqualTo("2.500.000 ₫");
        assertThat(presenter.unitPriceText(gone)).isEqualTo("190.000 ₫");
        assertThat(presenter.lineTotalText(gone)).isEqualTo(CartDrawerPresenter.NO_LINE_TOTAL).isEqualTo("—");
    }

    // ------------------------------------------------------------------ update (AC-004-11, QĐ6 table)

    @Test
    void increase_sendsTheQuantityPlusOne_toastsSuccess_refreshesTheBadgeAndReloads() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(keyboard(3)));
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenReturn(new CartChange(CartChange.Outcome.OK, 3));

        presenter.increase(KEYBOARD);

        verify(cartService).update(USER_CART, KEYBOARD, 3);
        verify(toasts).show("Đã cập nhật số lượng.", ToastKind.SUCCESS);
        verify(badge).refresh();
        verify(cartService, times(2)).view(USER_CART);
        assertThat(presenter.lines()).singleElement().extracting(CartLineView::quantity).isEqualTo(3);
        assertThat(presenter.totalTextProperty().get()).isEqualTo("3.750.000 ₫");
        assertThat(presenter.writingIds()).isEmpty();
        assertThat(direct.unhandled()).isEmpty();
    }

    @Test
    void decrease_sendsTheQuantityMinusOne() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(4)), view(keyboard(3)));
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenReturn(new CartChange(CartChange.Outcome.OK, 3));

        presenter.decrease(KEYBOARD);

        verify(cartService).update(USER_CART, KEYBOARD, 3);
        verify(toasts).show("Đã cập nhật số lượng.", ToastKind.SUCCESS);
    }

    @Test
    void decrease_whenTheQuantityIsOne_doesNotCallTheService() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1)));

        presenter.decrease(KEYBOARD);

        verify(cartService, never()).update(any(), anyLong(), anyInt());
        verifyNoInteractions(toasts, badge);
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void increase_whenTheQuantityIs99_doesNotCallTheService() {
        CartDrawerPresenter presenter = loadedPresenter(view(line(KEYBOARD, 99, CartLineStatus.AVAILABLE, 200, 1_000)));

        presenter.increase(KEYBOARD);

        verify(cartService, never()).update(any(), anyLong(), anyInt());
        verifyNoInteractions(toasts, badge);
    }

    @Test
    void increase_andDecrease_forALineThatIsNotShown_doNothing() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)));

        presenter.increase(999L);
        presenter.decrease(999L);

        verify(cartService, never()).update(any(), anyLong(), anyInt());
    }

    @Test
    void submitQuantity_withAValidNumber_updatesToThatNumberAndReturnsTrue() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(keyboard(5)));
        when(cartService.update(USER_CART, KEYBOARD, 5)).thenReturn(new CartChange(CartChange.Outcome.OK, 5));

        boolean sent = presenter.submitQuantity(KEYBOARD, " 5 ");

        assertThat(sent).isTrue();
        verify(cartService).update(USER_CART, KEYBOARD, 5);
        verify(toasts).show("Đã cập nhật số lượng.", ToastKind.SUCCESS);
        verify(badge).refresh();
    }

    @Test
    void submitQuantity_withTheNumberAlreadyThere_stillUpdatesAndSaysSo() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(keyboard(2)));
        when(cartService.update(USER_CART, KEYBOARD, 2)).thenReturn(new CartChange(CartChange.Outcome.OK, 2));

        boolean sent = presenter.submitQuantity(KEYBOARD, "2");

        assertThat(sent).isTrue();
        verify(cartService).update(USER_CART, KEYBOARD, 2);
        verify(toasts).show("Đã cập nhật số lượng.", ToastKind.SUCCESS);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "100", "abc", "", "   ", "1.5", "1e1", "٣"})
    void submitQuantity_withABadQuantity_toastsAnErrorAndNeverCallsTheService(String raw) {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)));

        boolean sent = presenter.submitQuantity(KEYBOARD, raw);

        assertThat(sent).isFalse();
        verify(toasts).show("Số lượng không hợp lệ.", ToastKind.ERROR);
        verify(cartService, never()).update(any(), anyLong(), anyInt());
        verify(badge, never()).refresh();
        assertThat(presenter.writingIds()).as("the line stays unlocked").isEmpty();
        assertThat(presenter.lines()).singleElement().extracting(CartLineView::quantity).isEqualTo(2);
    }

    @Test
    void update_cappedAtStock_toastsHowManyAreLeftAsInfoAndReloads() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(keyboard(8)));
        when(cartService.update(USER_CART, KEYBOARD, 50)).thenReturn(new CartChange(CartChange.Outcome.CAPPED_AT_STOCK, 8));

        presenter.submitQuantity(KEYBOARD, "50");

        verify(toasts).show("Chỉ còn 8 sản phẩm trong kho.", ToastKind.INFO);
        verify(badge).refresh();
        assertThat(presenter.lines()).singleElement().extracting(CartLineView::quantity).isEqualTo(8);
    }

    @Test
    void update_cappedAtMax_toastsTheMaximumAsInfo() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(keyboard(99)));
        when(cartService.update(USER_CART, KEYBOARD, 99)).thenReturn(new CartChange(CartChange.Outcome.CAPPED_AT_MAX, 99));

        presenter.submitQuantity(KEYBOARD, "99");

        verify(toasts).show("Số lượng tối đa cho mỗi sản phẩm là 99.", ToastKind.INFO);
        verify(toasts).show(CartDrawerPresenter.MSG_MAX, ToastKind.INFO);
    }

    @Test
    void update_outOfStock_toastsAnErrorAndReloadsSoTheLineBecomesSoldOut() {
        CartLineView soldOut = line(KEYBOARD, 2, CartLineStatus.OUT_OF_STOCK, 0, 1_250_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(soldOut));
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenReturn(new CartChange(CartChange.Outcome.OUT_OF_STOCK, 0));

        presenter.increase(KEYBOARD);

        verify(toasts).show("Sản phẩm này đã hết hàng.", ToastKind.ERROR);
        assertThat(presenter.labelFor(presenter.lines().getFirst())).isEqualTo("Hết hàng");
        assertThat(presenter.orderableProperty().get()).isFalse();
        assertThat(presenter.showNotOrderableNoticeProperty().get()).isTrue();
    }

    @Test
    void update_notInCart_showsNoToastAndReloadsSoTheLineDisappears() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view());
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenReturn(new CartChange(CartChange.Outcome.NOT_IN_CART, 0));

        presenter.increase(KEYBOARD);

        verifyNoInteractions(toasts);
        verify(badge).refresh();
        assertThat(presenter.lines()).isEmpty();
        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void update_whenTheProductWasJustHidden_showsNoToastAndReloadsSoTheLineBecomesDiscontinued() {
        CartLineView gone = line(KEYBOARD, 2, CartLineStatus.DISCONTINUED, 8, 1_250_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view(gone));
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenThrow(new NotFoundException("hidden"));

        presenter.increase(KEYBOARD);

        verifyNoInteractions(toasts, errors);
        verify(badge).refresh();
        assertThat(presenter.labelFor(presenter.lines().getFirst())).isEqualTo("Sản phẩm đã ngừng bán");
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void update_whenTheServiceFailsUnexpectedly_reportsOnceAndUnlocksTheLine() {
        RuntimeException failure = new IllegalStateException("lock timeout");
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)));
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenThrow(failure);

        presenter.increase(KEYBOARD);

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts);
        assertThat(presenter.writingIds()).isEmpty();

        presenter.increase(KEYBOARD);

        verify(cartService, times(2)).update(USER_CART, KEYBOARD, 3);
    }

    // ------------------------------------------------------------------ remove (AC-004-12)

    @Test
    void remove_toastsSuccess_refreshesTheBadgeAndReloadsWithoutTheLine() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2), mouse(1)), view(mouse(1)));

        presenter.remove(KEYBOARD);

        verify(cartService).remove(USER_CART, KEYBOARD);
        verify(toasts).show("Đã xóa sản phẩm khỏi giỏ hàng.", ToastKind.SUCCESS);
        verify(badge).refresh();
        assertThat(presenter.lines()).extracting(CartLineView::productId).containsExactly(MOUSE);
        assertThat(presenter.totalTextProperty().get()).isEqualTo("190.000 ₫");
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void remove_ofTheLastLine_leavesAnEmptyCart() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)), view());

        presenter.remove(KEYBOARD);

        assertThat(presenter.emptyProperty().get()).isTrue();
        assertThat(presenter.orderableProperty().get()).isFalse();
    }

    @Test
    void remove_ofADiscontinuedLine_isAllowedAndClearsTheNotice() {
        CartLineView gone = line(MOUSE, 1, CartLineStatus.DISCONTINUED, 5, 190_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), gone), view(keyboard(1)));
        assertThat(presenter.showNotOrderableNoticeProperty().get()).isTrue();

        presenter.remove(MOUSE);

        verify(cartService).remove(USER_CART, MOUSE);
        assertThat(presenter.showNotOrderableNoticeProperty().get()).isFalse();
        assertThat(presenter.orderableProperty().get()).isTrue();
    }

    @Test
    void remove_whenTheServiceFails_reportsOnceAndUnlocksTheLine() {
        RuntimeException failure = new IllegalStateException("lock timeout");
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)));
        org.mockito.Mockito.doThrow(failure).when(cartService).remove(USER_CART, KEYBOARD);

        presenter.remove(KEYBOARD);

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, badge);
        assertThat(presenter.writingIds()).isEmpty();
    }

    // ------------------------------------------------------------------ guests and users (AC-004-23, AC-004-18)

    @Test
    void aGuest_canUpdateAndRemoveWithoutLoggingIn() {
        when(cartService.view(guestCart)).thenReturn(view(keyboard(2), mouse(1)));
        when(cartService.update(guestCart, KEYBOARD, 3)).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter();
        presenter.load();

        presenter.increase(KEYBOARD);
        presenter.remove(MOUSE);

        verify(cartService).update(guestCart, KEYBOARD, 3);
        verify(cartService).remove(guestCart, MOUSE);
        verify(toasts).show("Đã cập nhật số lượng.", ToastKind.SUCCESS);
        verify(toasts).show("Đã xóa sản phẩm khỏi giỏ hàng.", ToastKind.SUCCESS);
        verify(badge, times(2)).refresh();
        verify(cartService, never()).update(eq(USER_CART), anyLong(), anyInt());
    }

    @Test
    void aWrite_usesTheOwnerOfTheMomentItWasStarted_evenIfTheSessionChangesBeforeTheWorkRuns() {
        when(cartService.view(any())).thenReturn(view(keyboard(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        logIn();
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);

        presenter.increase(KEYBOARD);
        logOut();
        manual.run(1);

        verify(cartService).update(USER_CART, KEYBOARD, 3);
        verify(cartService, never()).update(eq(guestCart), anyLong(), anyInt());
        // the reload after the write is for the same owner, not for the guest the session turned into
        manual.run(2);
        verify(cartService, times(2)).view(USER_CART);
        verify(cartService, never()).view(guestCart);
    }

    // ------------------------------------------------------------------ a line being written (QĐ6)

    @Test
    void aLineBeingWritten_isLockedAndRefusesASecondCommand() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);

        presenter.increase(KEYBOARD);
        assertThat(presenter.writingIds()).containsExactly(KEYBOARD);
        presenter.increase(KEYBOARD);
        presenter.decrease(KEYBOARD);
        presenter.remove(KEYBOARD);
        boolean typed = presenter.submitQuantity(KEYBOARD, "5");

        assertThat(typed).isFalse();
        assertThat(manual.submittedCount()).as("load + the one update").isEqualTo(2);
        verify(cartService, never()).remove(any(), anyLong());
    }

    @Test
    void repeatedPlusOnTheSameLine_whileTheWriteIsInFlight_sendsExactlyOneUpdate_andAfterwardsTheNextOneIsAccepted() {
        // The drawer keeps the row's controls enabled while it writes (so a repeated Enter keeps the focus), which
        // makes this refusal the only guard against a double write.
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);

        presenter.increase(KEYBOARD);
        presenter.increase(KEYBOARD);
        presenter.increase(KEYBOARD);

        assertThat(manual.submittedCount()).as("load + one update").isEqualTo(2);
        assertThat(presenter.writingIds()).containsExactly(KEYBOARD);
        manual.run(1);
        verify(cartService, times(1)).update(USER_CART, KEYBOARD, 3);
        verify(cartService, never()).update(USER_CART, KEYBOARD, 4);

        when(cartService.view(USER_CART)).thenReturn(view(keyboard(3)));
        manual.run(2);
        assertThat(presenter.writingIds()).isEmpty();
        presenter.increase(KEYBOARD);
        assertThat(manual.submittedCount()).as("a new press after the reload is accepted").isEqualTo(4);
    }

    @Test
    void aLineBeingWritten_staysLockedUntilTheReloadAfterTheWriteIsApplied() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.increase(KEYBOARD);

        manual.run(1); // the write is done, the reload is queued
        assertThat(presenter.writingIds()).as("still locked while reloading").containsExactly(KEYBOARD);

        manual.run(2); // the reload arrives
        assertThat(presenter.writingIds()).isEmpty();
    }

    @Test
    void anotherLine_canBeWrittenWhileOneIsBeingWritten() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2), mouse(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);

        presenter.increase(KEYBOARD);
        presenter.increase(MOUSE);

        assertThat(presenter.writingIds()).containsExactlyInAnyOrder(KEYBOARD, MOUSE);
        assertThat(manual.submittedCount()).isEqualTo(3);
    }

    @Test
    void twoWritesInARow_bothLinesAreUnlockedByTheNewestReload_andAnOlderReloadIsIgnored() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        CartView older = view(keyboard(3), mouse(2));
        CartView newer = view(keyboard(3), mouse(3));
        // answers are handed out in the order the work runs: the newest reload (task 4) runs first, the older one last
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2), mouse(2)), newer, older);
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.increase(KEYBOARD); // task 1
        presenter.increase(MOUSE);    // task 2
        manual.run(1);                // queues reload #1 (task 3)
        manual.run(2);                // queues reload #2 (task 4)

        manual.run(4); // the newest reload arrives first
        assertThat(presenter.lines()).containsExactlyElementsOf(newer.lines());
        assertThat(presenter.writingIds()).isEmpty();

        manual.runAll(); // the older reload arrives late
        assertThat(presenter.lines()).as("an older answer never overwrites a newer one")
                .containsExactlyElementsOf(newer.lines());
    }

    // ------------------------------------------------------------------ badge (AC-004-22)

    @Test
    void badge_isRefreshedAfterEveryWrite_andOnlyAfterAWrite() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2), mouse(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        verify(badge, never()).refresh();

        presenter.increase(KEYBOARD);
        presenter.decrease(KEYBOARD);
        presenter.submitQuantity(MOUSE, "4");
        presenter.remove(MOUSE);
        presenter.submitQuantity(MOUSE, "abc");

        verify(badge, times(4)).refresh();
    }

    // ------------------------------------------------------------------ navigation (QĐ7, AC-005-1)

    @Test
    void checkout_whenOrderable_closesTheDrawerThenOpensTheCheckout() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1)));

        presenter.checkout();

        InOrder order = inOrder(drawerHost, checkoutEntry);
        order.verify(drawerHost).close();
        order.verify(checkoutEntry).open();
        verifyNoMoreInteractions(checkoutEntry);
    }

    @Test
    void checkout_asAGuest_stillGoesThroughTheCheckoutEntryWhichAsksForLogin() {
        when(cartService.view(guestCart)).thenReturn(view(keyboard(1)));
        CartDrawerPresenter presenter = presenter();
        presenter.load();

        presenter.checkout();

        verify(drawerHost).close();
        verify(checkoutEntry).open();
    }

    @Test
    void checkout_whenACartLineCannotBeOrdered_doesNothing() {
        CartLineView gone = line(MOUSE, 1, CartLineStatus.DISCONTINUED, 5, 190_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), gone));

        presenter.checkout();

        verifyNoInteractions(drawerHost, checkoutEntry);
    }

    @Test
    void checkout_whenTheCartIsEmpty_doesNothing() {
        CartDrawerPresenter presenter = loadedPresenter(view());

        presenter.checkout();

        verifyNoInteractions(drawerHost, checkoutEntry);
    }

    @Test
    void checkout_whileALineIsBeingWritten_doesNothingUntilTheReloadAfterTheWriteIsApplied() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.increase(KEYBOARD);

        presenter.checkout();

        verifyNoInteractions(drawerHost, checkoutEntry);

        manual.run(1); // the write is done, the reload is queued
        presenter.checkout();
        verifyNoInteractions(drawerHost, checkoutEntry);

        manual.run(2); // the reload arrives and unlocks the line
        presenter.checkout();

        verify(drawerHost).close();
        verify(checkoutEntry).open();
    }

    @Test
    void checkout_beforeTheCartIsLoaded_doesNothing() {
        presenter().checkout();

        verifyNoInteractions(drawerHost, checkoutEntry);
    }

    @Test
    void continueShopping_closesTheDrawerThenOpensTheNewestProducts() {
        CartDrawerPresenter presenter = loadedPresenter(view());

        presenter.continueShopping();

        InOrder order = inOrder(drawerHost, navigator);
        order.verify(drawerHost).close();
        order.verify(navigator).navigateTo(CatalogRoute.all());
    }

    @Test
    void openProduct_closesTheDrawerThenOpensTheDetailWithTheListAsBack() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), mouse(1)));

        presenter.openProduct(MOUSE);

        InOrder order = inOrder(drawerHost, navigator);
        order.verify(drawerHost).close();
        order.verify(navigator).navigateTo(new ProductDetailRoute(MOUSE, CatalogRoute.all()));
    }

    @Test
    void openProduct_ofADiscontinuedLine_doesNothing() {
        CartLineView gone = line(MOUSE, 1, CartLineStatus.DISCONTINUED, 5, 190_000);
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1), gone));

        presenter.openProduct(MOUSE);

        verifyNoInteractions(drawerHost, navigator);
    }

    @Test
    void openProduct_ofAnOutOfStockLine_stillOpensItBecauseItIsNotHidden() {
        CartLineView soldOut = line(MOUSE, 1, CartLineStatus.OUT_OF_STOCK, 0, 190_000);
        CartDrawerPresenter presenter = loadedPresenter(view(soldOut));

        presenter.openProduct(MOUSE);

        verify(navigator).navigateTo(new ProductDetailRoute(MOUSE, CatalogRoute.all()));
    }

    @Test
    void openProduct_ofAProductThatIsNotInTheCart_doesNothing() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1)));

        presenter.openProduct(999L);

        verifyNoInteractions(drawerHost, navigator);
    }

    @Test
    void close_closesTheDrawer() {
        presenter().close();

        verify(drawerHost).close();
        verifyNoInteractions(navigator, checkoutEntry);
    }

    // ------------------------------------------------------------------ dispose

    @Test
    void dispose_beforeTheLoadFinishes_changesNoProperty() {
        when(cartService.view(any())).thenReturn(view(keyboard(2)));
        ManualBackgroundTasks manual = ManualBackgroundTasks.ignoringCancel();
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        assertThat(presenter.lines()).isEmpty();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.emptyProperty().get()).isFalse();
        assertThat(presenter.totalTextProperty().get()).isEqualTo("0 ₫");
    }

    @Test
    void dispose_cancelsThePendingLoad() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();

        assertThat(manual.handle(0).isCancelled()).isTrue();
    }

    @Test
    void dispose_whileAFailureIsInFlight_doesNotReportIt() {
        when(cartService.view(any())).thenThrow(new IllegalStateException("late"));
        ManualBackgroundTasks manual = ManualBackgroundTasks.ignoringCancel();
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        verifyNoInteractions(errors);
    }

    @Test
    void dispose_whileAWriteIsInFlight_stillToastsAndRefreshesTheBadge_butChangesNoPropertyAndDoesNotReload() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2)));
        when(cartService.update(any(), anyLong(), anyInt())).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.increase(KEYBOARD);

        presenter.dispose();
        manual.runAll();

        verify(cartService).update(USER_CART, KEYBOARD, 3);
        verify(toasts).show("Đã cập nhật số lượng.", ToastKind.SUCCESS);
        verify(badge).refresh();
        assertThat(manual.submittedCount()).as("no reload after dispose").isEqualTo(2);
        assertThat(presenter.lines()).singleElement().extracting(CartLineView::quantity).isEqualTo(2);
    }

    @Test
    void dispose_whileARemoveIsInFlight_stillToastsAndRefreshesTheBadge() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2)));
        CartDrawerPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        presenter.remove(KEYBOARD);

        presenter.dispose();
        manual.runAll();

        verify(toasts).show("Đã xóa sản phẩm khỏi giỏ hàng.", ToastKind.SUCCESS);
        verify(badge).refresh();
        assertThat(manual.submittedCount()).isEqualTo(2);
        assertThat(presenter.lines()).hasSize(1);
    }

    @Test
    void afterDispose_everyCommandDoesNothing() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(2)));
        presenter.dispose();

        presenter.load();
        presenter.increase(KEYBOARD);
        presenter.decrease(KEYBOARD);
        presenter.remove(KEYBOARD);
        boolean typed = presenter.submitQuantity(KEYBOARD, "4");
        presenter.checkout();
        presenter.continueShopping();
        presenter.openProduct(KEYBOARD);

        assertThat(typed).isFalse();
        verify(cartService, times(1)).view(USER_CART);
        verify(cartService, never()).update(any(), anyLong(), anyInt());
        verify(cartService, never()).remove(any(), anyLong());
        verifyNoInteractions(drawerHost, navigator, checkoutEntry, toasts, badge);
    }

    @Test
    void dispose_calledTwice_isHarmless() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1)));

        presenter.dispose();
        presenter.dispose();

        assertThat(presenter.lines()).hasSize(1);
    }

    // ------------------------------------------------------------------ load failure and retry (QĐ12, AC-009-12)

    @Test
    void load_firstLoadFails_setsLoadFailed_reportsOnce_andStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(cartService.view(any())).thenThrow(failure);
        CartDrawerPresenter presenter = presenter();

        presenter.load();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isFalse();
        assertThat(presenter.lines()).isEmpty();
        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, badge, navigator, drawerHost, checkoutEntry);
    }

    @Test
    void retry_afterAFailedFirstLoad_loadsTheCartAgain_clearsLoadFailed_andShowsTheLines() {
        logIn();
        when(cartService.view(USER_CART)).thenThrow(new IllegalStateException("down"))
                .thenReturn(view(keyboard(2)));
        CartDrawerPresenter presenter = presenter();
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.lines()).extracting(CartLineView::productId).containsExactly(KEYBOARD);
        verify(cartService, times(2)).view(USER_CART);
        verify(errors, times(1)).report(any());
    }

    @Test
    void retry_readsTheCartOwnerWhenRetrying_notTheOwnerOfTheFailedLoad() {
        when(cartService.view(any())).thenThrow(new IllegalStateException("down"));
        CartDrawerPresenter presenter = presenter();
        presenter.load();
        logIn();
        org.mockito.Mockito.doReturn(view(mouse(1))).when(cartService).view(USER_CART);

        presenter.retry();

        verify(cartService, times(1)).view(guestCart);
        verify(cartService, times(1)).view(USER_CART);
        assertThat(presenter.lines()).extracting(CartLineView::productId).containsExactly(MOUSE);
        assertThat(presenter.loadFailedProperty().get()).isFalse();
    }

    @Test
    void retry_whenTheRetryFailsToo_setsLoadFailedAgain_andReportsEachFailureOnce() {
        RuntimeException failure = new IllegalStateException("down");
        when(cartService.view(any())).thenThrow(failure);
        CartDrawerPresenter presenter = presenter();
        presenter.load();

        presenter.retry();

        assertThat(presenter.loadFailedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        verify(errors, times(2)).report(failure);
    }

    @Test
    void retry_whileTheRetryIsLoading_isIgnored() {
        logIn();
        when(cartService.view(USER_CART)).thenThrow(new IllegalStateException("down"))
                .thenReturn(view(keyboard(1)));
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CartDrawerPresenter presenter = presenter(manual);
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
        when(cartService.view(any())).thenThrow(new IllegalStateException("down"));
        CartDrawerPresenter presenter = presenter();
        presenter.load();
        presenter.dispose();

        presenter.retry();

        verify(cartService, times(1)).view(any());
        assertThat(presenter.loadedProperty().get()).isFalse();
    }

    @Test
    void retry_whenNothingFailed_doesNothing() {
        CartDrawerPresenter presenter = loadedPresenter(view(keyboard(1)));

        presenter.retry();

        verify(cartService, times(1)).view(USER_CART);
    }

    @Test
    void reload_failsAfterTheCartWasShown_keepsTheLines_andLoadFailedStaysFalse() {
        RuntimeException failure = new IllegalStateException("down");
        logIn();
        when(cartService.view(USER_CART)).thenReturn(view(keyboard(2))).thenThrow(failure);
        when(cartService.update(USER_CART, KEYBOARD, 3)).thenReturn(new CartChange(CartChange.Outcome.OK, 3));
        CartDrawerPresenter presenter = presenter();
        presenter.load();

        presenter.increase(KEYBOARD);

        assertThat(presenter.loadFailedProperty().get()).isFalse();
        assertThat(presenter.loadedProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.lines()).singleElement().extracting(CartLineView::quantity).isEqualTo(2);
        assertThat(presenter.writingIds()).isEmpty();
        verify(errors, times(1)).report(failure);
        presenter.retry();
        verify(cartService, times(2)).view(USER_CART);
    }
}

package com.minishop.order;

import com.minishop.cart.CartBadge;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.desktop.DrawerHost;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.support.ManualBackgroundTasks;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CheckoutPresenter}: the checkout screen's state and decisions (AC-005-3, -4, -5, -9, -11 to -17, AC-009-4;
 * QĐ4, QĐ8, QĐ9, QĐ10 of the D4 plan). No JavaFX toolkit and no Spring: the order service, navigator, toasts, badge,
 * error reporter, session and drawer host are mocks; the shell presenter and the validator are real, so the field
 * rules and the "open the cart" action are the real ones. {@link ManualBackgroundTasks} is used where the state
 * <b>while</b> the work is pending matters.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutPresenterTest {

    private static final long USER_ID = 3L;
    private static final CurrentUser USER = new CurrentUser(USER_ID, "user@minishop.local", "Khách hàng mẫu", Role.USER);

    private static final String PHONE_MSG = "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.";
    private static final String PHONE_REQUIRED = "Vui lòng nhập số điện thoại.";
    private static final String NAME_REQUIRED = "Vui lòng nhập họ tên người nhận.";
    private static final String NAME_TOO_LONG = "Họ tên người nhận tối đa 100 ký tự.";
    private static final String ADDRESS_REQUIRED = "Vui lòng nhập địa chỉ giao hàng.";
    private static final String ADDRESS_TOO_LONG = "Địa chỉ giao hàng tối đa 500 ký tự.";
    private static final String NOTE_TOO_LONG = "Ghi chú tối đa 500 ký tự.";

    private static final BigDecimal TOTAL = new BigDecimal("2690000");

    private static ValidatorFactory factory;
    private static Validator validator;

    @Mock
    private OrderService orders;
    @Mock
    private AppSession session;
    @Mock
    private Navigator navigator;
    @Mock
    private DrawerHost drawerHost;
    @Mock
    private Toasts toasts;
    @Mock
    private CartBadge cartBadge;
    @Mock
    private UnexpectedErrors errors;
    @Mock
    private Runnable openCart;

    private final ShellPresenter shell = new ShellPresenter();
    private final DirectBackgroundTasks direct = new DirectBackgroundTasks();
    private final ReadOnlyBooleanWrapper drawerShowing = new ReadOnlyBooleanWrapper(false);

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @BeforeEach
    void setUp() {
        when(session.currentUser()).thenReturn(Optional.of(USER));
        when(drawerHost.showingProperty()).thenReturn(drawerShowing.getReadOnlyProperty());
        shell.setAction(ShellAction.CART, openCart);
    }

    private CheckoutPresenter presenter(BackgroundTasks tasks) {
        return new CheckoutPresenter(orders, session, tasks, navigator, shell, drawerHost, toasts, cartBadge, errors,
                validator);
    }

    private CheckoutPresenter presenter() {
        return presenter(direct);
    }

    // ------------------------------------------------------------------ test data

    private static CheckoutLineView keyboard(int quantity) {
        return new CheckoutLineView("Bàn phím cơ cỡ nhỏ", new BigDecimal("1250000"), quantity,
                new BigDecimal("1250000").multiply(BigDecimal.valueOf(quantity)));
    }

    private static CheckoutLineView mouse(int quantity) {
        return new CheckoutLineView("Chuột không dây văn phòng", new BigDecimal("190000"), quantity,
                new BigDecimal("190000").multiply(BigDecimal.valueOf(quantity)));
    }

    private static CheckoutView summary(BigDecimal total, boolean orderable, CheckoutLineView... lines) {
        return new CheckoutView(List.of(lines), total, orderable);
    }

    private static CheckoutView orderableSummary() {
        return summary(TOTAL, true, keyboard(2), mouse(1));
    }

    private void checkoutPageIs(CheckoutView view, String phone) {
        when(orders.checkoutPage(USER_ID)).thenReturn(new CheckoutPage(view, CheckoutForm.prefill("Khách hàng mẫu", phone)));
    }

    private CheckoutPresenter loadedPresenter() {
        checkoutPageIs(orderableSummary(), "");
        CheckoutPresenter presenter = presenter();
        presenter.load();
        return presenter;
    }

    private static void fillValidForm(CheckoutPresenter presenter) {
        presenter.receiverNameProperty().set("Nguyễn Văn An");
        presenter.phoneProperty().set("0912 345 678");
        presenter.shippingAddressProperty().set("12 Lê Lợi, Quận 1, TP.HCM");
        presenter.noteProperty().set("Giao giờ hành chính");
    }

    // ------------------------------------------------------------------ loading (AC-005-3)

    @Test
    void load_prefillsTheNameAndShowsTheSummaryAndTheTotal() {
        CheckoutPresenter presenter = loadedPresenter();

        assertThat(presenter.receiverNameProperty().get()).isEqualTo("Khách hàng mẫu");
        assertThat(presenter.phoneProperty().get()).isEmpty();
        assertThat(presenter.shippingAddressProperty().get()).isEmpty();
        assertThat(presenter.noteProperty().get()).isEmpty();
        assertThat(presenter.lines()).containsExactly(keyboard(2), mouse(1));
        assertThat(presenter.totalTextProperty().get()).isEqualTo("2.690.000 ₫");
        assertThat(presenter.readyProperty().get()).isTrue();
        assertThat(presenter.orderableProperty().get()).isTrue();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isFalse();
        assertThat(presenter.noticeTextProperty().get()).isEmpty();
        verifyNoInteractions(navigator, toasts, errors, cartBadge);
        verify(openCart, never()).run();
    }

    @Test
    void load_prefillsThePhoneWhenTheProfileHasOne() {
        checkoutPageIs(orderableSummary(), "0912345678");
        CheckoutPresenter presenter = presenter();

        presenter.load();

        assertThat(presenter.phoneProperty().get()).isEqualTo("0912345678");
    }

    @Test
    void load_asksForOnePageWithTheIdOfTheSessionUser() {
        loadedPresenter();

        verify(orders).checkoutPage(USER_ID);
        verifyNoMoreInteractions(orders);
    }

    @Test
    void load_readsTheUserWhenCalled_notWhenTheBackgroundWorkRuns() {
        checkoutPageIs(orderableSummary(), "");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);

        presenter.load();
        when(session.currentUser()).thenReturn(Optional.empty());
        manual.run(0);

        verify(orders).checkoutPage(USER_ID);
    }

    @Test
    void load_whilePending_isLoadingNotReadyAndTheConfirmButtonIsLocked() {
        checkoutPageIs(orderableSummary(), "");
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);

        presenter.load();

        assertThat(presenter.loadingProperty().get()).isTrue();
        assertThat(presenter.readyProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();

        manual.runAll();

        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.readyProperty().get()).isTrue();
        assertThat(presenter.confirmDisabledBinding().get()).isFalse();
    }

    @Test
    void load_twice_loadsOnlyOnce() {
        CheckoutPresenter presenter = loadedPresenter();

        presenter.load();

        verify(orders, times(1)).checkoutPage(USER_ID);
    }

    @Test
    void load_ofAnEmptyCartTheFirstTime_goesHomeThenOpensTheCartDrawer() {
        checkoutPageIs(summary(BigDecimal.ZERO, false), "");
        CheckoutPresenter presenter = presenter();

        presenter.load();

        InOrder order = inOrder(navigator, openCart);
        order.verify(navigator).goHome();
        order.verify(openCart).run();
        assertThat(presenter.lines()).isEmpty();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        verifyNoInteractions(toasts);
    }

    @Test
    void load_ofACartThatCannotBeOrdered_toastsOpensTheCartLocksTheButtonAndShowsTheNotice() {
        CheckoutView notOrderable = summary(TOTAL, false, keyboard(2), mouse(1));
        checkoutPageIs(notOrderable, "");
        CheckoutPresenter presenter = presenter();

        presenter.load();

        String message = "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng.";
        verify(toasts).show(message, ToastKind.ERROR);
        verify(openCart).run();
        assertThat(presenter.noticeTextProperty().get()).isEqualTo(message);
        assertThat(presenter.orderableProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        assertThat(presenter.lines()).hasSize(2);
        verify(navigator, never()).goHome();
    }

    @Test
    void load_asAGuest_neverCallsTheServiceAndGoesHome() {
        when(session.currentUser()).thenReturn(Optional.empty());
        CheckoutPresenter presenter = presenter();

        presenter.load();

        verifyNoInteractions(orders);
        verify(navigator).goHome();
    }

    @Test
    void load_whenTheServiceFails_reportsOnceAndStopsLoading() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(orders.checkoutPage(USER_ID)).thenThrow(failure);
        CheckoutPresenter presenter = presenter();

        presenter.load();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.readyProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
    }

    @Test
    void load_whenTheFirstLoadFails_reportsOnceAndGoesHomeSoTheUserIsNeverStuckOnTheSpinner() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(orders.checkoutPage(USER_ID)).thenThrow(failure);
        CheckoutPresenter presenter = presenter();

        presenter.load();

        verify(errors, times(1)).report(failure);
        verify(navigator, times(1)).goHome();
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.readyProperty().get()).isFalse();
    }

    @Test
    void load_whenTheFirstLoadFailsAfterDispose_reportsButDoesNotNavigate() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(orders.checkoutPage(USER_ID)).thenThrow(failure);
        ManualBackgroundTasks manual = ManualBackgroundTasks.ignoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        presenter.dispose();

        manual.runAll();

        verify(navigator, never()).goHome();
    }

    @Test
    void load_asAGuest_theGoHomeTaskIsCancelledByDispose() {
        when(session.currentUser()).thenReturn(Optional.empty());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        verify(navigator, never()).goHome();
    }

    // ------------------------------------------------------------------ form validation (AC-005-9, -10)

    @Test
    void confirm_withEmptyPhoneAndAddress_showsOneMessagePerFieldAndNeverCallsTheService() {
        CheckoutPresenter presenter = loadedPresenter();

        presenter.confirm();

        assertThat(presenter.receiverNameErrorProperty().get()).isEmpty();
        assertThat(presenter.phoneErrorProperty().get()).isEqualTo(PHONE_REQUIRED);
        assertThat(presenter.shippingAddressErrorProperty().get()).isEqualTo(ADDRESS_REQUIRED);
        assertThat(presenter.noteErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(CheckoutPresenter.Field.PHONE);
        verify(orders, never()).placeOrder(anyLong(), any(), any());
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(cartBadge, toasts);
    }

    @Test
    void confirm_withEveryFieldEmpty_focusesTheReceiverNameFirst() {
        CheckoutPresenter presenter = loadedPresenter();
        presenter.receiverNameProperty().set("");

        presenter.confirm();

        assertThat(presenter.receiverNameErrorProperty().get()).isEqualTo(NAME_REQUIRED);
        assertThat(presenter.phoneErrorProperty().get()).isEqualTo(PHONE_REQUIRED);
        assertThat(presenter.shippingAddressErrorProperty().get()).isEqualTo(ADDRESS_REQUIRED);
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(CheckoutPresenter.Field.RECEIVER_NAME);
    }

    @Test
    void confirm_withOnlySpacesInTheFields_countsThemAsEmpty() {
        CheckoutPresenter presenter = loadedPresenter();
        presenter.receiverNameProperty().set("   ");
        presenter.phoneProperty().set("  ");
        presenter.shippingAddressProperty().set(" \n ");

        presenter.confirm();

        assertThat(presenter.receiverNameErrorProperty().get()).isEqualTo(NAME_REQUIRED);
        assertThat(presenter.phoneErrorProperty().get()).isEqualTo(PHONE_REQUIRED);
        assertThat(presenter.shippingAddressErrorProperty().get()).isEqualTo(ADDRESS_REQUIRED);
        verify(orders, never()).placeOrder(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0912345", "09123456789", "+840912345678", "+1 212 555 0100", "1912345678"})
    void confirm_withABadPhone_showsThePhoneRuleAndKeepsWhatWasTyped(String phone) {
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.phoneProperty().set(phone);

        presenter.confirm();

        assertThat(presenter.phoneErrorProperty().get()).isEqualTo(PHONE_MSG);
        assertThat(presenter.receiverNameErrorProperty().get()).isEmpty();
        assertThat(presenter.shippingAddressErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(CheckoutPresenter.Field.PHONE);
        assertThat(presenter.phoneProperty().get()).isEqualTo(phone);
        assertThat(presenter.shippingAddressProperty().get()).isEqualTo("12 Lê Lợi, Quận 1, TP.HCM");
        verify(orders, never()).placeOrder(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "0912 345 678", "0912.345.678", "0912-345-678", "+84 912 345 678",
            "84912345678"})
    void confirm_withAnAcceptedPhoneShape_passesTheValidationAndPlacesTheOrder(String phone) {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.phoneProperty().set(phone);

        presenter.confirm();

        assertThat(presenter.phoneErrorProperty().get()).isEmpty();
        ArgumentCaptor<CheckoutForm> form = ArgumentCaptor.forClass(CheckoutForm.class);
        verify(orders).placeOrder(eq(USER_ID), form.capture(), any());
        assertThat(form.getValue().normalizedPhone()).isEqualTo("0912345678");
    }

    @Test
    void confirm_withTooLongTexts_showsTheSizeMessages() {
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.receiverNameProperty().set("a".repeat(101));
        presenter.shippingAddressProperty().set("b".repeat(501));
        presenter.noteProperty().set("c".repeat(501));

        presenter.confirm();

        assertThat(presenter.receiverNameErrorProperty().get()).isEqualTo(NAME_TOO_LONG);
        assertThat(presenter.shippingAddressErrorProperty().get()).isEqualTo(ADDRESS_TOO_LONG);
        assertThat(presenter.noteErrorProperty().get()).isEqualTo(NOTE_TOO_LONG);
        assertThat(presenter.phoneErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isEqualTo(CheckoutPresenter.Field.RECEIVER_NAME);
        verify(orders, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void confirm_withTheLimitLengths_isAccepted() {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.receiverNameProperty().set("a".repeat(100));
        presenter.shippingAddressProperty().set("b".repeat(500));
        presenter.noteProperty().set("c".repeat(500));

        presenter.confirm();

        verify(orders).placeOrder(eq(USER_ID), any(), any());
    }

    @Test
    void confirm_withAnEmptyNote_isAccepted() {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.noteProperty().set("");

        presenter.confirm();

        ArgumentCaptor<CheckoutForm> form = ArgumentCaptor.forClass(CheckoutForm.class);
        verify(orders).placeOrder(eq(USER_ID), form.capture(), any());
        assertThat(form.getValue().note()).isNull();
    }

    @Test
    void typingInAFieldClearsItsOwnErrorOnly() {
        CheckoutPresenter presenter = loadedPresenter();
        presenter.confirm();
        assertThat(presenter.phoneErrorProperty().get()).isNotEmpty();
        assertThat(presenter.shippingAddressErrorProperty().get()).isNotEmpty();

        presenter.phoneProperty().set("0912345678");

        assertThat(presenter.phoneErrorProperty().get()).isEmpty();
        assertThat(presenter.shippingAddressErrorProperty().get()).isEqualTo(ADDRESS_REQUIRED);
    }

    @Test
    void confirm_afterFixingTheForm_clearsTheOldErrorsAndPlacesTheOrder() {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        presenter.confirm();
        assertThat(presenter.firstErrorFieldProperty().get()).isNotNull();

        fillValidForm(presenter);
        presenter.confirm();

        assertThat(presenter.phoneErrorProperty().get()).isEmpty();
        assertThat(presenter.shippingAddressErrorProperty().get()).isEmpty();
        assertThat(presenter.firstErrorFieldProperty().get()).isNull();
        verify(orders).placeOrder(eq(USER_ID), any(), any());
    }

    @Test
    void confirm_withAnInvalidForm_neverTouchesTheServiceNotEvenForTheCartRead() {
        CheckoutPresenter presenter = loadedPresenter();

        presenter.confirm();

        verify(orders, times(1)).checkoutPage(USER_ID);
        verify(orders, never()).placeOrder(anyLong(), any(), any());
        assertThat(direct.submittedCount()).as("only the initial load").isEqualTo(1);
    }

    // ------------------------------------------------------------------ placing the order (QĐ9)

    @Test
    void confirm_withAValidForm_placesTheOrderOnceWithTheUserAndTheTotalOnTheScreen() {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        ArgumentCaptor<CheckoutForm> form = ArgumentCaptor.forClass(CheckoutForm.class);
        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orders, times(1)).placeOrder(eq(USER_ID), form.capture(), total.capture());
        assertThat(total.getValue()).isEqualByComparingTo(TOTAL);
        assertThat(form.getValue().receiverName()).isEqualTo("Nguyễn Văn An");
        assertThat(form.getValue().normalizedPhone()).isEqualTo("0912345678");
        assertThat(form.getValue().shippingAddress()).isEqualTo("12 Lê Lợi, Quận 1, TP.HCM");
        assertThat(form.getValue().note()).isEqualTo("Giao giờ hành chính");
    }

    @Test
    void confirm_readsTheUserWhenClicked_notWhenTheBackgroundWorkRuns() {
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);

        presenter.confirm();
        when(session.currentUser()).thenReturn(Optional.empty());
        manual.run(1);

        verify(orders).placeOrder(eq(USER_ID), any(), any());
    }

    @Test
    void confirm_whileThePlacementIsPending_keepsTheButtonEnabledSoTheFocusStaysAndAnotherConfirmSendsNothing() {
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);

        presenter.confirm();

        assertThat(presenter.busyProperty().get()).isTrue();
        // A disabled button would push the focus to another control, and a second Enter would land there
        assertThat(presenter.confirmDisabledBinding().get()).as("the button stays focusable while busy").isFalse();
        presenter.confirm();
        presenter.confirm();
        assertThat(manual.submittedCount()).as("the load and one placement").isEqualTo(2);

        manual.runAll();

        verify(orders, times(1)).placeOrder(anyLong(), any(), any());
    }

    @Test
    void confirm_afterASuccessfulPlacement_staysLockedSoItCannotBeClickedAgain() {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.confirm();

        presenter.confirm();

        verify(orders, times(1)).placeOrder(anyLong(), any(), any());
        assertThat(presenter.busyProperty().get()).isTrue();
    }

    @Test
    void confirm_success_refreshesTheBadgeThenOpensTheOrderDetailWithTheJustPlacedBanner() {
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        InOrder order = inOrder(cartBadge, navigator);
        order.verify(cartBadge).refresh();
        order.verify(navigator).navigateTo(new OrderDetailRoute(42L, OrdersRoute.first(), true));
        verifyNoInteractions(toasts, errors);
    }

    @Test
    void confirm_emptyCart_toastsInfoRefreshesTheBadgeAndOpensTheOrderList() {
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.EmptyCart());
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        verify(toasts).show("Giỏ hàng đang trống nên chưa có đơn mới nào được tạo.", ToastKind.INFO);
        verify(cartBadge).refresh();
        verify(navigator).navigateTo(OrdersRoute.first());
        assertThat(presenter.busyProperty().get()).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void confirm_notOrderable_toastsAnErrorOpensTheDrawerAndKeepsTheScreenLocked() {
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.NotOrderable());
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        String message = "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng.";
        verify(toasts).show(message, ToastKind.ERROR);
        verify(openCart).run();
        verify(cartBadge).refresh();
        verify(navigator, never()).navigateTo(any());
        assertThat(presenter.noticeTextProperty().get()).isEqualTo(message);
        assertThat(presenter.orderableProperty().get()).isFalse();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        assertThat(presenter.receiverNameProperty().get()).as("the form is kept").isEqualTo("Nguyễn Văn An");
    }

    @Test
    void confirm_insufficientStock_namesTheProductInTheToastAndOpensTheDrawer() {
        when(orders.placeOrder(anyLong(), any(), any()))
                .thenThrow(new PlaceOrderException.InsufficientStock("Bàn phím cơ cỡ nhỏ"));
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        verify(toasts).show("Sản phẩm Bàn phím cơ cỡ nhỏ không đủ hàng. Vui lòng cập nhật giỏ hàng.", ToastKind.ERROR);
        verify(openCart).run();
        verify(cartBadge).refresh();
        verify(navigator, never()).navigateTo(any());
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.shippingAddressProperty().get()).isEqualTo("12 Lê Lợi, Quận 1, TP.HCM");
    }

    @Test
    void confirm_priceChanged_replacesTheSummaryShowsTheNoticeKeepsTheFormAndUnlocks() {
        CheckoutView current = summary(new BigDecimal("2900000"), true, keyboard(2), mouse(2));
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.PriceChanged(current));
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        assertThat(presenter.noticeTextProperty().get())
                .isEqualTo("Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận.");
        assertThat(presenter.noticeKindProperty().get()).isEqualTo(CheckoutPresenter.NoticeKind.ERROR);
        assertThat(presenter.lines()).containsExactly(keyboard(2), mouse(2));
        assertThat(presenter.totalTextProperty().get()).isEqualTo("2.900.000 ₫");
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.orderableProperty().get()).isTrue();
        assertThat(presenter.confirmDisabledBinding().get()).isFalse();
        assertThat(presenter.receiverNameProperty().get()).isEqualTo("Nguyễn Văn An");
        assertThat(presenter.phoneProperty().get()).isEqualTo("0912 345 678");
        assertThat(presenter.shippingAddressProperty().get()).isEqualTo("12 Lê Lợi, Quận 1, TP.HCM");
        assertThat(presenter.noteProperty().get()).isEqualTo("Giao giờ hành chính");
        verify(navigator, never()).navigateTo(any());
        verifyNoInteractions(toasts, errors);
    }

    @Test
    void confirm_afterPriceChanged_sendsTheNewTotal() {
        BigDecimal newTotal = new BigDecimal("2900000");
        CheckoutView current = summary(newTotal, true, keyboard(2), mouse(2));
        when(orders.placeOrder(anyLong(), any(), any()))
                .thenThrow(new PlaceOrderException.PriceChanged(current))
                .thenReturn(42L);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.confirm();

        presenter.confirm();

        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orders, times(2)).placeOrder(eq(USER_ID), any(), total.capture());
        assertThat(total.getAllValues().get(0)).isEqualByComparingTo(TOTAL);
        assertThat(total.getAllValues().get(1)).isEqualByComparingTo(newTotal);
        verify(navigator).navigateTo(new OrderDetailRoute(42L, OrdersRoute.first(), true));
    }

    @Test
    void confirm_priceChangedToACartThatCannotBeOrdered_locksTheButtonWithTheCartNotice() {
        CheckoutView current = summary(new BigDecimal("2900000"), false, keyboard(2), mouse(2));
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.PriceChanged(current));
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        assertThat(presenter.orderableProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        assertThat(presenter.noticeTextProperty().get()).isEqualTo(CheckoutPresenter.MSG_NOT_ORDERABLE);
    }

    @Test
    void confirm_unexpectedFailure_reportsExactlyOnceAndUnlocksTheForm() {
        RuntimeException failure = new IllegalStateException("lock timeout");
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(failure);
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);

        presenter.confirm();

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.confirmDisabledBinding().get()).isFalse();
        verify(navigator, never()).navigateTo(any());
        verifyNoInteractions(toasts);
    }

    @Test
    void confirm_whenTheCartIsNotOrderable_doesNothing() {
        checkoutPageIs(summary(TOTAL, false, keyboard(2)), "");
        CheckoutPresenter presenter = presenter();
        presenter.load();
        fillValidForm(presenter);

        presenter.confirm();

        verify(orders, never()).placeOrder(anyLong(), any(), any());
    }

    @Test
    void confirm_whenTheSessionBecameAGuest_goesHomeWithoutCallingTheService() {
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        when(session.currentUser()).thenReturn(Optional.empty());

        presenter.confirm();

        verify(orders, never()).placeOrder(anyLong(), any(), any());
        verify(navigator).goHome();
        assertThat(presenter.busyProperty().get()).isFalse();
    }

    // ------------------------------------------------------------------ back to the cart, reload on drawer close (QĐ8)

    @Test
    void backToCart_opensTheCartDrawerAndKeepsTheForm() {
        CheckoutPresenter presenter = loadedPresenter();
        presenter.shippingAddressProperty().set("12 Lê Lợi");

        presenter.backToCart();

        verify(openCart).run();
        assertThat(presenter.shippingAddressProperty().get()).isEqualTo("12 Lê Lợi");
        verify(navigator, never()).navigateTo(any());
    }

    @Test
    void closingTheDrawer_reloadsTheSummaryAndTheTotalButKeepsWhatWasTyped() {
        CheckoutView smaller = summary(new BigDecimal("1250000"), true, keyboard(1));
        when(orders.checkoutPage(USER_ID))
                .thenReturn(new CheckoutPage(orderableSummary(), CheckoutForm.prefill("Khách hàng mẫu", "0911111111")))
                .thenReturn(new CheckoutPage(smaller, CheckoutForm.prefill("Tên khác", "0922222222")));
        CheckoutPresenter presenter = presenter();
        presenter.load();
        presenter.receiverNameProperty().set("Người nhận mới");
        presenter.phoneProperty().set("0933333333");
        presenter.shippingAddressProperty().set("12 Lê Lợi");
        presenter.noteProperty().set("Gọi trước khi giao");

        drawerShowing.set(true);
        drawerShowing.set(false);

        verify(orders, times(2)).checkoutPage(USER_ID);
        assertThat(presenter.lines()).containsExactly(keyboard(1));
        assertThat(presenter.totalTextProperty().get()).isEqualTo("1.250.000 ₫");
        assertThat(presenter.receiverNameProperty().get()).isEqualTo("Người nhận mới");
        assertThat(presenter.phoneProperty().get()).isEqualTo("0933333333");
        assertThat(presenter.shippingAddressProperty().get()).isEqualTo("12 Lê Lợi");
        assertThat(presenter.noteProperty().get()).isEqualTo("Gọi trước khi giao");
        verifyNoInteractions(toasts);
        verify(openCart, never()).run();
    }

    @Test
    void closingTheDrawer_thenConfirming_sendsTheReloadedTotal() {
        CheckoutView smaller = summary(new BigDecimal("1250000"), true, keyboard(1));
        when(orders.checkoutPage(USER_ID))
                .thenReturn(new CheckoutPage(orderableSummary(), CheckoutForm.prefill("Khách hàng mẫu", "")))
                .thenReturn(new CheckoutPage(smaller, CheckoutForm.prefill("Khách hàng mẫu", "")));
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        CheckoutPresenter presenter = presenter();
        presenter.load();
        fillValidForm(presenter);
        drawerShowing.set(true);
        drawerShowing.set(false);

        presenter.confirm();

        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orders).placeOrder(eq(USER_ID), any(), total.capture());
        assertThat(total.getValue()).isEqualByComparingTo("1250000");
    }

    @Test
    void openingTheDrawer_doesNotReload() {
        loadedPresenter();

        drawerShowing.set(true);

        verify(orders, times(1)).checkoutPage(USER_ID);
    }

    @Test
    void closingTheDrawer_afterTheCartWasFixed_unlocksTheButtonAndClearsTheNotice() {
        when(orders.checkoutPage(USER_ID))
                .thenReturn(new CheckoutPage(summary(TOTAL, false, keyboard(2), mouse(1)),
                        CheckoutForm.prefill("Khách hàng mẫu", "")))
                .thenReturn(new CheckoutPage(summary(new BigDecimal("190000"), true, mouse(1)),
                        CheckoutForm.prefill("Khách hàng mẫu", "")));
        CheckoutPresenter presenter = presenter();
        presenter.load();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        assertThat(presenter.noticeTextProperty().get()).isNotEmpty();

        drawerShowing.set(true);
        drawerShowing.set(false);

        assertThat(presenter.confirmDisabledBinding().get()).isFalse();
        assertThat(presenter.noticeTextProperty().get()).isEmpty();
        assertThat(presenter.orderableProperty().get()).isTrue();
        assertThat(presenter.lines()).containsExactly(mouse(1));
    }

    @Test
    void closingTheDrawer_whenTheCartIsStillNotOrderable_keepsTheNoticeWithoutANewToastOrDrawer() {
        when(orders.checkoutPage(USER_ID))
                .thenReturn(new CheckoutPage(summary(TOTAL, false, keyboard(2), mouse(1)),
                        CheckoutForm.prefill("Khách hàng mẫu", "")));
        CheckoutPresenter presenter = presenter();
        presenter.load();
        verify(toasts, times(1)).show(anyString(), eq(ToastKind.ERROR));
        verify(openCart, times(1)).run();

        drawerShowing.set(true);
        drawerShowing.set(false);

        assertThat(presenter.noticeTextProperty().get()).isEqualTo(CheckoutPresenter.MSG_NOT_ORDERABLE);
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
        verify(toasts, times(1)).show(anyString(), eq(ToastKind.ERROR));
        verify(openCart, times(1)).run();
    }

    @Test
    void closingTheDrawer_whenTheUserEmptiedTheCart_onlyGoesHome() {
        when(orders.checkoutPage(USER_ID))
                .thenReturn(new CheckoutPage(orderableSummary(), CheckoutForm.prefill("Khách hàng mẫu", "")))
                .thenReturn(new CheckoutPage(summary(BigDecimal.ZERO, false), CheckoutForm.prefill("Khách hàng mẫu", "")));
        CheckoutPresenter presenter = presenter();
        presenter.load();

        drawerShowing.set(true);
        drawerShowing.set(false);

        verify(navigator).goHome();
        verify(openCart, never()).run();
        assertThat(presenter.lines()).isEmpty();
        assertThat(presenter.confirmDisabledBinding().get()).isTrue();
    }

    @Test
    void closingTheDrawer_duringAPlacement_waitsAndReloadsAfterAFailureThatKeepsTheScreen() {
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.NotOrderable());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);
        presenter.confirm(); // task 1, pending

        drawerShowing.set(true);
        drawerShowing.set(false);
        assertThat(manual.submittedCount()).as("no reload while the order is being placed").isEqualTo(2);

        manual.run(1);

        assertThat(manual.submittedCount()).as("the summary is reloaded once the placement ended").isEqualTo(3);
    }

    @Test
    void closingTheDrawer_duringAPlacement_reloadsAfterAnUnexpectedFailureToo() {
        checkoutPageIs(orderableSummary(), "");
        RuntimeException failure = new IllegalStateException("lock timeout");
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(failure);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);
        presenter.confirm(); // task 1, pending

        drawerShowing.set(true);
        drawerShowing.set(false);
        assertThat(manual.submittedCount()).as("no reload while the order is being placed").isEqualTo(2);

        manual.run(1);

        verify(errors, times(1)).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(manual.submittedCount()).as("the deferred reload runs after the unexpected failure")
                .isEqualTo(3);
    }

    @Test
    void closingTheDrawer_asAGuest_goesHomeWithoutCallingTheService() {
        CheckoutPresenter presenter = loadedPresenter();
        when(session.currentUser()).thenReturn(Optional.empty());

        drawerShowing.set(true);
        drawerShowing.set(false);

        verify(navigator).goHome();
        verify(orders, times(1)).checkoutPage(USER_ID);
        assertThat(presenter.lines()).hasSize(2);
    }

    @Test
    void closingTheDrawer_whenTheReloadFails_reportsOnce() {
        RuntimeException failure = new IllegalStateException("down");
        when(orders.checkoutPage(USER_ID))
                .thenReturn(new CheckoutPage(orderableSummary(), CheckoutForm.prefill("Khách hàng mẫu", "")))
                .thenThrow(failure);
        CheckoutPresenter presenter = presenter();
        presenter.load();

        drawerShowing.set(true);
        drawerShowing.set(false);

        verify(errors, times(1)).report(failure);
        assertThat(presenter.loadingProperty().get()).isFalse();
        assertThat(presenter.lines()).as("the old summary stays").hasSize(2);
    }

    // ------------------------------------------------------------------ dispose (QĐ8, QĐ9)

    @Test
    void load_addsOneListenerToTheDrawerAndDisposeRemovesThatSameListener() {
        ReadOnlyBooleanProperty showing = mock(ReadOnlyBooleanProperty.class);
        when(drawerHost.showingProperty()).thenReturn(showing);
        checkoutPageIs(orderableSummary(), "");
        CheckoutPresenter presenter = presenter();

        presenter.load();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ChangeListener<Boolean>> added = ArgumentCaptor.forClass(ChangeListener.class);
        verify(showing).addListener(added.capture());

        presenter.dispose();

        verify(showing).removeListener(added.getValue());
    }

    @Test
    void dispose_thenClosingTheDrawer_doesNotReload() {
        CheckoutPresenter presenter = loadedPresenter();

        presenter.dispose();
        drawerShowing.set(true);
        drawerShowing.set(false);

        verify(orders, times(1)).checkoutPage(USER_ID);
    }

    @Test
    void dispose_cancelsThePendingLoadAndItsAnswerChangesNothing() {
        checkoutPageIs(orderableSummary(), "");
        ManualBackgroundTasks manual = ManualBackgroundTasks.ignoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();

        presenter.dispose();
        manual.runAll();

        assertThat(presenter.lines()).isEmpty();
        assertThat(presenter.readyProperty().get()).isFalse();
        assertThat(presenter.receiverNameProperty().get()).isEmpty();
        verifyNoInteractions(toasts, navigator, errors);
        verify(openCart, never()).run();
    }

    @Test
    void dispose_isHarmlessWhenCalledTwice() {
        CheckoutPresenter presenter = loadedPresenter();

        presenter.dispose();
        presenter.dispose();

        assertThat(presenter.lines()).hasSize(2);
    }

    @Test
    void dispose_whileThePlacementRuns_theSuccessStillRefreshesTheBadgeAndToastsButDoesNotNavigate() {
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenReturn(42L);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);
        presenter.confirm();

        presenter.dispose();
        manual.run(1);

        verify(orders).placeOrder(eq(USER_ID), any(), any());
        verify(cartBadge).refresh();
        verify(toasts).show("Đặt hàng thành công. Mã đơn của bạn là #42.", ToastKind.SUCCESS);
        verify(navigator, never()).navigateTo(any());
        verifyNoInteractions(errors);
    }

    @Test
    void dispose_whileThePlacementRuns_aPlaceOrderExceptionShowsNothingAndNavigatesNowhere() {
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.NotOrderable());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);
        presenter.confirm();

        presenter.dispose();
        manual.run(1);

        // the cart did change (or may have), so the badge is refreshed (QĐ4); nothing else is shown
        verify(cartBadge).refresh();
        verifyNoInteractions(toasts, navigator, errors);
        verify(openCart, never()).run();
    }

    @Test
    void dispose_whileThePlacementRuns_anEmptyCartShowsNothingAndNavigatesNowhere() {
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(new PlaceOrderException.EmptyCart());
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);
        presenter.confirm();

        presenter.dispose();
        manual.run(1);

        verifyNoInteractions(toasts, navigator, errors);
    }

    @Test
    void dispose_whileThePlacementRuns_anUnexpectedFailureIsStillReported() {
        RuntimeException failure = new IllegalStateException("lock timeout");
        checkoutPageIs(orderableSummary(), "");
        when(orders.placeOrder(anyLong(), any(), any())).thenThrow(failure);
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        CheckoutPresenter presenter = presenter(manual);
        presenter.load();
        manual.run(0);
        fillValidForm(presenter);
        presenter.confirm();

        presenter.dispose();
        manual.run(1);

        verify(errors, times(1)).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void afterDispose_confirmAndBackToCartDoNothing() {
        CheckoutPresenter presenter = loadedPresenter();
        fillValidForm(presenter);
        presenter.dispose();

        presenter.confirm();
        presenter.backToCart();

        verify(orders, never()).placeOrder(anyLong(), any(), any());
        verify(openCart, never()).run();
    }
}

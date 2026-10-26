package com.minishop.user;

import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellMenuEntry;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AccountPresenter} with the real {@link ShellPresenter} and the real {@link AppSession}, no toolkit
 * (AC-009-8 menu by role, AC-009-10 logout; QĐ9, QĐ10 of the D2 plan). The navigator and the toasts are mocks.
 */
@ExtendWith(MockitoExtension.class)
class AccountPresenterTest {

    private static final CurrentUser USER = new CurrentUser(7L, "user@minishop.local", "Khách hàng mẫu", Role.USER);
    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@minishop.local", "Quản trị viên", Role.ADMIN);

    @Mock
    private LoginPrompt loginPrompt;
    @Mock
    private Navigator navigator;
    @Mock
    private Toasts toasts;

    private final List<String> events = new ArrayList<>();
    private final ShellPresenter shell = new ShellPresenter();
    private AppSession session;
    private AccountPresenter presenter;

    @BeforeEach
    void setUp() {
        buildPresenterWith(List.of());
    }

    /** The presenter listens to the session like the Spring context wires it; extra listeners run before it. */
    private void buildPresenterWith(List<SessionListener> before) {
        events.clear();
        List<SessionListener> listeners = new ArrayList<>(before);
        listeners.add((previous, current) -> events.add(current == null ? "session:guest" : "session:user"));
        listeners.add((previous, current) -> presenter.sessionChanged(previous, current));
        session = new AppSession(() -> listeners);
        presenter = new AccountPresenter(session, loginPrompt, navigator, toasts);
    }

    private List<String> menuIds() {
        return shell.accountMenuEntries().stream().map(ShellMenuEntry::id).toList();
    }

    private List<String> menuLabels() {
        return shell.accountMenuEntries().stream().map(ShellMenuEntry::label).toList();
    }

    private ShellMenuEntry entry(String id) {
        return shell.accountMenuEntries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ guest

    @Test
    void contribute_forAGuest_showsLoginWithoutAMenuAndTheButtonIsEnabled() {
        presenter.contribute(shell);

        assertThat(shell.accountLabelProperty().get()).isEqualTo("Đăng nhập");
        assertThat(shell.accountMenuEntries()).isEmpty();
        assertThat(shell.enabledBinding(ShellAction.ACCOUNT).get()).isTrue();
        assertThat(shell.accountAccessibleTextProperty().get()).isEqualTo("Đăng nhập");
    }

    @Test
    void accountAction_forAGuest_opensTheLoginDialog() {
        presenter.contribute(shell);

        shell.trigger(ShellAction.ACCOUNT);

        verify(loginPrompt).open();
    }

    @Test
    void contribute_withNull_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter.contribute(null));
    }

    @Test
    void sessionChanged_beforeContribute_doesNothingAndDoesNotFail() {
        session.signIn(USER);

        assertThat(shell.accountLabelProperty().get()).isEqualTo("Đăng nhập");
        assertThat(shell.accountMenuEntries()).isEmpty();
    }

    @Test
    void contribute_afterTheSessionAlreadyChanged_showsTheCurrentUser() {
        session.signIn(USER);

        presenter.contribute(shell);

        assertThat(shell.accountLabelProperty().get()).isEqualTo("Khách hàng mẫu");
        assertThat(menuIds()).containsExactly(AccountPresenter.LOGOUT_ID);
    }

    // ------------------------------------------------------------------ AC-009-8: menu by role

    @Test
    void signIn_asAUser_showsTheNameAndAMenuWithOnlyLogout() {
        presenter.contribute(shell);

        session.signIn(USER);

        assertThat(shell.accountLabelProperty().get()).isEqualTo("Khách hàng mẫu");
        assertThat(shell.accountAccessibleTextProperty().get()).isEqualTo("Xin chào, Khách hàng mẫu");
        assertThat(menuIds()).containsExactly("accountMenuLogout");
        assertThat(menuLabels()).containsExactly("Đăng xuất");
        assertThat(shell.enabledBinding(ShellAction.ACCOUNT).get()).as("enabled by the menu").isTrue();
    }

    @Test
    void signIn_asAUser_removesTheLoginActionSoTheButtonOnlyOpensTheMenu() {
        presenter.contribute(shell);
        session.signIn(USER);

        shell.trigger(ShellAction.ACCOUNT);

        verifyNoInteractions(loginPrompt);
    }

    @Test
    void signIn_asAUser_whoseAdminActionWasRegistered_doesNotSeeAdministration() {
        presenter.contribute(shell);
        presenter.setAction(AccountMenuItem.ADMIN, () -> { });

        session.signIn(USER);

        assertThat(menuLabels()).doesNotContain("Quản trị").containsExactly("Đăng xuất");
    }

    @Test
    void signIn_asAUser_withOrdersAndAdminActions_seesOnlyOrdersThenLogout() {
        presenter.contribute(shell);
        presenter.setAction(AccountMenuItem.ORDERS, () -> { });
        presenter.setAction(AccountMenuItem.ADMIN, () -> { });

        session.signIn(USER);

        assertThat(menuLabels()).containsExactly("Đơn hàng của tôi", "Đăng xuất");
    }

    @Test
    void signIn_asAnAdmin_withTheAdminActionRegistered_seesAdministrationBeforeLogout() {
        presenter.contribute(shell);
        presenter.setAction(AccountMenuItem.ADMIN, () -> { });

        session.signIn(ADMIN);

        assertThat(menuLabels()).containsExactly("Quản trị", "Đăng xuất");
        assertThat(menuIds()).containsExactly("accountMenuAdmin", AccountPresenter.LOGOUT_ID);
    }

    @Test
    void signIn_asAnAdmin_withBothActions_keepsTheDocumentedOrder() {
        presenter.contribute(shell);
        presenter.setAction(AccountMenuItem.ADMIN, () -> { });
        presenter.setAction(AccountMenuItem.ORDERS, () -> { });

        session.signIn(ADMIN);

        assertThat(menuLabels()).containsExactly("Đơn hàng của tôi", "Quản trị", "Đăng xuất");
    }

    @Test
    void signIn_asAnAdmin_withNoActionRegistered_seesOnlyLogout() {
        presenter.contribute(shell);

        session.signIn(ADMIN);

        assertThat(menuLabels()).containsExactly("Đăng xuất");
        assertThat(shell.accountLabelProperty().get()).isEqualTo("Quản trị viên");
    }

    @Test
    void menuEntry_forAFeatureItem_runsTheRegisteredAction() {
        AtomicInteger ran = new AtomicInteger();
        presenter.contribute(shell);
        presenter.setAction(AccountMenuItem.ORDERS, ran::incrementAndGet);
        session.signIn(USER);

        entry("accountMenuOrders").action().run();

        assertThat(ran).hasValue(1);
    }

    @Test
    void setAction_afterTheUserSignedIn_addsTheEntryAtOnceAndNullRemovesIt() {
        presenter.contribute(shell);
        session.signIn(ADMIN);
        assertThat(menuLabels()).containsExactly("Đăng xuất");

        presenter.setAction(AccountMenuItem.ADMIN, () -> { });
        assertThat(menuLabels()).containsExactly("Quản trị", "Đăng xuất");

        presenter.setAction(AccountMenuItem.ADMIN, null);
        assertThat(menuLabels()).containsExactly("Đăng xuất");
    }

    @Test
    void setAction_forAGuest_neverShowsAMenu() {
        presenter.contribute(shell);

        presenter.setAction(AccountMenuItem.ORDERS, () -> { });
        presenter.setAction(AccountMenuItem.ADMIN, () -> { });

        assertThat(shell.accountMenuEntries()).isEmpty();
        assertThat(shell.accountLabelProperty().get()).isEqualTo("Đăng nhập");
    }

    @Test
    void setAction_beforeContribute_isRememberedAndShownAfterwards() {
        presenter.setAction(AccountMenuItem.ORDERS, () -> { });
        session.signIn(USER);

        presenter.contribute(shell);

        assertThat(menuLabels()).containsExactly("Đơn hàng của tôi", "Đăng xuất");
    }

    @Test
    void setAction_withANullItem_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter.setAction(null, () -> { }));
    }

    @Test
    void anAdminMenuIsNotLeftBehindForTheNextUser() {
        presenter.contribute(shell);
        presenter.setAction(AccountMenuItem.ADMIN, () -> { });
        session.signIn(ADMIN);
        assertThat(menuLabels()).contains("Quản trị");

        session.signOut();
        assertThat(shell.accountMenuEntries()).isEmpty();
        assertThat(shell.accountLabelProperty().get()).isEqualTo("Đăng nhập");

        session.signIn(USER);
        assertThat(menuLabels()).as("a USER signing in after an ADMIN").containsExactly("Đăng xuất");
    }

    // ------------------------------------------------------------------ label

    @Test
    void signIn_withALongName_cutsTheLabelButTheScreenReaderGetsTheFullName() {
        String longName = "Nguyễn Thị Rất Rất Dài Họ Và Tên Của Một Người Dùng";
        presenter.contribute(shell);

        session.signIn(new CurrentUser(3L, "long@example.com", longName, Role.USER));

        assertThat(shell.accountLabelProperty().get()).isEqualTo("Nguyễn Thị Rất Rất Dài…");
        assertThat(shell.accountAccessibleTextProperty().get()).isEqualTo(AccountPresenter.GREETING_PREFIX + longName);
    }

    @Test
    void signIn_withABlankName_usesTheEmailSoTheButtonDoesNotLookLikeGuest() {
        presenter.contribute(shell);

        session.signIn(new CurrentUser(3L, "noname@example.com", "   ", Role.USER));

        assertThat(shell.accountLabelProperty().get()).isEqualTo("noname@example.com");
    }

    @Test
    void signIn_withNullName_usesTheEmail() {
        presenter.contribute(shell);

        session.signIn(new CurrentUser(3L, "noname@example.com", null, Role.USER));

        assertThat(shell.accountLabelProperty().get()).isEqualTo("noname@example.com");
    }

    @Test
    void signIn_stripsTheNameOnTheLabelAndInTheGreeting() {
        presenter.contribute(shell);

        session.signIn(new CurrentUser(3L, "x@example.com", "  An  ", Role.USER));

        assertThat(shell.accountLabelProperty().get()).isEqualTo("An");
        assertThat(shell.accountAccessibleTextProperty().get()).isEqualTo("Xin chào, An");
    }

    // ------------------------------------------------------------------ AC-009-10: logout

    @Test
    void logoutEntry_signsOutGoesHomeAndShowsTheInfoToastInThatOrder() {
        presenter.contribute(shell);
        session.signIn(USER);
        events.clear();
        doAnswer(invocation -> events.add("goHome")).when(navigator).goHome();
        doAnswer(invocation -> events.add("toast:" + invocation.getArgument(0) + ":" + invocation.getArgument(1)))
                .when(toasts).show(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        entry(AccountPresenter.LOGOUT_ID).action().run();

        assertThat(events).containsExactly("session:guest", "goHome", "toast:Bạn đã đăng xuất.:INFO");
    }

    @Test
    void logout_returnsTheSessionToGuestAndTheButtonToLogin() {
        presenter.contribute(shell);
        session.signIn(USER);

        presenter.logout();

        assertThat(session.isLoggedIn()).isFalse();
        assertThat(session.currentUser()).isEmpty();
        assertThat(shell.accountLabelProperty().get()).isEqualTo("Đăng nhập");
        assertThat(shell.accountMenuEntries()).isEmpty();
        assertThat(shell.enabledBinding(ShellAction.ACCOUNT).get()).isTrue();
        verify(navigator).goHome();
        verify(toasts).show(AccountPresenter.LOGGED_OUT_TOAST, ToastKind.INFO);
        assertThat(AccountPresenter.LOGGED_OUT_TOAST).isEqualTo("Bạn đã đăng xuất.");
    }

    @Test
    void logout_thenTheButtonOpensTheLoginDialogAgain() {
        presenter.contribute(shell);
        session.signIn(ADMIN);
        presenter.logout();

        shell.trigger(ShellAction.ACCOUNT);

        verify(loginPrompt).open();
    }

    @Test
    void logout_whenAnotherSessionListenerFails_stillGoesHomeAndTellsTheUser() {
        buildPresenterWith(List.of((previous, current) -> {
            if (current == null) {
                throw new IllegalStateException("listener failed");
            }
        }));
        presenter.contribute(shell);
        session.signIn(USER);

        assertThatThrownBy(presenter::logout).isInstanceOf(IllegalStateException.class);

        assertThat(session.isLoggedIn()).isFalse();
        assertThat(shell.accountLabelProperty().get()).as("the other listeners still ran").isEqualTo("Đăng nhập");
        verify(navigator).goHome();
        verify(toasts).show(AccountPresenter.LOGGED_OUT_TOAST, ToastKind.INFO);
    }

    // ------------------------------------------------------------------ menu items

    @ParameterizedTest
    @CsvSource({"ORDERS,USER,true", "ORDERS,ADMIN,true", "ADMIN,USER,false", "ADMIN,ADMIN,true"})
    void accountMenuItem_visibleTo_followsTheRole(AccountMenuItem item, Role role, boolean visible) {
        assertThat(item.visibleTo(role)).isEqualTo(visible);
    }

    @Test
    void accountMenuItem_hasTheLabelsAndIdsOfThePlan() {
        assertThat(AccountMenuItem.ORDERS.label()).isEqualTo("Đơn hàng của tôi");
        assertThat(AccountMenuItem.ORDERS.menuId()).isEqualTo("accountMenuOrders");
        assertThat(AccountMenuItem.ADMIN.label()).isEqualTo("Quản trị");
        assertThat(AccountMenuItem.ADMIN.menuId()).isEqualTo("accountMenuAdmin");
        assertThat(AccountMenuItem.values()).containsExactly(AccountMenuItem.ORDERS, AccountMenuItem.ADMIN);
    }
}

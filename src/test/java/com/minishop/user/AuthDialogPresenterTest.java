package com.minishop.user;

import com.minishop.desktop.UnexpectedErrors;
import com.minishop.desktop.DirectBackgroundTasks;
import com.minishop.support.ManualBackgroundTasks;
import com.minishop.user.AuthDialogPresenter.Mode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthDialogPresenter}: the state and the decisions of the login and registration dialog, with no JavaFX
 * toolkit (AC-009-4, -6, -7; QĐ7, QĐ8 of the D2 plan). The session is the real {@link AppSession}; the services are
 * mocks. {@link DirectBackgroundTasks} runs the work at once; {@link ManualBackgroundTasks} is used where the test must
 * look at the dialog while the work is still pending.
 */
@ExtendWith(MockitoExtension.class)
class AuthDialogPresenterTest {

    private static final CurrentUser USER = new CurrentUser(7L, "user@minishop.local", "Khách hàng mẫu", Role.USER);
    private static final String GOOD_PASSWORD = "abcdefg1";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @Mock
    private AuthService authService;
    @Mock
    private UserService userService;
    @Mock
    private UnexpectedErrors unexpectedErrors;

    private final List<String> events = new ArrayList<>();
    private AppSession session;
    private DirectBackgroundTasks direct;
    private AuthDialogPresenter presenter;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        session = new AppSession(() -> List.of(
                (previous, current) -> events.add(current == null ? "session:guest" : "session:user")));
        direct = new DirectBackgroundTasks();
        presenter = presenterWith(direct);
    }

    private AuthDialogPresenter presenterWith(com.minishop.desktop.BackgroundTasks tasks) {
        return new AuthDialogPresenter(authService, userService, session, tasks, unexpectedErrors, validator);
    }

    private void typeLogin(String email, String password) {
        presenter.loginEmailProperty().set(email);
        presenter.loginPasswordProperty().set(password);
    }

    private void typeRegistration(String fullName, String email, String password, String confirm) {
        presenter.regFullNameProperty().set(fullName);
        presenter.regEmailProperty().set(email);
        presenter.regPasswordProperty().set(password);
        presenter.regConfirmPasswordProperty().set(confirm);
    }

    private void typeValidRegistration() {
        typeRegistration("  Nguyễn An ", "  Moi@Example.com ", GOOD_PASSWORD, GOOD_PASSWORD);
    }

    private void stubLoginSuccess() {
        when(authService.login(any())).thenReturn(new LoginResult.Success(USER));
    }

    // ------------------------------------------------------------------ AC-009-6: wrong login

    @Test
    void submitLogin_withWrongCredentials_showsOneGenericSentenceClearsThePasswordAndKeepsTheEmail() {
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        presenter.open();
        typeLogin("user@minishop.local", "wrong-password");

        presenter.submitLogin();

        assertThat(presenter.loginErrorProperty().get()).isEqualTo("Email hoặc mật khẩu không đúng.");
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(presenter.loginEmailProperty().get()).isEqualTo("user@minishop.local");
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.showingProperty().get()).as("the dialog stays open").isTrue();
        assertThat(session.isLoggedIn()).isFalse();
        verifyNoInteractions(unexpectedErrors);
    }

    @Test
    void submitLogin_passesTheTypedValuesToTheServiceAndClearsThePasswordBeforeTheWorkRuns() {
        List<String> passwordSeenByTheWork = new ArrayList<>();
        when(authService.login(any())).thenAnswer(invocation -> {
            passwordSeenByTheWork.add(presenter.loginPasswordProperty().get());
            return new LoginResult.InvalidCredentials();
        });
        presenter.open();
        typeLogin("  User@MiniShop.local ", "  s3cret pass  ");

        presenter.submitLogin();

        ArgumentCaptor<LoginForm> form = ArgumentCaptor.forClass(LoginForm.class);
        verify(authService).login(form.capture());
        assertThat(form.getValue().email()).as("the email is normalised by the service, not cleaned here")
                .isEqualTo("  User@MiniShop.local ");
        assertThat(form.getValue().password()).as("a password is never cleaned").isEqualTo("  s3cret pass  ");
        assertThat(passwordSeenByTheWork).as("the field was already empty while the work ran").containsExactly("");
    }

    @ParameterizedTest
    @CsvSource(value = {"'',pass1234", "'   ',pass1234", "user@minishop.local,''"})
    void submitLogin_withAnEmptyField_showsTheSameSentenceAndDoesNotCallTheService(String email, String password) {
        presenter.open();
        typeLogin(email, password);

        presenter.submitLogin();

        assertThat(presenter.loginErrorProperty().get()).isEqualTo("Email hoặc mật khẩu không đúng.");
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(presenter.loginEmailProperty().get()).isEqualTo(email);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(direct.submittedCount()).isZero();
        verifyNoInteractions(authService);
    }

    @Test
    void submitLogin_withAPasswordOfOnlySpaces_stillCallsTheService() {
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        typeLogin("user@minishop.local", "   ");

        presenter.submitLogin();

        verify(authService).login(any());
    }

    @Test
    void submitLogin_whenTheServiceFails_reportsTheErrorOnceAndEnablesTheDialogAgain() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(authService.login(any())).thenThrow(failure);
        presenter.open();
        typeLogin("user@minishop.local", "pass1234");

        presenter.submitLogin();

        verify(unexpectedErrors).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(presenter.loginErrorProperty().get()).as("not a wrong-password message").isEmpty();
        assertThat(presenter.showingProperty().get()).isTrue();
        assertThat(session.isLoggedIn()).isFalse();
        assertThat(direct.unhandled()).isEmpty();
    }

    @Test
    void submitLogin_afterAWrongAttempt_clearsTheOldErrorWhileTheNewAttemptRuns() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        presenter = presenterWith(manual);
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        typeLogin("user@minishop.local", "wrong");
        presenter.submitLogin();
        manual.runAll();
        assertThat(presenter.loginErrorProperty().get()).isNotEmpty();
        typeLogin("user@minishop.local", "again");

        presenter.submitLogin();

        assertThat(presenter.loginErrorProperty().get()).as("cleared while the new attempt is pending").isEmpty();
        assertThat(presenter.busyProperty().get()).isTrue();
        manual.runAll();
        assertThat(presenter.loginErrorProperty().get()).isEqualTo("Email hoặc mật khẩu không đúng.");
    }

    // ------------------------------------------------------------------ AC-009-4: remembered action

    @Test
    void requireLogin_whenAlreadyLoggedIn_runsAtOnceAndDoesNotOpenTheDialog() {
        session.signIn(USER);
        AtomicInteger ran = new AtomicInteger();

        presenter.requireLogin(ran::incrementAndGet);

        assertThat(ran).hasValue(1);
        assertThat(presenter.showingProperty().get()).isFalse();
    }

    @Test
    void requireLogin_whenGuest_opensTheLoginDialogAndDoesNotRunYet() {
        AtomicInteger ran = new AtomicInteger();

        presenter.requireLogin(ran::incrementAndGet);

        assertThat(presenter.showingProperty().get()).isTrue();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
        assertThat(ran).hasValue(0);
    }

    @Test
    void requireLogin_afterASuccessfulLogin_runsTheActionOnceAndAfterTheSessionChanged() {
        stubLoginSuccess();
        presenter.requireLogin(() -> events.add("action"));

        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();

        assertThat(events).containsExactly("session:user", "action");
        assertThat(session.currentUser()).contains(USER);
        assertThat(presenter.showingProperty().get()).as("the dialog is closed first").isFalse();
    }

    @Test
    void requireLogin_actionRunsWhenTheDialogIsAlreadyClosedAndTheFieldsAreEmpty() {
        stubLoginSuccess();
        List<String> seen = new ArrayList<>();
        presenter.requireLogin(() -> {
            seen.add("showing=" + presenter.showingProperty().get());
            seen.add("password=" + presenter.loginPasswordProperty().get());
            seen.add("email=" + presenter.loginEmailProperty().get());
        });
        typeLogin("user@minishop.local", "User@123");

        presenter.submitLogin();

        assertThat(seen).containsExactly("showing=false", "password=", "email=");
    }

    @Test
    void requireLogin_actionIsForgottenAfterItRan_soLaterLoginsDoNotRunItAgain() {
        stubLoginSuccess();
        AtomicInteger ran = new AtomicInteger();
        presenter.requireLogin(ran::incrementAndGet);
        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();
        session.signOut();

        presenter.open();
        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();

        assertThat(ran).hasValue(1);
    }

    @Test
    void requireLogin_calledAgainBeforeLogin_replacesTheRememberedAction() {
        stubLoginSuccess();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        presenter.requireLogin(first::incrementAndGet);
        presenter.requireLogin(second::incrementAndGet);

        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();

        assertThat(first).hasValue(0);
        assertThat(second).hasValue(1);
    }

    @Test
    void close_beforeLogin_dropsTheRememberedAction() {
        stubLoginSuccess();
        AtomicInteger ran = new AtomicInteger();
        presenter.requireLogin(ran::incrementAndGet);

        boolean closed = presenter.close();
        presenter.open();
        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();

        assertThat(closed).isTrue();
        assertThat(ran).as("closing without logging in forgets the action").hasValue(0);
        assertThat(session.isLoggedIn()).isTrue();
    }

    @Test
    void requireLogin_isKeptWhenTheUserSwitchesToRegisterAndBack() {
        stubLoginSuccess();
        AtomicInteger ran = new AtomicInteger();
        presenter.requireLogin(ran::incrementAndGet);

        presenter.showRegister();
        presenter.showLogin();
        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();

        assertThat(ran).hasValue(1);
    }

    @Test
    void requireLogin_isKeptThroughASuccessfulRegistrationAndRunsAfterTheFollowingLogin() {
        stubLoginSuccess();
        when(userService.register(any())).thenReturn(9L);
        AtomicInteger ran = new AtomicInteger();
        presenter.requireLogin(ran::incrementAndGet);
        presenter.showRegister();
        typeValidRegistration();

        presenter.submitRegister();

        assertThat(ran).as("registering does not log in").hasValue(0);
        assertThat(session.isLoggedIn()).isFalse();
        verify(authService, never()).login(any());
        typeLogin(presenter.loginEmailProperty().get(), "abcdefg1");
        presenter.submitLogin();
        assertThat(ran).hasValue(1);
    }

    @Test
    void requireLogin_whenTheDialogIsAlreadyShowing_keepsItOpenAndReplacesTheAction() {
        stubLoginSuccess();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        presenter.requireLogin(first::incrementAndGet);
        typeLogin("user@minishop.local", "");

        presenter.requireLogin(second::incrementAndGet);

        assertThat(presenter.showingProperty().get()).isTrue();
        assertThat(presenter.loginEmailProperty().get()).as("already typed text is not wiped").isEqualTo("user@minishop.local");
        presenter.loginPasswordProperty().set("User@123");
        presenter.submitLogin();
        assertThat(first).hasValue(0);
        assertThat(second).hasValue(1);
    }

    @Test
    void requireLogin_withNullAction_throwsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> presenter.requireLogin(null));
        assertThat(presenter.showingProperty().get()).isFalse();
    }

    @Test
    void open_whenAlreadyLoggedIn_doesNothing() {
        session.signIn(USER);

        presenter.open();

        assertThat(presenter.showingProperty().get()).isFalse();
    }

    @Test
    void open_afterAClosedDialogInRegisterMode_startsEmptyInLoginMode() {
        presenter.open();
        presenter.showRegister();
        typeRegistration("An", "an@example.com", GOOD_PASSWORD, GOOD_PASSWORD);
        typeLogin("typed@example.com", "typed1234");
        presenter.close();

        presenter.open();

        assertThat(presenter.showingProperty().get()).isTrue();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
        assertThat(presenter.loginEmailProperty().get()).isEmpty();
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(presenter.regFullNameProperty().get()).isEmpty();
        assertThat(presenter.regEmailProperty().get()).isEmpty();
        assertThat(presenter.regPasswordProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordProperty().get()).isEmpty();
        assertThat(presenter.noticeProperty().get()).isEmpty();
    }

    @Test
    void submitLogin_whenASessionListenerFails_stillClosesTheDialogAndGivesTheErrorToTheUncaughtHandler() {
        session = new AppSession(() -> List.of((previous, current) -> {
            throw new IllegalStateException("listener failed");
        }));
        presenter = presenterWith(direct);
        stubLoginSuccess();
        AtomicInteger remembered = new AtomicInteger();
        presenter.requireLogin(remembered::incrementAndGet);
        typeLogin("user@minishop.local", "User@123");

        presenter.submitLogin();

        assertThat(presenter.showingProperty().get()).isFalse();
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(session.isLoggedIn()).as("the session did change").isTrue();
        assertThat(direct.unhandled()).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(remembered).as("the remembered action is dropped, not run").hasValue(0);
    }

    // ------------------------------------------------------------------ QĐ8: busy

    @Test
    void whileTheLoginRuns_closeIsRefusedAndTheDialogStaysOpen() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        presenter = presenterWith(manual);
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        presenter.open();
        typeLogin("user@minishop.local", "pass1234");
        presenter.submitLogin();

        boolean closed = presenter.close();

        assertThat(presenter.busyProperty().get()).isTrue();
        assertThat(closed).isFalse();
        assertThat(presenter.showingProperty().get()).isTrue();
        manual.runAll();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.close()).isTrue();
        assertThat(presenter.showingProperty().get()).isFalse();
    }

    @Test
    void whileTheLoginRuns_aRefusedCloseKeepsTheRememberedAction() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        presenter = presenterWith(manual);
        stubLoginSuccess();
        AtomicInteger ran = new AtomicInteger();
        presenter.requireLogin(ran::incrementAndGet);
        typeLogin("user@minishop.local", "User@123");
        presenter.submitLogin();

        presenter.close();
        manual.runAll();

        assertThat(ran).hasValue(1);
        assertThat(session.isLoggedIn()).isTrue();
    }

    @Test
    void whileTheLoginRuns_submittingAgainDoesNotCallTheServiceTwice() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        presenter = presenterWith(manual);
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        presenter.open();
        typeLogin("user@minishop.local", "pass1234");
        presenter.submitLogin();

        presenter.submitLogin();
        presenter.submitRegister();

        assertThat(manual.submittedCount()).isEqualTo(1);
        manual.runAll();
        verify(authService).login(any());
        verifyNoInteractions(userService);
    }

    @Test
    void whileTheLoginRuns_switchingModeDoesNothing() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        presenter = presenterWith(manual);
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        presenter.open();
        typeLogin("user@minishop.local", "pass1234");
        presenter.submitLogin();

        presenter.showRegister();

        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
        manual.runAll();
    }

    @Test
    void whileTheRegistrationRuns_theDialogIsBusyAndCannotBeClosedOrSwitched() {
        ManualBackgroundTasks manual = ManualBackgroundTasks.honoringCancel();
        presenter = presenterWith(manual);
        when(userService.register(any())).thenReturn(9L);
        presenter.open();
        presenter.showRegister();
        typeValidRegistration();
        presenter.submitRegister();

        assertThat(presenter.busyProperty().get()).isTrue();
        assertThat(presenter.close()).isFalse();
        presenter.showLogin();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.REGISTER);
        presenter.submitRegister();
        assertThat(manual.submittedCount()).isEqualTo(1);

        manual.runAll();

        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
    }

    @Test
    void close_whenTheDialogIsNotShowing_isAllowed() {
        assertThat(presenter.close()).isTrue();
        assertThat(presenter.showingProperty().get()).isFalse();
    }

    // ------------------------------------------------------------------ AC-009-7: registration

    @Test
    void submitRegister_onSuccess_goesToLoginModeWithTheNormalisedEmailAndTheNotice() {
        when(userService.register(any())).thenReturn(9L);
        presenter.open();
        presenter.showRegister();
        typeValidRegistration();

        presenter.submitRegister();

        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
        assertThat(presenter.loginEmailProperty().get()).isEqualTo("moi@example.com");
        assertThat(presenter.noticeProperty().get()).isEqualTo("Đăng ký thành công. Vui lòng đăng nhập.");
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(presenter.regFullNameProperty().get()).isEmpty();
        assertThat(presenter.regEmailProperty().get()).isEmpty();
        assertThat(presenter.regPasswordProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordProperty().get()).isEmpty();
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.showingProperty().get()).isTrue();
        assertThat(session.isLoggedIn()).as("registering does not log in").isFalse();
        verifyNoInteractions(authService, unexpectedErrors);
    }

    @Test
    void submitRegister_sendsTheCleanedFormToTheServiceAndClearsThePasswordsBeforeTheWorkRuns() {
        List<String> passwordsSeenByTheWork = new ArrayList<>();
        when(userService.register(any())).thenAnswer(invocation -> {
            passwordsSeenByTheWork.add(presenter.regPasswordProperty().get() + "|"
                    + presenter.regConfirmPasswordProperty().get());
            return 9L;
        });
        presenter.showRegister();
        typeValidRegistration();

        presenter.submitRegister();

        ArgumentCaptor<RegistrationForm> form = ArgumentCaptor.forClass(RegistrationForm.class);
        verify(userService).register(form.capture());
        assertThat(form.getValue().fullName()).isEqualTo("Nguyễn An");
        assertThat(form.getValue().email()).isEqualTo("Moi@Example.com");
        assertThat(form.getValue().password()).isEqualTo(GOOD_PASSWORD);
        assertThat(form.getValue().confirmPassword()).isEqualTo(GOOD_PASSWORD);
        assertThat(passwordsSeenByTheWork).containsExactly("|");
    }

    @Test
    void submitRegister_withAnEmailThatIsTaken_showsItUnderTheEmailFieldAndKeepsEverythingButThePasswords() {
        when(userService.register(any())).thenThrow(new EmailAlreadyRegisteredException());
        presenter.open();
        presenter.showRegister();
        typeRegistration("Nguyễn An", "user@minishop.local", GOOD_PASSWORD, GOOD_PASSWORD);

        presenter.submitRegister();

        assertThat(presenter.regEmailErrorProperty().get()).isEqualTo("Email này đã được đăng ký.");
        assertThat(presenter.regFullNameErrorProperty().get()).isEmpty();
        assertThat(presenter.regPasswordErrorProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordErrorProperty().get()).isEmpty();
        assertThat(presenter.regFullNameProperty().get()).isEqualTo("Nguyễn An");
        assertThat(presenter.regEmailProperty().get()).isEqualTo("user@minishop.local");
        assertThat(presenter.regPasswordProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordProperty().get()).isEmpty();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.REGISTER);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.noticeProperty().get()).isEmpty();
        verifyNoInteractions(unexpectedErrors);
    }

    @Test
    void submitRegister_whenTheServiceFailsUnexpectedly_reportsOnceReenablesAndClearsThePasswords() {
        RuntimeException failure = new IllegalStateException("database is down");
        when(userService.register(any())).thenThrow(failure);
        presenter.open();
        presenter.showRegister();
        typeValidRegistration();

        presenter.submitRegister();

        verify(unexpectedErrors).report(failure);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(presenter.regPasswordProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordProperty().get()).isEmpty();
        assertThat(presenter.regEmailErrorProperty().get()).isEmpty();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.REGISTER);
        assertThat(direct.unhandled()).isEmpty();
    }

    @Test
    void submitRegister_afterAnErrorAndACorrection_clearsTheOldErrors() {
        when(userService.register(any())).thenReturn(9L);
        presenter.open();
        presenter.showRegister();
        typeRegistration("", "abc", "abc", "abd");
        presenter.submitRegister();
        assertThat(presenter.regFullNameErrorProperty().get()).isNotEmpty();

        typeValidRegistration();
        presenter.submitRegister();

        assertThat(presenter.regFullNameErrorProperty().get()).isEmpty();
        assertThat(presenter.regEmailErrorProperty().get()).isEmpty();
        assertThat(presenter.regPasswordErrorProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordErrorProperty().get()).isEmpty();
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
    }

    /**
     * One row per case of spec 003 section 6: what is typed (name, email, password, confirmation) and the single
     * sentence expected under each field ("" for none). The first sentence in the fixed order wins per field:
     * required, then format, then size, then the other rules.
     */
    static Stream<Arguments> invalidRegistrations() {
        String name101 = "a".repeat(101);
        String longEmail = "a".repeat(64) + "@" + String.join(".", "b".repeat(63), "c".repeat(63), "d".repeat(63));
        String tooManyBytes = "ệ".repeat(25) + "1";
        return Stream.of(
                Arguments.of("empty form", "", "", "", "",
                        "Vui lòng nhập họ và tên.", "Vui lòng nhập email.",
                        "Mật khẩu phải có từ 8 đến 64 ký tự.", ""),
                Arguments.of("blank name and email", "   ", "   ", GOOD_PASSWORD, GOOD_PASSWORD,
                        "Vui lòng nhập họ và tên.", "Vui lòng nhập email.", "", ""),
                Arguments.of("name too long", name101, "an@example.com", GOOD_PASSWORD, GOOD_PASSWORD,
                        "Họ và tên tối đa 100 ký tự.", "", "", ""),
                Arguments.of("malformed email", "An", "abc", GOOD_PASSWORD, GOOD_PASSWORD,
                        "", "Email không hợp lệ.", "", ""),
                Arguments.of("non-ASCII email", "An", "ánh@example.com", GOOD_PASSWORD, GOOD_PASSWORD,
                        "", "Email không hợp lệ.", "", ""),
                Arguments.of("email longer than 255", "An", longEmail, GOOD_PASSWORD, GOOD_PASSWORD,
                        "", "Email tối đa 255 ký tự.", "", ""),
                Arguments.of("password too short", "An", "an@example.com", "abc", "abc",
                        "", "", "Mật khẩu phải có từ 8 đến 64 ký tự.", ""),
                Arguments.of("password without a digit", "An", "an@example.com", "abcdefgh", "abcdefgh",
                        "", "", "Mật khẩu phải có cả chữ và số.", ""),
                Arguments.of("password over 72 bytes", "An", "an@example.com", tooManyBytes, tooManyBytes,
                        "", "", "Mật khẩu quá dài.", ""),
                Arguments.of("confirmation differs", "An", "an@example.com", GOOD_PASSWORD, "abcdefg2",
                        "", "", "", "Mật khẩu nhập lại không khớp."),
                Arguments.of("everything wrong at once", "", "abc", "abc", "abd",
                        "Vui lòng nhập họ và tên.", "Email không hợp lệ.",
                        "Mật khẩu phải có từ 8 đến 64 ký tự.", "Mật khẩu nhập lại không khớp."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrations")
    void submitRegister_withAnInvalidForm_showsOneSentencePerFieldClearsThePasswordsAndDoesNotCallTheService(
            String label, String fullName, String email, String password, String confirm,
            String nameError, String emailError, String passwordError, String confirmError) {
        presenter.open();
        presenter.showRegister();
        typeRegistration(fullName, email, password, confirm);

        presenter.submitRegister();

        assertThat(presenter.regFullNameErrorProperty().get()).isEqualTo(nameError);
        assertThat(presenter.regEmailErrorProperty().get()).isEqualTo(emailError);
        assertThat(presenter.regPasswordErrorProperty().get()).isEqualTo(passwordError);
        assertThat(presenter.regConfirmPasswordErrorProperty().get()).isEqualTo(confirmError);
        assertThat(presenter.regPasswordProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordProperty().get()).isEmpty();
        assertThat(presenter.regFullNameProperty().get()).as("the name is kept").isEqualTo(fullName);
        assertThat(presenter.regEmailProperty().get()).as("the email is kept").isEqualTo(email);
        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.REGISTER);
        assertThat(presenter.busyProperty().get()).isFalse();
        assertThat(direct.submittedCount()).isZero();
        verifyNoInteractions(userService, unexpectedErrors);
    }

    // ------------------------------------------------------------------ switching mode

    @Test
    void showRegister_clearsTheLoginErrorTheNoticeAndThePassword() {
        when(authService.login(any())).thenReturn(new LoginResult.InvalidCredentials());
        presenter.open();
        typeLogin("user@minishop.local", "wrong");
        presenter.submitLogin();
        typeLogin("user@minishop.local", "half-typed");

        presenter.showRegister();

        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.REGISTER);
        assertThat(presenter.loginErrorProperty().get()).isEmpty();
        assertThat(presenter.loginPasswordProperty().get()).isEmpty();
        assertThat(presenter.loginEmailProperty().get()).as("the email is kept").isEqualTo("user@minishop.local");
    }

    @Test
    void showLogin_clearsTheRegistrationErrorsAndPasswords() {
        presenter.open();
        presenter.showRegister();
        typeRegistration("", "abc", "abc", "abd");
        presenter.submitRegister();
        typeRegistration("", "abc", "half-typed1", "half-typed1");

        presenter.showLogin();

        assertThat(presenter.modeProperty().get()).isEqualTo(Mode.LOGIN);
        assertThat(presenter.regFullNameErrorProperty().get()).isEmpty();
        assertThat(presenter.regEmailErrorProperty().get()).isEmpty();
        assertThat(presenter.regPasswordErrorProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordErrorProperty().get()).isEmpty();
        assertThat(presenter.regPasswordProperty().get()).isEmpty();
        assertThat(presenter.regConfirmPasswordProperty().get()).isEmpty();
    }

    @Test
    void showLogin_whenAlreadyInLoginMode_changesNothingAndKeepsTheNotice() {
        when(userService.register(any())).thenReturn(9L);
        presenter.open();
        presenter.showRegister();
        typeValidRegistration();
        presenter.submitRegister();

        presenter.showLogin();

        assertThat(presenter.noticeProperty().get()).isEqualTo("Đăng ký thành công. Vui lòng đăng nhập.");
    }

    @Test
    void loginForm_neverPrintsTheEmailOrThePassword() {
        assertThat(new LoginForm("user@minishop.local", "s3cret").toString())
                .doesNotContain("user@minishop.local")
                .doesNotContain("s3cret");
    }
}

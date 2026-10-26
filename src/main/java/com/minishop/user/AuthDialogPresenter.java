package com.minishop.user;

import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.UnexpectedErrors;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Trạng thái và quyết định của hộp thoại đăng nhập/đăng ký (QĐ7, QĐ8 của D2): đang ở chế độ nào, ô nào báo lỗi gì,
 * khi nào nút bị khóa, đăng nhập xong thì làm gì. Không import {@code javafx.scene}; {@link AuthDialog} chỉ dựng
 * node và gắn vào các property này. Cũng là cài đặt của {@link LoginPrompt} cho các tính năng khác.
 * <p>
 * <b>Luồng:</b> mọi phương thức gọi trên luồng giao diện. Việc gọi service đi qua {@link BackgroundTasks}, kết quả
 * về lại luồng giao diện; phiên chỉ đổi ở đó ({@code AppSession} ghi trên luồng giao diện).
 * <p>
 * <b>Mật khẩu:</b> chỉ sống ngắn nhất có thể. Ngay lúc gửi, giá trị được chụp vào form rồi ô bị xóa; không bao giờ
 * vào log hay {@code toString}. Hai ô mật khẩu của form đăng ký bị xóa sau mọi lần gửi.
 * <p>
 * <b>Đang chạy:</b> {@link #busyProperty()} đúng trong lúc gọi service. Khi đó gửi lần nữa, đổi chế độ và
 * {@link #close()} đều bị từ chối, vì đóng giữa chừng thì việc gộp giỏ vẫn chạy nền trong khi phiên không đổi.
 */
@Component
class AuthDialogPresenter implements LoginPrompt {

    /** Chế độ của hộp thoại. */
    enum Mode { LOGIN, REGISTER }

    static final String INVALID_CREDENTIALS = "Email hoặc mật khẩu không đúng.";
    static final String EMAIL_TAKEN = "Email này đã được đăng ký.";
    static final String REGISTERED_NOTICE = "Đăng ký thành công. Vui lòng đăng nhập.";

    private final AuthService authService;
    private final UserService userService;
    private final AppSession session;
    private final BackgroundTasks tasks;
    private final UnexpectedErrors unexpectedErrors;
    private final Validator validator;

    private final ReadOnlyBooleanWrapper showing = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyObjectWrapper<Mode> mode = new ReadOnlyObjectWrapper<>(Mode.LOGIN);

    private final StringProperty loginEmail = new SimpleStringProperty("");
    private final StringProperty loginPassword = new SimpleStringProperty("");
    private final ReadOnlyStringWrapper loginError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper notice = new ReadOnlyStringWrapper("");

    private final StringProperty regFullName = new SimpleStringProperty("");
    private final StringProperty regEmail = new SimpleStringProperty("");
    private final StringProperty regPassword = new SimpleStringProperty("");
    private final StringProperty regConfirmPassword = new SimpleStringProperty("");
    private final ReadOnlyStringWrapper regFullNameError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper regEmailError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper regPasswordError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper regConfirmPasswordError = new ReadOnlyStringWrapper("");

    /** Việc cần làm sau khi đăng nhập xong ({@link #requireLogin}); chạy đúng một lần rồi quên. */
    private Runnable pendingAction;

    AuthDialogPresenter(AuthService authService, UserService userService, AppSession session,
                        BackgroundTasks tasks, UnexpectedErrors unexpectedErrors, Validator validator) {
        this.authService = authService;
        this.userService = userService;
        this.session = session;
        this.tasks = tasks;
        this.unexpectedErrors = unexpectedErrors;
        this.validator = validator;
    }

    // ------------------------------------------------------------------ LoginPrompt

    @Override
    public void open() {
        if (showing.get() || session.isLoggedIn()) {
            return;
        }
        resetFields();
        mode.set(Mode.LOGIN);
        showing.set(true);
    }

    @Override
    public void requireLogin(Runnable onLoggedIn) {
        Objects.requireNonNull(onLoggedIn, "onLoggedIn");
        if (session.isLoggedIn()) {
            onLoggedIn.run();
            return;
        }
        pendingAction = onLoggedIn;
        open();
    }

    /**
     * Đóng hộp thoại (nút "Đóng", Esc, Alt+F4), bỏ việc đã nhớ và xóa mọi ô. Trả {@code false} và không làm gì khi
     * đang đăng nhập hoặc đăng ký.
     */
    boolean close() {
        if (busy.get()) {
            return false;
        }
        if (showing.get()) {
            pendingAction = null;
            hide();
        }
        return true;
    }

    // ------------------------------------------------------------------ chuyển chế độ

    void showRegister() {
        switchTo(Mode.REGISTER);
    }

    void showLogin() {
        switchTo(Mode.LOGIN);
    }

    /** Chuyển qua lại giữ nguyên việc đã nhớ; lỗi, thông báo và mật khẩu của chế độ cũ bị xóa. */
    private void switchTo(Mode target) {
        if (busy.get() || mode.get() == target) {
            return;
        }
        clearMessages();
        loginPassword.set("");
        regPassword.set("");
        regConfirmPassword.set("");
        mode.set(target);
    }

    // ------------------------------------------------------------------ đăng nhập

    void submitLogin() {
        if (busy.get()) {
            return;
        }
        LoginForm form = new LoginForm(loginEmail.get(), loginPassword.get());
        loginPassword.set("");
        clearMessages();
        if (form.email().isBlank() || form.password().isEmpty()) {
            loginError.set(INVALID_CREDENTIALS);
            return;
        }
        busy.set(true);
        tasks.submit(() -> authService.login(form), this::onLoginResult, this::onFailure);
    }

    private void onLoginResult(LoginResult result) {
        busy.set(false);
        switch (result) {
            case LoginResult.Success success -> finishLogin(success.user());
            case LoginResult.InvalidCredentials _ -> loginError.set(INVALID_CREDENTIALS);
        }
    }

    /**
     * Phiên đổi trước (nhãn, badge và listener khác cập nhật), rồi hộp thoại đóng và xóa hết các ô, rồi việc đã
     * nhớ chạy. Việc đó được lấy ra và quên đi trước khi phiên đổi, nên không bao giờ chạy hai lần.
     */
    private void finishLogin(CurrentUser user) {
        Runnable then = pendingAction;
        pendingAction = null;
        try {
            session.signIn(user);
        } finally {
            hide();
        }
        if (then != null) {
            then.run();
        }
    }

    // ------------------------------------------------------------------ đăng ký

    void submitRegister() {
        if (busy.get()) {
            return;
        }
        RegistrationForm form = new RegistrationForm(
                regFullName.get(), regEmail.get(), regPassword.get(), regConfirmPassword.get());
        regPassword.set("");
        regConfirmPassword.set("");
        clearMessages();

        Set<ConstraintViolation<RegistrationForm>> violations = validator.validate(form);
        if (!violations.isEmpty()) {
            showViolations(violations);
            return;
        }
        busy.set(true);
        tasks.submit(() -> userService.register(form), id -> onRegistered(form), this::onRegisterFailure);
    }

    /** Về chế độ đăng nhập với email đã chuẩn hóa và thông báo (AC-009-7). */
    private void onRegistered(RegistrationForm form) {
        busy.set(false);
        regFullName.set("");
        regEmail.set("");
        loginEmail.set(EmailNormalizer.normalize(form.email()));
        loginPassword.set("");
        clearMessages();
        notice.set(REGISTERED_NOTICE);
        mode.set(Mode.LOGIN);
    }

    private void onRegisterFailure(Throwable error) {
        if (error instanceof EmailAlreadyRegisteredException) {
            busy.set(false);
            regEmailError.set(EMAIL_TAKEN);
            return;
        }
        onFailure(error);
    }

    // ------------------------------------------------------------------ lỗi và kiểm tra form

    /** Lỗi không lường trước: mở lại nút rồi chuyển cho bộ bắt lỗi chung (AC-009-12). */
    private void onFailure(Throwable error) {
        busy.set(false);
        unexpectedErrors.report(error);
    }

    /**
     * Mỗi ô một lỗi, chọn theo thứ tự cố định: NotBlank, rồi Email/Pattern, rồi Size, rồi các ràng buộc còn lại
     * (mật khẩu theo quy tắc, nhập lại không khớp). Cùng hạng thì lấy câu đứng trước theo chữ cái, cho kết quả ổn
     * định.
     */
    private void showViolations(Set<ConstraintViolation<RegistrationForm>> violations) {
        Map<String, ConstraintViolation<RegistrationForm>> first = new HashMap<>();
        Comparator<ConstraintViolation<RegistrationForm>> order = Comparator
                .comparingInt(AuthDialogPresenter::rank)
                .thenComparing(ConstraintViolation::getMessage);
        for (ConstraintViolation<RegistrationForm> violation : violations) {
            first.merge(violation.getPropertyPath().toString(), violation,
                    (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }
        boolean shown = false;
        shown |= setError(regFullNameError, first.get("fullName"));
        shown |= setError(regEmailError, first.get("email"));
        shown |= setError(regPasswordError, first.get("password"));
        shown |= setError(regConfirmPasswordError, first.get("confirmPassword"));
        if (!shown) {
            // Một ràng buộc không gắn được vào ô nào: lỗi lập trình, không để người dùng nhìn form im lặng.
            unexpectedErrors.report(new IllegalStateException("Registration violation without a field"));
        }
    }

    private static boolean setError(ReadOnlyStringWrapper target, ConstraintViolation<RegistrationForm> violation) {
        if (violation == null) {
            return false;
        }
        target.set(violation.getMessage());
        return true;
    }

    private static int rank(ConstraintViolation<RegistrationForm> violation) {
        Class<? extends Annotation> type = violation.getConstraintDescriptor().getAnnotation().annotationType();
        if (type == NotBlank.class) {
            return 0;
        }
        if (type == Email.class || type == Pattern.class) {
            return 1;
        }
        if (type == Size.class) {
            return 2;
        }
        return 3;
    }

    // ------------------------------------------------------------------ xóa trạng thái

    private void clearMessages() {
        loginError.set("");
        notice.set("");
        regFullNameError.set("");
        regEmailError.set("");
        regPasswordError.set("");
        regConfirmPasswordError.set("");
    }

    private void resetFields() {
        loginEmail.set("");
        loginPassword.set("");
        regFullName.set("");
        regEmail.set("");
        regPassword.set("");
        regConfirmPassword.set("");
        clearMessages();
    }

    /** Xóa hết ô rồi đóng, để lần mở sau là hộp thoại trống. */
    private void hide() {
        busy.set(false);
        resetFields();
        showing.set(false);
    }

    // ------------------------------------------------------------------ property cho AuthDialog

    ReadOnlyBooleanProperty showingProperty() {
        return showing.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<Mode> modeProperty() {
        return mode.getReadOnlyProperty();
    }

    StringProperty loginEmailProperty() {
        return loginEmail;
    }

    StringProperty loginPasswordProperty() {
        return loginPassword;
    }

    ReadOnlyStringProperty loginErrorProperty() {
        return loginError.getReadOnlyProperty();
    }

    /** Thông báo thành công (sau đăng ký) hiện trên chế độ đăng nhập; rỗng khi không có. */
    ReadOnlyStringProperty noticeProperty() {
        return notice.getReadOnlyProperty();
    }

    StringProperty regFullNameProperty() {
        return regFullName;
    }

    StringProperty regEmailProperty() {
        return regEmail;
    }

    StringProperty regPasswordProperty() {
        return regPassword;
    }

    StringProperty regConfirmPasswordProperty() {
        return regConfirmPassword;
    }

    ReadOnlyStringProperty regFullNameErrorProperty() {
        return regFullNameError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty regEmailErrorProperty() {
        return regEmailError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty regPasswordErrorProperty() {
        return regPasswordError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty regConfirmPasswordErrorProperty() {
        return regConfirmPasswordError.getReadOnlyProperty();
    }
}

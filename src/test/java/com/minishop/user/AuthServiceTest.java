package com.minishop.user;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.minishop.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Login rules of {@link AuthService} (spec 003 BR-003-5, spec 009 AC-009-6, QĐ3 and QĐ4 of the D2 plan). Real
 * BCrypt through a counting wrapper, a mocked repository, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@minishop.local";
    private static final String PASSWORD = "Passw0rd123";
    private static final String FULL_NAME = "Khách hàng mẫu";
    private static final long USER_ID = 7L;

    @Mock
    UserRepository users;

    CountingEncoder encoder;
    String realHash;
    User user;
    AuthService service;

    @BeforeEach
    void setUp() {
        encoder = new CountingEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        realHash = encoder.delegate.encode(PASSWORD);
        user = userWith(USER_ID, EMAIL, realHash, Role.USER);
        service = serviceWith();
    }

    private AuthService serviceWith(LoginHook... hooks) {
        return new AuthService(users, encoder, providerOf(hooks));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LoginHook> providerOf(LoginHook... hooks) {
        ObjectProvider<LoginHook> provider = mock(ObjectProvider.class);
        lenient().when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(hooks));
        return provider;
    }

    private static User userWith(long id, String email, String hash, Role role) {
        User u = new User(email, hash, FULL_NAME, role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static LoginForm form(String email, String password) {
        return new LoginForm(email, password);
    }

    /** A password of exactly {@code bytes} UTF-8 bytes made of one ASCII prefix and the letter a. */
    private static String asciiPassword(int bytes) {
        return "Pw1" + "a".repeat(bytes - 3);
    }

    // ------------------------------------------------------------------ success

    @Test
    void login_withCorrectPassword_returnsSuccessWithAllFieldsOfCurrentUser() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        LoginResult result = service.login(form(EMAIL, PASSWORD));

        assertThat(result).isEqualTo(new LoginResult.Success(new CurrentUser(USER_ID, EMAIL, FULL_NAME, Role.USER)));
    }

    @Test
    void login_forAnAdmin_carriesTheAdminRole() {
        User admin = userWith(1L, "admin@minishop.local", realHash, Role.ADMIN);
        when(users.findByEmail("admin@minishop.local")).thenReturn(Optional.of(admin));

        LoginResult result = service.login(form("admin@minishop.local", PASSWORD));

        assertThat(result).isInstanceOfSatisfying(LoginResult.Success.class,
                success -> assertThat(success.user().role()).isEqualTo(Role.ADMIN));
    }

    @Test
    void login_normalizesTheEmailBeforeTheLookup() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        LoginResult result = service.login(form("  User@MiniShop.LOCAL ", PASSWORD));

        assertThat(result).isInstanceOf(LoginResult.Success.class);
        verify(users).findByEmail(EMAIL);
    }

    @Test
    void login_doesNotTrimThePassword() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThat(service.login(form(EMAIL, PASSWORD + " "))).isEqualTo(new LoginResult.InvalidCredentials());

        String spaced = " " + PASSWORD + " ";
        user = userWith(USER_ID, EMAIL, encoder.delegate.encode(spaced), Role.USER);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThat(service.login(form(EMAIL, spaced))).isInstanceOf(LoginResult.Success.class);
    }

    // ------------------------------------------------------------------ rejected (BR-003-5)

    @Test
    void login_wrongPasswordAndUnknownEmail_giveTheSameResult() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@minishop.local")).thenReturn(Optional.empty());

        LoginResult wrongPassword = service.login(form(EMAIL, "Wrong-pass-1"));
        LoginResult unknownEmail = service.login(form("nobody@minishop.local", PASSWORD));

        assertThat(wrongPassword).isEqualTo(new LoginResult.InvalidCredentials());
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void login_withAnUnknownEmailEvenWhenThePasswordIsRight_isRejected() {
        when(users.findByEmail("nobody@minishop.local")).thenReturn(Optional.empty());

        assertThat(service.login(form("nobody@minishop.local", PASSWORD)))
                .isEqualTo(new LoginResult.InvalidCredentials());
    }

    // ------------------------------------------------------------------ matches exactly once

    @Test
    void login_unknownEmail_callsMatchesExactlyOnceAgainstTheFakeHash() {
        when(users.findByEmail("nobody@minishop.local")).thenReturn(Optional.empty());

        service.login(form("nobody@minishop.local", PASSWORD));

        assertThat(encoder.matchesCalls).hasSize(1);
        assertThat(encoder.matchesCalls.getFirst().raw()).isEqualTo(PASSWORD);
        assertThat(encoder.encodedHashes).as("the fake hash is a hash made by this encoder").hasSize(1)
                .doesNotContain(realHash);
        assertThat(encoder.matchesCalls.getFirst().hash()).isEqualTo(encoder.encodedHashes.getFirst());
    }

    @Test
    void login_wrongPassword_callsMatchesExactlyOnceAgainstTheRealHash() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        service.login(form(EMAIL, "Wrong-pass-1"));

        assertThat(encoder.matchesCalls).hasSize(1);
        assertThat(encoder.matchesCalls.getFirst().hash()).isEqualTo(realHash);
    }

    @Test
    void login_correctPassword_callsMatchesExactlyOnce() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        service.login(form(EMAIL, PASSWORD));

        assertThat(encoder.matchesCalls).hasSize(1);
        assertThat(encoder.matchesCalls.getFirst().hash()).isEqualTo(realHash);
    }

    @Test
    void login_passwordOver72Bytes_callsMatchesExactlyOnceWithAFixedStringNotTheTypedPassword() {
        String tooLong = asciiPassword(73);

        LoginResult result = service.login(form(EMAIL, tooLong));

        assertThat(result).isEqualTo(new LoginResult.InvalidCredentials());
        assertThat(encoder.matchesCalls).hasSize(1);
        assertThat(encoder.matchesCalls.getFirst().raw()).isNotEqualTo(tooLong);
        assertThat(encoder.matchesCalls.getFirst().hash()).isEqualTo(encoder.encodedHashes.getFirst());
        verifyNoInteractions(users);
    }

    @ParameterizedTest(name = "[{index}] email=''{0}'' password=''{1}''")
    @CsvSource(value = {
            "'', Passw0rd123",
            "'   ', Passw0rd123",
            "user@minishop.local, ''",
            "'', ''"}, quoteCharacter = '\'')
    void login_withAnEmptyEmailOrPassword_isRejectedWithoutMatchesAndWithoutTheRepository(String email, String password) {
        LoginResult result = service.login(form(email, password));

        assertThat(result).isEqualTo(new LoginResult.InvalidCredentials());
        assertThat(encoder.matchesCalls).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void login_withNullFields_isRejectedLikeEmptyOnes() {
        assertThat(service.login(new LoginForm(null, null))).isEqualTo(new LoginResult.InvalidCredentials());

        assertThat(encoder.matchesCalls).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void login_withAnEmailOf1001Characters_isRejectedWithoutMatchesAndWithoutTheRepository() {
        String tooLong = "a".repeat(1001 - "@x.io".length()) + "@x.io";
        assertThat(tooLong).hasSize(1001);

        LoginResult result = service.login(form(tooLong, PASSWORD));

        assertThat(result).isEqualTo(new LoginResult.InvalidCredentials());
        assertThat(encoder.matchesCalls).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void login_withAValidEmailPaddedWithSpacesToExactly1000Characters_stillSucceeds() {
        int padding = (1000 - EMAIL.length()) / 2;
        String padded = " ".repeat(padding) + EMAIL + " ".repeat(1000 - EMAIL.length() - padding);
        assertThat(padded).hasSize(1000);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        LoginResult result = service.login(form(padded, PASSWORD));

        assertThat(result).isEqualTo(new LoginResult.Success(new CurrentUser(USER_ID, EMAIL, FULL_NAME, Role.USER)));
        verify(users).findByEmail(EMAIL);
    }

    @Test
    void login_createsTheFakeHashOnlyOnce_atTheStartOfTheVeryFirstCall() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@minishop.local")).thenReturn(Optional.empty());

        service.login(form(EMAIL, PASSWORD));
        assertThat(encoder.encodedHashes).as("the first call, with a known user, already created it").hasSize(1);

        service.login(form("nobody@minishop.local", PASSWORD));
        service.login(form(EMAIL, "Wrong-pass-1"));

        assertThat(encoder.encodedHashes).hasSize(1);
    }

    // ------------------------------------------------------------------ the 72 byte limit

    /**
     * The reason for the limit: {@code BCrypt.checkpw} of spring-security-crypto (the version of this project) compares
     * only the first 72 bytes, so without the guard "real password + x" would log in.
     */
    @Test
    void login_realPasswordOf72BytesPlusOneMoreCharacter_isRejected_althoughBCryptItselfWouldMatch() {
        String real72 = asciiPassword(72);
        assertThat(real72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        String hash = encoder.delegate.encode(real72);
        String longer = real72 + "x";

        assertThat(new BCryptPasswordEncoder().matches(longer, hash.substring("{bcrypt}".length())))
                .as("precondition: BCrypt in this spring-security-crypto ignores bytes after the 72nd").isTrue();
        assertThat(encoder.delegate.matches(longer, hash)).as("also through the delegating encoder").isTrue();

        // the guard returns before the lookup, so a stored hash of the 72 byte password is never even read
        assertThat(service.login(form(EMAIL, longer))).isEqualTo(new LoginResult.InvalidCredentials());
        verify(users, never()).findByEmail(EMAIL);
    }

    @Test
    void login_realPasswordOfExactly72Bytes_stillWorks() {
        String real72 = asciiPassword(72);
        when(users.findByEmail(EMAIL))
                .thenReturn(Optional.of(userWith(USER_ID, EMAIL, encoder.delegate.encode(real72), Role.USER)));

        assertThat(service.login(form(EMAIL, real72))).isInstanceOf(LoginResult.Success.class);
    }

    @Test
    void login_passwordOf72BytesMadeOfAccentedLetters_stillWorks_and80BytesIsRejectedWithoutException() {
        String accented72 = "á".repeat(36);
        String accented80 = "á".repeat(40);
        assertThat(accented72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(accented80.getBytes(StandardCharsets.UTF_8)).hasSize(80);
        when(users.findByEmail(EMAIL))
                .thenReturn(Optional.of(userWith(USER_ID, EMAIL, encoder.delegate.encode(accented72), Role.USER)));

        assertThat(service.login(form(EMAIL, accented72))).isInstanceOf(LoginResult.Success.class);
        encoder.matchesCalls.clear();

        LoginResult[] result = new LoginResult[1];
        assertThatCode(() -> result[0] = service.login(form(EMAIL, accented80))).doesNotThrowAnyException();
        assertThat(result[0]).isEqualTo(new LoginResult.InvalidCredentials());
        assertThat(encoder.matchesCalls).hasSize(1);
    }

    // ------------------------------------------------------------------ hooks (QĐ4)

    @Test
    void login_success_runsTheHooksInOrderWithTheCurrentUser() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        List<String> order = new ArrayList<>();
        List<CurrentUser> received = new ArrayList<>();
        AuthService withHooks = serviceWith(
                u -> {
                    order.add("first");
                    received.add(u);
                },
                u -> {
                    order.add("second");
                    received.add(u);
                });

        LoginResult result = withHooks.login(form(EMAIL, PASSWORD));

        CurrentUser expected = new CurrentUser(USER_ID, EMAIL, FULL_NAME, Role.USER);
        assertThat(result).isEqualTo(new LoginResult.Success(expected));
        assertThat(order).containsExactly("first", "second");
        assertThat(received).containsExactly(expected, expected);
    }

    @Test
    void login_whenItFails_runsNoHook() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@minishop.local")).thenReturn(Optional.empty());
        List<CurrentUser> received = new ArrayList<>();
        AuthService withHooks = serviceWith(received::add);

        withHooks.login(form(EMAIL, "Wrong-pass-1"));
        withHooks.login(form("nobody@minishop.local", PASSWORD));
        withHooks.login(form("", PASSWORD));
        withHooks.login(form(EMAIL, asciiPassword(80)));

        assertThat(received).isEmpty();
    }

    @Test
    void login_whenAHookFails_stillSucceeds_runsTheNextHook_andLogsOneWarningWithoutPersonalData() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        SQLException sql = new SQLException("Map entry (3, ('" + EMAIL + "', '{bcrypt}secret-hash')) with key <3>");
        List<String> ran = new ArrayList<>();
        AuthService withHooks = serviceWith(
                new FailingHook(new IllegalStateException("could not merge " + EMAIL, sql), ran),
                new RecordingHook(ran));

        try (LogCapture capture = LogCapture.of(LoggerFactory.getLogger(AuthService.class))) {
            LoginResult result = withHooks.login(form(EMAIL, PASSWORD));

            assertThat(result).isInstanceOf(LoginResult.Success.class);
            assertThat(ran).containsExactly("FailingHook", "RecordingHook");
            List<ILoggingEvent> warnings = capture.events(Level.WARN);
            assertThat(warnings).hasSize(1);
            ILoggingEvent warning = warnings.getFirst();
            assertThat(warning.getFormattedMessage()).contains("FailingHook").contains("user " + USER_ID);
            assertThat(everythingText(warning)).doesNotContain(EMAIL).doesNotContain("bcrypt")
                    .doesNotContain("Map entry").doesNotContain("secret-hash");
            assertThat(warning.getThrowableProxy()).as("the redacted exception is still logged").isNotNull();
        }
    }

    // ------------------------------------------------------------------ logs

    @Test
    void login_neverLogsTheEmailOrThePassword() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@minishop.local")).thenReturn(Optional.empty());
        String typedPassword = "Typed-S3cret";

        try (LogCapture capture = LogCapture.of(LoggerFactory.getLogger(AuthService.class))) {
            service.login(form(EMAIL, PASSWORD));
            service.login(form(EMAIL, typedPassword));
            service.login(form("nobody@minishop.local", typedPassword));
            service.login(form("", typedPassword));
            service.login(form(EMAIL, typedPassword.repeat(10)));

            assertThat(capture.events()).isNotEmpty();
            for (ILoggingEvent event : capture.events()) {
                assertThat(everythingText(event)).doesNotContain(EMAIL).doesNotContain("nobody@")
                        .doesNotContain(PASSWORD).doesNotContain(typedPassword).doesNotContain("{bcrypt}");
            }
            assertThat(capture.events(Level.INFO)).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("User " + USER_ID + " logged in");
            assertThat(capture.events(Level.DEBUG)).hasSize(4)
                    .extracting(ILoggingEvent::getFormattedMessage).containsOnly("Login rejected");
        }
    }

    @Test
    void loginForm_toString_hidesEmailAndPassword() {
        String text = form(EMAIL, PASSWORD).toString();

        assertThat(text).doesNotContain(EMAIL).doesNotContain(PASSWORD).contains("***");
    }

    // ------------------------------------------------------------------ transactions (QĐ3)

    @Test
    void login_isNotSupported_asATransaction_andTheClassHasNoClassLevelTransactional() throws NoSuchMethodException {
        Transactional onMethod = AuthService.class.getDeclaredMethod("login", LoginForm.class)
                .getAnnotation(Transactional.class);

        assertThat(onMethod).as("@Transactional on login").isNotNull();
        assertThat(onMethod.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(AnnotatedElementUtils.findMergedAnnotation(AuthService.class, Transactional.class))
                .as("no @Transactional on the class").isNull();
    }

    // ------------------------------------------------------------------ helpers

    /** Message, formatted message and the whole cause chain of one log event. */
    private static String everythingText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage()).append(' ').append(event.getMessage());
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            text.append(' ').append(proxy.getClassName()).append(' ').append(proxy.getMessage());
        }
        return text.toString();
    }

    private record MatchesCall(CharSequence raw, String hash) {
    }

    /** The real encoder, but it remembers every {@code matches} call and every hash it made. */
    private static final class CountingEncoder implements PasswordEncoder {

        final PasswordEncoder delegate;
        final List<MatchesCall> matchesCalls = new ArrayList<>();
        final List<String> encodedHashes = new ArrayList<>();

        CountingEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            String hash = delegate.encode(rawPassword);
            // hashes made by the test itself go through "delegate" directly, so only the service lands here
            encodedHashes.add(hash);
            return hash;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.add(new MatchesCall(rawPassword, encodedPassword));
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    private record FailingHook(RuntimeException error, List<String> ran) implements LoginHook {

        @Override
        public void afterLogin(CurrentUser user) {
            ran.add("FailingHook");
            throw error;
        }
    }

    private record RecordingHook(List<String> ran) implements LoginHook {

        @Override
        public void afterLogin(CurrentUser user) {
            ran.add("RecordingHook");
        }
    }
}

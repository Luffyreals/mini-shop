package com.minishop.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.WebAttributes;
import org.springframework.web.servlet.FlashMap;
import java.util.Map;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-003-11, BR-003-5: đăng nhập sai quay về /login?error, chỉ mang email (đã strip, tối đa 255) qua flash. */
class LoginFailureHandlerTest {

    private final SessionFlashMapManager flashMapManager = new SessionFlashMapManager();
    private final LoginFailureHandler handler = new LoginFailureHandler(flashMapManager);

    private MockHttpServletRequest request(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        if (email != null) {
            request.setParameter("email", email);
        }
        request.setParameter("password", "secret-pass-123");
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        handler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials for unknown@x"));
        MockHttpServletRequest next = new MockHttpServletRequest("GET", "/login");
        next.setSession(request.getSession(false));
        return (Map<String, Object>) flashMapManager.retrieveAndUpdate(next, new MockHttpServletResponse());
    }

    @Test
    void onFailure_redirectsToLoginError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("a@b.vn"), response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void onFailure_flashesStrippedEmailOnly() throws Exception {
        Map<String, Object> flash = run(request("  a@b.vn  "), new MockHttpServletResponse());

        assertThat(flash).isNotNull();
        assertThat(flash).containsOnlyKeys("email");
        assertThat(flash.get("email")).isEqualTo("a@b.vn");
        assertThat(((FlashMap) flash).getTargetRequestPath()).isEqualTo("/login");
    }

    @Test
    void onFailure_doesNotLeakPasswordOrExceptionMessageInFlash() throws Exception {
        Map<String, Object> flash = run(request("a@b.vn"), new MockHttpServletResponse());

        assertThat(flash.values().toString())
                .doesNotContain("secret-pass-123")
                .doesNotContain("Bad credentials");
    }

    @Test
    void onFailure_capsEmailAt255Characters() throws Exception {
        Map<String, Object> flash = run(request("a".repeat(300)), new MockHttpServletResponse());

        assertThat((String) flash.get("email")).hasSize(255);
    }

    /** Cắt theo code point: emoji nằm ở ranh giới 255 không bị tách đôi thành surrogate lẻ. */
    @Test
    void onFailure_capAtEmojiBoundary_neverSplitsSurrogatePair() throws Exception {
        Map<String, Object> flash = run(request("a".repeat(254) + "😀😀"), new MockHttpServletResponse());

        String email = (String) flash.get("email");
        assertThat(email).isEqualTo("a".repeat(254) + "😀");
        assertThat(Character.isHighSurrogate(email.charAt(email.length() - 1))).isFalse();
    }

    @Test
    void onFailure_blankEmail_savesNoFlashButStillRedirects() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, Object> flash = run(request("   "), response);

        assertThat(flash).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void onFailure_missingEmail_savesNoFlashButStillRedirects() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, Object> flash = run(request(null), response);

        assertThat(flash).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    // ------------------------------------------------------------------ GĐ8: a blocked attempt (AC-008-7)

    @SuppressWarnings("unchecked")
    private Map<String, Object> runBlocked(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        handler.onAuthenticationFailure(request, response, new LoginBlockedException());
        MockHttpServletRequest next = new MockHttpServletRequest("GET", "/login");
        next.setSession(request.getSession(false));
        return (Map<String, Object>) flashMapManager.retrieveAndUpdate(next, new MockHttpServletResponse());
    }

    @Test
    void onBlocked_redirectsToLoginBlocked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        runBlocked(request("a@b.vn"), response);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?blocked");
    }

    @Test
    void onBlocked_stillFlashesStrippedEmailOnly() throws Exception {
        Map<String, Object> flash = runBlocked(request("  a@b.vn  "), new MockHttpServletResponse());

        assertThat(flash).isNotNull();
        assertThat(flash).containsOnlyKeys("email");
        assertThat(flash.get("email")).isEqualTo("a@b.vn");
        assertThat(((FlashMap) flash).getTargetRequestPath()).isEqualTo("/login");
        assertThat(flash.values().toString()).doesNotContain("secret-pass-123").doesNotContain("blocked");
    }

    @Test
    void onBlocked_capsFlashedEmailAt255Characters() throws Exception {
        Map<String, Object> flash = runBlocked(request("a".repeat(300)), new MockHttpServletResponse());

        assertThat((String) flash.get("email")).hasSize(255);
    }

    @Test
    void onBlocked_withoutEmail_createsNoSessionAtAll() throws Exception {
        MockHttpServletRequest request = request(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new LoginBlockedException());

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?blocked");
        assertThat(request.getSession(false)).as("a blocked attempt must not create a session").isNull();
    }

    @Test
    void onBlocked_neverStoresTheExceptionInTheSession_unlikeAWrongPassword() throws Exception {
        MockHttpServletRequest blocked = request("a@b.vn");
        handler.onAuthenticationFailure(blocked, new MockHttpServletResponse(), new LoginBlockedException());
        MockHttpServletRequest wrong = request("a@b.vn");
        handler.onAuthenticationFailure(wrong, new MockHttpServletResponse(), new BadCredentialsException("x"));

        // the session exists only because of the flash map of the typed email
        assertThat(blocked.getSession(false)).isNotNull();
        assertThat(blocked.getSession(false).getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
        assertThat(wrong.getSession(false).getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void onWrongPassword_afterPhase8_stillRedirectsToLoginErrorNotBlocked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("a@b.vn"), response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }
}

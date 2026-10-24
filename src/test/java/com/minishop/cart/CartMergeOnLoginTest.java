package com.minishop.cart;

import com.minishop.user.CurrentUser;
import com.minishop.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CartMergeOnLoginTest {

    @Mock
    CartService cartService;

    @InjectMocks
    CartMergeOnLogin listener;

    private final MockHttpSession session = new MockHttpSession();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static InteractiveAuthenticationSuccessEvent eventFor(Object principal) {
        Authentication auth = principal instanceof CurrentUser u
                ? UsernamePasswordAuthenticationToken.authenticated(u, null, u.getAuthorities())
                : new TestingAuthenticationToken(principal, null, "ROLE_USER");
        return new InteractiveAuthenticationSuccessEvent(auth, CartMergeOnLoginTest.class);
    }

    private static CurrentUser currentUser() {
        return new CurrentUser(42L, "u@cart.test", null, "U", Role.USER);
    }

    private void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private SessionCart guestCartInSession(long productId, int quantity) {
        SessionCart cart = new SessionCart();
        cart.put(productId, quantity);
        session.setAttribute(SessionCart.SESSION_ATTRIBUTE, cart);
        return cart;
    }

    /** BR-004-7 */
    @Test
    void onLogin_withGuestCart_mergesAndClearsSessionAttribute() {
        bindRequest();
        SessionCart cart = guestCartInSession(5L, 2);

        listener.onLogin(eventFor(currentUser()));

        verify(cartService).mergeGuestCart(42L, cart);
        assertThat(session.getAttribute(SessionCart.SESSION_ATTRIBUTE)).isNull();
    }

    /** BR-004-7 */
    @Test
    void onLogin_whenMergeThrows_doesNotThrowAndKeepsGuestCart() {
        bindRequest();
        SessionCart cart = guestCartInSession(5L, 2);
        doThrow(new IllegalStateException("db down")).when(cartService).mergeGuestCart(42L, cart);

        assertThatCode(() -> listener.onLogin(eventFor(currentUser()))).doesNotThrowAnyException();

        assertThat(session.getAttribute(SessionCart.SESSION_ATTRIBUTE)).isSameAs(cart);
    }

    /** QĐ5 */
    @Test
    void onLogin_whenPrincipalIsNotCurrentUser_isIgnored() {
        bindRequest();
        SessionCart cart = guestCartInSession(5L, 2);

        listener.onLogin(eventFor("someone"));

        verifyNoInteractions(cartService);
        assertThat(session.getAttribute(SessionCart.SESSION_ATTRIBUTE)).isSameAs(cart);
    }

    /** QĐ5 */
    @Test
    void onLogin_withoutRequestContext_isIgnored() {
        RequestContextHolder.resetRequestAttributes();

        assertThatCode(() -> listener.onLogin(eventFor(currentUser()))).doesNotThrowAnyException();

        verifyNoInteractions(cartService);
    }

    /** QĐ5 */
    @Test
    void onLogin_withEmptyGuestCart_onlyClearsIt() {
        bindRequest();
        session.setAttribute(SessionCart.SESSION_ATTRIBUTE, new SessionCart());

        listener.onLogin(eventFor(currentUser()));

        verifyNoInteractions(cartService);
        assertThat(session.getAttribute(SessionCart.SESSION_ATTRIBUTE)).isNull();
    }

    /** QĐ5: không có giỏ khách thì không làm gì, và không tạo session. */
    @Test
    void onLogin_withoutGuestCart_doesNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        listener.onLogin(eventFor(currentUser()));

        verifyNoInteractions(cartService);
        assertThat(request.getSession(false)).isNull();
    }
}

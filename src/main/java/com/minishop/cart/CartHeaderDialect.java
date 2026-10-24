package com.minishop.cart;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;
import org.thymeleaf.web.servlet.IServletWebExchange;

import java.util.Set;

/**
 * Provides {@code ${#cartHeader.quantity()}} for the layout header.
 *
 * <p>Why a dialect and not a {@code @ControllerAdvice} with {@code @ModelAttribute}: the object is only built
 * when a template actually uses it, so redirect-only POSTs cost nothing, and it also works for views rendered
 * by an {@code @ExceptionHandler} (such as the 404 page), whose model would not contain the advice attributes.
 *
 * <p>For a guest the count is read from the session with {@code getSession(false)}: no query, no
 * transaction and no session is created. For a customer it is one {@code sum} query, memoized per render.
 */
@Component
class CartHeaderDialect extends AbstractDialect implements IExpressionObjectDialect {

    private static final String CART_HEADER = "cartHeader";

    private final CartService cartService;

    CartHeaderDialect(CartService cartService) {
        super("Cart header");
        this.cartService = cartService;
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {
            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Set.of(CART_HEADER);
            }

            @Override
            public Object buildObject(IExpressionContext context, String expressionObjectName) {
                if (!CART_HEADER.equals(expressionObjectName)) {
                    return null;
                }
                HttpServletRequest request = null;
                if (context instanceof IWebContext webContext
                        && webContext.getExchange() instanceof IServletWebExchange exchange) {
                    request = (HttpServletRequest) exchange.getNativeRequestObject();
                }
                return new CartHeader(cartService, SecurityContextHolder.getContext().getAuthentication(), request);
            }

            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }
}

package com.minishop.cart;

import com.minishop.support.ErrorTestEndpoints;
import com.minishop.support.LiveClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * AC-007-6: the error page is still drawn when the query behind the header's cart count fails. {@code CartHeader}
 * swallows the failure (WARN, shows no count), so the page of a 500 does not turn into a second failure.
 *
 * <p>Has its own Spring context (the {@code CartService} spy is part of its cache key) and its own in-memory
 * database; it does not need Docker. The count query is made to fail by stubbing {@code totalQuantity}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:cart-header-error;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles("h2")
@Import(ErrorTestEndpoints.class)
@ExtendWith(OutputCaptureExtension.class)
class CartHeaderErrorPageTest {

    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^.*\\sERROR\\s+\\d+\\s---.*$");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    CartService cartService;

    @Value("${app.seed.user-password}")
    String userPassword;

    @BeforeEach
    void countQueryFails() {
        doThrow(new DataAccessResourceFailureException("db down")).when(cartService).totalQuantity(any(CartOwner.class));
    }

    private LiveClient userWithTwoItemsInCart() throws Exception {
        LiveClient user = new LiveClient(port).login("user@minishop.local", userPassword);
        Long productId = jdbc.queryForObject("select min(id) from products where active = true and stock >= 5",
                Long.class);
        HttpResponse<String> add = user.postForm("/cart/items",
                Map.of("productId", String.valueOf(productId), "quantity", "2"), true);
        assertThat(add.statusCode()).as("add to cart").isEqualTo(302);
        return user;
    }

    private void emptyUserCart() {
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, "user@minishop.local");
        jdbc.update("delete from cart_items where cart_id in (select id from carts where user_id = ?)", userId);
        jdbc.update("delete from carts where user_id = ?", userId);
    }

    @Test
    void serverErrorPage_whenCartCountQueryFails_isStillDrawnWithoutCount(CapturedOutput output) throws Exception {
        try {
            LiveClient user = userWithTwoItemsInCart();
            int offset = output.getAll().length();

            HttpResponse<String> response = user.get("/test/boom");

            String body = response.body();
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(body).contains("<title>Đã có lỗi xảy ra | Mini Shop</title>")
                    .contains(">Đã có lỗi xảy ra</h1>")
                    .contains(">Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.<")
                    .contains("href=\"/\">Về trang chủ</a>")
                    .contains(">Giỏ hàng</a>")
                    .doesNotContain("Giỏ hàng (", "Whitelabel", "db down", "DataAccessResourceFailureException");
            verify(cartService, atLeastOnce()).totalQuantity(any(CartOwner.class));
            String log = output.getAll().substring(offset);
            assertThat(log).as("the swallowed failure is logged").contains("Cart header count failed");
            assertThat(ERROR_LINE.matcher(log).results().count()).as("only the 500 itself is an ERROR").isEqualTo(1);
        } finally {
            emptyUserCart();
        }
    }

    @Test
    void anyPage_whenCartCountQueryFails_staysUpWithoutCountAndControlShowsTheCount() throws Exception {
        try {
            LiveClient user = userWithTwoItemsInCart();

            HttpResponse<String> broken = user.get("/products");

            assertThat(broken.statusCode()).isEqualTo(200);
            assertThat(broken.body()).contains(">Giỏ hàng</a>").doesNotContain("Giỏ hàng (");
            verify(cartService, atLeastOnce()).totalQuantity(any(CartOwner.class));

            // control: with the real query the same page shows the count, so the failure above really was the cause
            Mockito.reset(cartService);
            HttpResponse<String> healthy = user.get("/products");
            assertThat(healthy.body()).contains("Giỏ hàng (2)");
        } finally {
            emptyUserCart();
        }
    }
}

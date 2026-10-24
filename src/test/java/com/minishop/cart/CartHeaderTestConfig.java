package com.minishop.cart;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Mọi {@code @WebMvcTest} render layout phải {@code @Import} class này: header gọi {@code #cartHeader}
 * (QĐ7 plan GĐ4), dialect của nó cần bean {@link CartService}, mà {@code CartService} là package-private
 * nên test ở package khác không tự mock được. Mock trả 0, nên header là "Giỏ hàng".
 */
@TestConfiguration
public class CartHeaderTestConfig {

    @Bean
    CartService cartService() {
        return Mockito.mock(CartService.class);
    }
}

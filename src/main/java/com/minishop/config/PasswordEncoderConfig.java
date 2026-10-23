package com.minishop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Tách riêng khỏi SecurityConfig để test JPA import được encoder mà không cần dựng HttpSecurity. */
@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfig {

    /** Hash có dạng {@code {bcrypt}$2a$...}, nên sau này đổi thuật toán không phải băm lại hash cũ. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

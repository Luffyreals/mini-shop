package com.minishop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Bean {@code PasswordEncoder} (bcrypt qua DelegatingPasswordEncoder); tách riêng để test JPA import được. */
@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfig {

    /** Hash có dạng {@code {bcrypt}$2a$...}, nên sau này đổi thuật toán không phải băm lại hash cũ. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

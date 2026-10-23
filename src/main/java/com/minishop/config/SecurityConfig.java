package com.minishop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cấu hình bảo mật nền: chặn mặc định, chỉ mở các đường dẫn công khai (trang chủ, /products/**, tài nguyên tĩnh) (spec 001, AC-001-6).
 * <p>Phân quyền USER/ADMIN và trang đăng nhập riêng được thêm ở GĐ3.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/", "/products", "/products/**", "/error", "/favicon.ico",
            "/css/**", "/js/**", "/images/**", "/fonts/**", "/webjars/**", "/credits.html",
            "/actuator/health"
    };

    /** CSP bật thật (AC-001-16). {@code img-src data:} cần vì CSS Bootstrap nhúng icon SVG dạng data URI. */
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());
        return http.build();
    }

    /**
     * H2 console chỉ tồn tại ở profile {@code h2}. Console dùng iframe và form không có CSRF token,
     * nên cần chuỗi filter riêng, ưu tiên cao hơn và chỉ áp dụng cho {@code /h2-console/**}.
     */
    @Bean
    @Order(1)
    @Profile("h2")
    SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/h2-console/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }
}

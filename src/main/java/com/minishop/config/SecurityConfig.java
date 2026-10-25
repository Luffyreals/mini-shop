package com.minishop.config;

import com.minishop.user.LoginThrottle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.FlashMapManager;

import java.time.Clock;

/**
 * Cấu hình bảo mật nền: chặn mặc định, chỉ mở các đường dẫn công khai (trang chủ, /products/**, tài nguyên tĩnh) (spec 001, AC-001-6).
 * <p>GĐ3 thêm: trang đăng nhập riêng {@code /login}, phân quyền ({@code /admin/**} chỉ ADMIN, {@code /orders/**} và
 * {@code /checkout/**} cần đăng nhập) và trang 403 {@code /access-denied}. Đường dẫn công khai vẫn chỉ cho GET;
 * {@code /register} và {@code /access-denied} mở cho mọi method (CSRF vẫn áp dụng). Quy tắc theo vai trò đứng đầu.
 * <p>GĐ4 mở {@code /cart} và {@code /cart/**} cho mọi người và mọi method (khách cũng có giỏ), có CSRF.
 * <p>GĐ6 mở {@code GET /uploads/**} (ảnh sản phẩm do admin tải lên), đường dẫn công khai duy nhất của GĐ6 (BR-006-1).
 * <p>GĐ8 thêm giới hạn đăng nhập sai ở mọi profile: {@link LoginThrottle} (bean ở đây để mọi slice test import
 * {@code SecurityConfig} đều có), {@link LoginThrottleFilter} vừa chặn vừa đếm lần thử ngay trước bước kiểm mật khẩu,
 * và {@link LoginSuccessHandler} trả lại phần đã đếm khi đăng nhập đúng (ADR-0012).
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/", "/products", "/products/**", "/favicon.ico",
            "/css/**", "/js/**", "/images/**", "/fonts/**", "/webjars/**", "/credits.html",
            "/uploads/**", "/actuator/health"
    };

    /** CSP bật thật (AC-001-16). {@code img-src data:} cần vì CSS Bootstrap nhúng icon SVG dạng data URI. */
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    /** In-memory failed-login counter shared by the filter and the handlers; not configurable (AC-008-10). */
    @Bean
    LoginThrottle loginThrottle() {
        return new LoginThrottle(Clock.systemUTC());
    }

    @Bean
    SecurityFilterChain appSecurityFilterChain(HttpSecurity http, FlashMapManager flashMapManager,
                                               LoginThrottle throttle) throws Exception {
        LoginFailureHandler redirectOnFailure = new LoginFailureHandler(flashMapManager);
        http
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .requestMatchers("/orders", "/orders/**", "/checkout", "/checkout/**").authenticated()
                        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
                        // formLogin().permitAll() so khớp cả query string, và vì dùng failureHandler riêng nên nó
                        // không mở /login?error. Mở GET /login theo đường dẫn (bỏ qua query) để trang lỗi hiện được.
                        .requestMatchers(HttpMethod.GET, "/login").permitAll()
                        // Mọi method: forward (403) và error dispatch giữ nguyên method gốc, nên POST lỗi cũng phải
                        // tới được /access-denied và /error thay vì bị chuyển sang /login. CSRF vẫn áp dụng.
                        .requestMatchers("/register", "/access-denied", "/error").permitAll()
                        // GĐ4: giỏ hàng cho cả khách, mọi method; CSRF vẫn áp dụng (POST không token -> 403).
                        .requestMatchers("/cart", "/cart/**").permitAll()
                        .anyRequest().authenticated())
                // Sau CsrfFilter, trước bước kiểm mật khẩu; cổng đếm lần thử ngay (nguyên tử), nên lần bị chặn không bị tính thêm.
                .addFilterBefore(new LoginThrottleFilter(throttle, redirectOnFailure),
                        UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(new LoginSuccessHandler(throttle))
                        // Không cần đếm ở đây: LoginThrottleFilter đã đếm lần thử trước khi kiểm mật khẩu.
                        .failureHandler(redirectOnFailure)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/?logout").permitAll())
                .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"));
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

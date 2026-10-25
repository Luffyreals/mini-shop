package com.minishop.user;

import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-003-19, chống session fixation, trên Tomcat thật: đăng nhập phải đổi {@code JSESSIONID}, nên một mã phiên bị
 * cài sẵn trước khi đăng nhập không dùng được sau đó.
 *
 * <p>Không dùng MockMvc: {@code MockHttpSession} chỉ mô phỏng việc đổi mã phiên, và test cũ trên MockMvc đổi kết quả
 * theo thứ tự chạy test (lỗi khi chạy riêng lẻ) dù ứng dụng thật luôn đổi mã.
 */
@LiveServerTest
class SessionFixationTest {

    @LocalServerPort
    int port;

    @Value("${app.seed.user-password}")
    String userPassword;

    @Test
    void login_givesTheVisitorANewSessionId() throws Exception {
        LiveClient visitor = new LiveClient(port);
        visitor.get("/login");
        String before = visitor.sessionId();
        assertThat(before).as("GET /login opens a session for the CSRF token").isNotNull();

        visitor.login("user@minishop.local", userPassword);

        assertThat(visitor.sessionId()).isNotNull().isNotEqualTo(before);
    }

    @Test
    void oldSessionId_isNotLoggedInAfterTheVisitorLogsIn() throws Exception {
        LiveClient visitor = new LiveClient(port);
        visitor.get("/login");
        String planted = visitor.sessionId();
        visitor.login("user@minishop.local", userPassword);

        LiveClient attacker = new LiveClient(port).withSessionId(planted);

        var orders = attacker.get("/orders");
        assertThat(orders.statusCode()).as("the planted id gives no access").isEqualTo(302);
        assertThat(orders.headers().firstValue("Location").orElse("")).endsWith("/login");
        assertThat(visitor.get("/orders").statusCode()).as("the visitor's new session is logged in").isEqualTo(200);
    }
}

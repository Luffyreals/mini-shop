package com.minishop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chạy ở mọi nơi, không cần Docker: V1 chạy được trên H2 (PostgreSQL mode), Hibernate validate đạt,
 * seed chạy được (AC-001-13, AC-001-4 phía profile h2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class H2ProfileSmokeTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoads_withFlywayAndHibernateValidateOnH2() {
        assertThat(context).isNotNull();
    }

    @Test
    void healthEndpoint_isPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void h2ConsoleSecurityFilterChain_existsInH2Profile() {
        assertThat(context.containsBean("h2ConsoleSecurityFilterChain")).isTrue();
    }

    /**
     * AC-001-4 phía profile h2: lớp bảo mật cho qua {@code /h2-console}, không chuyển sang trang đăng nhập.
     * MockMvc chỉ chạy qua DispatcherServlet chứ không qua servlet riêng của H2 console, nên ở đây không
     * assert 200; việc console thật sự mở được nằm trong checklist của phase-verifier.
     */
    @Test
    void h2Console_isNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/h2-console/"))
                .andExpect(result -> assertThat(result.getResponse().getStatus() / 100).isNotEqualTo(3))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isNull());
    }

    @Test
    void seed_populatesProductsAndUsers() {
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isGreaterThanOrEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
    }

    /** AC-002-1, 2 trên H2: truy vấn Specification thật; 22 sản phẩm seed trừ 1 sản phẩm bị ẩn. */
    @Test
    void productList_isPublicAndCountsOnlyActiveSeedProducts() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tìm thấy 21 sản phẩm")));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tìm thấy 21 sản phẩm")));
    }

    /** AC-002-16: mọi image_path của dữ liệu mẫu trỏ tới file có thật trong static/. */
    @Test
    void seededImagePaths_pointToExistingStaticResources() {
        List<String> paths = jdbc.queryForList(
                "select image_path from products where image_path is not null", String.class);

        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path -> assertThat(new ClassPathResource("static" + path).exists())
                .as("static resource for %s", path).isTrue());
    }
}

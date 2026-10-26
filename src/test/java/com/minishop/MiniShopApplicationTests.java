package com.minishop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khởi động toàn bộ ứng dụng trên H2 trong bộ nhớ (ADR-0013); không cần Docker hay database cài sẵn.
 * Từ ADR-0014 context không phải web: không máy chủ, không servlet.
 */
@SpringBootTest
class MiniShopApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void context_isNotAWebApplicationContext() {
        String[] interfaces = Arrays.stream(ClassUtils.getAllInterfacesForClass(context.getClass()))
                .map(Class::getName).toArray(String[]::new);

        assertThat(interfaces).noneMatch(name -> name.endsWith("WebApplicationContext"));
        assertThat(context.getClass().getName()).doesNotContain("Web");
        assertThat(context.getEnvironment().getClass().getName()).doesNotContain("Web");
    }

    @Test
    void application_isDeducedAsNotWeb() {
        assertThat(new SpringApplication(MiniShopApplication.class).getWebApplicationType())
                .isEqualTo(WebApplicationType.NONE);
    }
}

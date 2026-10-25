package com.minishop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Khởi động toàn bộ ứng dụng trên H2 trong bộ nhớ (ADR-0013); không cần Docker hay database cài sẵn.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiniShopApplicationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpoint_isPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}

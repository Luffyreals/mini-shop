package com.minishop.storage;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import com.minishop.support.TestImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /uploads/**} is public, served from the upload directory only (AC-006-27, 31; BR-006-1).
 * Files that must never be served sit next to the upload directory, so a traversal bug would show their content.
 */
@WebMvcTest(UploadsServingTest.NoHandlers.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class UploadsServingTest {

    private static final String PNG_NAME = "3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png";
    private static final String JPG_NAME = "4f2b8c1e-aaaa-bbbb-cccc-0123456789ab.jpg";
    private static final String WEBP_NAME = "5f2b8c1e-aaaa-bbbb-cccc-0123456789ab.webp";
    private static final String SECRET = "TOP-SECRET-OUTSIDE-THE-UPLOAD-DIRECTORY";

    /** A controller without any mapping: the slice then holds only the security chain and the resource handler. */
    @Controller
    static class NoHandlers {
    }

    @TempDir
    static Path tempDir;

    static Path uploadDir;

    @BeforeAll
    static void createFiles() throws IOException {
        uploadDir = Files.createDirectory(tempDir.resolve("uploads"));
        Files.write(uploadDir.resolve(PNG_NAME), TestImages.png(64));
        Files.write(uploadDir.resolve(JPG_NAME), TestImages.jpeg(64));
        Files.write(uploadDir.resolve(WEBP_NAME), TestImages.webp(64));
        // Siblings of the upload directory: reachable only through a traversal.
        Files.writeString(tempDir.resolve("application.yml"), SECRET, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("x"), SECRET, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("secret.txt"), SECRET, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local.upload-dir", () -> tempDir.resolve("uploads").toString());
    }

    @Autowired
    MockMvc mockMvc;

    // ---------- served ----------

    /** AC-006-27: anonymous, right type, nosniff, exact bytes. */
    @Test
    void get_png_isServedToAnonymousWithTypeNosniffAndExactBytes() throws Exception {
        mockMvc.perform(get("/uploads/" + PNG_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(TestImages.png(64)));
    }

    @Test
    void get_jpg_isServedAsJpeg() throws Exception {
        mockMvc.perform(get("/uploads/" + JPG_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(TestImages.jpeg(64)));
    }

    @Test
    void get_webp_isServedAsWebp() throws Exception {
        mockMvc.perform(get("/uploads/" + WEBP_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(TestImages.webp(64)));
    }

    // ---------- not found ----------

    @ParameterizedTest
    @ValueSource(strings = {"/uploads/does-not-exist.png", "/uploads/00000000-0000-0000-0000-000000000000.jpg",
            "/uploads/", "/uploads/sub/", "/uploads/secret.txt"})
    void get_unknownName_is404(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    // ---------- traversal ----------

    /**
     * AC-006-31: every spelling of "go up one directory" is refused with a 4xx, and the body never holds the
     * content of the files that sit next to the upload directory.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/uploads/../application.yml",
            "/uploads/%2e%2e/application.yml",
            "/uploads/%2E%2E/application.yml",
            "/uploads/..%2fx",
            "/uploads/..%2Fx",
            "/uploads/%2e%2e%2fx",
            "/uploads/..%5capplication.yml",
            "/uploads/..\\application.yml",
            "/uploads/%252e%252e/application.yml",
            "/uploads/./../application.yml",
            "/uploads/sub/../../application.yml",
            "/uploads//../application.yml",
            "/uploads/....//application.yml",
            "/uploads/%00/../application.yml",
            "/uploads/..;/application.yml",
            "/uploads/../secret.txt"})
    void get_traversal_isRefusedAndNeverLeaksAFileOutsideTheUploadDirectory(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResponse().getStatus()).as(path).isBetween(400, 499);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRET);
    }

    @Test
    void uploadDirectory_isTheOneThatWasConfigured() {
        // Guards the guard: if the property were ignored the traversal tests above would prove nothing.
        assertThat(uploadDir).isDirectory();
        assertThat(tempDir.resolve("application.yml")).exists();
        assertThat(uploadDir.resolve("application.yml")).doesNotExist();
    }
}

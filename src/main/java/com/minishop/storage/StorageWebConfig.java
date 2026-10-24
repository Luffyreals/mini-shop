package com.minishop.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Phục vụ {@code GET /uploads/**} từ thư mục upload. Chỉ được phụ thuộc {@code @Value}: {@code @WebMvcTest} tự nạp
 * mọi {@code WebMvcConfigurer}, nên inject bean khác sẽ làm hỏng mọi slice test.
 */
@Configuration
class StorageWebConfig implements WebMvcConfigurer {

    private final String location;

    StorageWebConfig(@Value("${app.storage.local.upload-dir}") String uploadDir) {
        String uri = UploadRoot.resolve(uploadDir).toUri().toString();
        this.location = uri.endsWith("/") ? uri : uri + "/"; // thư mục chưa tồn tại thì toUri() không có "/" cuối
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}

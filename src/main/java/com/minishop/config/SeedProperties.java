package com.minishop.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Mật khẩu của tài khoản demo; thiếu cấu hình thì app báo lỗi ngay lúc khởi động. */
@ConfigurationProperties("app.seed")
@Validated
public record SeedProperties(@NotBlank String adminPassword, @NotBlank String userPassword) {

    /** Không để mật khẩu lọt ra log hay message lỗi qua toString() tự sinh của record. */
    @Override
    public String toString() {
        return "SeedProperties[adminPassword=***, userPassword=***]";
    }
}

package com.minishop.storage;

import java.nio.file.Path;

/** Một chỗ duy nhất đổi cấu hình thư mục upload thành đường dẫn tuyệt đối đã chuẩn hóa. */
final class UploadRoot {

    private UploadRoot() {
    }

    static Path resolve(String configured) {
        return Path.of(configured).toAbsolutePath().normalize();
    }
}

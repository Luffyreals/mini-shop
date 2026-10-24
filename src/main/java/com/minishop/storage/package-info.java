/**
 * Lưu trữ file ảnh sản phẩm qua interface {@code StorageService} (bản local ghi ra thư mục uploads/).
 * <p>API công khai: {@code StorageService}, {@code ImageUpload} (chỉ tạo được qua {@code ImageUpload.from}, đã kiểm
 * đuôi, content type, magic bytes và kích thước), {@code ImageFormat}, {@code InvalidImageException}.
 * {@code LocalStorageService} và {@code StorageWebConfig} là package-private; ảnh được phục vụ công khai tại
 * {@code /uploads/**} bằng resource handler (ADR-0010).
 * <p>Không phụ thuộc tính năng nào. GĐ8 thay {@code LocalStorageService} bằng bản đám mây.
 */
package com.minishop.storage;

/**
 * Lưu trữ file ảnh sản phẩm qua interface {@code StorageService} (bản local ghi ra thư mục uploads/).
 * <p>API công khai: {@code StorageService}, {@code ImageUpload} (chỉ tạo được qua {@code ImageUpload.from}, đã kiểm
 * đuôi, content type, magic bytes và kích thước), {@code ImageFormat}, {@code InvalidImageException}.
 * {@code LocalStorageService} là package-private. Ảnh đã upload được ghi ra đĩa; việc đổi đường dẫn thành URL mà
 * JavaFX nạp được là việc của D3 (ADR-0010, ADR-0014).
 * <p>Không phụ thuộc tính năng nào.
 */
package com.minishop.storage;

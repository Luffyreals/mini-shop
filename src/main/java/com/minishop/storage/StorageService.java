package com.minishop.storage;

/**
 * Nơi lưu ảnh sản phẩm. Bản local ghi ra thư mục uploads/; GĐ8 thay bằng bản đám mây mà không sửa nghiệp vụ.
 */
public interface StorageService {

    /**
     * Lưu một ảnh đã được kiểm tra. Khi đang có transaction, file bị xóa nếu transaction không commit.
     *
     * @return đường dẫn lưu vào {@code products.image_path}, dạng {@code /uploads/{uuid}.{jpg|png|webp}}
     */
    String store(ImageUpload image);

    /**
     * Xóa ảnh cũ sau khi transaction hiện tại commit (xóa ngay nếu không có transaction). Chỉ nhận đường dẫn do
     * {@link #store} tạo; ảnh seed, {@code null} và chuỗi lạ bị bỏ qua. Lỗi xóa chỉ được ghi log.
     */
    void deleteAfterCommit(String imagePath);

    /**
     * Đổi {@code image_path} thành URL có scheme ({@code file:} hoặc {@code jar:}) mà JavaFX nạp được. Chỉ nhận ảnh
     * seed ({@code /images/products/...}) và ảnh do {@link #store} tạo; không kiểm file upload có tồn tại.
     *
     * @return URL của ảnh, hoặc {@code null} khi không có ảnh hoặc giá trị không do ứng dụng tạo ra
     */
    String toUrl(String imagePath);
}

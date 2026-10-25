# Architecture Decision Records (ADR)

Mỗi quyết định kiến trúc quan trọng được ghi thành một file ngắn: bối cảnh, quyết định, hệ quả, và các phương án đã bị loại. Mục đích là để vài tháng sau (hoặc khi phỏng vấn) vẫn trả lời được câu hỏi "**vì sao** lại làm thế này?".

## Quy ước

- Tên file: `NNNN-tieu-de-ngan.md`, đánh số tăng dần và không bao giờ dùng lại số.
- ADR đã **Chấp nhận** thì không sửa nội dung. Muốn đổi quyết định thì viết ADR mới, rồi đánh dấu ADR cũ là *Bị thay thế bởi ADR-NNNN*.
- Dùng mẫu ở [template.md](template.md).

## Danh sách

| # | Quyết định | Trạng thái |
|---|---|---|
| [0001](0001-java-25-spring-boot-4.md) | Dùng Java 25 và Spring Boot 4.1 | Chấp nhận |
| [0002](0002-modular-monolith-package-by-feature.md) | Modular monolith, chia package theo tính năng | Chấp nhận |
| [0003](0003-server-side-rendering-thymeleaf.md) | Render phía server bằng Thymeleaf | Bị thay thế bởi ADR-0014 |
| [0004](0004-postgresql-flyway.md) | PostgreSQL ở mọi môi trường, Flyway quản lý schema | Bị thay thế bởi ADR-0013 |
| [0005](0005-atomic-stock-decrement.md) | Trừ tồn kho bằng câu UPDATE có điều kiện | Chấp nhận |
| [0006](0006-session-cart-merged-on-login.md) | Giỏ hàng: session cho khách, DB cho user, gộp khi đăng nhập | Chấp nhận (giỏ khách trong session bị thay thế bởi ADR-0014) |
| [0007](0007-session-form-login-current-user.md) | Đăng nhập bằng form trên session, principal riêng, email luôn chữ thường | Bị thay thế bởi ADR-0014 |
| [0008](0008-cart-writes-serialized-per-user.md) | Ghi giỏ hàng DB tuần tự theo từng người dùng bằng khóa dòng `users` | Chấp nhận |
| [0009](0009-order-placement-and-cancel-locking.md) | Đặt và hủy đơn: thứ tự khóa cố định, chuyển trạng thái đơn qua khóa dòng `orders` | Chấp nhận |
| [0010](0010-product-images-and-admin-stock-adjustment.md) | Ảnh sản phẩm qua `StorageService` tại `/uploads/**`; admin điều chỉnh tồn kho bằng câu UPDATE có điều kiện; khóa `categories` trước `products` | Chấp nhận (phục vụ `/uploads/**` bị thay thế bởi ADR-0014) |
| [0011](0011-centralized-error-handling.md) | Xử lý lỗi tập trung: trang lỗi qua `BasicErrorController`, một cơ chế 404, log 500 bằng resolver cuối chuỗi | Bị thay thế bởi ADR-0014 |
| [0012](0012-share-from-personal-machine-public-mode.md) | Chia sẻ app từ máy cá nhân qua Cloudflare Quick Tunnel; chế độ công khai và chạy PostgreSQL không Docker | Bị thay thế bởi ADR-0014 (phần database bởi ADR-0013) |
| [0013](0013-h2-file-database-no-docker.md) | Một database duy nhất: H2 nhúng lưu ra file trong `data/`, bỏ PostgreSQL, Docker và Testcontainers | Chấp nhận |
| [0014](0014-javafx-desktop-app.md) | Ứng dụng desktop JavaFX một tiến trình thay cho ứng dụng web | Chấp nhận |

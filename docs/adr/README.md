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
| [0003](0003-server-side-rendering-thymeleaf.md) | Render phía server bằng Thymeleaf | Chấp nhận |
| [0004](0004-postgresql-flyway.md) | PostgreSQL ở mọi môi trường, Flyway quản lý schema | Chấp nhận |
| [0005](0005-atomic-stock-decrement.md) | Trừ tồn kho bằng câu UPDATE có điều kiện | Chấp nhận |
| [0006](0006-session-cart-merged-on-login.md) | Giỏ hàng: session cho khách, DB cho user, gộp khi đăng nhập | Chấp nhận |
| [0007](0007-session-form-login-current-user.md) | Đăng nhập bằng form trên session, principal riêng, email luôn chữ thường | Chấp nhận |
| [0008](0008-cart-writes-serialized-per-user.md) | Ghi giỏ hàng DB tuần tự theo từng người dùng bằng khóa dòng `users` | Chấp nhận |
| [0009](0009-order-placement-and-cancel-locking.md) | Đặt và hủy đơn: thứ tự khóa cố định, chuyển trạng thái đơn qua khóa dòng `orders` | Chấp nhận |

# ADR-0003: Render phía server bằng Thymeleaf

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-23

## Bối cảnh

Cần giao diện cho khách hàng và admin. Có hai hướng: render phía server, hoặc REST API cộng với một SPA (React/Vue).

## Quyết định

Dùng **Thymeleaf + Bootstrap 5.3**, render phía server, không có bước build JavaScript. Đăng nhập dựa trên session, bật CSRF.

## Hệ quả

- Tích cực: một dự án, một lần deploy; bảo mật bằng session + CSRF đơn giản và an toàn; trang sản phẩm tốt cho SEO ngay từ đầu; người học tập trung đi sâu vào Spring.
- Đánh đổi: tương tác kém mượt hơn SPA, vì mỗi thao tác tải lại trang. Chấp nhận được với một cửa hàng nhỏ.

## Phương án đã cân nhắc

- **REST + React SPA**: hai dự án; phải xử lý CORS, JWT và refresh token; khối lượng gần gấp đôi. Nếu cần thể hiện kỹ năng REST, có thể thêm `/api/...` dùng lại service hiện có.
- **Thymeleaf + htmx**: mượt hơn mà vẫn nhẹ, nhưng thêm một khái niệm mới. Để dành cho sau này nếu cần.

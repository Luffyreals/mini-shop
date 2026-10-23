# ADR-0002: Modular monolith, chia package theo tính năng

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-23

## Bối cảnh

Một người phát triển, một miền nghiệp vụ nhỏ (sản phẩm, giỏ hàng, đơn hàng, người dùng). Cần cấu trúc dễ đọc, dễ mở rộng, và thể hiện được tư duy thiết kế.

## Quyết định

- Một ứng dụng Spring Boot duy nhất. Package gốc `com.minishop` chia theo tính năng: `catalog`, `cart`, `order`, `user`, `admin`, `storage`, `common`, `config`.
- Trong mỗi tính năng phân lớp controller → service → repository. Nghiệp vụ chỉ nằm trong service có `@Transactional`.
- Hướng phụ thuộc một chiều: `order → cart → catalog`. Package `admin` chỉ chứa tầng web và gọi service của các tính năng khác.
- Form và DTO là `record`; entity không bao giờ được bind trực tiếp từ request.

## Hệ quả

- Tích cực: sửa một tính năng chỉ cần mở một package; ranh giới rõ ràng nên sau này dễ thêm REST API hoặc tách module.
- Đánh đổi: phải tự giữ kỷ luật về hướng phụ thuộc (kiểm tra bằng review), vì Java không tự chặn.

## Phương án đã cân nhắc

- **Chia package theo lớp** (`controller/`, `service/`…): code của một tính năng nằm rải rác, khó thấy ranh giới.
- **Microservices**: thêm network, triển khai và dữ liệu phân tán mà không giải quyết vấn đề nào của dự án này.
- **Spring Modulith**: kiểm tra ranh giới module tự động, nhưng thêm phụ thuộc và khái niệm mới. Có thể cân nhắc sau.

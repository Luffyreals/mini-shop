# ADR-0004: PostgreSQL ở mọi môi trường, Flyway quản lý schema

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-23

## Bối cảnh

Yêu cầu ban đầu là H2 khi phát triển và MySQL khi chạy thật. Hai DB khác nhau dễ sinh lỗi kiểu "chạy trên H2 nhưng hỏng trên DB thật", nhất là với câu SQL trừ kho. Máy phát triển có Docker.

## Quyết định

- **PostgreSQL** ở mọi môi trường. Dev dùng `spring-boot-docker-compose`, tự bật Postgres khi chạy `./mvnw spring-boot:run`. Test dùng Testcontainers. Prod dùng Postgres được quản lý.
- Giữ profile **`h2`** (chế độ tương thích PostgreSQL) làm phương án dự phòng cho máy không có Docker.
- **Flyway** quản lý schema; `spring.jpa.hibernate.ddl-auto=validate`. Migration đã commit thì không bao giờ sửa.

## Hệ quả

- Tích cực: dev, test và prod chạy cùng một loại DB; lịch sử schema được version hóa và review được như code.
- Đánh đổi: chạy app cần Docker; SQL migration phải viết sao cho chạy được cả trên H2.

## Phương án đã cân nhắc

- **H2 khi dev + MySQL khi chạy thật**: lệch hành vi giữa hai DB; MySQL ít gói host miễn phí hơn khi cần demo online.
- **`ddl-auto=update`**: tiện lúc đầu nhưng không kiểm soát được thay đổi, không an toàn cho dữ liệu thật.

# ADR-0001: Dùng Java 25 và Spring Boot 4.1

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-23

## Bối cảnh

Yêu cầu ban đầu là Java 17 và Spring Boot 3.x. Tháng 10/2026, start.spring.io chỉ còn tạo được project Spring Boot 4.0.x và 4.1.x; dòng 3.x đã hết hỗ trợ miễn phí (OSS). Máy phát triển đã cài sẵn JDK 25.

## Quyết định

Dùng **Java 25 (LTS)** và **Spring Boot 4.1.x**. Phiên bản thư viện để BOM của Spring Boot quản lý.

## Hệ quả

- Tích cực: nhận bản vá bảo mật; dùng được record, pattern matching, virtual threads; dự án hợp thời khi đưa vào portfolio.
- Đánh đổi: phần lớn tutorial trên mạng vẫn viết cho Boot 3 / Security 6, nên khi gặp API khác phải tra tài liệu chính thức thay vì chép code cũ.

## Phương án đã cân nhắc

- **Java 17 + Boot 3.5**: đúng yêu cầu gốc, nhưng bắt đầu dự án mới trên dòng đã hết hỗ trợ.
- **Java 21**: vẫn là LTS, nhưng không có lợi thế gì so với 25 khi máy đã cài sẵn 25.

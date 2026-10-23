# Changelog

Mọi thay đổi đáng chú ý của dự án được ghi ở đây.
Định dạng theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/); mỗi giai đoạn hoàn thành được gắn tag `v0.<giai đoạn>.0`.

## [Unreleased]

## [0.2.0] - 2026-10-23

GĐ2: danh sách và chi tiết sản phẩm ([spec 002](docs/specs/002-product-catalog.md)).

### Thêm
- Trang danh sách sản phẩm (`/`, `/products`): tìm theo tên (không phân biệt hoa thường), lọc theo danh mục, sắp xếp mới nhất / giá tăng / giá giảm, phân trang 12 sản phẩm; tham số sai tự quay về mặc định.
- Trang chi tiết sản phẩm (`/products/{id}`) và trang 404 "Không tìm thấy sản phẩm" (kể cả sản phẩm bị ẩn và id không hợp lệ).
- Truy vấn bằng Spring Data `Specification` + `@EntityGraph` (không có N+1), `ProductService` chỉ đọc, DTO cho view.
- Helper Thymeleaf `#vnd` định dạng giá `1.250.000 ₫`; `PageNav` dựng link phân trang giữ nguyên bộ lọc.
- Giao diện storefront theo hướng thiết kế "The specimen catalogue" (skill `design-taste-frontend`): layout và fragment dùng chung, font Bricolage Grotesque tự host, ảnh thay thế khi sản phẩm không có ảnh.
- 19 ảnh sản phẩm mẫu có giấy phép mở và trang ghi nguồn `/credits.html`.
- Test: truy vấn trên PostgreSQL (tìm có dấu, lọc, sắp xếp ổn định, N+1), service, controller, định dạng giá, phân trang, quét template tuân thủ CSP.

### Thay đổi
- Trang chủ `/` giờ là danh sách sản phẩm (tiêu đề tab `Sản phẩm | Mini Shop`), thay cho trang tạm của GĐ1.

## [0.1.0] - 2026-10-23

GĐ1: nền móng dự án ([spec 001](docs/specs/001-project-foundation.md)).

### Thêm
- Quy trình đặc tả: `docs/specs/` (template, spec 001) và skill `/write-spec`.
- Khung dự án Spring Boot 4.1.1 / Java 25: Maven Wrapper, profile mặc định (PostgreSQL qua Docker Compose) và profile `h2`, các package theo tính năng, cấu hình bảo mật nền, trang chủ tạm, Bootstrap qua WebJars.
- Test khởi động app với Testcontainers, test MockMvc cho trang chủ và bảo mật.
- GitHub Actions CI (`./mvnw verify`) và Dependabot cho Maven, Actions, Docker Compose.
- Migration Flyway `V1` cho 7 bảng (users, categories, products, carts, cart_items, orders, order_items), với ràng buộc CHECK, UNIQUE, khóa ngoại và index.
- Entity, enum và repository cho các aggregate root; `ProductRepository.decrementStock` trừ kho nguyên tử.
- Dữ liệu mẫu (`DataInitializer`): 5 danh mục, 22 sản phẩm, 2 tài khoản demo với mật khẩu băm BCrypt; mật khẩu cấu hình qua `app.seed.*`.
- Test: migration và ràng buộc, trừ kho, dữ liệu mẫu (trên PostgreSQL thật), smoke test profile `h2`, test kiến trúc bằng ArchUnit, test header bảo mật.
- Header Content-Security-Policy (`default-src 'self'`).
- Báo cáo độ phủ test JaCoCo; CI đính kèm báo cáo và bảng tổng kết test.
- Bản mô tả dự án, kiến trúc và lộ trình 8 giai đoạn (`docs/project-brief.md`).
- Architecture Decision Records 0001–0006 (`docs/adr/`).
- `CLAUDE.md` và 8 subagent trong `.claude/agents/`.
- Cấu hình repo: `.gitattributes`, `.editorconfig`, template cho PR và issue, `CONTRIBUTING.md`.
- `.claude/settings.json`: chặn Claude Code đọc file bí mật (`.env`, khóa, keystore).

### Thay đổi
- CI fail khi có test bị bỏ qua; `persist-credentials: false`; chỉ hủy lượt chạy cũ trên PR.
- `spring-boot-h2console` chuyển sang scope `runtime`.

### Bảo mật
- Bật Dependabot alerts và Dependabot security updates trên GitHub.
- Chỉ cho phép squash merge; tự xóa nhánh sau khi merge.
- Commit dùng email noreply của GitHub thay cho email cá nhân.

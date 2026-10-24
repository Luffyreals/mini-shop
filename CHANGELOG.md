# Changelog

Mọi thay đổi đáng chú ý của dự án được ghi ở đây.
Định dạng theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/); mỗi giai đoạn hoàn thành được gắn tag `v0.<giai đoạn>.0`.

## [Unreleased]

GĐ4: giỏ hàng ([spec 004](docs/specs/004-shopping-cart.md)).

### Thêm
- Nút "Thêm vào giỏ" kèm ô số lượng ở trang chi tiết sản phẩm (chỉ khi còn hàng).
- Trang giỏ hàng `/cart`: đơn giá, thành tiền, tổng cộng theo giá hiện tại; sửa số lượng, xóa dòng; nhãn "Sản phẩm đã ngừng bán", "Hết hàng", "Chỉ còn {n} sản phẩm".
- Giỏ trong session cho khách vãng lai, giỏ trong DB cho người đã đăng nhập; gộp giỏ session vào giỏ DB khi đăng nhập.
- Link "Giỏ hàng (n)" trên header ở mọi trang.
- Số lượng mỗi dòng từ 1 đến 99 và không vượt tồn kho; vượt thì được đặt bằng giới hạn kèm thông báo.
- ADR-0008: ghi giỏ hàng DB tuần tự theo từng người dùng bằng khóa dòng `users`.

### Bảo mật
- Mọi form giỏ là POST có CSRF; giỏ luôn xác định từ người đang đăng nhập hoặc session, không có id giỏ trong URL.
- Hai yêu cầu thêm giỏ đồng thời của cùng một người không gây lỗi và không mất số lượng.

## [0.3.0] - 2026-10-24

GĐ3: đăng ký, đăng nhập và phân quyền ([spec 003](docs/specs/003-authentication.md)).

### Thêm
- Trang đăng ký `/register`: họ tên, email, mật khẩu (8–64 ký tự, có chữ và số, tối đa 72 byte), nhập lại mật khẩu; tài khoản mới luôn là `USER`; báo lỗi theo từng ô, không điền lại mật khẩu.
- Trang đăng nhập `/login` riêng, đăng nhập bằng email không phân biệt hoa thường; đăng xuất bằng POST; sau khi đăng nhập quay lại trang đã yêu cầu.
- Phân quyền: `/admin/**` chỉ cho `ADMIN` (người dùng thường nhận trang 403), `/orders/**` và `/checkout/**` cần đăng nhập; trang `/admin` tạm.
- Header hiển thị theo trạng thái đăng nhập: "Xin chào, {họ tên}", nút đăng xuất, link "Quản trị" cho admin.
- Migration V2: ràng buộc `ck_users_email_lowercase` bảo đảm email luôn chữ thường.
- ADR-0007: đăng nhập bằng form trên session, principal riêng `CurrentUser`, email luôn chữ thường.

### Thay đổi
- Spring Security dùng người dùng trong DB thay cho tài khoản tự sinh (không còn dòng "Using generated security password").

### Bảo mật
- Chống tự cấp quyền khi đăng ký (form không có trường vai trò), CSRF cho mọi form, đổi session ID sau khi đăng nhập, thông báo đăng nhập sai không tiết lộ email có tồn tại hay không, hash mật khẩu bị xóa khỏi phiên sau khi đăng nhập.
- Hai yêu cầu đăng ký cùng email đồng thời chỉ tạo một tài khoản (unique constraint của DB), không gây lỗi 500.

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

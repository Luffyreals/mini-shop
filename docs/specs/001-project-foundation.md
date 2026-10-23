# Spec 001: Nền móng dự án

- **Giai đoạn:** GĐ1
- **Trạng thái:** Nháp
- **Liên quan:** brief §3, §6, §11; ADR-0001, 0002, 0004, 0005

## 1. Mục tiêu

Người phát triển (và CI) clone repo về là build, test và chạy được ngay. Ứng dụng có đủ schema, entity, repository và dữ liệu mẫu, để từ GĐ2 trở đi chỉ việc xây tính năng lên trên.

## 2. Phạm vi

**Trong phạm vi**
- Khung Maven (Spring Boot 4.1.1, Java 25, Maven Wrapper) và cấu trúc package theo tính năng.
- Profile mặc định chạy PostgreSQL qua Docker Compose; profile `h2` cho máy không có Docker.
- CI bằng GitHub Actions và Dependabot.
- Migration Flyway `V1` cho toàn bộ 7 bảng; entity và repository tương ứng.
- Dữ liệu mẫu (seed) và câu lệnh trừ kho nguyên tử, kèm test.
- Cấu hình bảo mật nền: chặn mặc định, chỉ mở các đường dẫn công khai.

**Ngoài phạm vi**
- Thiết kế giao diện (GĐ2, dùng skill `design-taste-frontend`). GĐ1 chỉ có trang chủ tạm.
- Trang đăng nhập, đăng ký và phân quyền theo vai trò (GĐ3).
- Service nghiệp vụ cho giỏ hàng và đơn hàng (GĐ4–5). GĐ1 chỉ có repository.
- Dockerfile và deploy (GĐ8).

## 3. User stories

- **US-001-1:** Là người phát triển, tôi muốn chạy app bằng một lệnh để bắt đầu làm việc ngay.
- **US-001-2:** Là người review, tôi muốn CI tự build và test mỗi PR để biết `main` luôn chạy được.
- **US-001-3:** Là người phát triển, tôi muốn có sẵn dữ liệu mẫu và tài khoản demo để thử mọi luồng mà không phải nhập tay.

## 4. Tiêu chí chấp nhận

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-001-1 | Repo vừa clone, có JDK 25 và Docker | chạy `./mvnw verify` | build xanh, mọi test chạy (kể cả Testcontainers) | CI |
| AC-001-2 | Máy không có Docker | chạy `./mvnw verify` | build vẫn xanh; các test Testcontainers được **bỏ qua** (skipped) chứ không fail | checklist |
| AC-001-3 | Docker đang chạy | chạy `./mvnw spring-boot:run` | Postgres tự khởi động; `GET /actuator/health` trả `{"status":"UP"}` | checklist |
| AC-001-4 | Không có Docker | chạy với `-Dspring-boot.run.profiles=h2` | app chạy; `/h2-console` mở được. Ở profile mặc định, `/h2-console` **không** tồn tại | checklist |
| AC-001-5 | Khách chưa đăng nhập | mở `/` | HTTP 200, trang có tiêu đề "Mini Shop" | `HomeControllerTest` |
| AC-001-6 | Khách chưa đăng nhập | mở một đường dẫn không công khai (ví dụ `/orders`) | bị chuyển hướng sang trang đăng nhập, không lộ nội dung | `SecurityConfigTest` |
| AC-001-7 | Push hoặc mở PR vào `main` | GitHub Actions chạy | job `build` chạy `./mvnw -B verify` và báo kết quả lên PR | CI |
| AC-001-8 | DB trống | app khởi động | Flyway tạo đủ 7 bảng với các ràng buộc ở mục 7; Hibernate `validate` không báo lỗi | `SchemaMigrationTest` |
| AC-001-9 | DB trống | app khởi động | seed tạo 5 danh mục, ít nhất 20 sản phẩm (có ít nhất 1 sản phẩm hết hàng và 1 sản phẩm bị ẩn), 1 ADMIN và 1 USER với mật khẩu băm BCrypt | `DataInitializerTest` |
| AC-001-10 | DB đã có dữ liệu | app khởi động lại | seed **không** chạy lại, không tạo bản ghi trùng | `DataInitializerTest` |
| AC-001-11 | Sản phẩm có tồn kho 5 | trừ 3 bằng `decrementStock` | trả về 1 dòng bị ảnh hưởng; tồn kho còn 2 | `ProductRepositoryTest` |
| AC-001-12 | Sản phẩm có tồn kho 2 | trừ 3 bằng `decrementStock` | trả về 0 dòng; tồn kho vẫn là 2 | `ProductRepositoryTest` |

## 5. Quy tắc nghiệp vụ

- **BR-001-1:** Tồn kho không bao giờ âm; DB có ràng buộc `CHECK (stock >= 0)` (ADR-0005).
- **BR-001-2:** Mật khẩu chỉ lưu dạng hash. Mật khẩu demo đọc từ cấu hình `app.seed.*` và chỉ dùng ở môi trường dev.
- **BR-001-3:** Không ai được sửa migration đã commit; mọi thay đổi schema đi vào file `V<n+1>__…sql` mới.

## 6. Giao diện và luồng

| URL | Phương thức | Quyền | Mô tả |
|---|---|---|---|
| `/` | GET | Mọi người | Trang chủ tạm: "Mini Shop" và dòng "Cửa hàng đang được xây dựng". GĐ2 thay bằng danh sách sản phẩm. |
| `/css/**`, `/js/**`, `/images/**`, `/webjars/**` | GET | Mọi người | Tài nguyên tĩnh |
| `/actuator/health` | GET | Mọi người | Kiểm tra app còn sống |
| `/h2-console/**` | GET, POST | Mọi người, **chỉ ở profile `h2`** | Xem DB khi dev |
| Mọi đường dẫn khác | * | Phải đăng nhập | Chặn mặc định |

## 7. Dữ liệu

Migration `V1__init_schema.sql`. Tên bảng ở dạng số nhiều để tránh từ khóa SQL (`user`, `order`).

| Bảng | Cột chính | Ràng buộc |
|---|---|---|
| `users` | id, email, password_hash, full_name, phone, role, created_at | `email` unique; `role` ∈ {USER, ADMIN} |
| `categories` | id, name, description | `name` unique |
| `products` | id, category_id, name, description, price, stock, image_path, active, created_at, updated_at | FK category; `price >= 0`; `stock >= 0`; index trên `category_id`, `name` |
| `carts` | id, user_id, updated_at | `user_id` unique, FK |
| `cart_items` | id, cart_id, product_id, quantity | unique (cart_id, product_id); `quantity > 0` |
| `orders` | id, user_id, status, receiver_name, phone, shipping_address, note, payment_method, total_amount, created_at, updated_at | FK user; `status` ∈ {PENDING, CONFIRMED, SHIPPING, DELIVERED, CANCELLED}; index trên `user_id`, `status`, `created_at` |
| `order_items` | id, order_id, product_id, product_name, unit_price, quantity | FK; `quantity > 0`; `unit_price >= 0` |

- Tiền dùng `numeric(12,0)` (VNĐ không có phần lẻ), ánh xạ sang `BigDecimal`.
- Thời gian dùng `timestamp with time zone`, ánh xạ sang `Instant`.
- Khóa chính dùng `bigint generated by default as identity`.

## 8. Yêu cầu phi chức năng

- `spring.jpa.open-in-view=false`, `ddl-auto=validate`.
- Bootstrap nạp qua **WebJars**, không dùng CDN: chạy được khi offline, phiên bản do Maven và Dependabot quản lý.
- Ảnh Postgres trong `compose.yaml` và trong test được ghim phiên bản (`postgres:17-alpine`), không dùng `latest`.

## 9. Câu hỏi mở

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Ảnh cho sản phẩm mẫu lấy ở đâu? | GĐ1 dùng ảnh placeholder; đến GĐ2 skill thiết kế mới chọn ảnh thật có giấy phép miễn phí. |
| 2 | Có bỏ qua test Testcontainers khi máy không có Docker không? | Có, bỏ qua ở local; CI luôn chạy đủ. |
| 3 | Đặt tên bảng ở dạng số nhiều? | Có, như mục 7. |
| 4 | Nạp Bootstrap qua WebJars hay CDN? | WebJars, như mục 8. |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-23 | Tạo bản nháp. Khung dự án (AC-001-1 đến AC-001-7) được làm trước trên nhánh `phase-1/project-skeleton`. |

# Mini Shop

Website bán hàng mini viết bằng Java / Spring Boot, render phía server. Đây là dự án học tập và portfolio, **không bán hàng thật**.

> 🚧 **Đang phát triển.** Đã xong GĐ1 (`v0.1.0`, nền móng), GĐ2 (`v0.2.0`, danh sách và chi tiết sản phẩm tại http://localhost:8080) và GĐ3 (`v0.3.0`, đăng ký, đăng nhập, phân quyền). Tiếp theo: GĐ4 giỏ hàng. Tiến độ xem ở mục [Lộ trình](#lộ-trình).

## Tính năng (MVP)

**Khách hàng**
- Xem sản phẩm: phân trang, tìm theo tên, lọc theo danh mục, sắp xếp theo giá; trang chi tiết sản phẩm
- Giỏ hàng: lưu trong session khi chưa đăng nhập, gộp vào tài khoản khi đăng nhập
- Đăng ký / đăng nhập
- Đặt hàng thanh toán khi nhận hàng (COD), xem lịch sử đơn, tự hủy đơn đang chờ xử lý

**Quản trị (`/admin`)**
- Dashboard: số đơn hôm nay, doanh thu, số đơn chờ xử lý
- Quản lý danh mục và sản phẩm (có upload ảnh)
- Quản lý đơn hàng: lọc theo trạng thái, đổi trạng thái; hủy đơn thì tự hoàn lại tồn kho

## Công nghệ

| | |
|---|---|
| Ngôn ngữ | Java 25 |
| Framework | Spring Boot 4.1: Web MVC, Data JPA, Security, Validation |
| Giao diện | Thymeleaf, Bootstrap 5.3 |
| Cơ sở dữ liệu | PostgreSQL, Flyway (H2 làm phương án dự phòng) |
| Kiểm thử | JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers |
| Build | Maven Wrapper, Docker Compose |

Lý do chọn từng công nghệ: xem [docs/adr](docs/adr/README.md).

## Kiến trúc

Modular monolith: chia package theo tính năng (`catalog`, `cart`, `order`, `user`, `admin`…). Trong mỗi tính năng phân lớp controller → service → repository. Một vài điểm thiết kế đáng chú ý:

- Trừ tồn kho bằng câu `UPDATE` nguyên tử nên không bán vượt kho khi nhiều người đặt cùng lúc ([ADR-0005](docs/adr/0005-atomic-stock-decrement.md))
- Đơn hàng lưu lại tên và giá sản phẩm tại thời điểm mua
- Luồng trạng thái đơn được kiểm soát chặt: `PENDING → CONFIRMED → SHIPPING → DELIVERED`, hoặc `CANCELLED`

Chi tiết: [docs/project-brief.md](docs/project-brief.md).

## Bắt đầu nhanh

Yêu cầu: **JDK 25** và **Docker Desktop đang chạy**. Không cần cài Maven vì dự án dùng Maven Wrapper; lần chạy đầu sẽ tự tải Maven về.

```bash
./mvnw spring-boot:run
```

Mở http://localhost:8080. PostgreSQL được Spring Boot tự bật từ `compose.yaml`.

| Việc cần làm | Lệnh |
|---|---|
| Chạy khi **không có Docker** (H2 trong bộ nhớ, có `/h2-console`) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2` |
| Build và chạy toàn bộ test | `./mvnw verify` |
| Chạy một lớp test | `./mvnw test -Dtest=HomeControllerTest` |

| Kiểm tra không có test nào bị bỏ qua (CI cũng chạy bước này) | `bash .github/scripts/check-no-skipped-tests.sh` |
| Xem độ phủ test | mở `target/site/jacoco/index.html` sau khi chạy `./mvnw verify` |

Trên Windows PowerShell, thay `./mvnw` bằng `.\mvnw.cmd`. Máy không bật Docker thì các test dùng Testcontainers sẽ được bỏ qua; CI luôn chạy đủ và báo lỗi nếu có test bị bỏ qua.

### Tài khoản demo

Được tạo tự động khi app khởi động với DB trống. **Chỉ dùng cho môi trường dev.**

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Quản trị (ADMIN) | `admin@minishop.local` | `Admin@123` |
| Khách hàng (USER) | `user@minishop.local` | `User@123` |

Đổi mật khẩu demo bằng biến môi trường `SEED_ADMIN_PASSWORD` và `SEED_USER_PASSWORD`.

Đăng nhập ở http://localhost:8080/login. Tạo tài khoản mới (luôn là `USER`) ở http://localhost:8080/register: mật khẩu 8–64 ký tự, có cả chữ và số. Chỉ tài khoản `ADMIN` vào được `/admin`.

Dữ liệu mẫu gồm 5 danh mục và 22 sản phẩm, trong đó có 1 sản phẩm hết hàng và 1 sản phẩm bị ẩn, để thử các trường hợp đặc biệt. 19 sản phẩm có ảnh; 3 sản phẩm cố ý không có ảnh để kiểm tra ảnh thay thế.

> **Dữ liệu mẫu chỉ được tạo khi DB trống.** PostgreSQL dev lưu dữ liệu trong volume Docker `mini-shop_postgres-data`, nên sau khi dữ liệu mẫu thay đổi (ví dụ thêm ảnh ở GĐ2), hãy xóa volume để seed lại:
>
> ```bash
> docker compose down -v
> ```
>
> Profile `h2` luôn seed lại vì dùng DB trong bộ nhớ.

### Ảnh và font: nguồn và giấy phép

Ảnh sản phẩm là ảnh minh họa có giấy phép mở (CC0, public domain, CC BY, CC BY-SA), **không phải ảnh của sản phẩm thật**. Danh sách đầy đủ (tác giả, giấy phép, link gốc) nằm ở trang `/credits.html` của ứng dụng, có link "Nguồn ảnh" ở footer mọi trang. Font **Bricolage Grotesque** dùng giấy phép SIL OFL 1.1 (`static/fonts/OFL.txt`). Muốn thêm hoặc đổi ảnh, cập nhật cả `static/credits.html`.

## Cấu trúc repo

```
mini-shop/
├── src/main/java/com/minishop/
│   ├── catalog/ cart/ order/ user/   Các tính năng nghiệp vụ
│   ├── admin/                        Tầng web của khu quản trị
│   └── storage/ common/ config/      Lưu file, thành phần dùng chung, cấu hình
├── src/main/resources/  application*.yml, db/migration (Flyway), templates, static
├── compose.yaml         PostgreSQL cho dev
├── docs/
│   ├── project-brief.md Phạm vi, kiến trúc, lộ trình
│   ├── specs/           Đặc tả tính năng và tiêu chí chấp nhận
│   ├── adr/             Các quyết định kiến trúc
│   └── plans/           Kế hoạch chi tiết từng giai đoạn
├── .github/             CI, Dependabot, template PR và issue
├── .claude/             Subagent, skill /write-spec, cấu hình Claude Code
├── CLAUDE.md            Hướng dẫn cho Claude Code
├── CONTRIBUTING.md      Quy trình nhánh, commit, phát hành
└── CHANGELOG.md
```

## Lộ trình

- [x] GĐ1: Khởi tạo project, CI, entity, migration, dữ liệu mẫu (`v0.1.0`)
- [x] GĐ2: Danh sách và chi tiết sản phẩm (`v0.2.0`)
- [x] GĐ3: Đăng ký, đăng nhập, phân quyền (`v0.3.0`)
- [ ] GĐ4: Giỏ hàng
- [ ] GĐ5: Đặt hàng và lịch sử đơn
- [ ] GĐ6: Trang quản trị
- [ ] GĐ7: Hoàn thiện (validation, xử lý lỗi, giao diện, tài liệu)
- [ ] GĐ8: Deploy online *(tùy chọn)*

## Đóng góp

Xem [CONTRIBUTING.md](CONTRIBUTING.md).

## Giấy phép

[MIT](LICENSE)

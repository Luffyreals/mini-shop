# Mini Shop

Website bán hàng mini viết bằng Java / Spring Boot, render phía server. Đây là dự án học tập và portfolio, **không bán hàng thật**.

> 🚧 **Đang phát triển (GĐ1).** Nền móng đã xong: có schema, dữ liệu mẫu và tài khoản demo. Các trang mua hàng bắt đầu từ GĐ2. Tiến độ xem ở mục [Lộ trình](#lộ-trình).

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

Đổi mật khẩu demo bằng biến môi trường `SEED_ADMIN_PASSWORD` và `SEED_USER_PASSWORD`. Trang đăng nhập riêng có từ GĐ3.

Dữ liệu mẫu gồm 5 danh mục và 22 sản phẩm, trong đó có 1 sản phẩm hết hàng và 1 sản phẩm bị ẩn, để thử các trường hợp đặc biệt.

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

- [ ] GĐ1: Khởi tạo project, CI, entity, migration, dữ liệu mẫu
- [ ] GĐ2: Danh sách và chi tiết sản phẩm
- [ ] GĐ3: Đăng ký, đăng nhập, phân quyền
- [ ] GĐ4: Giỏ hàng
- [ ] GĐ5: Đặt hàng và lịch sử đơn
- [ ] GĐ6: Trang quản trị
- [ ] GĐ7: Hoàn thiện (validation, xử lý lỗi, giao diện, tài liệu)
- [ ] GĐ8: Deploy online *(tùy chọn)*

## Đóng góp

Xem [CONTRIBUTING.md](CONTRIBUTING.md).

## Giấy phép

[MIT](LICENSE)

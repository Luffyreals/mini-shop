# Kiến trúc Mini Shop

Tài liệu cho người muốn đọc code: công nghệ, cách chia package, các bảng, các quyết định thiết kế và cấu trúc repo. Cách chạy app xem [README](../README.md).

## Công nghệ

| | |
|---|---|
| Ngôn ngữ | Java 25 |
| Framework | Spring Boot 4.1 không có web server: Data JPA, Validation, `spring-security-crypto` (băm mật khẩu) |
| Giao diện | JavaFX 25 (desktop Windows), CSS của JavaFX, phông Plus Jakarta Sans đóng gói kèm; đọc ảnh WebP bằng TwelveMonkeys `imageio-webp` |
| Cơ sở dữ liệu | H2 lưu ra file (chế độ tương thích PostgreSQL), Flyway |
| Kiểm thử | JUnit, Mockito, AssertJ, ArchUnit, TestFX (vài smoke test mở cửa sổ thật) |
| Build | Maven Wrapper |

## Kiến trúc

Ứng dụng desktop một tiến trình ([ADR-0014](adr/0014-javafx-desktop-app.md)). Modular monolith: chia package theo tính năng (`catalog`, `cart`, `order`, `user`, `admin`…); trong mỗi tính năng phân lớp màn hình JavaFX → service → repository. Màn hình nằm trong package tính năng nên service vẫn package-private. Một vài điểm thiết kế đáng chú ý:

- Trừ tồn kho bằng câu `UPDATE` nguyên tử nên không bán vượt kho khi nhiều người đặt cùng lúc ([ADR-0005](adr/0005-atomic-stock-decrement.md))
- Đơn hàng lưu lại tên và giá sản phẩm tại thời điểm mua
- Luồng trạng thái đơn được kiểm soát chặt: `PENDING → CONFIRMED → SHIPPING → DELIVERED`, hoặc `CANCELLED`
- Ảnh upload được kiểm cả nội dung file (magic bytes) và lưu bằng tên ngẫu nhiên ([ADR-0010](adr/0010-product-images-and-admin-stock-adjustment.md))
- Không gọi service trên luồng JavaFX: `desktop.BackgroundTasks` chạy việc trên virtual thread rồi trả kết quả về luồng giao diện
- Phiên đăng nhập là bean `user.AppSession` (một `CurrentUser` bất biến, chỉ đổi trên luồng giao diện). `AuthService.login` chạy ngoài giao dịch; việc gộp giỏ khách cắm vào qua `user.LoginHook` (do `cart` cài đặt) nên `user` không phụ thuộc `cart`, và gộp lỗi không làm hỏng đăng nhập
- Mỗi màn là một `Route` bất biến (`catalog.CatalogRoute` mang từ khóa, danh mục, sắp xếp, trang, như URL của bản web); đổi bộ lọc là điều hướng tới route mới, nên kết quả cũ không đè được kết quả mới. Mỗi `XScreen` có lớp lồng `XScreen.Factory` dựng màn và một `XPresenter` mới
- Nút "Thêm vào giỏ" nằm trong màn của `catalog`, nhưng `catalog` không được phụ thuộc `cart`: `catalog` khai báo interface `AddToCart`, `cart.AddToCartPresenter` cài đặt nó (cùng cách đảo phụ thuộc như `LoginHook`)
- JavaFX không đọc được WebP: `desktop.WebpDecoder` đọc bằng `javax.imageio` với thư viện TwelveMonkeys `imageio-webp` (scope `runtime`), ngoài luồng giao diện, từ chối ảnh trên 24 triệu điểm ảnh

**Luồng khởi động:**

1. `desktop.Launcher.main` gọi `Application.launch(MiniShopApp.class)`. `Launcher` không kế thừa `Application`, vì JavaFX nằm trên classpath sẽ báo "JavaFX runtime components are missing" nếu chạy thẳng lớp con của `Application`.
2. `MiniShopApp.init()` (ngoài luồng giao diện) dựng Spring context: Flyway, seed. Nếu H2 báo file đang bị khóa (mã 90020), app hiện "Mini Shop đang chạy ở một cửa sổ khác." rồi thoát.
3. `start()` nạp phông, theme và `ui/main-window.fxml`; controller FXML lấy từ Spring (`controllerFactory = context::getBean`).
4. `stop()` đóng context: executor nền dừng, H2 nhả khóa file.

Lỗi không lường trước đi qua một uncaught handler: một dòng log (thông điệp SQL đã bị lọc, vì H2 đưa cả dòng dữ liệu vào đó) và hộp thoại "Đã có lỗi xảy ra. Vui lòng thử lại.".

**Các package và chiều phụ thuộc** (mũi tên = "được phép dùng"; `ArchitectureTest` làm build thất bại nếu sai):

```mermaid
flowchart LR
    admin["admin<br/>(chỉ màn hình và form)"] --> catalog
    admin --> order
    admin --> storage
    admin --> user
    order -->|"chỉ CartCheckout"| cart
    order --> catalog
    order --> user
    cart --> catalog
    cart --> user
    catalog --> storage
    config --> catalog
    config --> user
    admin & order & cart & catalog & user --> common
    admin & order & cart & catalog & user --> desktop["desktop<br/>(khung cửa sổ, điều hướng, chạy nền)"]
```

<details>
<summary><b>Các bảng</b> (Flyway <code>V1</code>, <code>V2</code>)</summary>

```mermaid
erDiagram
    users ||--o| carts : "có"
    users ||--o{ orders : "đặt"
    carts ||--o{ cart_items : "gồm"
    categories ||--o{ products : "chứa"
    products ||--o{ cart_items : "nằm trong"
    orders ||--|{ order_items : "gồm"
    products ||--o{ order_items : "được mua"

    users {
        bigint id PK
        varchar email UK "luôn chữ thường"
        varchar password_hash
        varchar full_name
        varchar phone
        varchar role "USER hoặc ADMIN"
    }
    categories {
        bigint id PK
        varchar name UK
        varchar description
    }
    products {
        bigint id PK
        bigint category_id FK
        varchar name
        numeric price "VND, >= 0"
        integer stock ">= 0"
        varchar image_path
        boolean active "ẩn thay vì xóa"
    }
    carts {
        bigint id PK
        bigint user_id FK,UK
    }
    cart_items {
        bigint id PK
        bigint cart_id FK
        bigint product_id FK
        integer quantity "> 0"
    }
    orders {
        bigint id PK
        bigint user_id FK
        varchar status
        varchar receiver_name
        varchar phone
        varchar shipping_address
        varchar payment_method "COD"
        numeric total_amount
    }
    order_items {
        bigint id PK
        bigint order_id FK
        bigint product_id FK
        varchar product_name "chụp lúc mua"
        numeric unit_price "chụp lúc mua"
        integer quantity "> 0"
    }
```

</details>

Phạm vi, đặc tả và kế hoạch của từng giai đoạn GĐ1–GĐ8 được giữ ở tag [`v0.8.0`](https://github.com/Luffyreals/mini-shop/tree/v0.8.0/docs).

## Quyết định thiết kế

Mỗi quyết định có một ADR ghi bối cảnh, lựa chọn và phương án đã loại ([docs/adr](adr/README.md)):

| ADR | Chọn gì | Vì sao |
|---|---|---|
| [0001](adr/0001-java-25-spring-boot-4.md) | Java 25, Spring Boot 4.1 | Bản LTS mới nhất; dòng Boot 3.x đã hết hỗ trợ miễn phí |
| [0002](adr/0002-modular-monolith-package-by-feature.md) | Modular monolith, package theo tính năng | Một người, một miền nghiệp vụ nhỏ; ranh giới module được kiểm bằng ArchUnit |
| [0003](adr/0003-server-side-rendering-thymeleaf.md) | Render phía server bằng Thymeleaf | Bị thay thế bởi ADR-0014 |
| [0005](adr/0005-atomic-stock-decrement.md) | Trừ kho bằng `UPDATE … WHERE stock >= :q` | Không bán vượt kho khi nhiều người đặt cùng lúc |
| [0006](adr/0006-session-cart-merged-on-login.md) | Giỏ trong session cho khách, trong DB cho user, gộp khi đăng nhập | Khách mua được ngay, không mất giỏ khi đăng nhập (giỏ khách nay nằm trong bộ nhớ, ADR-0014) |
| [0007](adr/0007-session-form-login-current-user.md) | Đăng nhập bằng form trên session, principal riêng | Bị thay thế bởi ADR-0014 |
| [0008](adr/0008-cart-writes-serialized-per-user.md) | Ghi giỏ DB tuần tự theo người dùng (khóa dòng `users`) | Hai tab cùng sửa giỏ không ghi đè nhau |
| [0009](adr/0009-order-placement-and-cancel-locking.md) | Thứ tự khóa cố định khi đặt và hủy đơn | Không deadlock, kho chỉ được hoàn một lần |
| [0010](adr/0010-product-images-and-admin-stock-adjustment.md) | Ảnh qua `StorageService`, admin chỉnh kho bằng `UPDATE` có điều kiện | File upload không thành bề mặt tấn công; không ghi đè số khách vừa mua |
| [0011](adr/0011-centralized-error-handling.md) | Xử lý lỗi tập trung | Bị thay thế bởi ADR-0014 |
| [0012](adr/0012-share-from-personal-machine-public-mode.md) | Chia sẻ từ máy cá nhân qua Cloudflare Quick Tunnel, chế độ `public` (sau MVP) | Bị thay thế bởi ADR-0014 |
| [0013](adr/0013-h2-file-database-no-docker.md) | H2 lưu ra file trong `data/`, bỏ PostgreSQL và Docker | Ai clone cũng chạy được chỉ với JDK; dữ liệu còn sau khi tắt app |
| [0014](adr/0014-javafx-desktop-app.md) | Ứng dụng desktop JavaFX một tiến trình thay cho web | Giao diện 100% Java; giữ nguyên tầng nghiệp vụ và test |

## Cấu trúc repo

```
mini-shop/
├── src/main/java/com/minishop/
│   ├── catalog/ cart/ order/ user/   Các tính năng nghiệp vụ
│   ├── admin/                        Màn hình và form của khu quản trị
│   ├── desktop/                      Khởi động JavaFX, khung cửa sổ, điều hướng, chạy nền, lỗi
│   └── storage/ common/ config/      Lưu file, thành phần dùng chung, cấu hình
├── src/main/resources/
│   ├── ui/              FXML, theme.css (token), phông, biểu tượng
│   ├── images/          Ảnh sản phẩm mẫu và credits.tsv (nguồn, giấy phép)
│   ├── db/migration/    Schema (Flyway)
│   └── application.yml  Cấu hình
├── src/test/            Test (JUnit, ArchUnit, TestFX)
├── docs/
│   ├── architecture.md  Tài liệu này
│   └── adr/             Các quyết định kiến trúc
├── .github/             CI: build, test, kiểm không có test bị bỏ qua
├── pom.xml, mvnw, mvnw.cmd   Maven và Maven Wrapper (không cần cài Maven)
├── CHANGELOG.md
└── LICENSE
```

Thư mục `data/` (database, ảnh upload) và `target/` (build) được tạo khi chạy và không nằm trong git.

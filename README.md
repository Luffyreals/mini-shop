# Mini Shop

[![CI](https://github.com/Luffyreals/mini-shop/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Luffyreals/mini-shop/actions/workflows/ci.yml)

Ứng dụng bán hàng mini chạy trên Windows, viết 100% bằng Java: giao diện JavaFX, nghiệp vụ Spring (không có web server). Đây là **dự án học tập tập trung vào UI/UX**: không phải cửa hàng thật, không có thanh toán thật.

> Đến `v0.8.0`, Mini Shop là website (Spring MVC + Thymeleaf). Từ `v0.9.0` app được làm lại thành ứng dụng desktop JavaFX ([ADR-0014](docs/adr/0014-javafx-desktop-app.md)) qua các giai đoạn D1–D6; bản web vẫn xem được ở tag `v0.8.0`.

Có hai cách dùng: tải bản Windows đóng gói sẵn (**không cần cài Java**, xem [Tải và chạy bản Windows](#tải-và-chạy-bản-windows)), hoặc clone mã nguồn và chạy với **JDK 25**: không cần Docker, không cần cài database. Dữ liệu lưu trên máy bạn, nên tắt app mở lại vẫn còn.

## Chạy thử

1. **Cài JDK 25** (bỏ qua nếu `java -version` đã in ra `25`): Windows `winget install EclipseAdoptium.Temurin.25.JDK`, macOS `brew install --cask temurin@25`, Linux tải từ [adoptium.net](https://adoptium.net/temurin/releases/?version=25).
2. **Tải mã nguồn và chạy:**

   ```bash
   git clone https://github.com/Luffyreals/mini-shop.git
   cd mini-shop
   ./mvnw javafx:run
   ```

   Trên Windows PowerShell dùng `.\mvnw.cmd javafx:run`. Lần chạy đầu tự tải Maven và thư viện nên mất vài phút; sau đó cửa sổ "Mini Shop" mở trong khoảng 10 giây. Đóng cửa sổ là tắt app.

**Tài khoản demo** (tự tạo ở lần chạy đầu, cùng 5 danh mục và 22 sản phẩm mẫu; bấm "Đăng nhập" ở góc trên phải):

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Quản trị | `admin@minishop.local` | `Admin@123` |
| Khách hàng | `user@minishop.local` | `User@123` |

## Tải và chạy bản Windows

Bản đóng gói (`jpackage`) mang theo Java riêng, nên máy chỉ cần Windows.

1. **Lấy thư mục app:** tải artifact `MiniShop-0.9.0-windows` ở lần chạy CI mới nhất trên `main` (trang [Actions](https://github.com/Luffyreals/mini-shop/actions/workflows/ci.yml), artifact giữ 7 ngày), hoặc tự build trên Windows:

   ```powershell
   .\mvnw.cmd -Pjpackage -DskipTests package
   ```

   Kết quả nằm ở `target\dist\MiniShop\` (khoảng 140 MB, gồm `MiniShop.exe`, `app\` và `runtime\`).
2. **Giải nén rồi nhấp đúp `MiniShop.exe`.** App chưa được ký số nên Windows SmartScreen có thể cảnh báo: bấm "Thông tin thêm" (More info) rồi "Vẫn chạy" (Run anyway).

   Đặt thư mục app ở đường dẫn **không dấu**, ví dụ `C:\MiniShop`. Đường dẫn có chữ nằm ngoài bảng mã ANSI của Windows (ví dụ "ư" khi Windows dùng tiếng Anh) làm `MiniShop.exe` thoát ngay, không cửa sổ, không log. `DATA_DIR` có dấu thì không sao.

Bản này lưu dữ liệu ở `%LOCALAPPDATA%\MiniShop`: database, ảnh upload trong `uploads\`, log trong `logs\mini-shop.log` (cửa sổ app không có console). Xóa thư mục đó để làm lại dữ liệu mẫu; đặt biến `DATA_DIR` là một đường dẫn tuyệt đối để đổi chỗ. Lần mở đầu, JavaFX chép vài file DLL vào `%USERPROFILE%\.openjfx\cache`. Giấy phép của các thư viện đóng gói kèm nằm ở `app\THIRD-PARTY.txt` và `app\licenses\`.

## Tính năng

Các tính năng của bản web được giữ nguyên ở tầng nghiệp vụ và đã được nối lại vào giao diện desktop:

- **Khách hàng:** xem, tìm, lọc, sắp xếp sản phẩm; giỏ hàng cho cả khách chưa đăng nhập; đăng ký, đăng nhập; đặt hàng COD, lịch sử đơn, tự hủy đơn.
- **Quản trị** (mục "Quản trị" trong menu tài khoản, chỉ tài khoản quản trị thấy): tổng quan (đơn theo trạng thái, doanh thu, sắp hết hàng); quản lý danh mục, sản phẩm có ảnh, tồn kho; đổi trạng thái đơn, hủy đơn tự hoàn kho. Service quản trị tự kiểm vai trò của phiên, không chỉ dựa vào việc ẩn menu.
- **Nghiệp vụ được bảo đảm bằng test:** trừ kho nguyên tử, khóa theo thứ tự cố định khi đặt và hủy đơn, giá và tên được chụp lại trong đơn, mỗi khách chỉ thấy đơn của mình.

## Giao diện (UI/UX)

- Phong cách "soft modern": nền xám rất nhạt, thẻ trắng bo góc lớn, nút dạng viên thuốc, một màu nhấn cam, phông Plus Jakarta Sans đóng gói kèm app (đủ dấu tiếng Việt). Token màu và cỡ chữ nằm ở đầu [`ui/theme.css`](src/main/resources/ui/theme.css).
- Bàn phím: `Ctrl+K` đưa con trỏ vào ô tìm kiếm; Tab đi qua bộ lọc, các thẻ sản phẩm và thanh trang, Enter trên thẻ mở chi tiết. Ngăn giỏ hàng trượt ra trong cửa sổ, đóng bằng Esc, "Đóng" hoặc bấm ra ngoài; con trỏ quay về nút giỏ.
- Hành động khó hoàn tác (hủy đơn, xác nhận đã giao, xóa danh mục) luôn hỏi lại, và nút được chọn sẵn là nút không làm gì ("Giữ đơn hàng", "Quay lại"), nên lỡ nhấn Enter cũng không mất gì.
- Không gọi database trên luồng giao diện: mọi lời gọi service chạy nền, nên cửa sổ không bị "Not responding".
- Mỗi lần chỉ chạy được một cửa sổ app (H2 chỉ cho một tiến trình mở `data/`); mở lần hai sẽ có thông báo "Mini Shop đang chạy ở một cửa sổ khác.".
- Một màn nạp lỗi bất ngờ không bỏ trống: ngoài hộp thoại lỗi, màn hiện "Đã có lỗi xảy ra. Vui lòng thử lại." cùng nút "Thử lại". Hộp thoại (đăng nhập, xác nhận, lỗi) luôn nằm giữa cửa sổ app, và không tràn ra ngoài màn hình khi cửa sổ app nằm sát mép.

Ảnh sản phẩm là ảnh minh họa có giấy phép mở. Tác giả, giấy phép và nguồn của từng ảnh, của phông và của biểu tượng app hiện trong hộp thoại **"Nguồn ảnh"** (chân trang của trang chủ); dữ liệu nằm ở [`images/credits.tsv`](src/main/resources/images/credits.tsv), phông ở [`ui/fonts/`](src/main/resources/ui/fonts).

## Dữ liệu và lệnh thường dùng

App dùng **H2**, một database viết bằng Java chạy bên trong app. Chạy từ mã nguồn thì dữ liệu lưu ra `data/minishop.mv.db` và ảnh admin upload nằm ở `data/uploads/` (thư mục `data/` không nằm trong git); bản đóng gói dùng `%LOCALAPPDATA%\MiniShop`. `DATA_DIR` ghi đè cả hai.

| Việc cần làm | Cách làm |
|---|---|
| Làm lại dữ liệu mẫu từ đầu | Đóng app, xóa thư mục `data/`, chạy lại |
| Đặt dữ liệu ở thư mục khác | Biến môi trường `DATA_DIR`, đường dẫn tương đối hoặc tuyệt đối, ví dụ `DATA_DIR=data2 ./mvnw javafx:run` |
| Build và chạy toàn bộ test | `./mvnw verify` (test dùng H2 trong bộ nhớ, không đụng `data/`). Vài test giao diện mở cửa sổ thật trong giây lát |
| Bỏ qua test giao diện | `./mvnw verify -DexcludedGroups=ui` |
| Chạy một lớp test | `./mvnw test -Dtest=OrderServiceTest` |
| Đóng gói bản Windows | `.\mvnw.cmd -Pjpackage -DskipTests package` (chỉ trên Windows), ra `target\dist\MiniShop\MiniShop.exe`. Tắt `MiniShop.exe` trước khi build lại |

**Xử lý sự cố:**
- `release version 25 not supported`: máy đang dùng JDK cũ hơn; cài JDK 25 hoặc đặt `JAVA_HOME` trỏ tới nó.
- "Mini Shop đang chạy ở một cửa sổ khác.": đóng cửa sổ đang mở (hoặc tiến trình `java` còn sót) rồi chạy lại.
- Bản đóng gói nhấp đúp mà không thấy cửa sổ: xem `%LOCALAPPDATA%\MiniShop\logs\mini-shop.log` (hoặc `logs\` trong `DATA_DIR`).

## Tài liệu

- [docs/architecture.md](docs/architecture.md): công nghệ, package và chiều phụ thuộc, luồng khởi động, các bảng, cấu trúc repo.
- [docs/adr](docs/adr/README.md): các quyết định kiến trúc và lý do.
- [CHANGELOG.md](CHANGELOG.md): thay đổi theo từng phiên bản.

## Lộ trình

- [x] GĐ1–GĐ8 (`v0.1.0`–`v0.8.0`): bản web, gồm nền móng, sản phẩm, đăng nhập, giỏ hàng, đặt hàng, quản trị, hoàn thiện
- [x] Tái cấu trúc: chạy chỉ với JDK, dữ liệu lưu trong `data/`
- [x] D1: nền tảng desktop JavaFX (cửa sổ khung, chạy nền, lỗi, một cửa sổ)
- [x] D2: tài khoản và giỏ khách (đăng nhập, đăng ký, đăng xuất, gộp giỏ khách khi đăng nhập)
- [x] D3: cửa hàng (trang chủ, danh sách sản phẩm, chi tiết, thêm vào giỏ, đọc ảnh WebP)
- [x] D4: giỏ hàng và đặt hàng (ngăn giỏ, thanh toán COD, đơn hàng của tôi, hủy đơn có xác nhận)
- [x] D5: quản trị (tổng quan, danh mục, sản phẩm có ảnh, điều chỉnh tồn kho, đổi trạng thái đơn, kiểm vai trò ở service)
- [x] D6: hoàn thiện và đóng gói (`MiniShop.exe` bằng `jpackage`, hộp thoại "Nguồn ảnh", màn lỗi có "Thử lại", job CI Windows)

## Đóng góp

- Làm trên nhánh riêng (`feat/…`, `fix/…`, `docs/…`, `chore/…`, `desktop/…`), mở Pull Request vào `main` và chọn **Squash and merge**. `./mvnw verify` phải xanh; CI chạy lại khi mở PR.
- Commit message theo [Conventional Commits](https://www.conventionalcommits.org/), tiếng Anh, scope là tên tính năng: `feat(order): restore stock when an order is cancelled`.
- Chữ hiển thị tiếng Việt, tên trong code tiếng Anh. Đổi schema thì thêm migration Flyway mới, không sửa file cũ.
- Quyết định thay đổi kiến trúc, công nghệ hay ràng buộc nghiệp vụ thì viết ADR mới trong [docs/adr](docs/adr/README.md).

## Giấy phép

Mã nguồn và biểu tượng app: [MIT](LICENSE). Ảnh sản phẩm và phông theo giấy phép riêng của từng tác giả (xem hộp thoại "Nguồn ảnh", `images/credits.tsv` và `ui/fonts/OFL.txt`). Ảnh WebP được đọc bằng thư viện [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (BSD-3-Clause, văn bản giấy phép ở [`src/packaging/licenses/`](src/packaging/licenses)). Bản đóng gói kèm danh sách giấy phép của mọi thư viện trong `app\THIRD-PARTY.txt`.

# Changelog

Mọi thay đổi đáng chú ý của dự án được ghi ở đây.
Định dạng theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/); mỗi giai đoạn hoàn thành được gắn tag `v0.<giai đoạn>.0`.

## [Unreleased]

Tái cấu trúc để repo public: ai clone về cũng chạy được chỉ với JDK 25 ([ADR-0013](docs/adr/0013-h2-file-database-no-docker.md)). Sau đó Mini Shop chuyển từ website sang ứng dụng desktop JavaFX ([ADR-0014](docs/adr/0014-javafx-desktop-app.md)); giai đoạn D1 dựng nền tảng, D2 làm lại tài khoản, D3 làm lại cửa hàng, D4 làm lại giỏ hàng và đặt hàng, D5 làm lại khu quản trị.

### Thêm
- Trang chủ chỉ dùng dữ liệu thật: sản phẩm mới nhất (thêm vào giỏ, xem chi tiết), hai sản phẩm mới kế tiếp, khối "Sắp hết hàng" (còn 1–5 cái, ẩn khi không có) và thẻ "Giao hàng và thanh toán". App mở vào trang chủ; nút "Cửa hàng" và đăng xuất cũng về đó.
- Danh sách sản phẩm: tìm theo tên từ ô tìm kiếm trên thanh trên (giữ danh mục và sắp xếp đang chọn), lọc theo danh mục (hàng nút và menu "Danh mục ▾"), sắp xếp, phân trang 12 sản phẩm, "Tìm thấy N sản phẩm", trạng thái rỗng với "Xóa bộ lọc". Lưới 4/3/2 cột theo chiều rộng cửa sổ.
- Trang chi tiết sản phẩm: ảnh, danh mục, giá, "Còn hàng"/"Hết hàng", mô tả, ô "Số lượng" và "Thêm vào giỏ"; "Về danh sách sản phẩm" quay lại đúng trang và bộ lọc cũ.
- Thêm vào giỏ (cả khi chưa đăng nhập) từ trang chi tiết, từ nút "+ Thêm" trên thẻ sản phẩm và từ thẻ lớn ở trang chủ; thông báo theo đúng quy tắc giỏ hàng (kẹp theo tồn kho, tối đa 99) và badge giỏ cập nhật sau mỗi lần thêm.
- Phím tắt `Ctrl+K` đưa con trỏ vào ô tìm kiếm.
- Đọc ảnh WebP bằng thư viện TwelveMonkeys `imageio-webp` (BSD-3-Clause), giải mã ngoài luồng giao diện; ảnh khai kích thước quá lớn hoặc file hỏng hiện ô giữ chỗ.
- Ngăn giỏ hàng mở từ nút giỏ trên thanh trên: ảnh, tên, đơn giá, thành tiền, nhãn dòng (ngừng bán, hết hàng, sắp hết), sửa số lượng bằng −/+ hoặc gõ số rồi Enter, nút "Xóa", "Tổng cộng" và "Đặt hàng". Đóng bằng Esc, "Đóng" hoặc bấm ra ngoài. Toast "Đã thêm vào giỏ hàng." có nút "Xem giỏ hàng".
- Màn Thanh toán (COD): tóm tắt giỏ, form người nhận điền sẵn họ tên, lỗi theo từng ô. "Đặt hàng" khi chưa đăng nhập mở hộp thoại đăng nhập rồi đi tiếp. Hết hàng hoặc giá vừa đổi lúc xác nhận thì báo đúng câu của quy tắc đặt hàng; đóng ngăn giỏ thì tóm tắt được nạp lại, chữ đã nhập được giữ.
- "Đơn hàng của tôi" trong menu tài khoản: danh sách đơn có phân trang, chi tiết đơn (đặt xong hiện "Đặt hàng thành công. Mã đơn của bạn là #{id}."), tự hủy đơn đang chờ xác nhận qua hộp thoại "Bạn có chắc muốn hủy đơn hàng này?".
- Thành phần dùng chung trong `desktop`: `DrawerHost` (ngăn trượt trong cửa sổ, nằm dưới lớp toast), `ConfirmDialog` (hộp xác nhận, focus sẵn ở nút từ chối), `ToastAction` (nút trong toast).
- Khu quản trị trên desktop, mở từ mục "Quản trị" trong menu tài khoản (chỉ tài khoản quản trị thấy), có thanh bên "Tổng quan", "Đơn hàng", "Sản phẩm", "Danh mục"; thanh trên vẫn dùng được.
- Tổng quan: số đơn theo từng trạng thái (bấm để mở danh sách đã lọc), doanh thu đơn đã giao, đơn mới nhất, sản phẩm sắp hết hàng.
- Danh mục: danh sách, thêm, sửa, xóa qua hộp xác nhận; không xóa được danh mục còn sản phẩm. Menu "Danh mục ▾" của thanh trên cập nhật ngay.
- Sản phẩm: danh sách cả sản phẩm đã ẩn, tìm theo tên, 20 sản phẩm một trang, ẩn/hiện ngay trên hàng; form thêm/sửa có chọn ảnh (kiểm ngay khi chọn, xem trước, "Bỏ chọn ảnh"), khối "Điều chỉnh tồn kho" không xóa chữ đang nhập ở form.
- Đơn hàng: lọc theo trạng thái, phân trang, chi tiết đơn, các nút đổi trạng thái theo luồng cho phép; "Hủy đơn" và "Xác nhận đã giao" hỏi lại trước.

### Thay đổi
- App là ứng dụng desktop JavaFX 25 chạy bằng `./mvnw javafx:run`; Spring chạy không có web server. Cửa sổ khung có thanh trên cùng (logo, mục điều hướng, ô tìm kiếm, nút giỏ, nút đăng nhập; các nút bật dần ở D2–D4), theme "soft modern" và phông Plus Jakarta Sans đóng gói kèm.
- Lời gọi service chạy nền trên virtual thread; lỗi không lường trước ghi một dòng log và hiện hộp thoại "Đã có lỗi xảy ra. Vui lòng thử lại." thay vì làm treo app.
- Chỉ một cửa sổ app chạy được một lúc: mở lần hai báo "Mini Shop đang chạy ở một cửa sổ khác." và thoát.
- Đăng nhập và đăng ký trong một hộp thoại mở từ nút "Đăng nhập". Sai email hoặc mật khẩu báo "Email hoặc mật khẩu không đúng.", xóa ô mật khẩu và giữ email; đăng ký xong hộp thoại chuyển sang đăng nhập với email vừa đăng ký.
- Đã đăng nhập thì nút tài khoản hiện họ tên và mở menu "Đăng xuất"; đăng xuất đưa app về trạng thái khách và báo "Bạn đã đăng xuất.".
- Giỏ khách nằm trong bộ nhớ của app (mất khi đóng app); khi đăng nhập được gộp vào giỏ của tài khoản theo quy tắc cũ, và badge giỏ cập nhật theo phiên.
- Hộp thoại đăng ký không còn cao hơn cửa sổ: phần ô nhập cuộn được, tiêu đề và nút "Đăng ký" luôn hiện. Nút tài khoản có độ rộng tối đa để tên dài không làm chật ô tìm kiếm.
- `PageNav` chỉ còn số trang (không còn URL); `desktop.PageBar` vẽ thanh trang dùng chung.
- `OrderService.checkoutPage` trả tóm tắt giỏ và form điền sẵn trong một lần đọc, cho màn Thanh toán.
- `CategoryAdminService`, `ProductAdminService`, `OrderAdminService` gọi `AdminAccess.requireAdmin()` đầu tiên ở mọi phương thức public: phiên khách hoặc USER bị từ chối trước mọi khóa và câu SQL. Tham số `adminId` bị bỏ; id quản trị lấy từ chính phiên.
- `ImageUpload.fromFile(Path)` nhận ảnh từ file đã chọn: kiểm kích thước trước khi đọc, lấy kiểu file theo đuôi khi Windows không biết, vẫn luôn kiểm magic bytes; lỗi đọc file không ghi đường dẫn vào log.
- Ảnh sản phẩm mẫu chuyển sang `images/products/` (đường dẫn lưu trong DB không đổi); nguồn và giấy phép ảnh chuyển từ `credits.html` sang `images/credits.tsv`. Thay 5 ảnh sản phẩm chưa đúng chủ thể và thêm ảnh cho 2 cuốn sách.
- `CurrentUser` là record không chứa hash mật khẩu; chỉ còn `spring-security-crypto` để băm mật khẩu.
- Test web (MockMvc, Tomcat thật) được bỏ; các quy tắc nghiệp vụ chúng kiểm được chuyển xuống test service. CI chạy test giao diện dưới màn hình ảo `xvfb`.
- Database duy nhất là H2 nhúng lưu ra file `data/minishop.mv.db`; dữ liệu còn sau khi tắt app. Ảnh upload chuyển vào `data/uploads/`. Biến `DATA_DIR` đổi thư mục.
- Test chạy trên H2 trong bộ nhớ, mỗi Spring context một database; CI chỉ cần JDK.
- README viết lại cho người mới clone: chạy thử, phần giao diện (UI/UX), dữ liệu lưu ở đâu.
- `.claude/` và `CLAUDE.md` (công cụ AI lúc phát triển) không còn nằm trong git.
- README rút gọn; kiến trúc, sơ đồ bảng, bảng ADR và cấu trúc repo chuyển sang `docs/architecture.md`; quy ước đóng góp gộp vào README.
- Link tới đặc tả giai đoạn trong ADR và CHANGELOG trỏ tới tag `v0.8.0`.

### Bỏ
- Giao diện web: controller, Thymeleaf, Bootstrap/WebJars, CSS/JS, trang lỗi HTTP, ảnh chụp màn hình web trong `docs/images/` (vẫn xem được ở tag `v0.8.0`).
- Spring Security phần web (form login, CSRF, CSP, giới hạn số lần đăng nhập sai), actuator, H2 console, DevTools.
- Chế độ `public` và chia sẻ qua Cloudflare Tunnel (`application-public.yml`, `docs/deploy-cloudflare.md`).
- PostgreSQL, Docker Compose (`compose.yaml`), Testcontainers, profile `local` và `h2`.
- Tài liệu quy trình theo giai đoạn (`docs/project-brief.md`, `docs/specs/`, `docs/plans/`), vẫn xem được ở tag `v0.8.0`.
- `CONTRIBUTING.md`, template issue và PR, Dependabot.

### Sửa
- Trên database mới, sau khoảng 30 phút chạy app thì đặt hàng, đăng ký và đổi trạng thái đơn đều lỗi cho tới khi mở lại app. Nguyên nhân là lỗi của H2 2.4.240: ràng buộc `CHECK (… in (…))` gắn với kết nối Flyway đã tạo nó, và hỏng khi pool thay kết nối đó. Nâng H2 lên 2.5.252 (ghi đè bản của Spring Boot); file database cũ vẫn mở được. Hai test mới tái hiện lỗi: `H2CheckConstraintSessionTest` và `PoolConnectionRetireIntegrationTest`.
- `DATA_DIR` là đường dẫn tương đối không có `./` (ví dụ `data2`, `target/x`) làm app không khởi động vì H2 từ chối đường dẫn tương đối ngầm; `config.DataDirectory` tự thêm `./`.
- Test chống session fixation chạy trên Tomcat thật (`SessionFixationTest`) thay cho bản MockMvc phụ thuộc thứ tự chạy test.

### Bảo mật
- `StorageService.toUrl` chỉ đổi thành URL hai dạng đường dẫn do chính app tạo (ảnh mẫu trong app, ảnh upload trong `data/uploads/`); giá trị khác trong DB (`http://…`, `file:` bất kỳ, `..`) thành ô giữ chỗ, nên dữ liệu không khiến app tải từ mạng hay đọc file tùy ý.
- Đăng nhập với email không tồn tại và với mật khẩu sai cho cùng một kết quả và tốn thời gian tương đương (luôn so khớp một hash), nên không dò được email nào đã đăng ký.
- Mật khẩu dài hơn 72 byte bị từ chối trước khi so khớp: BCrypt chỉ xét 72 byte đầu, nên chuỗi dài hơn có cùng 72 byte đầu sẽ khớp nhầm.
- Lỗi database ghi vào log 500 chỉ còn tên lớp và stack frame, không còn message: H2 đưa nguyên dòng dữ liệu (email, hash mật khẩu) vào thông báo lỗi. Tắt thêm logger `org.hibernate.orm.jdbc.error`.

## [0.8.0] - 2026-10-25

GĐ8: chia sẻ app đang chạy trên máy cá nhân lên internet ([spec 008](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/008-deploy.md)).

### Thêm
- Chế độ công khai (profile `public`): mở app ra internet qua Cloudflare Quick Tunnel (`cloudflared tunnel --url http://127.0.0.1:8080`), README có hướng dẫn và cảnh báo.
- Profile `local`: chạy với PostgreSQL cài trên máy, không cần Docker (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).
- Dòng nhắc `Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.` trên form đăng ký và trang thanh toán.
- ADR-0012: chia sẻ từ máy cá nhân thay vì deploy PaaS.

### Thay đổi
- Brief §9, §11: GĐ8 là chia sẻ từ máy cá nhân qua tunnel; deploy PaaS chuyển sang "Hướng phát triển".
- H2 console chỉ còn ở profile `h2` (DevTools trước đây bật nó ở mọi profile).

### Bảo mật
- Chế độ công khai không khởi động nếu thiếu mật khẩu demo, mật khẩu yếu, trùng mật khẩu dev hoặc hai mật khẩu trùng nhau; mật khẩu hai tài khoản demo được đặt lại theo biến môi trường trước khi app nhận request.
- Giới hạn đăng nhập sai ở mọi chế độ: 5 lần/email và 20 lần/IP trong 15 phút, bị chặn thì không kiểm mật khẩu.
- Chỉ tin header `X-Forwarded-*` từ chính máy (tunnel chạy local); cookie phiên `SameSite=Lax`, thêm `Secure` ở chế độ công khai; ở chế độ công khai app chỉ nghe trên `127.0.0.1`.
- `lock_timeout` 5 giây cho mọi câu khóa dòng và câu trừ/cộng kho.
- Cổng PostgreSQL của `compose.yaml` chỉ mở trên `127.0.0.1`.

## [0.7.0] - 2026-10-25

GĐ7: hoàn thiện ([spec 007](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/007-polish.md)).

### Thêm
- Trang lỗi tiếng Việt đúng mã HTTP: `Không tìm thấy trang` (404), `Yêu cầu không hợp lệ` (400/405), `Phiên làm việc đã hết hạn` (403 do CSRF), `Tệp tải lên quá lớn` (413), `Đã có lỗi xảy ra` (500); lỗi trong `/admin/**` dùng layout quản trị. Không còn trang "Whitelabel Error Page".
- Favicon (SVG và `favicon.ico`), link `Bỏ qua đến nội dung chính` trên mọi trang, viền focus rõ ràng cho nút và ô nhập.
- README: badge CI, ảnh chụp, sơ đồ package và sơ đồ bảng (Mermaid), mục "Quyết định thiết kế", "Xử lý sự cố", "Hướng phát triển".
- ADR-0011: xử lý lỗi tập trung.

### Thay đổi
- Gom ba advice 404 của `catalog`, `cart`, `order` và ba handler 404 của admin thành một cơ chế chung (`@NotFoundPage` + `NotFoundPageAdvice`); chữ trên các trang 404 riêng không đổi.
- Mọi ô chữ được làm sạch chung (`FormText`): bỏ ký tự vô hình, đổi dấu cách đặc biệt thành dấu cách thường, đổi `\r\n` thành `\n`; tên chỉ gồm ký tự vô hình bị coi là trống.
- Số điện thoại nhận thêm dạng `+84…` / `84…` (lưu thành `0…`); ô giá nhận dấu cách không ngắt dòng; form đăng ký và thanh toán có `maxlength` khớp giới hạn của server.

### Bảo mật
- Trang lỗi và phản hồi JSON không bao giờ chứa stack trace, thông điệp exception hay câu SQL, ở mọi profile (`spring.web.error.*`).
- Mỗi lỗi 500 ghi đúng một dòng log, không có tham số, nội dung form hay dữ liệu cá nhân; lỗi DB không còn đưa giá trị dòng vào log.
- Tomcat chỉ đọc bỏ tối đa 50 MB phần thân của request vượt trần upload.

## [0.6.0] - 2026-10-24

GĐ6: trang quản trị ([spec 006](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/006-admin.md)).

### Thêm
- Layout quản trị riêng có sidebar (`Tổng quan`, `Đơn hàng`, `Sản phẩm`, `Danh mục`, `Về cửa hàng`) cho mọi trang `/admin/**`; trang 404 trong layout quản trị.
- Dashboard `/admin` thay trang tạm của GĐ3: số đơn theo từng trạng thái (bấm để lọc), doanh thu đơn đã giao, sản phẩm đang bán sắp hết hàng (tồn kho ≤ 5), 5 đơn mới nhất.
- Quản lý danh mục: thêm, sửa, xóa (chỉ khi không còn sản phẩm nào, kể cả sản phẩm đã ẩn); tên không được trùng, không phân biệt hoa thường.
- Quản lý sản phẩm: danh sách gồm cả sản phẩm đã ẩn (tìm theo tên, 20 sản phẩm/trang), thêm, sửa, ẩn và hiện lại; giá nhận dạng `1.250.000`.
- Upload ảnh sản phẩm (JPG, PNG, WebP, tối đa 2 MB) qua interface `StorageService`, lưu ở `uploads/` (đổi bằng `UPLOAD_DIR`) và phục vụ tại `/uploads/**`; thay ảnh thì file cũ bị xóa.
- Điều chỉnh tồn kho bằng một lượng tăng hoặc giảm, chạy bằng một câu `UPDATE` có điều kiện (0–100.000).
- Quản lý đơn hàng: danh sách mọi đơn (lọc theo trạng thái, 20 đơn/trang), chi tiết đơn kèm tài khoản đặt hàng, chuyển trạng thái `Xác nhận đơn` → `Bắt đầu giao hàng` → `Xác nhận đã giao`, admin hủy đơn chờ xử lý hoặc đã xác nhận và hoàn kho.
- ADR-0010: ảnh qua `StorageService`, điều chỉnh tồn kho nguyên tử, khóa `categories` trước `products`.

### Thay đổi
- Trần upload của Tomcat (`spring.servlet.multipart.max-file-size`) tăng từ 2 MB lên 10 MB; giới hạn 2 MB của ảnh được kiểm trong ứng dụng để hiện lỗi ngay trên form.
- `catalog.ProductImages` thành bean, chuyển đường dẫn ảnh thành URL qua `StorageService`.

### Bảo mật
- File upload được kiểm cả đuôi, content type và chữ ký đầu file (magic bytes); không nhận SVG, GIF; lưu bằng tên UUID, không bao giờ dùng tên gốc; chặn path traversal khi ghi và khi phục vụ file.
- Mọi form quản trị là POST có CSRF, kể cả form upload `multipart/form-data`; tham số giả mạo (`stock`, `active`, `imagePath`, `id`) bị bỏ qua.
- Admin và khách đổi cùng một đơn được xếp hàng bằng khóa dòng `orders`: kho chỉ được hoàn một lần.

## [0.5.0] - 2026-10-24

GĐ5: đặt hàng (COD), lịch sử đơn và tự hủy đơn ([spec 005](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/005-checkout-and-orders.md)).

### Thêm
- Nút "Đặt hàng" ở trang giỏ (chỉ khi mọi dòng đều đặt được) và trang thanh toán `/checkout`: thông tin nhận hàng (điền sẵn họ tên, số điện thoại của tài khoản), tóm tắt đơn, thanh toán khi nhận hàng (COD). Khách chưa đăng nhập được đưa qua bước đăng nhập rồi quay lại thanh toán với giỏ đã gộp.
- Đặt hàng trong một transaction: chụp tên và giá sản phẩm vào đơn, trừ tồn kho có điều kiện, xóa giỏ. Không đủ hàng thì không đặt gì và báo tên sản phẩm; giá vừa đổi thì hiện lại trang thanh toán với giá mới.
- Trang "Đơn hàng của tôi" `/orders` (10 đơn mỗi trang, mới nhất trước, giờ Việt Nam) và chi tiết đơn `/orders/{id}`; link "Đơn hàng của tôi" trên header.
- Khách tự hủy đơn đang "Chờ xử lý" (có hộp xác nhận); tồn kho được cộng lại trong cùng transaction.
- Luồng trạng thái đơn `OrderStatus.canTransitionTo()` cho cả GĐ6.
- ADR-0009: thứ tự khóa cố định khi đặt và hủy đơn; mọi chuyển trạng thái đơn khóa dòng `orders` trước.

### Bảo mật
- Chỉ xem và hủy được đơn của chính mình (đơn của người khác trả 404); mọi form đặt và hủy là POST có CSRF; tham số giả mạo (`userId`, `status`, giá, tổng) bị bỏ qua.
- Đặt hàng đồng thời không bán vượt kho, không tạo hai đơn từ một giỏ, không deadlock; hủy hai lần chỉ hoàn kho một lần.

## [0.4.0] - 2026-10-24

GĐ4: giỏ hàng ([spec 004](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/004-shopping-cart.md)).

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

GĐ3: đăng ký, đăng nhập và phân quyền ([spec 003](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/003-authentication.md)).

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

GĐ2: danh sách và chi tiết sản phẩm ([spec 002](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/002-product-catalog.md)).

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

GĐ1: nền móng dự án ([spec 001](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/001-project-foundation.md)).

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

# Spec 007: Hoàn thiện (trang lỗi, validation, giao diện, README)

- **Giai đoạn:** GĐ7
- **Trạng thái:** Hoàn thành
- **Liên quan:** brief §2 (có trang lỗi 404/403/500, mỗi thao tác có flash message), §7 (bảo mật ứng dụng), §8 (Definition of Done: form có validation tiếng Việt, README được cập nhật), §10 (`@ControllerAdvice` ghi log lỗi 500; README có mục xử lý sự cố), §12 (README có ảnh chụp, sơ đồ kiến trúc và entity, badge CI, mục "Quyết định thiết kế"); ADR-0002, 0007, 0010; spec 002 (404 sản phẩm), 003 (403, `SecurityConfig`), 004, 005 (`CheckoutForm`, số điện thoại), 006 (404 quản trị, giới hạn upload)

## 1. Mục tiêu

Không người dùng nào còn thấy trang lỗi mặc định của Spring Boot hay stack trace: mọi lỗi (không tìm thấy, không có quyền, phiên hết hạn, tệp quá lớn, lỗi hệ thống) đều có trang tiếng Việt đúng mã HTTP, trong giao diện của dự án. Các form chặn nốt những dữ liệu "trông như trống" và nhận thêm vài cách nhập phổ biến. Giao diện qua được một vòng kiểm tra truy cập (bàn phím, tương phản, favicon). README đủ để người lạ chạy được dự án trong khoảng 5 phút và hiểu các quyết định thiết kế.

## 2. Phạm vi

**Trong phạm vi**
- Trang lỗi chung cho 404 (URL không tồn tại), 400/405 (yêu cầu không hợp lệ), 403 do CSRF hết hạn hoặc sai, 413 (tệp tải lên quá lớn), 500 (lỗi hệ thống); giữ trang 403 "không có quyền" của GĐ3.
- Gom các cơ chế 404 rời rạc (`ProductNotFoundAdvice`, `CartNotFoundAdvice`, `OrderNotFoundAdvice`, handler 404 trong các controller admin) về một cơ chế chung, **giữ nguyên** chữ và mã lỗi người dùng đang thấy.
- Ghi log lỗi 500 đúng một lần, không lộ dữ liệu cá nhân.
- Validation: loại ký tự vô hình khỏi mọi ô chữ; nhận số điện thoại dạng `+84`; nhận dấu cách không ngắt dòng (NBSP) trong ô giá; `CheckoutForm` dùng chung `common.FormText`; `maxlength` trên form storefront khớp giới hạn của server.
- Giao diện: favicon, link "Bỏ qua đến nội dung chính", tương phản và focus theo WCAG 2.1 AA, kiểm lại mọi trang ở 375px và 1280px.
- README: badge CI, ảnh chụp, sơ đồ kiến trúc và sơ đồ bảng (Mermaid), mục "Quyết định thiết kế", mục "Xử lý sự cố", hướng dẫn chạy được kiểm lại trên một bản clone mới.

**Ngoài phạm vi**
- Từ danh sách "Loại ra" của brief: **quên mật khẩu**, **email**, **đa ngôn ngữ**, **REST API công khai**, **thanh toán online**, **đánh giá**, **mã giảm giá**, **wishlist**.
- Việc của GĐ8 (deploy): giới hạn số lần đăng nhập sai, khóa tài khoản, cookie `secure`/`same-site`, tắt seed (`app.seed.enabled`), `lock_timeout`, header cache cho `/css`, `/js`, `/images`, `/uploads`, `StorageService` bản đám mây, Dockerfile.
- Tính năng mới: tìm đơn theo email/số điện thoại/mã đơn trong admin, lọc sản phẩm admin theo danh mục, trang hồ sơ, sổ địa chỉ, chế độ tối, ảnh responsive (`srcset`), GIF minh họa (xem câu hỏi 9).
- Mã tham chiếu lỗi hiện cho người dùng (xem câu hỏi 8); trang lỗi riêng cho từng mã 4xx hiếm gặp khác (415, 429…): dùng chung trang "Yêu cầu không hợp lệ".
- Đổi schema: GĐ7 không có migration.

## 3. User stories

- **US-007-1:** Là khách, khi gõ nhầm đường dẫn hoặc gặp sự cố, tôi muốn thấy một trang tiếng Việt nói rõ chuyện gì xảy ra và có đường quay lại, thay vì một trang kỹ thuật khó hiểu.
- **US-007-2:** Là khách để form mở quá lâu, tôi muốn được báo là phiên đã hết hạn và cần tải lại trang, thay vì bị báo "không có quyền".
- **US-007-3:** Là admin, khi chọn nhầm một tệp rất lớn, tôi muốn được báo tệp quá lớn thay vì trình duyệt báo mất kết nối.
- **US-007-4:** Là chủ cửa hàng, tôi muốn mọi lỗi hệ thống được ghi log để sửa, nhưng không bao giờ lộ stack trace, câu SQL hay dữ liệu cá nhân ra trang.
- **US-007-5:** Là khách, tôi muốn nhập số điện thoại theo kiểu `+84…` quen thuộc và không bị chấp nhận một tên "trống" chỉ toàn ký tự vô hình.
- **US-007-6:** Là người dùng bàn phím hoặc trình đọc màn hình, tôi muốn bỏ qua được phần header, thấy rõ ô đang được chọn và đọc được mọi chữ.
- **US-007-7:** Là nhà tuyển dụng hoặc người review, tôi muốn đọc README là hiểu dự án làm gì, trông ra sao, kiến trúc thế nào, và chạy được trong khoảng 5 phút.

## 4. Tiêu chí chấp nhận

**Trang lỗi**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-007-1 | Người đã đăng nhập (`USER` hoặc `ADMIN`), hoặc khách với đường dẫn công khai (`/products/`, `/products/abc/xyz`, `/images/khong-co.jpg`, `/css/khong-co.css`) | mở URL không tồn tại | HTTP 404, tiêu đề tab `Không tìm thấy trang \| Mini Shop`, `h1` `Không tìm thấy trang`, dòng `Trang bạn tìm không tồn tại hoặc đã bị di chuyển.`, link `Về trang chủ`; có header và footer của cửa hàng; không phải trang "Whitelabel Error Page" | `ErrorPagesTest` |
| AC-007-2 | Khách chưa đăng nhập | mở URL không công khai và không tồn tại (`/khong-co-trang-nay`) | chuyển sang `/login` như mọi trang cần đăng nhập (GĐ3, câu hỏi 1); đăng nhập xong thì thấy trang 404 của AC-007-1 | `SecurityConfigTest`, `ErrorPagesTest` |
| AC-007-3 | Các trang 404 riêng đã có: sản phẩm (spec 002), id sản phẩm sai khi thêm vào giỏ (spec 004), đơn hàng (AC-005-24), quản trị (AC-006-5) | mở như trước | mã lỗi, tiêu đề, `h1`, chữ và link **không đổi** so với GĐ6 sau khi gom cơ chế 404 (BR-007-3) | test hiện có của GĐ2–GĐ6 vẫn xanh |
| AC-007-4 | Một controller ném exception không lường trước (ví dụ lỗi DB) | mở trang | HTTP 500, tiêu đề tab `Đã có lỗi xảy ra \| Mini Shop`, `h1` `Đã có lỗi xảy ra`, dòng `Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.`, link `Về trang chủ`. Thân trang **không** chứa stack trace, tên class Java, thông điệp exception, câu SQL hay đường dẫn file, kể cả khi chạy profile dev có devtools | `ErrorPagesTest` |
| AC-007-5 | Như AC-007-4 | lỗi xảy ra | server ghi **đúng một** dòng log mức `ERROR` cho lỗi đó, gồm phương thức HTTP, đường dẫn (không có query string) và stack trace; không có số điện thoại, địa chỉ, ghi chú, email, mật khẩu, nội dung form | `ErrorLoggingTest` (bắt log) |
| AC-007-6 | DB không truy cập được nên chính truy vấn số lượng giỏ trên header cũng lỗi | xảy ra lỗi 500 | trang 500 vẫn hiện đủ (HTTP 500, chữ của AC-007-4); header không hiện số lượng giỏ thay vì làm hỏng trang lỗi | `ErrorPagesTest` |
| AC-007-7 | Người đã đăng nhập | gửi `GET` tới URL chỉ nhận `POST` (`/cart/items`, `/orders/{id}/cancel`, `/admin/orders/{id}/status`), hoặc yêu cầu thiếu tham số bắt buộc / sai định dạng mà chưa có xử lý riêng | đúng mã HTTP (405 hoặc 400), tiêu đề tab `Yêu cầu không hợp lệ \| Mini Shop`, `h1` `Yêu cầu không hợp lệ`, dòng `Đường dẫn hoặc dữ liệu gửi lên không hợp lệ.`, link `Về trang chủ`; không đổi dữ liệu | `ErrorPagesTest` |
| AC-007-8 | Form bất kỳ (thêm vào giỏ, thanh toán, hủy đơn, form quản trị, đăng xuất) mở lâu đến khi phiên hết hạn, hoặc gửi với CSRF token thiếu/sai | gửi form | HTTP 403, tiêu đề tab `Phiên làm việc đã hết hạn \| Mini Shop`, `h1` `Phiên làm việc đã hết hạn`, dòng `Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại.`, link `Về trang chủ`; không đổi dữ liệu. Trang này **khác** trang 403 "không có quyền" | `CsrfErrorPageTest`, `SecurityConfigTest` |
| AC-007-9 | Tài khoản `USER` | mở `/admin` | vẫn là trang 403 của AC-003-14 (`Bạn không có quyền truy cập trang này.`), không phải trang phiên hết hạn | `SecurityConfigTest` (đã có) |
| AC-007-10 | Admin ở form thêm hoặc sửa sản phẩm | chọn ảnh lớn hơn trần upload của server (thử 12 MB và 30 MB) rồi bấm `Lưu` | HTTP 413, tiêu đề tab `Tệp tải lên quá lớn \| Quản trị Mini Shop`, `h1` `Tệp tải lên quá lớn`, dòng `Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.`, link `Về danh sách sản phẩm` (`/admin/products`); trình duyệt **không** báo mất kết nối; không tạo sản phẩm, không ghi file (câu hỏi 3) | `UploadTooLargeTest` (Tomcat thật) + checklist |
| AC-007-11 | Admin đã đăng nhập | gặp lỗi 404, 400/405, 413 hoặc 500 ở đường dẫn `/admin/**` | trang lỗi dùng layout quản trị (sidebar), tiêu đề tab dạng `{Tiêu đề} \| Quản trị Mini Shop`, link quay lại `Về trang quản trị` (`/admin`) thay cho `Về trang chủ` (câu hỏi 4). Lỗi ở mọi đường dẫn khác dùng layout cửa hàng | `ErrorPagesTest` |
| AC-007-12 | Client gửi `Accept: application/json` (ví dụ gọi `/actuator/health` sai đường dẫn, hoặc một URL không tồn tại) | xảy ra lỗi | phản hồi JSON mặc định của Spring Boot giữ đúng mã lỗi nhưng không có `trace`, `message`, `exception`, `errors` | `ErrorPagesTest` |
| AC-007-13 | Mọi trang lỗi của AC-007-1 đến AC-007-11 | xem ở 375px và 1280px | đúng một `h1`; không cuộn ngang; không lỗi CSP; template qua `TemplateCspComplianceTest`; mã HTTP đúng như bảng (không bao giờ trả 200 cho trang lỗi) | `TemplateCspComplianceTest`, `ErrorPagesTest` + checklist |

**Validation**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-007-14 | Họ tên đăng ký, họ tên người nhận, địa chỉ giao hàng, tên danh mục hoặc tên sản phẩm chỉ gồm khoảng trắng và ký tự vô hình (`U+200B`, `U+200C`, `U+200D`, `U+2060`, `U+FEFF`, `U+00A0`) | gửi form | bị coi là trống: lỗi "Vui lòng nhập …" của đúng ô đó (chữ đã có ở spec 003, 005, 006); không lưu gì | `FormTextTest`, `*FormValidationTest` |
| AC-007-15 | Ô chữ có ký tự vô hình xen giữa (`Áo​ thun`) | lưu | ký tự vô hình bị bỏ (`Áo thun`); dấu tiếng Việt và xuống dòng trong ô nhiều dòng giữ nguyên (BR-007-6) | `FormTextTest` |
| AC-007-16 | Số điện thoại `+84912345678`, `+84 912 345 678`, `+84-912-345-678`, `84912345678` | đặt hàng | đơn lưu `0912345678`. `+840912345678`, `+1 212 555 0100`, `+8491234567` (thiếu số) bị từ chối với lỗi `Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.` của spec 005 (câu hỏi 5) | `CheckoutFormValidationTest`, `PhoneNumberValidatorTest` |
| AC-007-17 | Ô giá trong form sản phẩm | nhập `1 250 000` với dấu cách không ngắt dòng (`U+00A0`) hoặc dấu cách hẹp (`U+202F`) | lưu giá `1250000`, như khi dùng dấu cách thường (BR-006-5) | `PriceInputTest` |
| AC-007-18 | Form đăng ký, thanh toán | xem trang | mỗi ô chữ có `maxlength` bằng giới hạn của server (họ tên 100, email 255, mật khẩu 64, họ tên người nhận 100, số điện thoại 20, địa chỉ 500, ghi chú 500); server vẫn kiểm như cũ khi request bỏ qua `maxlength` | `AuthControllerTest`, `CheckoutControllerTest` |
| AC-007-19 | Toàn bộ test về form của GĐ3–GĐ6 | chạy sau khi `CheckoutForm` chuyển sang dùng `common.FormText` | vẫn xanh: chữ lỗi, giá trị giữ lại khi lỗi, cách cắt khoảng trắng và giữ xuống dòng không đổi | test hiện có |

**Giao diện và truy cập**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-007-20 | Bất kỳ trang nào (cửa hàng, quản trị, lỗi) | mở trang | trình duyệt hiện favicon của Mini Shop; không còn request favicon trả 404 trong console | `FaviconTest` + checklist |
| AC-007-21 | Bất kỳ trang nào của layout cửa hàng và layout quản trị | nhấn `Tab` lần đầu | phần tử được focus đầu tiên là link `Bỏ qua đến nội dung chính`, hiện rõ khi được focus; bấm vào thì focus chuyển tới vùng nội dung chính (`main`) | `LayoutAccessibilityTest` + checklist |
| AC-007-22 | Mọi link, nút, ô nhập trên các trang | di chuyển bằng bàn phím | luôn thấy viền focus rõ ràng; thứ tự focus theo thứ tự đọc; làm được mọi luồng chính (tìm sản phẩm, thêm giỏ, đặt hàng, hủy đơn, các thao tác quản trị) chỉ bằng bàn phím | checklist |
| AC-007-23 | Mọi chữ trên các trang (kể cả nhãn trạng thái đơn, chữ mờ, chữ trên nút, chữ lỗi) | đo tương phản | tối thiểu 4,5:1 với chữ thường và 3:1 với chữ lớn (WCAG 2.1 AA) | checklist (công cụ đo của skill thiết kế) |
| AC-007-24 | Mọi trang cửa hàng, quản trị và lỗi | xem ở 375px và 1280px | không cuộn ngang cả trang; không lỗi CSP trong console; đúng một `h1`; `html lang="vi"`; ảnh sản phẩm có `alt` là tên sản phẩm (ảnh trang trí có `alt=""`); mọi ô form có `label` | `TemplateCspComplianceTest` + checklist |

**README**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-007-25 | README trên GitHub | mở | đầu trang có badge trạng thái CI của workflow `ci.yml` nhánh `main` | checklist |
| AC-007-26 | README | đọc | có ảnh chụp (PNG trong `docs/images/`, mỗi ảnh ≤ 300 KB) của: danh sách sản phẩm, chi tiết sản phẩm, giỏ hàng, trang thanh toán, chi tiết đơn của khách, dashboard quản trị, chi tiết đơn trong quản trị; ảnh chỉ chứa dữ liệu mẫu, không có dữ liệu cá nhân thật | checklist |
| AC-007-27 | README | đọc | có hai sơ đồ Mermaid (GitHub tự vẽ): (1) các package và chiều phụ thuộc (`admin → catalog/order/storage`, `order → cart → catalog`, `common`, `config`); (2) sơ đồ 7 bảng và quan hệ giữa chúng, khớp với migration V1–V2 | checklist |
| AC-007-28 | README | đọc | có mục `Quyết định thiết kế`: mỗi ADR 0001–0010 một dòng tóm tắt "chọn gì, vì sao" kèm link | checklist |
| AC-007-29 | README | đọc | có mục `Xử lý sự cố` cho ít nhất: Docker chưa chạy, cổng 8080 bị chiếm, dữ liệu mẫu không cập nhật (`docker compose down -v`), test Testcontainers bị bỏ qua khi tắt Docker, dùng `mvnw.cmd` trên Windows, thư mục ảnh upload (`UPLOAD_DIR`) | checklist |
| AC-007-30 | Một người chưa từng thấy dự án, máy có JDK 25 và Docker | làm theo README từ một bản clone mới (thư mục tạm, không dùng `target/` hay volume cũ) | chạy được app, đăng nhập bằng tài khoản demo, đặt một đơn và xử lý nó trong admin, trong khoảng 5 phút (không tính thời gian tải Maven/ảnh Docker lần đầu), không phải đoán bước nào | checklist (`phase-verifier`) |

## 5. Quy tắc nghiệp vụ

- **BR-007-1 (mã HTTP):** trang lỗi luôn trả đúng mã: 400, 403, 404, 405, 413, 500. Không bao giờ chuyển hướng (302) sang một trang lỗi trả 200.
- **BR-007-2 (không lộ chi tiết):** trang lỗi và phản hồi JSON lỗi không bao giờ chứa stack trace, thông điệp exception, tên class, câu SQL, đường dẫn file hay giá trị người dùng gửi lên, ở mọi profile.
- **BR-007-3 (404 riêng giữ nguyên):** sau khi gom cơ chế 404 về một chỗ, các trang "Không tìm thấy sản phẩm", "Không tìm thấy đơn hàng", "Không tìm thấy sản phẩm / đơn hàng / danh mục" (quản trị) giữ đúng view, chữ, link và mã 404 như GĐ2–GĐ6. Trang "Không tìm thấy trang" chung chỉ dùng cho URL không có handler nào.
- **BR-007-4 (403 có hai nghĩa):** thiếu quyền (USER vào `/admin/**`) → trang "Bạn không có quyền truy cập trang này." như cũ. CSRF token thiếu, sai hoặc hết hạn → trang "Phiên làm việc đã hết hạn". Cả hai đều 403 và không đổi dữ liệu.
- **BR-007-5 (log lỗi 500):** mỗi lỗi 500 ghi đúng một dòng `ERROR` (phương thức, đường dẫn không kèm query string, stack trace). Lỗi 4xx không ghi `ERROR` (tối đa `DEBUG`/`WARN`). Không log nội dung form, tham số hay dữ liệu cá nhân.
- **BR-007-6 (làm sạch ô chữ, `common.FormText`):** chuẩn hóa NFC; đổi `\r\n` và `\r` thành `\n`; bỏ ký tự điều khiển (ô nhiều dòng giữ `\n` và `\t`); bỏ ký tự định dạng vô hình (`Cf`: `U+200B`–`U+200D`, `U+2060`, `U+FEFF`…); đổi các dấu cách đặc biệt (`U+00A0`, `U+202F`, `U+2007`) thành dấu cách thường; cắt khoảng trắng hai đầu. Áp dụng cho mọi form có ô chữ: đăng ký, thanh toán, danh mục, sản phẩm. Mật khẩu **không** đi qua bước này (giữ nguyên như người dùng gõ).
- **BR-007-7 (số điện thoại):** sau khi bỏ dấu cách, dấu chấm, dấu gạch ngang: dạng `0` + 9 chữ số giữ nguyên; dạng `+84` hoặc `84` + 9 chữ số (chữ số đầu khác 0) đổi thành `0` + 9 chữ số; mọi dạng khác bị từ chối. Lưu luôn dạng `0xxxxxxxxx` như GĐ5.
- **BR-007-8 (giá):** `PriceInput` coi `U+00A0`, `U+202F` như dấu cách phân cách hàng nghìn; các quy tắc khác của BR-006-5 giữ nguyên (không nhận số 0 ở đầu).
- **BR-007-9 (README là tài liệu sống):** mọi lệnh trong README phải chạy đúng như ghi trên Windows (Git Bash và PowerShell) và Linux/macOS; ảnh chụp và sơ đồ phản ánh code ở thời điểm phát hành `v0.7.0`.

## 6. Giao diện và luồng

Không có URL mới cho người dùng. Các trang lỗi được hiện tại chính URL gây lỗi (không chuyển hướng). File tĩnh mới: favicon (đường dẫn công khai có sẵn trong `SecurityConfig.PUBLIC_PATHS` hoặc thêm có chủ đích).

**Chữ hiển thị chính xác:**

| Vị trí | Nội dung |
|---|---|
| 404 chung | tab `Không tìm thấy trang \| Mini Shop`; `h1` `Không tìm thấy trang`; `Trang bạn tìm không tồn tại hoặc đã bị di chuyển.`; link `Về trang chủ` |
| 400/405 | tab `Yêu cầu không hợp lệ \| Mini Shop`; `h1` `Yêu cầu không hợp lệ`; `Đường dẫn hoặc dữ liệu gửi lên không hợp lệ.`; link `Về trang chủ` |
| 403 CSRF | tab `Phiên làm việc đã hết hạn \| Mini Shop`; `h1` `Phiên làm việc đã hết hạn`; `Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại.`; link `Về trang chủ` |
| 403 không có quyền | giữ nguyên spec 003: `Bạn không có quyền truy cập trang này.`; link `Về trang chủ` |
| 413 | tab `Tệp tải lên quá lớn \| Quản trị Mini Shop`; `h1` `Tệp tải lên quá lớn`; `Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.`; link `Về danh sách sản phẩm` |
| 500 | tab `Đã có lỗi xảy ra \| Mini Shop`; `h1` `Đã có lỗi xảy ra`; `Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.`; link `Về trang chủ` |
| Lỗi trong `/admin/**` (admin đã đăng nhập) | cùng `h1` và nội dung như trên; tab `{Tiêu đề} \| Quản trị Mini Shop`; link `Về trang quản trị` (riêng 413 giữ link `Về danh sách sản phẩm`) |
| Link bỏ qua | `Bỏ qua đến nội dung chính` |
| README | tiêu đề mục `Quyết định thiết kế`, `Xử lý sự cố` |

## 7. Dữ liệu

- **Không có migration, không đổi bảng hay cột.**
- Dữ liệu đã lưu trước GĐ7 không bị sửa lại: quy tắc làm sạch và số điện thoại mới chỉ áp dụng cho dữ liệu nhập từ GĐ7 trở đi.
- Cấu hình có thể đổi (kế hoạch quyết định): cách Tomcat xử lý phần thân request vượt trần upload (`server.tomcat.max-swallow-size`) để AC-007-10 không thành lỗi mất kết nối; các khóa `server.error.*` để chắc chắn không lộ chi tiết lỗi ở mọi profile.

## 8. Yêu cầu phi chức năng

- **Kiến trúc:**
  - một cơ chế 404 dùng chung đặt ở `common`, không phụ thuộc vào package tính năng nào (`commonAndStorage_doNotDependOnFeatures` vẫn xanh); các tính năng chỉ ném `common.NotFoundException` (hoặc biến thể) kèm thông tin trang 404 riêng nếu cần;
  - không còn `@ControllerAdvice`/`@ExceptionHandler` 404 lặp lại trong `catalog`, `cart`, `order`, `admin`;
  - xử lý lỗi không chứa nghiệp vụ; controller không bắt `Exception` chung.
- **Bảo mật:** không lộ chi tiết lỗi (BR-007-2); trang lỗi vẫn theo CSP; phần thân request lớn không làm treo server (kế hoạch nêu giới hạn đọc bỏ phần thân, câu hỏi 3); trang 403 CSRF không tiết lộ token hay nguyên nhân chi tiết.
- **Độ bền:** trang lỗi phải render được khi DB lỗi (AC-007-6): những phần của layout cần DB (số lượng giỏ trên header) phải hỏng một cách êm.
- **Truy cập:** WCAG 2.1 AA cho tương phản chữ, focus nhìn thấy được, link bỏ qua, nhãn form, thông báo flash có `role` (đã có).
- **Giao diện:** dùng lại hướng thiết kế "The specimen catalogue" (cửa hàng) và hướng `.taste-skill/app` (quản trị); không vẽ lại; CSP giữ nguyên; không thêm thư viện JS hay font ngoài.
- **Tài liệu:** CLAUDE.md, CHANGELOG cập nhật; các ghi chú "GĐ7 gom lại…" trong code được xóa khi đã gom.

## 9. Câu hỏi mở

> **Đã trả lời (2026-10-24):** người dùng duyệt cả 13 câu theo khuyến nghị.

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Khách chưa đăng nhập mở một URL không tồn tại (không thuộc đường dẫn công khai): 404 ngay hay chuyển sang đăng nhập? | **Giữ chuyển sang `/login`** (AC-007-2). Đây là "mặc định từ chối" của GĐ3: mọi đường dẫn chưa được mở công khai đều cần đăng nhập, nên khách không dò được URL nào tồn tại. Đổi thành 404 ngay thì phải liệt kê mọi URL cần đăng nhập, dễ sót. |
| 2 | CSRF hết hạn: trang riêng hay dùng chung trang 403? | **Trang riêng "Phiên làm việc đã hết hạn"** (AC-007-8). Trang "không có quyền" hiện nay làm khách tưởng mình bị chặn. Không tự chuyển về `/login`, vì lỗi CSRF xảy ra cả với khách chưa đăng nhập (giỏ hàng). |
| 3 | Tệp lớn hơn trần 10 MB: hiện trang 413 tới cỡ nào? | **Tới 50 MB**: cho Tomcat đọc bỏ phần thân tới 50 MB để trả được trang 413 (AC-007-10 thử 12 MB và 30 MB). Lớn hơn nữa thì chấp nhận trình duyệt báo mất kết nối, để server không phải nhận vô hạn dữ liệu. |
| 4 | Trang lỗi trong `/admin/**` dùng layout nào? | **Layout quản trị** khi người xem là admin (AC-007-11), giống 404 quản trị của GĐ6; mọi nơi khác dùng layout cửa hàng. |
| 5 | Có nhận số điện thoại `+84…` không? | **Có** (BR-007-7): `+84` hoặc `84` + 9 chữ số được đổi về `0…`; vẫn lưu một dạng duy nhất nên dữ liệu cũ không đổi. Không nhận số nước ngoài (brief chỉ bán trong nước, không có phí ship quốc tế). |
| 6 | Ký tự vô hình trong ô chữ: bỏ đi hay báo lỗi? | **Bỏ đi khi làm sạch** (BR-007-6); nếu sau khi bỏ mà ô trống thì báo "Vui lòng nhập …". Báo lỗi riêng thì khách không hiểu vì không nhìn thấy ký tự đó. |
| 7 | Dấu cách không ngắt dòng trong ô giá (dán từ web/Excel)? | **Nhận** như dấu cách thường (AC-007-17); rẻ và tránh lỗi khó hiểu cho admin. |
| 8 | Trang 500 có hiện mã tham chiếu lỗi (để đối chiếu log) không? | **Không** ở GĐ7: cần sinh và ghi mã vào log, trong khi dự án chỉ có một người vận hành đọc log trực tiếp. Có thể thêm ở GĐ8 khi deploy. |
| 9 | README có GIF minh họa luồng đặt hàng không? | **Không**: GIF nặng repo và khó cập nhật khi giao diện đổi; thay bằng 7 ảnh chụp PNG nhỏ (AC-007-26). Có thể thêm sau nếu cần cho portfolio. |
| 10 | Badge CI khi repo đang private? | **Vẫn thêm** (AC-007-25): người có quyền xem repo thấy badge; khi repo chuyển public thì badge tự hiện cho mọi người. |
| 11 | Có thêm tìm đơn theo email/số điện thoại/mã đơn trong admin (đã để dành ở spec 006 câu 10)? | **Không**: GĐ7 chỉ hoàn thiện, không thêm tính năng; ghi vào "Hướng phát triển" trong README. |
| 12 | `/products/` (có dấu `/` ở cuối): 404 hay chuyển hướng về `/products`? | **404 chung** (AC-007-1): Spring 6+ đã bỏ so khớp dấu `/` cuối; thêm chuyển hướng là thêm quy tắc chỉ cho một trường hợp hiếm. |
| 13 | Favicon dạng nào? | **Một file SVG** tự vẽ (chữ "M" theo màu của hướng thiết kế), khai báo bằng `<link rel="icon">` ở cả hai layout, cộng `favicon.ico` để trình duyệt cũ và request mặc định không 404. Không dùng dịch vụ tạo favicon bên ngoài. |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-24 | Tạo bản nháp |
| 2026-10-24 | Người dùng duyệt spec và 13 câu hỏi mở theo khuyến nghị; trạng thái `Đã duyệt` |
| 2026-10-24 | Người dùng duyệt kế hoạch [phase-7](../plans/phase-7.md): BR-007-6 đổi `\r\n` và `\r` thành `\n` (để `maxlength` của trình duyệt và giới hạn của server đếm như nhau); AC-007-7 sửa lỗi gõ URL `/cart/add` thành `/cart/items` (không đổi ý); trạng thái `Đang làm` |
| 2026-10-25 | Sau review: JSON lỗi không còn trả lại đường dẫn (`spring.web.error.include-path: never`, BR-007-2); exception bảo mật bị bọc vẫn để Spring Security xử lý. `phase-verifier` đạt 32/32 mục (một số mục kiểm bằng cách mô phỏng: xóa cookie phiên, hộp `confirm()` của trình duyệt), `./mvnw verify` 1949 test, 0 bỏ qua; bản sao sạch làm theo README chạy được trong khoảng 2 phút. Trạng thái `Hoàn thành` |

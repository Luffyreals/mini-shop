# ADR-0011: Xử lý lỗi tập trung: trang lỗi qua `BasicErrorController`, một cơ chế 404, log 500 bằng resolver cuối chuỗi

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-24

## Bối cảnh

Tới GĐ6, lỗi được xử lý rời rạc:
- ba `@ControllerAdvice` 404 gần giống nhau (`catalog`, `cart`, `order`) và ba `@ExceptionHandler` 404 trong các controller admin;
- URL không tồn tại, lỗi 500, 400/405 rơi vào "Whitelabel Error Page" của Spring Boot;
- CSRF token hết hạn dùng chung trang 403 "Bạn không có quyền truy cập trang này.", làm khách hiểu nhầm;
- tệp lớn hơn trần upload 10 MB làm Tomcat đóng kết nối (ADR-0010, "giới hạn đã biết").

[Spec 007](../specs/007-polish.md) đòi trang tiếng Việt đúng mã HTTP cho mọi lỗi, không lộ chi tiết (BR-007-2), giữ nguyên các trang 404 riêng (BR-007-3), phân biệt hai nghĩa của 403 (BR-007-4), và log mỗi lỗi 500 đúng một lần, không có dữ liệu cá nhân (BR-007-5). Brief §10 ghi "`@ControllerAdvice` ghi log lỗi 500".

## Quyết định

**1. Trang lỗi đi qua `BasicErrorController` của Spring Boot; ta chỉ thay bước chọn view** bằng một bean `ErrorViewResolver` (`common.ErrorPageResolver`). Mọi lỗi (exception ở controller, `sendError` của Spring hay Spring Security, 413 của Tomcat, 404 của file tĩnh, 400 của firewall) đều kết thúc bằng error dispatch tới `/error` ngay tại URL gây lỗi, nên không có chuyển hướng. Chữ nằm trong enum `common.ErrorPage`; có hai template, layout cửa hàng và layout quản trị. Layout quản trị chỉ dùng khi URI gốc là `/admin` hoặc `/admin/…` **và** người xem có `ROLE_ADMIN`. `BasicErrorController` vẫn trả JSON cho client không phải trình duyệt. Các khóa `spring.web.error.include-*` (tên mới ở Boot 4, thay `server.error.*`), kể cả `include-path` (mặc định `always` sẽ trả lại đường dẫn người dùng gõ trong JSON), đặt `never`/`false` tường minh trong `application.yml`, để không profile nào (kể cả dev có DevTools) làm lộ chi tiết lỗi. Đọc jar DevTools 4.1.1 không thấy nó còn đặt các khóa này, nhưng khai báo tường minh vẫn là lưới an toàn rẻ.

**2. Một cơ chế 404:** mỗi controller khai báo trang 404 của mình bằng annotation `common.NotFoundPage` (view, và tùy chọn tiêu đề, link quay lại, mục sidebar); một advice duy nhất `common.NotFoundPageAdvice` (`@ControllerAdvice(annotations = NotFoundPage.class)`, ưu tiên cao nhất) bắt `NotFoundException` và `MethodArgumentTypeMismatchException`, đọc annotation trên controller và trả đúng view với 404. `common` không phụ thuộc tính năng nào. `NotFoundException` có `@ResponseStatus(NOT_FOUND)` làm lưới an toàn cho controller không khai báo trang riêng.

**3. Log lỗi 500 bằng một `HandlerExceptionResolver` đứng cuối chuỗi** (`common.UnhandledExceptionResolver`, `LOWEST_PRECEDENCE`), không dùng `@ControllerAdvice` + `@ExceptionHandler(Exception.class)`. Exception nào tới được resolver này (sau `@ExceptionHandler`, `@ResponseStatus` và `DefaultHandlerExceptionResolver`) mới là lỗi không lường trước: resolver ghi đúng một dòng `ERROR` (phương thức, đường dẫn không kèm query string, stack trace) rồi `sendError(500)`. Vì exception đã được xử lý, Tomcat không ghi thêm dòng thứ hai. Client ngắt kết nối và tham số sai định dạng (`InvalidParameterException`) chỉ log `DEBUG`. Cùng với đó: tắt log `ERROR` riêng của `org.hibernate.engine.jdbc.spi.SqlExceptionHelper` và đặt `logServerErrorDetail=false` cho driver PostgreSQL, để thông điệp lỗi DB không chứa giá trị dữ liệu (họ tên, số điện thoại…). Tên ràng buộc vẫn còn, nên `ConstraintViolations` vẫn chạy.

**4. Hai nghĩa của 403 phân biệt trong `common.AccessDeniedController`,** không đổi `SecurityConfig`: `AccessDeniedHandlerImpl` đã lưu exception gây từ chối vào attribute `WebAttributes.ACCESS_DENIED_403` trước khi forward. Nếu đó là `CsrfException` (thiếu hoặc sai token) thì hiện trang "Phiên làm việc đã hết hạn" (403, layout cửa hàng); còn lại giữ trang 403 cũ.

**5. Trang 413 cho tệp 10–50 MB:** Tomcat 11 đã tự trả 413 khi multipart vượt trần; lỗi "mất kết nối" chỉ do Tomcat không đọc bỏ phần thân lớn hơn `max-swallow-size` (mặc định 2 MB). Đặt `server.tomcat.max-swallow-size: 50MB`; trang 413 do quyết định 1 vẽ. Trên 50 MB vẫn ngắt kết nối, để server không phải nhận vô hạn dữ liệu. Tệp 2–10 MB vẫn là lỗi trên form như ADR-0010.

**6. Trang lỗi phải vẽ được khi DB hỏng:** header chỉ gọi `#cartHeader`, vốn đã nuốt lỗi truy vấn (GĐ4); template lỗi không đọc tham số request (request 413 có phần thân không parse được).

## Hệ quả

- **Tích cực:**
  - Không còn trang Whitelabel; mọi lỗi có trang tiếng Việt đúng mã, không lộ chi tiết ở mọi profile; client JSON vẫn nhận JSON.
  - Một chỗ cho chữ của trang lỗi, một chỗ cho 404; thêm controller mới chỉ cần một annotation.
  - Mỗi lỗi 500 đúng một dòng log, không có dữ liệu cá nhân, kể cả lỗi ràng buộc DB.
  - Khách để form quá lâu được báo đúng nguyên nhân; admin chọn nhầm tệp lớn thấy trang 413 thay vì mất kết nối.
- **Tiêu cực / đánh đổi:**
  - Exception ném ra trong lúc render template hoặc trong một filter không đi qua resolver: Tomcat log nó (một dòng `ERROR`, không có phương thức và đường dẫn) và vẫn hiện trang 500 nếu response chưa gửi đi.
  - Request lớn hơn 50 MB vẫn bị ngắt kết nối; server đọc bỏ tới 50 MB phần thân của request vượt trần. Mọi POST bị từ chối mà không đọc thân (CSRF 403, 404…) cũng được đọc bỏ tới 50 MB thay vì 2 MB, nên một client gửi rất chậm giữ thread lâu hơn. Không khuếch đại băng thông (kẻ tấn công phải gửi từng byte); trước khi deploy (GĐ8) cần giới hạn kích thước thân và request chậm ở reverse proxy.
  - Hai lỗi 500 do `DefaultHandlerExceptionResolver` tự xử lý (`ConversionNotSupportedException`, `HttpMessageNotWritableException`) ra trang 500 nhưng không đi tới resolver của ta nên không có dòng `ERROR`; cả hai đều là lỗi lập trình hiếm gặp, sẽ lộ ra khi test.
  - H2 vẫn đưa giá trị vào thông điệp lỗi ràng buộc (chỉ ảnh hưởng profile `h2` dùng cho dev).
  - Log `ERROR` của `SqlExceptionHelper` bị tắt: chi tiết SQL chỉ còn trong chuỗi cause của dòng log duy nhất.
  - Brief §10 ghi `@ControllerAdvice`; vai trò giữ nguyên, cơ chế khác (lý do ở quyết định 3).

## Phương án đã cân nhắc

- **Template theo quy ước** (`error/404.html`, `error/4xx.html`, `error/5xx.html`): muốn chọn layout quản trị thì phải viết điều kiện theo URL trong từng template, và chữ bị chép ra nhiều file.
- **Tự viết `ErrorController`:** phải viết lại thương lượng nội dung và phần JSON mà Boot đã làm đúng.
- **Một `@ControllerAdvice` cho mọi lỗi:** chỉ thấy lỗi trong controller (không thấy 404 file tĩnh, 400 firewall, 413 Tomcat, lỗi filter), và `@ExceptionHandler(Exception.class)` chạy trước các bước xử lý 400/404/405 của Spring nên nuốt nhầm chúng.
- **`NotFoundException` mang tên view:** service phải biết tên trang; `MethodArgumentTypeMismatchException` do Spring ném nên không mang được.
- **Bảng "controller → view" trong `common`:** `common` sẽ phụ thuộc vào các tính năng.
- **`AccessDeniedHandler` riêng + URL `/session-expired`:** thêm code và một đường dẫn công khai.
- **Chuyển về `/login` khi CSRF hết hạn:** khách chưa đăng nhập cũng gặp lỗi CSRF (giỏ hàng).
- **Nâng trần multipart lên 50 MB:** server ghi tạm 50 MB ra đĩa cho mỗi request trước khi từ chối trong ứng dụng.

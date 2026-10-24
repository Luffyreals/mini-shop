# Kế hoạch GĐ7: Hoàn thiện (trang lỗi, validation, giao diện, README)

- **Spec:** [007 Hoàn thiện](../specs/007-polish.md) (Đã duyệt 2026-10-24; người dùng chấp nhận cả 13 khuyến nghị ở §9: khách mở URL không công khai vẫn chuyển `/login`, trang CSRF riêng, trang 413 tới 50 MB, lỗi trong `/admin/**` dùng layout quản trị, nhận `+84`, bỏ ký tự vô hình, nhận NBSP trong ô giá, không mã tham chiếu lỗi, không GIF, vẫn thêm badge CI, không thêm tìm đơn trong admin, `/products/` là 404 chung, favicon SVG tự vẽ + `favicon.ico`).
- **Nhánh:** `phase-7/polish` (đã checkout). Spec 007 và dòng mới trong `docs/specs/README.md` (đang chưa commit) được commit trên nhánh này.
- **Đã có từ GĐ1–6, không làm lại:**
  - `/error` và `/access-denied` mở cho mọi method (GĐ3); `common.AccessDeniedController` + `error/403.html`; `/favicon.ico` đã nằm trong `SecurityConfig.PUBLIC_PATHS`;
  - `cart.CartHeader.quantity()` đã bắt `RuntimeException`, log `WARN` rồi hiện 0 (GĐ4), nên header đã "hỏng êm" khi DB lỗi (AC-007-6 chỉ cần test);
  - `common.FormText` (GĐ6), đã dùng trong mọi form quản trị; `common.NotFoundException`; `order.PhoneNumberValidator`; `admin.PriceInput`;
  - trần multipart 10 MB và kiểm 2 MB trong `ImageUpload` (ADR-0010); `admin.AdminUploadLimitTest` (khuôn test trên Tomcat thật bằng `HttpClient`).
- **Không đổi** `pom.xml`, `compose.yaml`, CI, schema. **Không có migration**, không đổi entity, nên **không cần** `schema-guardian` ở GĐ7. Nếu task nào thấy buộc phải đổi `@Column` hay thêm migration thì dừng lại và báo phiên chính.
- **Đã kiểm phiên bản thật** (đọc jar trong `~/.m2` và mã nguồn chính thức, không đoán):
  - Spring Boot 4.1.1: khóa `server.error.*` **đã bị bỏ** từ 4.0.0 (deprecation mức `error`), thay bằng `spring.web.error.*`. DevTools đặt `spring.web.error.include-message`, `include-stacktrace`, `include-binding-errors` = `always` (file `META-INF/spring-devtools.properties` trong `spring-boot-autoconfigure-4.1.1.jar`). `DefaultErrorViewResolver` chỉ được tạo khi chưa có bean `ErrorViewResolver` nào (`@ConditionalOnMissingBean`). `server.tomcat.max-swallow-size` mặc định `2MB`. `spring.security.filter.dispatcher-types` mặc định gồm cả `error` và `forward`.
  - Tomcat 11.0.24: khi multipart vượt trần, `Request.parseParts` gọi `checkSwallowInput()` rồi lưu `InvalidParameterException` mang mã **413**; `getParameter` ném lại exception đó; `StandardWrapperValve` đặt status theo mã của nó, chỉ log ở mức `DEBUG`, rồi chuyển sang trang lỗi `/error`.
  - Spring Security 7.1.1: `AccessDeniedHandlerImpl` lưu exception vào request attribute `WebAttributes.ACCESS_DENIED_403` trước khi forward; `MissingCsrfTokenException` và `InvalidCsrfTokenException` cùng kế thừa `org.springframework.security.web.csrf.CsrfException`; `CsrfFilter` là `OncePerRequestFilter` và không chạy lại ở error dispatch.
  - ArchUnit 1.5.1 có `TryCatchBlock` (dùng cho rule "controller không bắt `Exception` chung").

## 1. Mục tiêu

- Mọi lỗi (URL không tồn tại, yêu cầu sai, 405, CSRF hết hạn, tệp quá lớn, lỗi hệ thống) hiện một trang tiếng Việt **đúng mã HTTP**, ngay tại URL gây lỗi, trong layout cửa hàng (hoặc layout quản trị khi admin ở `/admin/**`). Không còn trang Whitelabel, stack trace, chữ của exception, kể cả khi chạy có DevTools. Client JSON nhận JSON mặc định của Boot không có `trace`/`message`/`exception`/`errors`.
- Bốn cơ chế 404 rời rạc gom về một chỗ trong `common`; mọi trang 404 riêng của GĐ2–6 giữ nguyên từng chữ. Mỗi lỗi 500 ghi đúng một dòng `ERROR` (method + đường dẫn, không query, không dữ liệu cá nhân); lỗi 4xx không ghi `ERROR`.
- Form chặn được dữ liệu "trông như trống" (ký tự vô hình), nhận số `+84…` và NBSP trong ô giá; giao diện có favicon, link "Bỏ qua đến nội dung chính", viền focus rõ, tương phản đạt WCAG 2.1 AA ở 375px và 1280px.
- README đủ để người lạ chạy dự án trong khoảng 5 phút (kiểm trên một bản sao sạch), có badge CI, 7 ảnh chụp, hai sơ đồ Mermaid, mục "Quyết định thiết kế" và "Xử lý sự cố". `./mvnw verify` xanh **với 0 test bị bỏ qua** (Docker bật); mọi AC-007-1 đến AC-007-30 có test hoặc mục checklist (mục 4 và mục 5).

## 2. Quyết định thiết kế

**QĐ1. Trang lỗi: giữ `BasicErrorController` của Spring Boot, chỉ thay bước chọn view bằng một `ErrorViewResolver` riêng (`common.ErrorPageResolver`). Chữ nằm trong enum `common.ErrorPage`; có hai template (cửa hàng và quản trị).**
- **Cơ chế:** mọi lỗi đều kết thúc bằng một *error dispatch* của Tomcat tới `/error`, ngay tại URL gây lỗi, nên không có chuyển hướng 302 nào (BR-007-1). Các nguồn lỗi gồm exception ở controller, `sendError` của Spring hay Spring Security, lỗi của Tomcat như 413, và 404 của tài nguyên tĩnh.
  - `BasicErrorController` tự lo thương lượng nội dung: trình duyệt (`Accept: text/html`) nhận trang HTML, còn client khác nhận JSON (AC-007-12).
  - Ta chỉ thay bước "chọn template nào". Vì Boot chỉ tạo `DefaultErrorViewResolver` khi chưa có bean `ErrorViewResolver`, bean của ta thay hẳn nó.
- **Bảng mã → trang** (`ErrorPage.forStatus`):

  | Mã | Trang |
  |---|---|
  | 404 | `NOT_FOUND` |
  | 413 | `PAYLOAD_TOO_LARGE` |
  | 403 | view `error/403` cũ (lưới an toàn; CSRF đi đường QĐ5) |
  | ≥ 500, hoặc mã lạ < 400 | `SERVER_ERROR` |
  | mọi 4xx khác (400, 401, 405, 415, 429…) | `BAD_REQUEST`. Spec §2: các 4xx hiếm gặp dùng chung trang "Yêu cầu không hợp lệ" |
- **Layout (AC-007-11, câu hỏi 4):** chỉ dùng `admin/error` khi thỏa **cả hai** điều kiện sau; mọi trường hợp khác dùng `error/page`.
  - URI gốc (`jakarta.servlet.error.request_uri`) là `/admin` hoặc bắt đầu bằng `/admin/`. Phải so cả dấu `/` để `/administrator` không bị tính là khu quản trị.
  - Người xem có `ROLE_ADMIN`. Cần điều kiện này vì sidebar quản trị đọc `#authentication.principal.fullName`, mà người chưa đăng nhập thì không có.
- **Chữ ở một chỗ:** enum giữ tiêu đề và câu mô tả cho cả hai layout. Template chỉ in `errorTitle`, `errorHeading`, `errorMessage`, `backUrl`, `backLabel`. Hậu tố tab (`| Mini Shop` hay `| Quản trị Mini Shop`) và link quay lại do `ErrorPage` quyết.
- **Khóa cấu hình (task DV1):** đặt `spring.web.error.include-stacktrace: never`, `include-message: never`, `include-binding-errors: never`, `include-exception: false`.
  - Giá trị trong `application.yml` thắng giá trị của DevTools, vì DevTools thêm nguồn thuộc tính của nó vào cuối danh sách. Khai báo tường minh như vậy là đủ cho BR-007-2 "ở mọi profile".
  - Trang HTML của ta vốn không in thuộc tính nào; các khóa này bảo vệ phản hồi JSON và trang dự phòng.
- **Whitelabel giữ bật** làm lưới cuối cùng. Resolver luôn trả view cho mọi mã nên bình thường không bao giờ rơi tới nó, và nếu có rơi tới thì cũng không lộ gì vì `include-*` đều là `never`. Nếu tắt Whitelabel thì lưới cuối thành trang của Tomcat.

*Phương án bị loại:*
- **Template theo quy ước** (`error/404.html`, `error/4xx.html`, `error/5xx.html`): muốn chọn layout quản trị thì phải viết `th:if` theo URL ngay trong template, và chữ bị chép ra 2 × 4 file.
- **Tự viết `ErrorController`:** phải viết lại phần JSON và thương lượng nội dung mà Boot đã làm đúng.
- **Một `@ControllerAdvice` cho mọi lỗi:** advice chỉ thấy lỗi phát sinh trong controller. Nó không thấy 404 của file tĩnh, 400 của firewall Spring Security, 413 của Tomcat hay lỗi trong filter.

**QĐ2. Gom 404: mỗi controller tự khai báo trang 404 của mình bằng annotation `@NotFoundPage`; một advice duy nhất trong `common` đọc annotation đó.**
- `common.NotFoundPage` (public, `@Target(TYPE)`) có các thuộc tính `view`, cộng các thuộc tính tùy chọn `title`, `backUrl`, `backLabel`, `section` (dùng cho trang 404 quản trị). Danh sách annotation cho từng controller ở mục 3.
- `common.NotFoundPageAdvice` khai báo `@ControllerAdvice(annotations = NotFoundPage.class)` và `@Order(HIGHEST_PRECEDENCE)` (giữ ghi chú của GĐ2: Spring chọn advice theo `@Order`, không theo độ cụ thể).
  - Advice bắt `NotFoundException` và `MethodArgumentTypeMismatchException`.
  - Nó nhận tham số `HandlerMethod` (Spring cho phép trong `@ExceptionHandler`), đọc `@NotFoundPage` trên lớp controller, thêm `notFoundTitle`, `backUrl`, `backLabel`, `section` vào model (chỉ khi khác rỗng; đúng tên model mà `admin/not-found.html` đang dùng), rồi trả view với 404.
  - Không có nghiệp vụ nào ở đây và không phụ thuộc tính năng nào (BR-007-3, spec §8).
- **Lưới an toàn:** `NotFoundException` được gắn `@ResponseStatus(NOT_FOUND)`. Controller không có annotation mà ném nó thì `ResponseStatusExceptionResolver` gọi `sendError(404)`, nên người dùng thấy trang "Không tìm thấy trang" chung (QĐ1) thay vì 500.
- **Id sai ở controller không khai báo:** advice chỉ áp dụng cho controller có annotation, nên `MethodArgumentTypeMismatchException` ở controller khác vẫn thành 400 theo mặc định của Spring. Đây đúng là "sai định dạng mà chưa có xử lý riêng" của AC-007-7.
- **Vì sao test cũ chứng minh được "không đổi":** `@WebMvcTest` tự nạp mọi `@ControllerAdvice`. Vì vậy các slice test hiện có (`ProductControllerTest`, `CartControllerTest`, `OrderControllerTest`, `Admin*ControllerTest`) chạy qua cơ chế mới mà không phải sửa dòng nào (AC-007-3).
- Xóa `ProductNotFoundAdvice`, `CartNotFoundAdvice`, `OrderNotFoundAdvice` và ba `@ExceptionHandler` trong controller admin. Các comment "GĐ7 gom lại" mất theo file.

*Phương án bị loại:*
- **`NotFoundException` mang tên view:** service (nơi ném exception) sẽ phải biết tên trang; còn `MethodArgumentTypeMismatchException` do Spring ném, không qua tay ta.
- **Bảng "controller → view" đặt trong `common`:** `common` sẽ phụ thuộc vào các tính năng, vi phạm `commonAndStorage_doNotDependOnFeatures`.
- **Giữ sáu handler:** spec §8 cấm.

**QĐ3. Log lỗi 500 đúng một lần bằng một `HandlerExceptionResolver` đứng cuối chuỗi (`common.UnhandledExceptionResolver`), không dùng `@ControllerAdvice` với `@ExceptionHandler(Exception.class)`.**
- **Vì sao đứng cuối:** `DispatcherServlet` hỏi các resolver theo thứ tự:
  1. `@ExceptionHandler` và advice;
  2. `@ResponseStatus`;
  3. `DefaultHandlerExceptionResolver` (405, 400, 404 của file tĩnh, `MaxUploadSizeExceededException` → 413…).

  Exception nào còn tới được resolver của ta (`Ordered.LOWEST_PRECEDENCE`) thì theo định nghĩa là lỗi không lường trước. Ngược lại, một advice `@ExceptionHandler(Exception.class)` chạy **trước** bước 2–3, nên sẽ nuốt luôn 405, 400, 404 và buộc ta giữ một danh sách exception để ném lại. Danh sách đó dễ sót.
- **Việc resolver làm:**
  - ghi `log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), ex)`. `getRequestURI()` không có query string. Không log tham số, thân request hay principal (BR-007-5);
  - gọi `response.sendError(500)`, trả `ModelAndView` rỗng, rồi trang được vẽ theo QĐ1;
  - vì exception đã được "xử lý", nó không lọt ra Tomcat, nên Tomcat không ghi thêm dòng `Servlet.service() … threw exception`. Đó là cách đạt "đúng một dòng".
- **Hai trường hợp riêng:**
  - client đã ngắt kết nối (`DisconnectedClientHelper.isClientDisconnectedException`): chỉ log `DEBUG`, không `sendError`;
  - trong chuỗi cause có `org.apache.tomcat.util.http.InvalidParameterException` (tham số sai định dạng bị đọc trong controller, ví dụ `?q=%zz`): gọi `sendError(e.getErrorCode())` (400/413) và log `DEBUG`. Đây là lỗi của client, không được thành 500 + `ERROR`.
- **Bỏ các nguồn `ERROR` thứ hai và dữ liệu cá nhân trong log (task DV1):**
  - `logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper: off`. Hibernate tự log lỗi JDBC ở mức `ERROR`, kể cả các ca đã xử lý êm (đua trùng email, trùng tên danh mục), nên một lỗi DB sẽ thành hai dòng. Thông tin SQL vẫn còn trong chuỗi cause của dòng log duy nhất;
  - `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false`. Khi đó pgjdbc bỏ phần `Detail: Key (email)=(…)` / `Failing row contains (…)` khỏi thông điệp; phần này có thể chứa họ tên, số điện thoại, địa chỉ. Tên ràng buộc vẫn nằm trong thông điệp chính, nên `common.ConstraintViolations` vẫn chạy.
- **Giới hạn đã biết:** exception ném ra *trong lúc render template* hoặc *trong một filter* không đi qua resolver. Tomcat log nó một lần ở `ERROR` (dòng này không có method và đường dẫn) và vẫn hiện trang 500 nếu response chưa gửi đi. Chấp nhận được; mục 6 ghi lại.
- Brief §10 ghi "`@ControllerAdvice` ghi log lỗi 500". Vai trò giữ nguyên, chỉ khác cơ chế; ADR-0011 ghi lý do.

*Phương án bị loại:*
- **Advice kèm danh sách exception để ném lại:** xem trên.
- **Log trong `ErrorPageResolver` lúc error dispatch:** lúc đó Tomcat đã log rồi, thành hai dòng; còn các 4xx do `sendError` thì không có exception để log.
- **Filter bọc `try/catch`:** lỗi trong MVC đã được chuyển thành response trước khi về tới filter, nên filter không bao giờ thấy chúng.

**QĐ4. Trang lỗi vẫn vẽ được khi DB hỏng, và vẫn biết ai đang đăng nhập.**
- `CartHeader` đã nuốt lỗi truy vấn (GĐ4), nên AC-007-6 chỉ cần test, không cần sửa code.
- **Security trên error dispatch:**
  - Boot đăng ký chuỗi filter cho cả dispatch `error`. `SecurityContextHolderFilter` nạp lại context từ session, nên `#authentication`, `sec:authorize` và sidebar quản trị đều chạy.
  - `/error` đã mở cho mọi method từ GĐ3.
  - `CsrfFilter` không chạy lại ở error dispatch. Nhưng attribute `_csrf` mà nó đặt ở lượt đầu vẫn nằm trên request, nên form `Đăng xuất` trên trang lỗi vẫn có token hợp lệ.
  - Với 413 cũng vậy: `CsrfFilter` đặt attribute **trước** khi đọc tham số (bước gây lỗi).
- Template lỗi không gọi gì ngoài `#cartHeader`, và không đọc `param`/`#request`. Lý do: request 413 có phần thân không parse được, đọc tham số sẽ ném lại lỗi.

**QĐ5. CSRF: phân biệt hai nghĩa của 403 trong `common.AccessDeniedController`; không đổi `SecurityConfig`.**
- **Hiện nay:** `accessDeniedPage("/access-denied")` tạo một `AccessDeniedHandlerImpl` dùng chung cho hai đường: `ExceptionTranslationFilter` (USER vào `/admin`) và `CsrfFilter` (token thiếu hoặc sai). Trước khi forward, nó lưu exception vào `WebAttributes.ACCESS_DENIED_403`.
- **Controller mới:**
  - attribute là `org.springframework.security.web.csrf.CsrfException` (gồm cả `Missing…` và `Invalid…`) → trang "Phiên làm việc đã hết hạn" (`ErrorPage.SESSION_EXPIRED`, view `error/page`, 403);
  - còn lại → `error/403` như cũ (AC-007-9, BR-007-4).
  - Trang không hiện token hay nguyên nhân chi tiết (spec §8).
- **Luôn dùng layout cửa hàng:** phiên có thể đã hết hạn, nên không giả định được người xem còn là admin; chữ của spec cũng dùng `| Mini Shop`.
- `POST /logout` với token cũ: `CsrfFilter` chạy trước `LogoutFilter`, nên người dùng thấy trang phiên hết hạn và **vẫn đăng nhập**. Đúng ý AC-007-8.
- Các test hiện có kiểm `forwardedUrl("/access-denied")` cho ca thiếu CSRF vẫn đúng; TW4 chỉ thêm kiểm loại exception trong attribute.

*Phương án bị loại:*
- **`AccessDeniedHandler` tự viết + URL mới `/session-expired`:** nhiều code hơn và thêm một đường dẫn công khai.
- **Chuyển về `/login`:** spec §9 câu 2 đã loại, vì khách chưa đăng nhập cũng gặp lỗi CSRF (giỏ hàng).
- **`sendError(403)` + attribute:** chạy được, nhưng MockMvc không làm error dispatch nên không kiểm được ở slice test.

**QĐ6. 413 cho tệp 10–50 MB: Tomcat 11 đã tự trả 413; ta chỉ cho Tomcat đọc bỏ phần thân tới 50 MB, còn trang thì QĐ1 vẽ.**
- **Chuỗi sự kiện** (đọc mã nguồn Tomcat 11.0.x, khớp con số "HTTP 413" đo ở GĐ6):
  1. `CsrfFilter` đọc `_csrf`;
  2. Tomcat parse multipart, gặp vượt trần, ném `InvalidParameterException(413)`;
  3. `StandardWrapperValve` đặt status 413 (chỉ log `DEBUG`);
  4. error dispatch tới `/error`;
  5. `ErrorPageResolver` vẽ trang `PAYLOAD_TOO_LARGE`, layout quản trị nếu admin ở `/admin/**` (link `Về danh sách sản phẩm`).
- **Thứ duy nhất phải đổi:** `server.tomcat.max-swallow-size: 50MB` (DV1). Với mặc định 2 MB, Tomcat **đóng kết nối** khi phần thân còn lại lớn hơn 2 MB, và trình duyệt báo "connection reset" (đúng lỗi AC-007-10 muốn bỏ).
  - Trên 50 MB vẫn bị ngắt, theo §9 câu 3: server không đọc vô hạn dữ liệu.
  - Phần thân bị *đọc bỏ*, không ghi ra đĩa.
- Tệp 2–10 MB không đổi: vẫn là lỗi `Ảnh tối đa 2 MB.` trên form (ADR-0010, `AdminUploadLimitTest`).
- Nếu việc parse lần đầu xảy ra trong `DispatcherServlet` (`MaxUploadSizeExceededException`), `DefaultHandlerExceptionResolver` vẫn ra 413 và cùng trang đó.
- Ngoài `/admin/**`, hoặc người xem không phải admin, thì dùng trang 413 kiểu cửa hàng, link `Về trang chủ`. Trường hợp này chỉ xảy ra khi request bị dựng tay, hoặc form urlencoded lớn hơn 2 MB.

*Phương án bị loại:*
- **Filter riêng parse multipart trước `CsrfFilter`:** làm lại việc Tomcat đã làm.
- **Nâng trần multipart lên 50 MB:** server sẽ ghi tạm 50 MB ra đĩa cho mỗi request rồi mới từ chối trong ứng dụng.
- **`MultipartFilter` trước Spring Security:** cần đưa CSRF token lên URL (GĐ6, QĐ6).

**QĐ7. Validation: mở rộng `common.FormText` theo BR-007-6 và dùng cho mọi form; số `+84` chuẩn hóa ngay trong `PhoneNumberValidator`; NBSP trong `PriceInput`.**
- **`FormText.clean(value, multiline)`**, xử lý theo thứ tự:
  1. `null` giữ `null`;
  2. duyệt từng code point:
     - ô nhiều dòng giữ `\n`, `\r`, `\t`;
     - mọi dấu cách loại `Zs` (`U+00A0`, `U+202F`, `U+2007`, `U+2000`–`U+200A`, `U+3000`…) đổi thành `U+0020`;
     - mọi ký tự định dạng `Cf` bị bỏ (`U+200B`–`U+200D`, `U+2060`, `U+FEFF`, cả `U+00AD` và các ký tự điều khiển chiều chữ `U+202A`–`U+202E`, `U+2066`–`U+2069`);
     - mọi ký tự điều khiển `Cc` còn lại bị bỏ;
  3. chuẩn hóa NFC **sau** bước lọc, để dấu tiếng Việt bị một ký tự vô hình chen giữa vẫn ghép lại được;
  4. `strip()`.

  Bước 2 phải chạy trước `strip()` vì `String.strip()` **không** coi NBSP là khoảng trắng.
- Mở rộng từ ba ký tự BR liệt kê lên cả nhóm `Zs`/`Cf` để không phải sửa lại khi gặp ký tự cùng loại. Hệ quả nhỏ: chuỗi emoji ghép bằng `U+200D` (👨‍👩‍👧) tách thành từng emoji (mục 6).
- **Áp dụng:**
  - `RegistrationForm` (họ tên, email; **không** áp cho mật khẩu);
  - `CheckoutForm` (bỏ hàm `clean` riêng, dùng `FormText`, AC-007-19);
  - form danh mục và sản phẩm (đã dùng sẵn, tự nhận quy tắc mới);
  - `user.EmailNormalizer`: đổi `strip()` thành `FormText.clean(…, false)` rồi chữ thường, để đăng ký và đăng nhập chuẩn hóa email **giống hệt nhau** (ADR-0007: một chỗ duy nhất).
- **Số điện thoại (BR-007-7):** `PhoneNumberValidator.normalize` bỏ dấu cách, dấu chấm, dấu gạch; nếu kết quả khớp `\+?84([1-9][0-9]{8})` thì trả `"0" + nhóm 1`, nếu không thì trả nguyên chuỗi đã bỏ dấu (để `isValid` từ chối).
  - Ví dụ bị từ chối: `+840912345678` (sau 84 là số 0), `+1 212 555 0100`, `+8491234567` (thiếu số).
  - Hai dạng `0xxxxxxxxx` (10 chữ số) và `84xxxxxxxxx` (11 chữ số) dài khác nhau nên không thể lẫn.
  - Form vẫn giữ chữ khách gõ; `normalizedPhone()` là thứ được lưu. Chữ lỗi không đổi.
- **Giá (BR-007-8):** `PriceInput.parse` đổi `U+00A0`, `U+202F`, `U+2007` thành dấu cách thường **trước** `strip()` và trước khi so mẫu. Form sản phẩm đã đi qua `FormText` nên thường đã sạch; nhưng `PriceInputTest` gọi thẳng `parse`, nên `parse` phải tự lo.
- **`maxlength` (AC-007-18):** đăng ký: họ tên 100, email 255, mật khẩu và nhập lại 64. Thanh toán: số điện thoại 20; ba ô còn lại đã có.
  - 20 là độ dài cột `phone`. Server không có `@Size` cho số điện thoại vì validator chỉ nhận đúng 10 chữ số sau khi bỏ dấu, nên dữ liệu lưu không bao giờ vượt cột.
- Dữ liệu đã lưu không bị làm sạch lại (spec §7).
- **Xuống dòng `\r\n`:** xem câu hỏi 1 ở mục 6.

*Phương án bị loại:*
- **Báo lỗi riêng khi gặp ký tự vô hình:** spec §9 câu 6 đã loại.
- **Regex `\p{Cf}` chép vào từng form:** sẽ có nhiều bản lệch nhau.
- **Annotation `@NotBlank` tự viết:** kiểm được "trống" nhưng giá trị lưu vẫn còn ký tự vô hình.

**QĐ8. Giao diện và truy cập: dùng lại hai hướng thiết kế có sẵn, không vẽ lại, không thêm khối hay tính năng.**
- **Favicon (§9 câu 13):**
  - `static/images/favicon.svg`: chữ "M" theo token màu của "The specimen catalogue". Đặt dưới `/images/**`, đường dẫn đã công khai, nên **không** phải thêm vào `PUBLIC_PATHS`.
  - `static/favicon.ico`: đã công khai. File `.ico` được tạo tại máy:
    1. render SVG ở 16, 32 và 48 px bằng Chrome headless (qua `lib/cdp.mjs` của skill);
    2. bọc các PNG vào một file ICO bằng một script Node nhỏ (định dạng ICO cho phép chứa PNG: 6 byte header + 16 byte cho mỗi ảnh);
    3. script nằm trong `.taste-skill/favicon/` (gitignore); không dùng dịch vụ ngoài.
  - Cả hai `<head>` khai báo `<link rel="icon" href="/favicon.ico" sizes="any">` và `<link rel="icon" href="/images/favicon.svg" type="image/svg+xml">`. Thêm một dòng vào `credits.html` (tự vẽ, MIT).
- **Link bỏ qua (AC-007-21):** fragment `fragments/layout :: skipLink` (`<a class="skip-link" href="#main">Bỏ qua đến nội dung chính</a>`) là con đầu tiên của `<body>` ở **mọi** trang: cửa hàng, quản trị, lỗi. Đích là `<main id="main" tabindex="-1">`. `tabindex="-1"` giúp mọi trình duyệt chuyển focus vào `main`, không cần JavaScript. Link ẩn khỏi màn hình cho tới khi được focus.
  - Vì sao không đặt trong `header`/`aside`: link nằm ngoài mọi landmark, đúng với ý "bỏ qua phần header"; và chữ chỉ có ở một chỗ.
- **Focus (AC-007-22):** `:focus-visible` có viền 2–3px màu mực của token, `outline-offset` 2px, trong `app.css` và `admin.css`. Không còn `outline: none` nào thiếu phần thay thế.
- **Tương phản (AC-007-23):**
  - `inspect.mjs` của skill đã đo tương phản theo đúng ngưỡng 4,5:1 và 3:1 (`lib/probe.mjs`);
  - kèm một bảng tỉ lệ cho mọi cặp token chữ/nền trong `app.css` và `admin.css`, gồm nhãn trạng thái đơn, chữ mờ, chữ lỗi, chữ trên nút;
  - trang cần đăng nhập thì kiểm bằng một script CDP có bước đăng nhập, hoặc kiểm tay bằng DevTools như GĐ6.
- **Quét 375px và 1280px** mọi trang cửa hàng, quản trị và lỗi (AC-007-13, 24), không cuộn ngang. Cửa hàng theo page run, `/admin/**` theo app-screen run (`references/app-screens.md`).
- CSP giữ nguyên; `TemplateCspComplianceTest` quét cả template mới.

*Phương án bị loại:*
- **Dịch vụ tạo favicon trên mạng:** spec §9 câu 13 đã loại.
- **JavaScript để dời focus khi bấm link bỏ qua:** không cần, `tabindex="-1"` đã đủ.

**QĐ9. README: phiên chính viết chữ, `frontend-developer` chụp ảnh, `phase-verifier` chạy thử trên bản sao sạch.**
- **Ảnh chụp (AC-007-26):** 7 file PNG trong `docs/images/`: `products.png`, `product-detail.png`, `cart.png`, `checkout.png`, `order-detail.png`, `admin-dashboard.png`, `admin-order-detail.png`.
  - Chụp từ jar đã đóng gói, profile `h2`: mỗi lần khởi động lại seed từ đầu, nên ai chụp lại cũng ra cùng dữ liệu.
  - Khung hình 1280×800, tỉ lệ điểm ảnh 1, chỉ phần đầu trang.
  - Thông tin nhận hàng là dữ liệu giả (`Khách hàng mẫu`, `0900000000`, `12 Đường Mẫu, Phường 1, Quận 1, TP. Hồ Chí Minh`). Tài khoản là tài khoản seed.
  - Script chụp nằm ở `.taste-skill/readme-shots/` (gitignore, như mọi file làm việc khác của skill).
  - Script chạy bằng đường dẫn file (không qua stdin) với `timeout`, và đóng Chrome trong `finally`. Đây là bài học đã ghi trong bộ nhớ: script Chrome headless hoặc đọc stdin bị treo trong Git Bash.
  - Mỗi ảnh ≤ 300 KB: trước hết chỉnh khung hình và cắt ảnh; nếu vẫn lớn hơn thì xem câu hỏi 2.
- **Sơ đồ Mermaid (AC-007-27):** sơ đồ package suy từ `ArchitectureTest` và các `import com.minishop.*` thật; sơ đồ bảng (`erDiagram`) suy từ `V1__init_schema.sql`, `V2__users_email_lowercase.sql`. Không vẽ theo trí nhớ.
- **Quyết định thiết kế (AC-007-28):** ADR 0001–0011, mỗi ADR một dòng "chọn gì, vì sao" kèm link.
- **Xử lý sự cố (AC-007-29):** đủ sáu mục của AC. Ghi chú PowerShell: tham số `-D…` phải đặt trong nháy kép (`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"`), biến môi trường dùng `$env:UPLOAD_DIR = "…"`.
- **Giấy phép ảnh:** ảnh chụp có ảnh sản phẩm mẫu theo CC BY / CC BY-SA, nên README ghi một dòng ghi công kèm link tới `src/main/resources/static/credits.html` và nói rõ ảnh chụp chia sẻ theo cùng điều kiện.
- **Sửa lỗi đang có trong README:** bảng lệnh bị một dòng trống cắt đôi; ví dụ `-Dtest=HomeControllerTest` trỏ tới lớp không còn tồn tại (`CLAUDE.md` cũng có lỗi này) → đổi sang `ProductControllerTest`.
- **Bản sao sạch (AC-007-30):** agent không được commit, nên verifier xuất cây thư mục làm việc (file đã theo dõi + file mới, trừ file gitignore và file đã xóa) sang một thư mục tạm. Tên thư mục khác thì tên project Compose khác, nên volume mới và DB trống. Cổng PostgreSQL là cổng ngẫu nhiên (`'5432'`), nên không đụng container dev.

**QĐ10. Có ADR-0011 "Xử lý lỗi tập trung".**
- Đây là thay đổi kiến trúc:
  - đổi khuôn 404 đã dùng từ GĐ2 tới GĐ6;
  - đổi cơ chế "`@ControllerAdvice` log 500" mà brief ghi;
  - đụng tới bảo mật (CSRF, giới hạn thân request của Tomcat) và chính sách log (không có dữ liệu cá nhân).
- Nội dung là QĐ1–QĐ6 cùng các phương án bị loại. ADR này bổ sung ADR-0010 (trang 413 thay cho "giới hạn đã biết" trên 10 MB), không thay thế ADR nào.
- A0 viết bản `Đề xuất`; D đổi thành `Chấp nhận` sau khi V đạt.

**QĐ11. Kiến trúc và tài liệu.**
- **`ArchitectureTest` thêm ba rule:**
  - `onlyCommonDeclaresControllerAdvice`: ngoài `com.minishop.common..` không lớp nào có `@ControllerAdvice` hoặc `@RestControllerAdvice`;
  - `exceptionHandlersOnlyInCommon`: ngoài `common` không method nào có `@ExceptionHandler`;
  - `controllers_doNotCatchGenericExceptions`: method của lớp `@Controller` không có khối `try/catch` bắt `Exception`, `RuntimeException` hay `Throwable`. Rule viết bằng `ArchCondition` tự định nghĩa trên `JavaCodeUnit.getTryCatchBlocks()` (spec §8).
  - `commonAndStorage_doNotDependOnFeatures` giữ nguyên và phải xanh.
- **`common/package-info.java`:** "xử lý lỗi tập trung (`ErrorPage`, `ErrorPageResolver`, `UnhandledExceptionResolver`, `NotFoundPage` + `NotFoundPageAdvice`, `AccessDeniedController`); chỉ phụ thuộc Spring, không phụ thuộc tính năng nào".
- `CLAUDE.md` thêm mục "Error handling patterns (GĐ7)" và sửa các dòng cũ về 404 (task D).

**QĐ12. Chiến lược test.**
- **MockMvc không làm error dispatch** (`sendError` chỉ để lại status) và không đi theo forward. Muốn kiểm *nội dung* trang lỗi thì cần Tomcat thật.
  - Các lớp loại này dùng chung một meta-annotation `support.LiveServerTest`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`, profile `h2`, DB riêng `jdbc:h2:mem:live-server;…`, `@Import(ErrorTestEndpoints.class)`.
  - Cấu hình giống hệt nhau, nên mọi lớp dùng **chung một Spring context** và chạy được khi không có Docker.
- **Endpoint thử lỗi:**
  - `support.ErrorTestEndpoints` là một `@TestConfiguration`, chứa `@Controller` lồng bên trong. Lớp lồng trong `@TestConfiguration` bị `TestTypeExcludeFilter` của Boot loại khỏi component scan, nên các `@SpringBootTest` khác **không** có `/test/boom`.
  - Đường dẫn: `/test/boom` (GET, POST) và `/admin/test/boom`.
  - Exception có thông điệp chứa chuỗi kiểu SQL và đường dẫn file, để chứng minh chúng không lọt ra trang.
- **Bắt log:** dùng `OutputCaptureExtension` của Boot; đếm dòng có mức `ERROR` theo mẫu log mặc định (`\sERROR\s+\d+\s---`).
- **Còn lại là test đơn vị và slice:** resolver, advice, `AccessDeniedController`. Các test PostgreSQL hiện có không đổi.

## 3. Hợp đồng code và view (chốt trước để backend, frontend, test làm song song)

Nếu cần thêm thuộc tính hay đổi tên, agent dừng lại và báo phiên chính; không tự đặt tên.

```java
// ── common ───────────────────────────────────────────────────────────────────────
enum ErrorPage {                                              // package-private
    NOT_FOUND("Không tìm thấy trang", "Trang bạn tìm không tồn tại hoặc đã bị di chuyển."),
    BAD_REQUEST("Yêu cầu không hợp lệ", "Đường dẫn hoặc dữ liệu gửi lên không hợp lệ."),
    SESSION_EXPIRED("Phiên làm việc đã hết hạn",
            "Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại."),
    PAYLOAD_TOO_LARGE("Tệp tải lên quá lớn", "Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại."),
    SERVER_ERROR("Đã có lỗi xảy ra", "Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.");
    String heading(); String message();
    static ErrorPage forStatus(int status);        // 404, 413, >=500 (và <400) như QĐ1; mọi 4xx khác -> BAD_REQUEST (403 do resolver xử lý trước)
    ModelAndView view(boolean adminLayout, HttpStatus status);
    // adminLayout=false: view "error/page",  errorTitle = heading + " | Mini Shop",        backUrl "/",  backLabel "Về trang chủ"
    // adminLayout=true : view "admin/error", errorTitle = heading + " | Quản trị Mini Shop", backUrl "/admin", backLabel "Về trang quản trị";
    //                    riêng PAYLOAD_TOO_LARGE: backUrl "/admin/products", backLabel "Về danh sách sản phẩm"
    // model luôn có: errorTitle, errorHeading, errorMessage, backUrl, backLabel
}
@Component class ErrorPageResolver implements ErrorViewResolver, Ordered {      // org.springframework.boot.webmvc.autoconfigure.error
    ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model);
    // 403 -> new ModelAndView("error/403", FORBIDDEN); còn lại ErrorPage.forStatus(...).view(admin, status)
    // admin = isAdminPath((String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)) && hiện có ROLE_ADMIN
    static boolean isAdminPath(String uri);      // "/admin" hoặc bắt đầu "/admin/"; null -> false
    int getOrder();                              // HIGHEST_PRECEDENCE
}
@Component class UnhandledExceptionResolver implements HandlerExceptionResolver, Ordered {
    ModelAndView resolveException(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex);
    // client ngắt -> DEBUG, trả new ModelAndView() không sendError
    // cause chain có org.apache.tomcat.util.http.InvalidParameterException -> sendError(getErrorCode()), DEBUG
    // còn lại -> log.error("Unhandled exception: {} {}", method, requestURI, ex); sendError(500) nếu chưa committed
    int getOrder();                              // LOWEST_PRECEDENCE
}
@Controller class AccessDeniedController {       // "/access-denied", mọi method, @ResponseStatus(FORBIDDEN) như cũ
    // request.getAttribute(WebAttributes.ACCESS_DENIED_403) instanceof org.springframework.security.web.csrf.CsrfException
    //   -> ErrorPage.SESSION_EXPIRED.view(false, FORBIDDEN);  còn lại -> "error/403"
}
@Target(TYPE) @Retention(RUNTIME) @Documented
public @interface NotFoundPage {
    String view(); String title() default ""; String backUrl() default ""; String backLabel() default ""; String section() default "";
}
@ControllerAdvice(annotations = NotFoundPage.class) @Order(Ordered.HIGHEST_PRECEDENCE)
class NotFoundPageAdvice {
    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e, HandlerMethod handler, Model model);   // log DEBUG; model: notFoundTitle, backUrl, backLabel, section (khi khác rỗng)
}
@ResponseStatus(HttpStatus.NOT_FOUND) public class NotFoundException extends RuntimeException { /* constructor như cũ */ }
public final class FormText { public static String clean(String value, boolean multiline); public static String blankToNull(String value); } // quy tắc QĐ7

// ── annotation đặt trên controller (B2) ──────────────────────────────────────────
// catalog.ProductController   @NotFoundPage(view = "product/not-found")
// cart.CartController         @NotFoundPage(view = "product/not-found")
// order.OrderController       @NotFoundPage(view = "order/not-found")
// admin.AdminCategoryController @NotFoundPage(view = "admin/not-found", title = "Không tìm thấy danh mục",
//                                 backUrl = "/admin/categories", backLabel = "Quay lại danh sách danh mục", section = "categories")
// admin.AdminProductController  @NotFoundPage(view = "admin/not-found", title = "Không tìm thấy sản phẩm",
//                                 backUrl = "/admin/products", backLabel = "Quay lại danh sách sản phẩm", section = "products")
// admin.AdminOrderController    @NotFoundPage(view = "admin/not-found", title = "Không tìm thấy đơn hàng",
//                                 backUrl = "/admin/orders", backLabel = "Quay lại danh sách đơn", section = "orders")
// Không annotation: CheckoutController, AuthController, AdminDashboardController, AccessDeniedController.

// ── order / admin / user (B3) ────────────────────────────────────────────────────
final class PhoneNumberValidator { static String normalize(String raw); }   // bỏ [ .-], rồi \+?84([1-9][0-9]{8}) -> "0"+nhóm 1
final class PriceInput { static Optional<BigDecimal> parse(String raw); }  // U+00A0, U+202F, U+2007 -> ' ' trước strip
final class EmailNormalizer { static String normalize(String raw); }       // FormText.clean(raw, false) rồi toLowerCase(Locale.ROOT); null -> ""
```

**View và model:**

| View | Khi nào | Status | Model |
|---|---|---|---|
| `error/page` (mới, layout cửa hàng) | mọi lỗi qua `/error` trừ 403 và trừ trường hợp layout quản trị; trang CSRF từ `/access-denied` | đúng mã (400, 403, 404, 405, 413, 500…) | `errorTitle`, `errorHeading`, `errorMessage`, `backUrl`, `backLabel` |
| `admin/error` (mới, layout quản trị) | lỗi ở `/admin` hoặc `/admin/**` khi người xem có `ROLE_ADMIN` | đúng mã | như trên |
| `error/403` (giữ) | `/access-denied` không phải CSRF; `sendError(403)` khác | 403 | – |
| `product/not-found`, `order/not-found`, `admin/not-found` (giữ, không sửa chữ) | `NotFoundPageAdvice` | 404 | `admin/not-found`: `notFoundTitle`, `backUrl`, `backLabel`, `section` |
| JSON mặc định của Boot | mọi lỗi khi `Accept` không phải HTML | đúng mã | `timestamp`, `status`, `error`, `path`; **không** `trace`, `message`, `exception`, `errors` |

**Quy ước markup** (test và verifier dựa vào đây; chữ đúng spec §6):
- **`error/page.html`:**
  - `<head th:replace="~{fragments/layout :: head(${errorTitle})}">`, `<body class="site-body">`;
  - `skipLink`, `header`, rồi `<main id="main" tabindex="-1" class="ms-container site-main">` có **đúng một** `<h1 class="page-title" th:text="${errorHeading}">`, `<p class="lead-text" th:text="${errorMessage}">`, `<a class="btn btn-primary" th:href="@{${backUrl}}" th:text="${backLabel}">`; cuối cùng `footer`;
  - không in thuộc tính nào khác của model (không `status`, `error`, `path`, `message`).
  - Bố cục có thể dùng lại khung hai cột của `product/not-found.html` (ảnh giữ chỗ là ảnh trang trí, `alt=""`); không thêm khối mới.
- **`admin/error.html`:** khung như `admin/not-found.html`:
  - `head(${errorTitle})`, `<body class="admin-body">`, `skipLink`, `sidebar(${null})` (không mục nào có `aria-current`);
  - `<main id="main" tabindex="-1" class="admin-main">` có một `h1` = `errorHeading`, một đoạn `errorMessage`, và link `backUrl` với chữ `backLabel`.
- **`fragments/layout.html`:**
  - `head(title)` thêm hai `<link rel="icon">` (QĐ8);
  - fragment mới `skipLink`: `<a class="skip-link" href="#main">Bỏ qua đến nội dung chính</a>`.
- **`fragments/admin.html`:** `head(title)` thêm hai `<link rel="icon">` như trên. Sidebar không đổi.
- **Mọi trang** (cửa hàng, quản trị, lỗi, `error/403`, các trang `not-found`): `<html lang="vi">`; con đầu tiên của `<body>` là `<a th:replace="~{fragments/layout :: skipLink}"></a>`; có đúng một `<main id="main" tabindex="-1" …>` (giữ nguyên class đang có).
- **`user/register.html`:** `fullName` `maxlength="100"`, `email` `maxlength="255"`, `password` và `confirmPassword` `maxlength="64"`. **`order/checkout.html`:** `phone` `maxlength="20"`.
- **Cấm** (như GĐ2–6): `<script>` nội tuyến, `<style>`, `style=`/`th:style`, `on…=`/`th:on…`, `javascript:`, `th:utext`. SVG favicon không có `<script>`.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static từ `src/main/resources/`; test từ `src/test/java/com/minishop/`.

| # | Task | Agent | File | AC / BR | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| A0 | ADR-0011 (`Đề xuất`), trạng thái spec, sửa lỗi gõ URL trong spec | phiên chính | `docs/adr/0011-centralized-error-handling.md` (mới), `docs/adr/README.md`, `docs/specs/007-polish.md`, `docs/specs/README.md` | spec §8 | câu hỏi 1 ở mục 6 (nếu người dùng trả lời khác khuyến nghị) | – | Xem chi tiết A0 |
| DV1 | Cấu hình: thân request, khóa lỗi, log | `devops-engineer` | `application.yml` | 007-4, 5, 10, 12; BR-007-2, 5; QĐ1, 3, 6 | A0 | B2, B3, F1 | Xem chi tiết DV1 |
| B2 | Gom 404 | `backend-developer` | `common/NotFoundPage`, `common/NotFoundPageAdvice` (mới); `common/NotFoundException`; `catalog/ProductNotFoundAdvice`, `cart/CartNotFoundAdvice`, `order/OrderNotFoundAdvice` (xóa); `catalog/ProductController`, `cart/CartController`, `order/OrderController`, `admin/AdminCategoryController`, `admin/AdminProductController`, `admin/AdminOrderController` | 007-3; BR-007-3; spec §8 | A0 | DV1, B3, F1 | Xem chi tiết B2 |
| B3 | Làm sạch ô chữ, `+84`, NBSP trong giá | `backend-developer` | `common/FormText`, `order/CheckoutForm`, `order/PhoneNumberValidator`, `order/PhoneNumber` (Javadoc), `admin/PriceInput`, `user/RegistrationForm`, `user/EmailNormalizer` | 007-14–17, 19; BR-007-6–8 | A0 | DV1, B2, F1 | Xem chi tiết B3 |
| F1 | Template lỗi, favicon | `frontend-developer` | `templates/error/page.html`, `templates/admin/error.html` (mới); `templates/fragments/layout.html`, `templates/fragments/admin.html` (chỉ `<head>`); `static/images/favicon.svg`, `static/favicon.ico` (mới); `static/credits.html`; `static/css/app.css`, `static/css/admin.css`; `.taste-skill/favicon/` (gitignore) | 007-1, 4, 7, 8, 10, 11, 13, 20 | A0 (dựng theo mục 3) | DV1, B2, B3 | Xem chi tiết F1 |
| B1 | Trang lỗi, log 500, trang CSRF | `backend-developer` | `common/ErrorPage`, `common/ErrorPageResolver`, `common/UnhandledExceptionResolver` (mới); `common/AccessDeniedController`; `common/package-info.java` | 007-1, 2, 4–13; BR-007-1, 2, 4, 5 | DV1, B2 (`package-info` nhắc tới lớp của B2); F1 (cho bước kiểm bằng curl) | TW1, F2 | Xem chi tiết B1 |
| TW1 | Test validation | `test-writer` | `common/FormTextTest`, `order/PhoneNumberValidatorTest`, `user/EmailNormalizerTest` (mới); `order/CheckoutFormValidationTest`, `order/OrderServiceTest`, `user/RegistrationFormValidationTest`, `admin/CategoryFormValidationTest`, `admin/ProductFormValidationTest`, `admin/PriceInputTest` (thêm) | 007-14–17, 19; BR-007-6–8 | B3 | B1, F2 | Xem chi tiết TW1 |
| F2 | Link bỏ qua, `main`, focus, `maxlength` | `frontend-developer` | `templates/fragments/layout.html` (fragment `skipLink`); **mọi** template trang (`templates/**/*.html` trừ `fragments/notice`, `fragments/pagination`, `fragments/product`); `static/css/app.css`, `static/css/admin.css` | 007-18, 21, 22, 24 | F1 | B1, TW1, TW2, TW3 | Xem chi tiết F2 |
| TW2 | Test đơn vị và slice của cơ chế lỗi; ArchUnit | `test-writer` | `common/ErrorPageResolverTest`, `common/UnhandledExceptionResolverTest`, `common/NotFoundPageAdviceTest` (mới); `common/AccessDeniedTest`, `ArchitectureTest` (thêm) | 007-3, 7, 8, 9, 11; BR-007-3–5; spec §8 | B1, B2 | TW1, F2, F3 | Xem chi tiết TW2 |
| TW3 | Test trên Tomcat thật | `test-writer` | `support/LiveServerTest`, `support/LiveClient`, `support/ErrorTestEndpoints`, `common/ErrorPagesTest`, `common/ErrorLoggingTest`, `config/CsrfErrorPageTest`, `admin/UploadTooLargeTest`, `cart/CartHeaderErrorPageTest`, `FaviconTest` (mới) | 007-1, 2, 4–13, 20; BR-007-1, 2, 4, 5 | DV1, B1, B2, F1 | F2, F3 | Xem chi tiết TW3 |
| TW4 | Slice web và quét template | `test-writer` | `user/AuthControllerTest`, `order/CheckoutControllerTest`, `config/SecurityConfigTest`, `TemplateCspComplianceTest` (thêm); `LayoutAccessibilityTest` (mới) | 007-2, 8, 9, 13, 16, 18, 21, 24 | B1, B3, F2 | F3, F4, D | Xem chi tiết TW4 |
| F3 | Gate của skill, rà truy cập, kiểm tay | `frontend-developer` | sửa nhỏ trong file của F1, F2 (chỉ class/CSS, **không đổi markup ở mục 3**) | 007-13, 20–24 | B1, F2 | TW2, TW3, TW4 | Xem chi tiết F3 |
| F4 | Ảnh chụp cho README | `frontend-developer` | `docs/images/*.png` (7 file mới); `.taste-skill/readme-shots/` (gitignore) | 007-26 | F3 | TW4, D (phần chữ) | Xem chi tiết F4 |
| D | Tài liệu | phiên chính | `README.md`, `CLAUDE.md`, `CHANGELOG.md`, `docs/adr/0011-…md`, `docs/adr/README.md`, `docs/specs/007-polish.md`, `docs/specs/README.md` | 007-25–30 | B1–B3, TW3 (số liệu), F4 (ảnh) | F3, TW4 | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | mọi task trên | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc; thư mục tạm cho bản sao sạch) | 007-1 đến 007-30 | R | – | Checklist mục 5 đạt |

**Thứ tự chạy:**
- **Đợt 0:** A0.
- **Đợt 1:** DV1, B2, B3 (ba task không chung file nào; phiên chính trao lượt Maven theo thứ tự DV1 → B2 → B3). F1 bắt đầu cùng lúc (không chạy Maven).
- **Đợt 2:** B1 (sau DV1, B2; bước kiểm bằng curl cần F1). TW1 (sau B3). F2 (sau F1).
- **Đợt 3:** TW3 (sau B1; dài nhất trong nhóm test nên làm trước), rồi TW2. Có thể là hai phiên `test-writer` tách file.
- **Đợt 4:** TW4 (sau F2, B1, B3). F3 (sau B1, F2).
- **Đợt 5:** F4 (sau F3). D (ảnh chờ F4; ADR chờ V).
- **Cuối cùng:** R, rồi V.

**Đường găng:** F1 → F2 → F3 → F4 → D → R → V. Nhánh backend DV1 → B2 → B1 → TW3 chạy song song và ngắn hơn. F3 phải chờ B1, vì trang lỗi chỉ hiện được khi resolver đã có.

**Không chạy hai lệnh Maven cùng lúc trong cùng thư mục** (bài học GĐ4):
- phiên chính trao lượt build;
- F1, F2 không chạy Maven;
- F3, F4 chạy **jar đã đóng gói** (`./mvnw -q -DskipTests package` do task đang giữ lượt Maven tạo, rồi `java -jar target/mini-shop-0.1.0-SNAPSHOT.jar --spring.profiles.active=h2`; jar không chứa DevTools), không chạy cùng lúc với `verify` của agent khác;
- `./mvnw verify` đầy đủ chỉ ở cuối DV1, B1, B2, B3, TW3 và V; TW1, TW2, TW4 chạy `-Dtest=…` của lớp mình.

**Build xanh sau mỗi task:**
- **Sau DV1:** chỉ đổi cấu hình. Rủi ro duy nhất là H2 từ chối thuộc tính `logServerErrorDetail`. `H2ProfileSmokeTest` và `AdminUploadLimitTest` sẽ bắt lỗi này (xem chi tiết DV1).
- **Sau B2:** các slice test 404 hiện có chạy qua advice mới mà không phải sửa (QĐ2). Đó chính là phép thử của AC-007-3.
- **Sau B3:** các test form hiện có phải xanh mà không sửa (AC-007-19). Nếu một test cũ thể hiện đúng hành vi mà BR-007-6 cố ý đổi (ví dụ chuỗi `"\u0000 abc"` trước đây còn dấu cách ở đầu), B3 dừng lại, báo tên test và lý do; phiên chính giao `test-writer` sửa **ngay** (cặp B3–TW1, như B4–TW1 của GĐ6). Không push giữa hai bước.
- **Sau F1:** template mới chưa ai dùng; `TemplateCspComplianceTest` quét chúng ngay.
- **Sau B1:** bean mới không được nạp vào slice test (`@WebMvcTest` không nạp `ErrorViewResolver` hay `HandlerExceptionResolver` tự viết). `AccessDeniedTest` hiện có không đặt attribute CSRF nên vẫn ra `error/403`. Không test nào hiện có chờ `ServletException` từ MockMvc (đã grep), nên việc lỗi 500 nay thành status 500 không làm đỏ test nào.
- **Sau F2:** test hiện có chỉ cắt HTML bằng `indexOf("<main")`, `indexOf("<header")` và kiểm `<body class="admin-body">`. Thêm `id`, `tabindex` và link bỏ qua không phá chúng; trang admin vẫn không có `<header`.

### Chi tiết A0 (phiên chính)

- **`docs/adr/0011-centralized-error-handling.md`** theo `template.md`, trạng thái `Đề xuất`:
  - bối cảnh: spec 007, brief §10, ADR-0007, ADR-0010;
  - các quyết định theo QĐ1–QĐ6;
  - hệ quả: lỗi lúc render được Tomcat log; trên 50 MB vẫn bị ngắt kết nối; H2 vẫn đưa giá trị vào thông điệp lỗi; `SqlExceptionHelper` bị tắt;
  - các phương án bị loại.

  Thêm dòng 0011 vào `docs/adr/README.md`.
- **Spec 007** và `docs/specs/README.md`: trạng thái `Đang làm`.
- **Sửa lỗi gõ trong AC-007-7:** `/cart/add` không tồn tại; URL chỉ nhận POST của giỏ là `/cart/items`. Nếu giữ `/cart/add` thì `GET /cart/add` sẽ là 404, không phải 405. Sửa thành `/cart/items` và ghi một dòng vào "Lịch sử thay đổi" ("sửa lỗi gõ URL, không đổi ý").
- Nếu người dùng trả lời câu hỏi 1 khác khuyến nghị, ghi vào lịch sử của spec.

### Chi tiết DV1 (`devops-engineer`)

Chỉ sửa `src/main/resources/application.yml`. Mỗi khóa có một dòng comment tiếng Việt nêu lý do, kèm "xem ADR-0011".

```yaml
server:
  tomcat:
    max-swallow-size: 50MB     # đọc bỏ phần thân tới 50 MB để trả được trang 413 thay vì ngắt kết nối (spec 007 câu 3)
spring:
  web:
    error:                     # Boot 4: thay cho server.error.*; ghi đè mặc định "always" của DevTools
      include-stacktrace: never
      include-message: never
      include-binding-errors: never
      include-exception: false
  datasource:
    hikari:
      data-source-properties:
        logServerErrorDetail: false   # pgjdbc không đưa "Detail: Key (…)=(…)" (có thể là dữ liệu cá nhân) vào thông điệp lỗi
logging:
  level:
    org.hibernate.engine.jdbc.spi.SqlExceptionHelper: off   # mỗi lỗi chỉ một dòng ERROR (UnhandledExceptionResolver)
```

- **Gộp** vào các khối `spring:` hiện có (không tạo khóa `spring:` thứ hai). Không đổi `spring.servlet.multipart`, `pom.xml`, `compose.yaml`, CI.

**Xong khi:**
- `./mvnw verify` xanh. Đặc biệt `H2ProfileSmokeTest` và `AdminUploadLimitTest` (profile `h2`) xanh: H2 bỏ qua thuộc tính lạ trong `Properties`. Nếu H2 từ chối thì dừng lại và báo phiên chính, phương án dự phòng là đặt thuộc tính trong `application.yml` nhưng tắt nó ở `application-h2.yml`;
- báo cáo xác nhận tên khóa trong `spring-configuration-metadata.json` của các jar 4.1.1 (`spring-boot-tomcat`, `spring-boot-autoconfigure`) và rằng không còn khóa `server.error.*` nào trong repo.

### Chi tiết B2 (`backend-developer`)

- `common/NotFoundPage`, `common/NotFoundPageAdvice` đúng mục 3. Advice đọc annotation bằng `AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), NotFoundPage.class)` và log `DEBUG` "Not found ({view}): {message}".
- `NotFoundException`: thêm `@ResponseStatus(HttpStatus.NOT_FOUND)` (không đặt `reason`). Javadoc nêu hai đường: controller có `@NotFoundPage` thì ra trang riêng; không có thì ra trang 404 chung.
- **Xóa** ba advice của `catalog`, `cart`, `order`. **Xóa** ba method `notFound` trong controller admin, cùng import và biến `log` nếu không còn dùng. Gắn `@NotFoundPage` theo bảng ở mục 3. Không đổi gì khác trong các controller đó.
- Chữ trong annotation phải **giống từng ký tự** với hằng đang có trong controller admin (BR-007-3).

**Xong khi:**
- `./mvnw verify` xanh **mà không sửa test nào**;
- `grep -rn "GĐ7\|NotFoundAdvice\|@ExceptionHandler" src/main/java` chỉ còn `NotFoundPageAdvice`;
- báo cáo liệt kê từng trang 404 và test hiện có đang chứng minh nó không đổi.

### Chi tiết B3 (`backend-developer`)

- **`FormText.clean`** đúng QĐ7. Javadoc liệt kê từng bước và lý do của thứ tự (lọc → NFC → `strip`). Nếu người dùng đồng ý câu hỏi 1: ô nhiều dòng đổi `\r\n` và `\r` đơn lẻ thành `\n` trước bước lọc.
- **`CheckoutForm`:** dùng `FormText.clean` cho bốn ô, và `FormText.blankToNull` cho `note`; xóa hàm `clean` riêng. `toString()` vẫn che mọi ô. Javadoc thêm "nhận `+84`".
- **`PhoneNumberValidator.normalize`** đúng mục 3; `isValid` giữ nguyên logic. Javadoc của `@PhoneNumber` ghi các dạng được nhận.
- **`PriceInput.parse`:** thay ba ký tự dấu cách đặc biệt rồi mới `strip()`; các mẫu và giới hạn giữ nguyên (BR-006-5: không nhận số 0 ở đầu, không trộn dấu chấm và dấu cách).
- **`RegistrationForm`:** `fullName` và `email` qua `FormText.clean(…, false)`; mật khẩu giữ nguyên. **`EmailNormalizer`** đúng mục 3; Javadoc ghi "giống `RegistrationForm` để đăng ký và đăng nhập so cùng một chuỗi".
- Không đổi chữ lỗi nào.

**Xong khi:** `./mvnw verify` xanh. Nếu có test cũ đỏ vì quy tắc mới thì dừng lại và báo như mục "Build xanh". Báo cáo bảng "đầu vào → kết quả" cho 10 ví dụ: các ký tự ở AC-007-14, `Áo​ thun`, NFD, `+84 912 345 678`, `1 250 000` có NBSP.

### Chi tiết F1 (`frontend-developer`)

- **Đọc trước:**
  - spec 007 §4, §6 (bảng "Chữ hiển thị chính xác"), §8;
  - QĐ1, QĐ8 và mục 3 của plan này;
  - mục "UI design skill" trong `CLAUDE.md`, `SKILL.md`;
  - comment đầu `static/css/app.css` và `admin.css`;
  - `product/not-found.html`, `admin/not-found.html`, `error/403.html`.
- **Hai template lỗi** theo mục 3. Dùng lại khuôn của các trang `not-found` hiện có: cùng hướng thiết kế, không vẽ lại, không thêm khối, không minh họa mới. Trên điện thoại, chữ dài trong `h1` phải xuống dòng được, không gây cuộn ngang.
- **Favicon:**
  - `favicon.svg` ≤ 2 KB, `viewBox="0 0 32 32"`, chữ "M" dùng màu token. Vẽ bằng `path`, không dùng `<text>` (font chưa chắc có sẵn khi trình duyệt vẽ favicon);
  - `favicon.ico` gồm 16, 32 và 48 px, tạo đúng như QĐ8;
  - script chạy bằng đường dẫn file với `timeout 120`, đóng Chrome trong `finally`, dùng thư mục profile tạm;
  - thêm hai `<link rel="icon">` vào `head` của `fragments/layout.html` và `fragments/admin.html`;
  - thêm dòng nguồn ảnh "Biểu tượng trang (favicon): tự vẽ cho dự án, giấy phép MIT" vào `credits.html`.
- **Không** sửa `<body>` hay `<main>` của template khác (việc của F2), không sửa template storefront hay admin khác.

**Xong khi:**
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả;
- bốn byte đầu của `favicon.ico` là `00 00 01 00` (kiểm bằng `od -A x -t x1 -N 4 src/main/resources/static/favicon.ico`);
- báo cáo liệt kê model attribute đã dùng, đối chiếu với mục 3.

### Chi tiết B1 (`backend-developer`)

- **`ErrorPage`, `ErrorPageResolver`, `UnhandledExceptionResolver`** đúng mục 3, QĐ1 và QĐ3.
  - Javadoc của `UnhandledExceptionResolver` giải thích vì sao đứng cuối và vì sao không phải `@ControllerAdvice`.
  - Javadoc của `ErrorPageResolver` nêu bảng mã và quy tắc layout.
  - Kiểm `ROLE_ADMIN` bằng `SecurityContextHolder.getContext().getAuthentication()` và `GrantedAuthority`. Không dùng `user.CurrentUser`, vì `common` không được phụ thuộc `user`.
- **`AccessDeniedController`:** nhánh CSRF theo QĐ5. Import đúng `org.springframework.security.web.csrf.CsrfException`; có một lớp cùng tên của WebFlux nằm trong `web.server.csrf`, không dùng lớp đó. Log `DEBUG` loại exception, không log token.
- **`common/package-info.java`** theo QĐ11.
- Không sửa `SecurityConfig`. Nếu thấy cần, dừng lại và báo phiên chính.

**Xong khi:**
- `./mvnw verify` xanh;
- chạy jar profile `h2` (có F1) và kiểm bằng `curl` (đọc log console song song):

  | Lệnh | Mong đợi |
  |---|---|
  | `curl -i -H 'Accept: text/html' localhost:8080/products/abc/xyz` | 404, tiêu đề `Không tìm thấy trang \| Mini Shop` |
  | `curl -i localhost:8080/products/abc/xyz` | JSON 404, không có `trace`/`message` |
  | `curl -i -H 'Accept: text/html' 'localhost:8080/products;x=1'` | 400, trang `Yêu cầu không hợp lệ` |
  | `curl -i -H 'Accept: text/html' 'localhost:8080/products?q=%zz'` | 200 (Tomcat bỏ tham số) hoặc trang 400, **không bao giờ** 500 |
  | `curl -i -H 'Accept: text/html' localhost:8080/error` | 500, trang `Đã có lỗi xảy ra` |
  | `curl -i -X POST -H 'Accept: text/html' localhost:8080/cart/items -d productId=1` | 403, `Phiên làm việc đã hết hạn` |
  | Đăng nhập admin bằng cookie jar, lấy `_csrf`, gửi multipart 12 MB tới `/admin/products` | 413 trong layout quản trị, không lỗi kết nối |

  Không lệnh nào ở trên sinh dòng `ERROR` trong log;
- báo cáo dán status và dòng `<title>` của từng lệnh.

### Chi tiết TW1 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `common/FormTextTest` (mới, JUnit thuần) | từng ký tự `U+200B`, `U+200C`, `U+200D`, `U+2060`, `U+FEFF`, `U+00A0` đứng một mình và trộn với dấu cách → `""`; `"Áo\u200B thun"`, `"Áo\u00A0thun"` → `Áo thun`; `"\u202F1\u2007"` → `1`; `"Đồ chơi"` dạng NFD → bằng dạng NFC; `"e\u200B\u0301"` → `é` (NFC sau khi lọc); `"\u0000 abc"` → `abc`; ô một dòng bỏ `\n`, `\t`; ô nhiều dòng giữ `\n`, `\t` (và `\r\n` theo câu trả lời câu hỏi 1); `U+202E` bị bỏ; câu tiếng Việt đủ dấu không đổi; `null` → `null`; `blankToNull` | 007-14, 15; BR-007-6 |
| `order/PhoneNumberValidatorTest` (mới) | `normalize`: `+84912345678`, `+84 912 345 678`, `+84-912-345-678`, `84912345678`, `0912 345 678`, `0912.345.678` → `0912345678`; `isValid` từ chối `+840912345678`, `+1 212 555 0100`, `+8491234567`, `849123456789`, `0912345`, `abc`; `null` và `""` hợp lệ (để `@NotBlank` báo) | 007-16; BR-007-7 |
| `user/EmailNormalizerTest` (mới) | `" User@Example.COM\u00A0"` → `user@example.com`; `"a\u200B@b.c"` → `a@b.c`; `null` → `""` | BR-007-6 |
| `order/CheckoutFormValidationTest` (thêm) | họ tên người nhận và địa chỉ chỉ gồm ký tự vô hình → đúng chữ "Vui lòng nhập …", mỗi ô một lỗi; ghi chú toàn ký tự vô hình → `null`; bốn dạng `+84` hợp lệ, `normalizedPhone()` = `0912345678`, `phone()` giữ chữ đã gõ; ba dạng sai → `Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.` | 007-14, 16, 19 |
| `order/OrderServiceTest` (thêm) | `placeOrder` với form có `+84 912 345 678` → `Order` được lưu (captor) có phone `0912345678` | 007-16 |
| `user/RegistrationFormValidationTest` (thêm) | họ tên toàn ký tự vô hình → `Vui lòng nhập họ và tên.`; email có NBSP ở cuối → hợp lệ, đã làm sạch; mật khẩu `" Abc12345 "` giữ nguyên dấu cách | 007-14; BR-007-6 |
| `admin/CategoryFormValidationTest`, `admin/ProductFormValidationTest` (thêm) | tên toàn ký tự vô hình → `Vui lòng nhập tên danh mục.` / `Vui lòng nhập tên sản phẩm.`; `Áo\u200B thun` → `Áo thun`; giá `1\u00A0250\u00A0000` qua form → `toDraft().price()` = 1 250 000 | 007-14, 15, 17 |
| `admin/PriceInputTest` (thêm) | `1\u00A0250\u00A0000`, `1\u202F250\u202F000`, `\u00A01250000\u00A0` → 1 250 000; `1\u00A0250.000` (trộn) → rỗng; `01\u00A0250` → rỗng | 007-17; BR-007-8 |

**Xong khi:** `./mvnw test -Dtest='FormTextTest,PhoneNumberValidatorTest,EmailNormalizerTest,CheckoutFormValidationTest,OrderServiceTest,RegistrationFormValidationTest,CategoryFormValidationTest,ProductFormValidationTest,PriceInputTest'` xanh.

### Chi tiết F2 (`frontend-developer`)

- **Fragment `skipLink`** trong `fragments/layout.html`. CSS `.skip-link` ở `app.css`: ẩn khỏi màn hình (không dùng `display:none`) cho tới khi `:focus`, rồi hiện ở góc trên bên trái với tương phản cao. `admin.css` không cần lặp lại vì trang admin đã nạp `app.css`.
- **Mọi trang:** chèn `skipLink` làm con đầu tiên của `<body>`; `<main>` thêm `id="main" tabindex="-1"` (giữ class cũ); kiểm `<html lang="vi">`. Danh sách lấy từ `grep -rl "<main" src/main/resources/templates`: 21 file hiện có cộng 2 file của F1. Báo cáo liệt kê đủ.
- **`maxlength`** theo mục 3 (đăng ký, số điện thoại ở trang thanh toán).
- **Focus:** quy tắc `:focus-visible` chung (QĐ8) trong `app.css` và `admin.css`, áp cho link, nút, ô nhập, `select`, `textarea`, link trong sidebar, ô sản phẩm. Kiểm mọi chỗ đang có `outline: none` hoặc `outline: 0`. `main:focus:not(:focus-visible) { outline: none; }` để bấm chuột không hiện viền.
- Không đổi chữ, `name`, `id` (trừ `main`), `action`.

**Xong khi:** grep CSP của F1 vẫn sạch; `grep -L 'id="main"' $(grep -rl "<main" src/main/resources/templates)` không trả file nào; báo cáo liệt kê file đã sửa.

### Chi tiết TW2 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `common/ErrorPageResolverTest` (mới, JUnit + `MockHttpServletRequest`; đặt và **xóa** `SecurityContextHolder` trong `@AfterEach`) | bảng mã: 400, 401, 405, 415, 429 → `Yêu cầu không hợp lệ`; 404; 413; 500, 503 → `Đã có lỗi xảy ra`; 403 → view `error/403`; `ModelAndView.getStatus()` đúng mã; ma trận layout: `/admin`, `/admin/x` + ADMIN → `admin/error`, tab `… \| Quản trị Mini Shop`, link `/admin` `Về trang quản trị` (413: `/admin/products` `Về danh sách sản phẩm`); `/admin/x` + USER, + anonymous, + không có xác thực → `error/page`; `/administrator` + ADMIN → `error/page`; `/khong-co` + ADMIN → `error/page`; URI `null` → `error/page`; model đủ năm khóa, chữ đúng từng ký tự spec §6 | 007-1, 4, 7, 10, 11; BR-007-1 |
| `common/UnhandledExceptionResolverTest` (mới; `OutputCaptureExtension`) | `IllegalStateException` → `ModelAndView` rỗng, response 500, đúng một dòng `ERROR` chứa `GET /x` mà không có `?q=`; `new InvalidParameterException(…, 400)` bọc trong exception khác → 400, không `ERROR`; `IOException("Broken pipe")` (client ngắt, theo `DisconnectedClientHelper`) → không `sendError`, không `ERROR`; response đã committed → không `sendError`, vẫn log; `getOrder()` = `LOWEST_PRECEDENCE` | 007-5; BR-007-5 |
| `common/NotFoundPageAdviceTest` (mới; `@WebMvcTest` + `@Import({SecurityConfig.class, CartHeaderTestConfig.class, …controller thử})`) | ba controller thử lồng trong lớp test, phải `@Import` tường minh (lớp lồng của lớp test bị loại khỏi scan): (1) `@NotFoundPage(view = "product/not-found")`, (2) bản kiểu admin có `title`/`backUrl`/`backLabel`/`section`, (3) không annotation; mỗi cái có `GET /t{n}/{id}` (`@PathVariable Long`) và `GET /t{n}/missing` (ném `NotFoundException`). Mong đợi: (1) 404, view `product/not-found` cho cả hai đường; (2) 404, view `admin/not-found`, model đủ bốn khóa, tiêu đề tab đúng (người dùng `TestUsers.admin()`); (3) `missing` → 404 không view, `abc` → 400 | 007-3, 7; BR-007-3 |
| `common/AccessDeniedTest` (thêm) | `requestAttr(WebAttributes.ACCESS_DENIED_403, new MissingCsrfTokenException(null))` và `new InvalidCsrfTokenException(token, "x")` → 403, view `error/page`, `<title>Phiên làm việc đã hết hạn \| Mini Shop</title>`, `h1`, câu mô tả, link `Về trang chủ`, không có chữ `Bạn không có quyền`, không có token; bản POST giống vậy; `new AccessDeniedException("x")` → `error/403` như cũ; ba test cũ không sửa | 007-8, 9; BR-007-4 |
| `ArchitectureTest` (thêm) | ba rule ở QĐ11; comment của rule ghi spec 007 §8 | spec §8 |

**Xong khi:** `./mvnw test -Dtest='ErrorPageResolverTest,UnhandledExceptionResolverTest,NotFoundPageAdviceTest,AccessDeniedTest,ArchitectureTest'` xanh. Thử tạm thêm một `@ExceptionHandler` vào một controller để chắc rule mới đỏ (không commit), rồi báo cáo kết quả.

### Chi tiết TW3 (`test-writer`)

**Hạ tầng** (public, trong `support`):
- `LiveServerTest`: meta-annotation theo QĐ12.
- `LiveClient`: `HttpClient` có `CookieManager`, không tự theo redirect, timeout 30–60 giây. Các method: `login(email, password)`, `get(path, accept)`, `postForm(path, Map, boolean withCsrf)`, `postMultipart(...)`, `csrfFrom(html)`. Lấy khuôn từ `AdminUploadLimitTest` (lớp đó **không** sửa).
- `ErrorTestEndpoints`: QĐ12. `/test/boom` (GET, POST) và `/admin/test/boom` ném `IllegalStateException("Boom for test: select 1 from secret_table; C:\\secret\\boom.txt")`.

Mọi request trang dùng `Accept: text/html,application/xhtml+xml`, như trình duyệt.

| Lớp | Kiểm | AC |
|---|---|---|
| `common/ErrorPagesTest` (`@LiveServerTest`) | **404:** khách: `/products/`, `/products/abc/xyz`, `/images/khong-co.jpg`, `/css/khong-co.css`; USER và ADMIN: `/khong-co-trang-nay` → 404, `<title>Không tìm thấy trang \| Mini Shop</title>`, đúng một `<h1`, câu mô tả, `href="/">Về trang chủ`, có `site-header` và `site-footer`, không có `Whitelabel`. **AC-007-2:** khách `/khong-co-trang-nay` → 302 `/login`; đăng nhập USER trên cùng client, đi theo `Location` → trang 404 ở trên. **500:** USER `/test/boom` → 500, chữ AC-007-4; thân trang không chứa `Boom`, `IllegalStateException`, `select`, `secret`, `C:\`, `java.`, `at com.`, `Exception`. **405:** USER `GET /cart/items`, `GET /orders/1/cancel` → 405 trang `Yêu cầu không hợp lệ`. **400:** `/products;x=1` → 400. **Layout quản trị:** ADMIN `GET /admin/khong-co` (404), `GET /admin/orders/1/status` (405), `/admin/test/boom` (500) → `<body class="admin-body">`, `\| Quản trị Mini Shop`, `href="/admin">Về trang quản trị`, `Xin chào, Quản trị viên`, form `Đăng xuất` có `_csrf`. **JSON:** khách `GET /products/abc/xyz` và USER `GET /test/boom`, `GET /actuator/khong-co` với `Accept: application/json` → đúng mã, JSON có `status`, `path` và **không** có khóa `trace`, `message`, `exception`, `errors`. Mọi trang lỗi: status ≠ 200, không có `Location` | 007-1, 2, 4, 7, 11, 12, 13; BR-007-1, 2 |
| `common/ErrorLoggingTest` (`@LiveServerTest`, `OutputCaptureExtension`) | `GET /test/boom?q=0987654321` → đúng **một** dòng `ERROR` mới, chứa `Unhandled exception: GET /test/boom`, không chứa `?` hay `0987654321`; sau đó có dòng stack trace; `POST /test/boom` có CSRF và các ô `phone=0987654321`, `shippingAddress=12 Đường Bí Mật`, `note=ghi-chu-bi-mat`, `email=bi-mat@example.com`, `password=MatKhauBiMat1` → một dòng `ERROR` `POST /test/boom`, và log mới không chứa giá trị nào trong số đó; 404, 405, 400, 403 (CSRF và không có quyền) → **không** có dòng `ERROR` nào; không có dòng `Servlet.service()` | 007-5; BR-007-5 |
| `config/CsrfErrorPageTest` (`@LiveServerTest`) | khách `POST /cart/items` (`productId` có thật) không có token → 403, tiêu đề, `h1`, câu `Biểu mẫu đã hết hạn…`, `Về trang chủ`; giỏ vẫn trống (`GET /cart`). USER: `POST /orders/{id}/cancel` với token sai → 403 trang phiên hết hạn, đơn vẫn `PENDING` (dùng một đơn được tạo qua luồng thật hoặc JDBC rồi dọn). `POST /logout` không có token → trang phiên hết hạn, `GET /orders` sau đó vẫn 200 (còn đăng nhập). Token của phiên cũ dùng ở client mới → trang phiên hết hạn. ADMIN: `POST /admin/categories` không token → trang phiên hết hạn với `\| Mini Shop`, số danh mục không đổi; multipart nhỏ không token → như trên. USER `GET /admin` → `Bạn không có quyền truy cập trang này.`, **không** có `Phiên làm việc` | 007-8, 9; BR-007-4 |
| `admin/UploadTooLargeTest` (`@LiveServerTest`, `OutputCaptureExtension`) | ADMIN, `_csrf` hợp lệ, multipart ảnh 12 MB và 30 MB → **không** có `IOException` (kết nối không bị ngắt), status 413, `<title>Tệp tải lên quá lớn \| Quản trị Mini Shop</title>`, `h1`, câu `Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.`, `href="/admin/products">Về danh sách sản phẩm`, sidebar; không có sản phẩm mới; thư mục upload không thêm file; không có dòng `ERROR` | 007-10, 11; BR-007-5 |
| `cart/CartHeaderErrorPageTest` (`@SpringBootTest(RANDOM_PORT)`, profile `h2`, DB `jdbc:h2:mem:cart-header-error`, `@Import(ErrorTestEndpoints.class)`, `@MockitoSpyBean CartService` với `totalQuantity` ném `DataAccessResourceFailureException`) | USER đăng nhập, `GET /test/boom` → 500, đủ chữ AC-007-4, header có `>Giỏ hàng</a>` và **không** có `Giỏ hàng (`; `GET /products` → 200, header như trên (hỏng êm); log có `WARN` của `CartHeader` | 007-6 |
| `FaviconTest` (`@LiveServerTest`) | khách `GET /favicon.ico` → 200, bốn byte đầu `00 00 01 00`; `GET /images/favicon.svg` → 200, `Content-Type` `image/svg+xml`, không chứa `<script`; `GET /` (khách) và `GET /admin` (ADMIN) có cả hai `<link rel="icon"` | 007-20 |

**Xong khi:**
- `./mvnw verify` xanh; `bash .github/scripts/check-no-skipped-tests.sh` exit 0. Các lớp `@LiveServerTest` chạy **một** Spring context: đếm số lần banner Spring Boot xuất hiện trong log, rồi báo cáo;
- báo cáo thời gian chạy của từng lớp và ghi chỗ nào khác cột "Kiểm bằng" của spec.

### Chi tiết TW4 (`test-writer`)

| Lớp | Kiểm | AC |
|---|---|---|
| `user/AuthControllerTest` (thêm) | `GET /register`: thẻ `fullName` có `maxlength="100"`, `email` `255`, `password` và `confirmPassword` `64`; POST họ tên 101 ký tự vẫn ra lỗi như cũ | 007-18 |
| `order/CheckoutControllerTest` (thêm) | `GET /checkout`: `phone` `maxlength="20"` (ba ô khác đã có); POST `phone=+84 912 345 678` hợp lệ → service nhận form có `normalizedPhone()` = `0912345678`; POST khi trang hiện lại có lỗi thì `+84 912 345 678` giữ nguyên chữ đã gõ | 007-16, 18 |
| `config/SecurityConfigTest` (thêm) | khách `GET /khong-co-trang-nay` → redirect `/login`; POST thiếu CSRF (`/cart/items` khách, `/orders/1/cancel` `@WithMockUser`, `/logout` `@WithMockUser`, `/admin/categories` và multipart `/admin/products` ADMIN) → 403, `forwardedUrl("/access-denied")` và `request().attribute(WebAttributes.ACCESS_DENIED_403, instanceOf(CsrfException.class))`; USER `GET /admin` → attribute **không** phải `CsrfException`; khách `GET /images/favicon.svg`, `GET /favicon.ico` không bị chuyển `/login` | 007-2, 8, 9, 20 |
| `TemplateCspComplianceTest` (thêm) | bắt buộc quét được `error/page.html`, `admin/error.html`, `static/images/favicon.svg` (thêm `static/**/*.svg` vào phạm vi quét: không `<script`, không `on…=`) | 007-13, 24 |
| `LayoutAccessibilityTest` (mới, JUnit thuần, quét `templates/**/*.html`) | mọi template có `<html` thì có `lang="vi"`; mọi template có `<main` thì có đúng một `<main`, có `id="main"`, và con đầu tiên của `<body …>` là thẻ chứa `fragments/layout :: skipLink`; `fragments/layout.html` có `<a class="skip-link" href="#main">Bỏ qua đến nội dung chính</a>`; hai fragment `head` có hai `<link rel="icon"`; mọi `<img` có `alt=` hoặc `th:alt=`; chống "xanh giả": phải quét được ít nhất 23 trang có `<main` | 007-20, 21, 24 |

**Xong khi:** `./mvnw test -Dtest='AuthControllerTest,CheckoutControllerTest,SecurityConfigTest,TemplateCspComplianceTest,LayoutAccessibilityTest'` xanh.

### Chi tiết F3 (`frontend-developer`)

- **Chạy** jar profile `h2` (mục "Không chạy hai lệnh Maven…").
- **Lint:** `node <skill>/scripts/lint.mjs` cho template và `app.css` của cửa hàng; `lint.mjs --app` cho `templates/admin`, `fragments/admin.html`, `admin.css`.
- **Inspect trang công khai:** `node <skill>/scripts/inspect.mjs <url> --widths 1280,375 --out .taste-skill/inspect/phase-7/<tên>`. Danh sách:
  - `/`, `/products?page=2`, chi tiết một sản phẩm có ảnh, chi tiết sản phẩm hết hàng;
  - `/login`, `/register` (cả khi có lỗi: gửi form trống bằng tay rồi kiểm tay);
  - `/cart` trống, `/credits.html`;
  - 404 `/products/abc/xyz`, 400 `/products;x=1`, 500 `/error`.
- **Trang cần đăng nhập** (giỏ có hàng, thanh toán, đơn, mọi trang admin, 404/405/413 trong admin, trang phiên hết hạn): dùng một script CDP có bước đăng nhập (dựa trên `lib/cdp.mjs`, đặt ở `.taste-skill/phase-7/`). Nếu không giữ được phiên thì kiểm tay trong DevTools device mode 375px và 1280px, như GĐ6.
- **Truy cập:**
  - bảng tỉ lệ tương phản của mọi cặp token chữ/nền (`app.css`, `admin.css`), gồm sáu nhãn `order-status--*`, chữ mờ, chữ lỗi, chữ trên nút, link bỏ qua;
  - một vòng chỉ dùng bàn phím: tìm sản phẩm → thêm giỏ → đặt hàng → hủy đơn; admin: đổi trạng thái đơn, thêm danh mục, sửa sản phẩm, điều chỉnh kho;
  - ghi thứ tự focus và chỗ không thấy viền.
- **Sửa:** chỉ class và CSS; **không đổi chữ, cấu trúc form, `name`, `id`, `action`** của mục 3. Tối đa 3 vòng.
- Lưu ảnh chụp vào `.taste-skill/inspect/phase-7/`. Tắt app khi xong; kiểm không còn tiến trình Chrome headless nào do script mở.

**Xong khi:**
- `lint.mjs` không còn `FAIL` (trừ mục ghi "sai lệch có chủ đích" kèm một câu lý do);
- `inspect.mjs` không có `FAIL` `contrast`, `side-scroll`, `overflow`, `many-h1`; mọi cặp token chữ ≥ 4,5:1 (chữ lớn ≥ 3:1);
- không cuộn ngang cả trang ở 375px; Console không lỗi CSP; không request favicon nào 404;
- báo cáo có bảng tương phản, kết quả vòng bàn phím và critique app-screen cho trang lỗi quản trị.

### Chi tiết F4 (`frontend-developer`)

- **Chuẩn bị:** jar profile `h2` mới khởi động (seed mới).
- **Script `.taste-skill/readme-shots/shoot.mjs`** (dùng `lib/cdp.mjs` của skill; profile Chrome tạm; `setTimeout(() => process.exit(2), 180000).unref()`; đóng Chrome trong `finally`; chạy bằng `timeout 300 node .taste-skill/readme-shots/shoot.mjs`, không chạy nền). Thứ tự:
  1. `/products` → `products.png`;
  2. chi tiết `Áo thun cotton cổ tròn` (sản phẩm có ảnh) → `product-detail.png`;
  3. đăng nhập `user@`, thêm 2 sản phẩm có ảnh → `/cart` → `cart.png`;
  4. `/checkout` với dữ liệu giả ở QĐ9 → `checkout.png`;
  5. đặt hàng → chi tiết đơn → `order-detail.png`;
  6. đăng xuất, đăng nhập `admin@` → `/admin` → `admin-dashboard.png`;
  7. mở đơn vừa đặt → `admin-order-detail.png`.
- **Ảnh:** khung 1280×800, tỉ lệ điểm ảnh 1, PNG, chỉ phần đầu trang. Ghi comment đầu script: danh sách trang, khung hình, tài khoản, dữ liệu giả, cách chạy lại.
- **Nếu ảnh > 300 KB:** thử khung hình thấp hơn (1280×720) hoặc cắt bớt. Vẫn vượt thì **dừng lại** và báo phiên chính (câu hỏi 2), không tự cài công cụ.

**Xong khi:** đủ 7 file trong `docs/images/`, mỗi file ≤ 300 KB (`ls -l`); mở từng ảnh để kiểm không có dữ liệu ngoài seed và dữ liệu giả; báo cáo kích thước từng file.

### Chi tiết D (phiên chính)

- **ADR-0011:** đổi thành `Chấp nhận` sau khi V đạt; cập nhật `docs/adr/README.md`.
- **`README.md`:**
  - **đầu trang:** badge `[![CI](https://github.com/Luffyreals/mini-shop/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Luffyreals/mini-shop/actions/workflows/ci.yml)`;
  - **nội dung:**
    - mục "Ảnh chụp" (7 ảnh, kèm dòng ghi công CC BY / CC BY-SA → `credits.html`);
    - mục "Kiến trúc" có hai sơ đồ Mermaid `flowchart` (package) và `erDiagram` (7 bảng) theo QĐ9;
    - mục `Quyết định thiết kế` (ADR 0001–0011);
    - mục `Xử lý sự cố` (sáu mục AC-007-29: Docker chưa chạy; cổng 8080 bị chiếm, `-Dspring-boot.run.arguments=--server.port=8081`; `docker compose down -v`; test Testcontainers bị bỏ qua; `mvnw.cmd` và cách đặt nháy kép trong PowerShell; `UPLOAD_DIR` cho Bash và PowerShell);
    - mục "Hướng phát triển": quên mật khẩu, tìm đơn trong admin, deploy GĐ8;
  - **sửa:** bảng lệnh bị tách đôi, ví dụ `-Dtest=…`;
  - **cuối cùng:** trạng thái GĐ7 và Lộ trình đánh dấu **sau khi V đạt**.
- **`CLAUDE.md`:**
  - Project status;
  - mục mới **"Error handling patterns (GĐ7)"**:
    - `ErrorPageResolver` và bảng mã, quy tắc layout;
    - `@NotFoundPage` + `NotFoundPageAdvice`, `NotFoundException` là lưới an toàn;
    - `UnhandledExceptionResolver` đứng cuối, và vì sao không dùng advice;
    - nhánh CSRF trong `AccessDeniedController`; 413 do Tomcat trả + `max-swallow-size`;
    - `spring.web.error.*` (Boot 4) và mặc định của DevTools; log không có dữ liệu cá nhân (`SqlExceptionHelper`, `logServerErrorDetail`);
    - MockMvc không làm error dispatch → `@LiveServerTest` và `ErrorTestEndpoints`;
    - quy tắc `FormText`, `+84`, NBSP; `skipLink` + `<main id="main">`; favicon;
  - **sửa** các dòng cũ: "404: throw … `ProductNotFoundAdvice`…", "Admin 404s are `@ExceptionHandler`s…", ví dụ `HomeControllerTest`;
  - "Visibility today": `common` thêm `NotFoundPage`.
- **`CHANGELOG.md`:** `[Unreleased]` với các mục Thêm (trang lỗi, trang phiên hết hạn, trang 413, favicon, link bỏ qua, README), Thay đổi (gom 404, làm sạch ô chữ, `+84`, NBSP, `maxlength`), Bảo mật (không lộ chi tiết lỗi ở mọi profile, log không có dữ liệu cá nhân, Tomcat đọc bỏ tối đa 50 MB). Chuyển thành `[0.7.0]` và gắn tag sau khi người dùng duyệt.
- **Spec 007 và `docs/specs/README.md`:** `Hoàn thành` sau khi V đạt.

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

Docker **đang bật**. Dùng `admin@minishop.local` / `user@minishop.local` (mật khẩu trong README). Trước mục 2, chụp `select id, name, price from products order by id`, `select id, name from categories`, `select coalesce(max(id), 0) from orders` và danh sách file trong `uploads/`, để khôi phục ở mục 32. Mọi lần "mở" dưới đây là trình duyệt thật (DevTools mở tab Network và Console), trừ khi ghi `curl`.

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build, AC-007-19 | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh` | BUILD SUCCESS; script exit 0; có đủ lớp test mới của TW1–TW4; mọi test form của GĐ3–6 xanh |
| 2 | Không có migration | `./mvnw spring-boot:run` (PostgreSQL, **có DevTools**); `select max(version) from flyway_schema_history` | App lên; version vẫn `2` |
| 3 | AC-007-1 | Cửa sổ ẩn danh: `/products/`, `/products/abc/xyz`, `/images/khong-co.jpg`, `/css/khong-co.css`; đăng nhập `user@` rồi `admin@`: `/khong-co-trang-nay` | 404 (Network); tab `Không tìm thấy trang \| Mini Shop`; `h1`, câu mô tả, `Về trang chủ`; có header và footer cửa hàng; không phải Whitelabel |
| 4 | AC-007-2 (âm) | Ẩn danh `/khong-co-trang-nay` → đăng nhập `user@` | Chuyển `/login`; sau đăng nhập thấy trang 404 ở mục 3 |
| 5 | AC-007-3 | `/products/abc`, `/products/999999`; trang chi tiết: DevTools đổi `productId` ẩn thành `abc` rồi `Thêm vào giỏ`; `user@` mở `/orders/999999`, `/orders/abc`; `admin@` mở `/admin/products/abc/edit`, `/admin/orders/999999`, `/admin/categories/x/edit` | Chữ, link và mã 404 **giống GĐ6** (đối chiếu mục 7 checklist GĐ6 và template `*/not-found.html`): `Không tìm thấy sản phẩm` (link `Về danh sách sản phẩm`), `Không tìm thấy đơn hàng` (`Đơn hàng của tôi`), 404 quản trị đúng loại và link `Quay lại danh sách …` |
| 6 | AC-007-4, 5, 6, BR-007-2 (DevTools bật) | App của mục 2. `user@` đã đăng nhập, mở `/products`; chạy `docker compose stop` trong thư mục repo; tải lại `/products` (chờ tối đa 30 giây); xem mã nguồn trang; đọc log console; `curl -s -H 'Accept: application/json' 'http://localhost:8080/products?q=abc'`; rồi `docker compose start` | HTTP 500, tab `Đã có lỗi xảy ra \| Mini Shop`, đủ chữ AC-007-4, header có `Giỏ hàng` **không** kèm số; mã nguồn không có `Exception`, `org.`, `java.`, `SQL`, `select`, `at com.`; log: **đúng một** dòng `ERROR` `Unhandled exception: GET /products` (có stack trace, không có `q=abc`), không có dòng `ERROR` của `SqlExceptionHelper`, có `WARN` của `CartHeader`; JSON có `status: 500`, không có `trace`/`message`/`exception`/`errors`; sau `start` trang bình thường lại |
| 7 | AC-007-7 (âm) | `user@` gõ vào thanh địa chỉ `/cart/items`, `/orders/1/cancel`; `admin@` gõ `/admin/orders/1/status`; `/products;x=1`; `curl -i -H 'Accept: text/html' 'http://localhost:8080/products?q=%zz'` | 405, 405, 405 (layout quản trị), 400; tab `Yêu cầu không hợp lệ \| …`, chữ đúng; `curl`: 200 hoặc 400, không bao giờ 500; dữ liệu không đổi |
| 8 | AC-007-8 (âm) | (a) Khách: trang chi tiết → DevTools xóa cookie `JSESSIONID` → `Thêm vào giỏ`; (b) `user@` ở `/checkout` → xóa cookie → `Đặt hàng`; (c) `user@` ở chi tiết đơn `PENDING`: DevTools xóa ô `_csrf` → `Hủy đơn hàng`; (d) `admin@`: xóa `_csrf` ở form thêm danh mục, và ở form thêm sản phẩm có chọn ảnh; (e) hai tab cùng đăng nhập `user@`: tab B đăng xuất rồi đăng nhập lại, tab A bấm `Đăng xuất` | Mỗi lần: 403, tab `Phiên làm việc đã hết hạn \| Mini Shop`, `h1`, `Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại.`, `Về trang chủ`; khác trang "không có quyền"; giỏ, đơn, danh mục, sản phẩm, `uploads/` không đổi; (e) tab A tải lại vẫn đang đăng nhập |
| 9 | AC-007-9 | `user@` mở `/admin`, `/admin/orders` | 403 `Bạn không có quyền truy cập trang này.`, không phải trang phiên hết hạn |
| 10 | AC-007-10 (âm) | Git Bash: `head -c 12582912 /dev/urandom > /tmp/big12.jpg`, `head -c 31457280 /dev/urandom > /tmp/big30.jpg`. `admin@` → thêm sản phẩm, chọn từng file, `Lưu`; rồi chọn ảnh JPG 2,5 MB | 12 MB và 30 MB: HTTP 413, tab `Tệp tải lên quá lớn \| Quản trị Mini Shop`, `h1`, `Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.`, link `Về danh sách sản phẩm` → `/admin/products`; **không** báo mất kết nối; không có sản phẩm mới; `uploads/` không đổi; log không có `ERROR`. 2,5 MB: vẫn là form với `Ảnh tối đa 2 MB.` (AC-006-29 không đổi) |
| 11 | AC-007-11 | `admin@`: `/admin/khong-co` (404), `/admin/orders/1/status` (405, mục 7), mục 10 (413); lặp lại mục 6 nhưng tải `/admin` khi DB tắt (500); `admin@` mở `/khong-co` | Bốn trang trong layout quản trị (sidebar, không header cửa hàng), tab `{Tiêu đề} \| Quản trị Mini Shop`, link `Về trang quản trị` (`/admin`), riêng 413 là `Về danh sách sản phẩm`; `/khong-co` dùng layout cửa hàng |
| 12 | AC-007-12 | `curl -i -H 'Accept: application/json' http://localhost:8080/products/abc/xyz`; `curl -i http://localhost:8080/khong-co` (không có `Accept`) | Lần 1: 404 JSON; lần 2: 302 `/login` (đường dẫn không công khai, AC-007-2); JSON không có `trace`, `message`, `exception`, `errors` |
| 13 | AC-007-13, 24 | Device mode 375px và 1280px cho mọi trang lỗi ở mục 3–11 và trang 403 | Đúng một `h1` (DevTools: `document.querySelectorAll('h1').length` gõ trong Console); không cuộn ngang; không lỗi CSP; không trang lỗi nào trả 200 |
| 14 | AC-007-14 (âm) | DevTools Console đặt giá trị chỉ gồm ký tự vô hình, ví dụ `document.getElementById('fullName').value = '\u200B\u00A0\uFEFF'`, rồi gửi: đăng ký (họ tên); thanh toán (họ tên người nhận, địa chỉ); thêm danh mục (tên); thêm sản phẩm (tên) | Mỗi ô: đúng chữ "Vui lòng nhập …" của spec 003/005/006; không lưu gì |
| 15 | AC-007-15 | Thêm danh mục tên `'Áo\u200B thun test'` (qua Console); psql `select name, length(name) from categories order by id desc limit 1`; thanh toán với địa chỉ hai dòng có dấu | Tên lưu `Áo thun test`, độ dài 12; địa chỉ giữ xuống dòng và dấu (xem ở chi tiết đơn) |
| 16 | AC-007-16 | Đặt 4 đơn với `+84912345678`, `+84 912 345 678`, `+84-912-345-678`, `84912345678`; thử `+840912345678`, `+1 212 555 0100`, `+8491234567` | psql `phone` của 4 đơn là `0912345678`; ba số sau: `Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.`, chữ đã gõ còn nguyên |
| 17 | AC-007-17 | Sửa giá một sản phẩm: Console đặt `'1\u00A0250\u00A0000'`, lưu; rồi `'1\u202F250\u202F000'` | Lưu giá `1250000` cả hai lần |
| 18 | AC-007-18 (âm) | Xem mã nguồn `/register`, `/checkout`; DevTools gỡ `maxlength`, nhập họ tên 101 ký tự, địa chỉ 501 ký tự | `maxlength` 100, 255, 64, 64; 100, 20, 500, 500; server vẫn báo lỗi độ dài như cũ |
| 19 | AC-007-20 | Xem tab trình duyệt ở trang chủ, trang admin, trang 404, trang 500; Network lọc `favicon` | Thấy favicon chữ "M" ở mọi trang; không request favicon nào 404 |
| 20 | AC-007-21 | Ở `/products`, `/cart`, `/admin`, `/admin/orders`, trang 404: tải trang, bấm `Tab` một lần, rồi `Enter`, rồi `Tab` | Lần đầu thấy rõ `Bỏ qua đến nội dung chính` ở góc trên; sau `Enter`, lần `Tab` tiếp theo vào phần tử đầu tiên trong `main` (không vào menu header/sidebar) |
| 21 | AC-007-22 | Chỉ dùng bàn phím: tìm sản phẩm → thêm giỏ → đặt hàng → hủy đơn; admin: xác nhận đơn, thêm danh mục, sửa sản phẩm, điều chỉnh kho; hộp xác nhận (`Enter`, `Esc`) | Làm được hết; luôn thấy viền focus; thứ tự focus theo thứ tự đọc; đối chiếu báo cáo F3 |
| 22 | AC-007-23 | Đọc bảng tương phản của F3; kiểm ngẫu nhiên bằng DevTools (Inspect → Accessibility → Contrast) nhãn `Chờ xử lý`, `Đã hủy`, chữ mờ ở danh sách đơn, chữ lỗi form, nút `Hủy đơn` | Mọi giá trị ≥ 4,5:1 (chữ lớn ≥ 3:1) |
| 23 | AC-007-24 | Device mode 375px và 1280px: mọi trang cửa hàng và quản trị (danh sách mục 3 F3); xem mã nguồn | Không cuộn ngang cả trang; không lỗi CSP; một `h1`; `html lang="vi"`; ảnh sản phẩm `alt` = tên, ảnh trang trí `alt=""`; mọi ô có `label` |
| 24 | AC-007-25, 27 | Đọc `README.md`; so sơ đồ package với `ArchitectureTest` và các `import com.minishop.` (`grep -rh "^import com.minishop" src/main/java/com/minishop/<pkg>`); so `erDiagram` với V1, V2 | Badge `ci.yml`, nhánh `main`, ở đầu trang; hai khối ` ```mermaid `, cú pháp hợp lệ; mọi mũi tên phụ thuộc có thật và không thiếu chiều nào; đủ 7 bảng, khóa ngoại đúng |
| 25 | AC-007-26 | `ls -l docs/images`; mở từng ảnh | 7 PNG đúng trang của AC, mỗi file ≤ 300 KB; chỉ có dữ liệu seed và dữ liệu giả |
| 26 | AC-007-28, 29 | Đọc README | `Quyết định thiết kế` có ADR 0001–0011, mỗi ADR một dòng kèm link chạy được; `Xử lý sự cố` đủ sáu mục; dòng ghi công ảnh |
| 27 | AC-007-30, BR-007-9 | Tắt mọi app; xuất bản sao sạch: `D=$(mktemp -d)/minishop-clean && mkdir -p "$D" && git ls-files -co --exclude-standard \| while IFS= read -r f; do [ -e "$f" ] && printf '%s\n' "$f"; done \| tar -cf - -T - \| tar -xf - -C "$D"`. Trong `$D`, làm **đúng từng bước** README bằng Git Bash, bấm giờ (không tính lần đầu tải Maven hay ảnh Docker): chạy app → đăng nhập `user@` → thêm giỏ → đặt hàng → `admin@` xác nhận → giao → đã giao. Rồi thử PowerShell: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"` lên được. Đọc các lệnh cho Linux/macOS | ≤ khoảng 5 phút; không phải đoán bước nào (ghi lại mọi chỗ phải đoán); dữ liệu seed mới (volume mới); lệnh PowerShell chạy đúng như README ghi; lệnh Linux/macOS đúng cú pháp. Dọn: tắt app, `docker compose down -v` trong `$D`, xóa `$D` |
| 28 | Profile H2 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`: `/products/abc/xyz`, `/error`, `user@` `GET /cart/items` | Trang 404, 500, 405 như trên PostgreSQL |
| 29 | Log (BR-007-5) | Đọc log console của mọi mục trên | Chỉ có dòng `ERROR` ở mục 6 và 11 (mỗi lỗi một dòng); không có số điện thoại, địa chỉ, ghi chú, email, mật khẩu, query string trong dòng `ERROR` nào |
| 30 | Kiến trúc (spec §8) | `grep -rn "@ControllerAdvice\|@ExceptionHandler\|NotFoundAdvice\|GĐ7 gom" src/main/java` | Chỉ còn `common/NotFoundPageAdvice`; rule ArchUnit mới nằm trong kết quả mục 1 |
| 31 | Ghi chú cho GĐ8 không bị đụng | `git diff main -- src/main/java/com/minishop/config/SecurityConfig.java src/main/java/com/minishop/config/DataInitializer.java` | Không đổi (seed, cookie, giới hạn đăng nhập sai vẫn để GĐ8) |
| 32 | Dọn dẹp | psql: xóa `order_items`, `orders` có id lớn hơn giá trị chụp đầu; khôi phục `products` (giá), xóa danh mục và sản phẩm mới; xóa file mới trong `uploads/` và `/tmp/big*.jpg`; tắt mọi app và mọi Chrome headless do mình mở | Dữ liệu seed như cũ; `uploads/` như cũ; không còn tiến trình; container Postgres đã dừng |

## 6. Câu hỏi cho người dùng

Không câu nào chặn việc bắt đầu: không trả lời thì plan làm theo khuyến nghị.

1. **Xuống dòng trong ô nhiều dòng (địa chỉ, ghi chú, mô tả).**
   - Trình duyệt đếm mỗi lần xuống dòng là **1** ký tự khi áp `maxlength="500"`, nhưng lúc gửi form lại gửi `\r\n` (**2** ký tự). Một địa chỉ 500 ký tự có 5 lần xuống dòng sẽ lọt qua trình duyệt rồi bị server báo `tối đa 500 ký tự`. Hiếm gặp, nhưng khó hiểu cho người dùng.
   - BR-007-6 ghi "giữ `\n`, `\r`, `\t`".
   - **Khuyến nghị:** cho `FormText` đổi `\r\n` và `\r` thành `\n` ở ô nhiều dòng. Xuống dòng vẫn được giữ, chỉ thống nhất một kiểu; dữ liệu cũ không đổi.
   - Cần trả lời trước B3 (đợt 1).
2. **Ảnh chụp README lớn hơn 300 KB.**
   - Ảnh sản phẩm là ảnh chụp thật, nén PNG kém; máy hiện không có công cụ giảm màu PNG (không có ImageMagick, Python không có Pillow).
   - **Khuyến nghị:** chỉ khi chỉnh khung hình và cắt ảnh vẫn chưa đủ, cho phép cài Pillow vào một môi trường ảo tạm trong `.taste-skill/` (đã gitignore, không thêm gì vào repo) để giảm còn 256 màu, vẫn là PNG.
   - Phương án khác: nới AC cho phép ảnh WebP hoặc JPEG.
3. **Cuối giai đoạn có commit, push và mở PR `phase-7/polish` → `main` không?**
   - **Khuyến nghị:** có, nhưng chưa merge, giống GĐ3–6. Các agent không tự commit.
   - Mục 27 dùng bản xuất từ thư mục làm việc. Sau khi push, bạn có thể tự `git clone` từ GitHub để kiểm lại lần nữa.
4. **Chỉ để bạn biết** (các điểm spec chưa nói, plan đã chọn):
   - **Sửa lỗi gõ ở AC-007-7:** URL chỉ nhận POST của giỏ là `/cart/items`, không phải `/cart/add` (URL này không tồn tại, nên `GET` sẽ ra 404 chứ không phải 405). A0 sửa spec và ghi lịch sử.
   - **Log 500 không dùng `@ControllerAdvice` như brief §10 ghi**, mà dùng một `HandlerExceptionResolver` đứng cuối (QĐ3). Vai trò y hệt, nhưng không nuốt nhầm 400/404/405. Ghi trong ADR-0011.
   - **Lỗi xảy ra *trong lúc render template*** (không phải trong controller) vẫn có trang 500, nhưng dòng log `ERROR` của nó do Tomcat ghi và không có method, đường dẫn.
   - **Trang "Phiên làm việc đã hết hạn" luôn dùng layout cửa hàng**, kể cả ở `/admin/**`, vì phiên có thể đã mất.
   - **Trang 413 ngoài khu quản trị**, hoặc khi người xem không phải admin, dùng layout cửa hàng với link `Về trang chủ`. Chỉ gặp khi request bị dựng tay.
   - **Ngoài ba ký tự của BR**, `FormText` đổi *mọi* dấu cách đặc biệt (nhóm Unicode `Zs`) thành dấu cách thường, và bỏ *mọi* ký tự định dạng vô hình (`Cf`), kể cả ký tự đảo chiều chữ. Hệ quả: emoji ghép (👨‍👩‍👧) bị tách thành từng emoji.
   - **Email khi đăng nhập** cũng được làm sạch như khi đăng ký (`EmailNormalizer` dùng `FormText`), để hai nơi luôn so cùng một chuỗi.
   - **Log bớt hai nguồn:** tắt log `ERROR` riêng của Hibernate (`SqlExceptionHelper`), và bảo driver PostgreSQL không đưa giá trị dòng dữ liệu vào thông điệp lỗi. Mục đích: đúng một dòng `ERROR`, không có dữ liệu cá nhân. Profile `h2` vẫn đưa giá trị vào thông điệp lỗi (chỉ dùng khi dev).
   - **`curl` không có header `Accept`** nhận JSON chứ không phải trang HTML; trình duyệt luôn nhận trang. Khách gọi `/actuator/…` sai đường dẫn bị chuyển `/login`, vì đó là đường dẫn không công khai (đúng quy tắc AC-007-2).
   - **Script tạo favicon và script chụp ảnh README** nằm trong `.taste-skill/` (không commit, như mọi file làm việc khác của skill). Cách chụp lại được ghi ở comment đầu script.
   - **Có ADR-0011 mới** (viết trước khi code): xử lý lỗi tập trung.

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Mã tham chiếu lỗi trên trang 500 + request id trong log (MDC):** hợp với GĐ8 khi có log tập trung (spec §9 câu 8).
- **Kiểm truy cập tự động bằng axe-core trong CI:** cần thêm công cụ JavaScript vào build; hiện tại dùng checklist và công cụ của skill.
- **Số phiên bản trong `pom.xml` vẫn là `0.1.0-SNAPSHOT`** dù đã phát hành `v0.6.0`: nên tăng lúc phát hành (một commit `chore`), để tên jar khớp tag.
- **Header cache cho tài nguyên tĩnh, giới hạn đăng nhập sai, cookie `secure`/`same-site`, tắt seed, `lock_timeout`, `StorageService` đám mây, Dockerfile:** GĐ8 (đã ghi trong `CLAUDE.md`).
- **GIF luồng đặt hàng, tìm đơn trong admin, chế độ tối, ảnh responsive:** spec 007 đã để ngoài phạm vi.

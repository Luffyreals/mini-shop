# Spec 003: Đăng ký, đăng nhập và phân quyền

- **Giai đoạn:** GĐ3
- **Trạng thái:** Hoàn thành
- **Liên quan:** brief §4 (Tài khoản), §7 (Bảo mật); ADR-0002, 0003; spec 001 (bảng `users`, dữ liệu mẫu, CSP), spec 002 (layout, đường dẫn công khai)

## 1. Mục tiêu

Khách tạo được tài khoản và đăng nhập bằng email và mật khẩu. Ứng dụng biết ai đang đăng nhập và người đó có vai trò gì, nên các giai đoạn sau làm được giỏ hàng theo người dùng, đặt hàng và khu quản trị. Người dùng thường không vào được `/admin`.

## 2. Phạm vi

**Trong phạm vi**
- Trang đăng ký (họ tên, email, mật khẩu, nhập lại mật khẩu). Tài khoản mới luôn có vai trò `USER`.
- Trang đăng nhập riêng (thay trang mặc định của Spring Security), đăng xuất bằng POST.
- Tải người dùng từ DB cho Spring Security, thay tài khoản `user` tự sinh với mật khẩu in ra log.
- Phân quyền theo đường dẫn: `/admin/**` chỉ dành cho `ADMIN`; `/checkout/**` và `/orders/**` cần đăng nhập; trang sản phẩm vẫn công khai.
- Header hiển thị theo trạng thái đăng nhập (đăng nhập/đăng ký, hoặc lời chào, đăng xuất và link quản trị).
- Trang 403 tối thiểu và trang `/admin` tạm (xem câu hỏi mở 4). Các trang lỗi hoàn chỉnh làm ở GĐ7; dashboard thật làm ở GĐ6.

**Ngoài phạm vi**
- Từ danh sách "Loại ra" của brief: **quên mật khẩu**, **email** (không có xác minh email, không gửi thư).
- Gộp giỏ hàng trong session vào giỏ trong DB khi đăng nhập (ADR-0006). Việc này thuộc GĐ4, nhưng GĐ3 không được làm gì cản trở nó.
- Ghi nhớ đăng nhập ("remember me"), đăng nhập bằng Google hoặc mạng xã hội, xác thực hai lớp.
- Khóa tài khoản hoặc giới hạn số lần đăng nhập sai (xem câu hỏi mở 7).
- Sửa hồ sơ, đổi mật khẩu, admin quản lý người dùng.

## 3. User stories

- **US-003-1:** Là khách, tôi muốn tạo tài khoản bằng email và mật khẩu để sau này đặt hàng và xem lại đơn của mình.
- **US-003-2:** Là người đã có tài khoản, tôi muốn đăng nhập và đăng xuất an toàn.
- **US-003-3:** Là quản trị viên, tôi muốn chỉ tài khoản `ADMIN` mới vào được khu `/admin`.
- **US-003-4:** Là người dùng, tôi muốn header cho biết tôi đang đăng nhập bằng tài khoản nào.

## 4. Tiêu chí chấp nhận

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-003-1 | Khách chưa đăng nhập | mở `/register` | HTTP 200, form có các ô "Họ và tên", "Email", "Mật khẩu", "Nhập lại mật khẩu" và nút "Đăng ký"; tiêu đề tab `Đăng ký \| Mini Shop` | `AuthControllerTest` |
| AC-003-2 | Thông tin hợp lệ, email chưa có | gửi form đăng ký | tạo người dùng vai trò `USER`, mật khẩu lưu dạng `{bcrypt}…`; chuyển sang `/login` với thông báo "Đăng ký thành công. Vui lòng đăng nhập." | `UserServiceTest`, `RegistrationIntegrationTest` |
| AC-003-3 | Email có chữ hoa và khoảng trắng, ví dụ `  An@Example.COM ` | đăng ký, rồi đăng nhập bằng `an@example.com` | email được lưu là `an@example.com` (bỏ khoảng trắng hai đầu, chữ thường); đăng nhập thành công | `RegistrationIntegrationTest` |
| AC-003-4 | Email đã được đăng ký, kể cả khác hoa thường | gửi form đăng ký | không tạo tài khoản; form hiện lại với lỗi "Email này đã được đăng ký." dưới ô Email; các ô khác (trừ mật khẩu) giữ nguyên giá trị | `UserServiceTest`, `AuthControllerTest` |
| AC-003-5 | Hai yêu cầu đăng ký cùng email gửi gần như đồng thời | cả hai được xử lý | chỉ một tài khoản được tạo; yêu cầu còn lại nhận đúng lỗi của AC-003-4, không bao giờ trang 500 | `RegistrationIntegrationTest` |
| AC-003-6 | Dữ liệu không hợp lệ | gửi form đăng ký | form hiện lại với lỗi dưới từng ô, đúng chữ ở §6: họ tên trống hoặc dài hơn 100 ký tự; email trống, sai định dạng hoặc dài hơn 255; mật khẩu không đạt quy tắc BR-003-2; nhập lại mật khẩu không khớp. Mật khẩu không bao giờ được điền lại vào form | `AuthControllerTest`, `RegistrationFormValidationTest` |
| AC-003-7 | Kẻ xấu gửi thêm `role=ADMIN` (hoặc trường lạ khác) trong form đăng ký | gửi form | tài khoản tạo ra vẫn là `USER` (chống mass assignment) | `AuthControllerTest`, `RegistrationIntegrationTest` |
| AC-003-8 | Khách chưa đăng nhập | mở `/login` | HTTP 200, form có ô "Email", "Mật khẩu" và nút "Đăng nhập", link "Chưa có tài khoản? Đăng ký"; tiêu đề tab `Đăng nhập \| Mini Shop` | `AuthControllerTest` |
| AC-003-9 | Tài khoản `user@minishop.local` / mật khẩu demo | đăng nhập | đăng nhập thành công, về trang chủ; header hiện "Xin chào, {họ tên}" và nút "Đăng xuất" | `LoginFlowTest` |
| AC-003-10 | Khách mở `/orders` (cần đăng nhập) | bị chuyển sang `/login` rồi đăng nhập thành công | được đưa trở lại `/orders` (trang đã yêu cầu trước đó) | `LoginFlowTest` |
| AC-003-11 | Sai mật khẩu, hoặc email không tồn tại | đăng nhập | ở lại `/login` với thông báo chung "Email hoặc mật khẩu không đúng." (không tiết lộ email có tồn tại hay không); ô email giữ giá trị vừa nhập | `LoginFlowTest` |
| AC-003-12 | Đăng nhập bằng email viết hoa, có khoảng trắng | đăng nhập | thành công như email chữ thường | `LoginFlowTest` |
| AC-003-13 | Đang đăng nhập | bấm "Đăng xuất" (POST có CSRF token) | phiên bị hủy, về trang chủ với thông báo "Bạn đã đăng xuất."; `GET /logout` không đăng xuất | `LoginFlowTest` |
| AC-003-14 | Tài khoản `USER` | mở `/admin` hoặc bất kỳ `/admin/**` | HTTP **403** với trang "Bạn không có quyền truy cập trang này." và link "Về trang chủ" | `SecurityConfigTest`, `AccessDeniedTest` |
| AC-003-15 | Khách chưa đăng nhập | mở `/admin/**`, `/orders/**`, `/checkout/**` | chuyển sang `/login` | `SecurityConfigTest` |
| AC-003-16 | Tài khoản `ADMIN` | mở `/admin` | HTTP 200, trang tạm "Trang quản trị"; header có link "Quản trị" (link này không hiện với `USER` hay khách) | `SecurityConfigTest`, `AuthControllerTest` |
| AC-003-17 | Đã đăng nhập | mở `/login` hoặc `/register` | chuyển về trang chủ | `AuthControllerTest` |
| AC-003-18 | Bất kỳ form POST nào (đăng ký, đăng nhập, đăng xuất) | gửi **không có** CSRF token | bị từ chối (403), không có thay đổi dữ liệu | `SecurityConfigTest` |
| AC-003-19 | Đăng nhập thành công | so sánh session ID trước và sau | session ID **đổi** (chống session fixation) | `LoginFlowTest` |
| AC-003-20 | Ứng dụng khởi động | đọc log | không còn dòng "Using generated security password"; Spring Security dùng người dùng trong DB | `LoginFlowTest` + checklist |
| AC-003-21 | Họ tên chứa HTML, ví dụ `<script>alert(1)</script>` | đăng ký rồi đăng nhập | header hiện họ tên dưới dạng văn bản đã escape, không chạy script | `AuthControllerTest` |
| AC-003-22 | Mọi trang mới (đăng ký, đăng nhập, 403, admin tạm) | xem trên trình duyệt desktop và điện thoại 375px | không tràn ngang; console không có lỗi CSP; template qua được `TemplateCspComplianceTest` | `TemplateCspComplianceTest` + checklist |

## 5. Quy tắc nghiệp vụ

- **BR-003-1:** Email được chuẩn hóa (bỏ khoảng trắng hai đầu, chuyển chữ thường) **trước khi** lưu và trước khi tra cứu lúc đăng nhập, nên `An@Example.com` và `an@example.com` là cùng một tài khoản.
- **BR-003-2:** Mật khẩu dài 8–64 ký tự, có ít nhất một chữ cái và một chữ số, và không quá **72 byte** khi mã hóa UTF-8 (giới hạn của BCrypt; mật khẩu có nhiều chữ có dấu có thể vượt dù chưa tới 64 ký tự). Không ép ký tự đặc biệt.
- **BR-003-3:** Đăng ký luôn tạo vai trò `USER`. Không có đường nào trên giao diện để tự cấp `ADMIN`; tài khoản admin chỉ đến từ dữ liệu mẫu.
- **BR-003-4:** Mật khẩu (dạng rõ hay dạng băm) không bao giờ xuất hiện trong log, thông báo lỗi, hay được điền lại vào form.
- **BR-003-5:** Thông báo đăng nhập sai luôn giống nhau, dù sai email hay sai mật khẩu.

## 6. Giao diện và luồng

| URL | Phương thức | Quyền | Mô tả |
|---|---|---|---|
| `/register` | GET, POST | Khách (đã đăng nhập thì về `/`) | Form đăng ký |
| `/login` | GET | Khách (đã đăng nhập thì về `/`) | Form đăng nhập |
| `/login` | POST | Mọi người | Spring Security xử lý đăng nhập |
| `/logout` | POST | Đã đăng nhập | Đăng xuất |
| `/admin`, `/admin/**` | * | `ADMIN` | Khu quản trị (GĐ3 chỉ có trang tạm `/admin`) |
| `/checkout/**`, `/orders/**` | * | Đã đăng nhập | Chưa có trang (GĐ5); GĐ3 chỉ đặt quyền |
| `/`, `/products/**`, tài nguyên tĩnh, `/credits.html`, `/actuator/health` | GET | Mọi người | Như spec 001, 002 |

**Chữ hiển thị chính xác:**

| Vị trí | Nội dung |
|---|---|
| Tiêu đề tab | `Đăng ký \| Mini Shop`, `Đăng nhập \| Mini Shop`, `Không có quyền truy cập \| Mini Shop`, `Quản trị \| Mini Shop` |
| Form đăng ký | nhãn `Họ và tên`, `Email`, `Mật khẩu`, `Nhập lại mật khẩu`; nút `Đăng ký`; link `Đã có tài khoản? Đăng nhập` |
| Gợi ý mật khẩu | `Ít nhất 8 ký tự, gồm chữ và số.` |
| Lỗi họ tên | `Vui lòng nhập họ và tên.` / `Họ và tên tối đa 100 ký tự.` |
| Lỗi email | `Vui lòng nhập email.` / `Email không hợp lệ.` / `Email tối đa 255 ký tự.` / `Email này đã được đăng ký.` |
| Lỗi mật khẩu | `Mật khẩu phải có từ 8 đến 64 ký tự.` / `Mật khẩu phải có cả chữ và số.` / `Mật khẩu quá dài.` (vượt 72 byte) |
| Lỗi nhập lại | `Mật khẩu nhập lại không khớp.` |
| Đăng ký thành công (trên trang đăng nhập) | `Đăng ký thành công. Vui lòng đăng nhập.` |
| Form đăng nhập | nhãn `Email`, `Mật khẩu`; nút `Đăng nhập`; link `Chưa có tài khoản? Đăng ký` |
| Đăng nhập sai | `Email hoặc mật khẩu không đúng.` |
| Đăng xuất | `Bạn đã đăng xuất.` |
| Header khi chưa đăng nhập | `Đăng nhập`, `Đăng ký` |
| Header khi đã đăng nhập | `Xin chào, {họ tên}`, nút `Đăng xuất`; thêm link `Quản trị` nếu là `ADMIN` |
| Trang 403 | `Bạn không có quyền truy cập trang này.` + link `Về trang chủ` |
| Trang admin tạm | tiêu đề `Trang quản trị`, dòng `Khu quản trị sẽ được xây dựng ở giai đoạn sau.` |

## 7. Dữ liệu

- Dùng bảng `users` từ V1; không thêm cột.
- Migration **V2** thêm ràng buộc `ck_users_email_lowercase CHECK (email = lower(trim(email)))`, để DB cũng bảo đảm BR-003-1 (chữ thường và không có khoảng trắng hai đầu). Dữ liệu mẫu hiện có đều đạt nên V2 chạy được trên DB đã có dữ liệu.
- Email đăng ký chỉ nhận ký tự ASCII (sai thì báo `Email không hợp lệ.`), để cách chuyển chữ thường của Java và của PostgreSQL không bao giờ lệch nhau.

## 8. Yêu cầu phi chức năng

- **Bảo mật:** CSRF bật cho mọi form; session ID đổi sau khi đăng nhập; cookie phiên `HttpOnly` (mặc định); mật khẩu băm bằng `DelegatingPasswordEncoder` (`{bcrypt}`) đã có từ GĐ1; không lộ thông tin "email có tồn tại không" ở trang đăng nhập.
- **Kiến trúc:** form đăng ký là `record` có Jakarta Validation; logic đăng ký (chuẩn hóa email, kiểm tra trùng, băm mật khẩu, gán `USER`) nằm trong service `@Transactional`; controller không chạm `UserRepository` (`ArchitectureTest` đã kiểm). Việc tải người dùng cho Spring Security nằm trong package `user`.
- **CSP và giao diện:** dùng lại layout và hướng thiết kế của GĐ2; không script hay style nội tuyến; form có `label`, lỗi gắn với ô tương ứng.

## 9. Câu hỏi mở

> **Đã trả lời (2026-10-24):** người dùng duyệt cả 7 câu theo khuyến nghị. Migration V2 (câu 5) và trang `/admin` tạm (câu 4) nằm trong phạm vi GĐ3.

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Đăng ký xong thì tự đăng nhập, hay chuyển sang trang đăng nhập? | **Chuyển sang trang đăng nhập** kèm thông báo (AC-003-2). Chỉ có một đường đăng nhập duy nhất, nên việc gộp giỏ hàng ở GĐ4 chỉ cần gắn vào một chỗ. |
| 2 | Quy tắc mật khẩu? | 8–64 ký tự, có chữ và số, tối đa 72 byte (BR-003-2). Không bắt ký tự đặc biệt: độ dài quan trọng hơn. |
| 3 | Có hỏi số điện thoại khi đăng ký không? | **Không.** Số điện thoại nhận hàng được nhập ở bước đặt hàng (GĐ5). |
| 4 | GĐ3 có trang `/admin` tạm không? | **Có**, một trang tối thiểu chỉ `ADMIN` vào được, để link "Quản trị" không dẫn tới 404 và để kiểm được quyền theo cả hai chiều. GĐ6 thay bằng dashboard. |
| 5 | Thêm ràng buộc DB bảo đảm email luôn là chữ thường (migration V2)? | **Có**: chặn mọi đường ghi dữ liệu bỏ qua service (seed, SQL tay). |
| 6 | Đăng nhập xong về đâu? | **Trang đã yêu cầu trước đó** nếu có (AC-003-10), nếu không thì trang chủ, kể cả với `ADMIN`. |
| 7 | Có khóa tài khoản sau nhiều lần đăng nhập sai không? | **Không** trong MVP (dự án demo, chạy local). Ghi rủi ro vào phần "Trước khi deploy" cùng việc tắt dữ liệu mẫu (GĐ8). |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-24 | Tạo bản nháp |
| 2026-10-24 | Người dùng duyệt spec và 7 câu hỏi mở theo khuyến nghị; trạng thái `Đã duyệt` |
| 2026-10-24 | Người dùng duyệt kế hoạch [phase-3](../plans/phase-3.md); trạng thái `Đang làm` |
| 2026-10-24 | Theo review schema: CHECK của V2 kiểm cả `trim`; email đăng ký giới hạn ASCII (§7) |
| 2026-10-24 | `phase-verifier` đạt cả 24 mục checklist, 273 test xanh; trạng thái `Hoàn thành` |

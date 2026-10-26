# ADR-0007: Đăng nhập bằng form trên session, principal riêng, email luôn chữ thường

- **Trạng thái:** Bị thay thế bởi ADR-0014
- **Ngày:** 2026-10-24

## Bối cảnh

GĐ3 (spec 003) thêm đăng ký, đăng nhập và phân quyền `USER`/`ADMIN`. ADR-0003 đã chọn session + CSRF. ADR này bổ sung cho ADR-0003, không thay thế nó, và trả lời các câu hỏi còn lại:
- Spring Security giữ gì trong session để biết "ai đang đăng nhập"?
- Email được so khớp không phân biệt hoa thường ở đâu, và DB có bảo đảm điều đó không?
- Làm sao để hai người đăng ký cùng một email cùng lúc không tạo ra hai tài khoản, và không ai gặp trang 500?

## Quyết định

1. **Form login của Spring Security, trang `/login` riêng, phiên lưu trong `HttpSession`.**
   - Đăng xuất chỉ bằng `POST /logout` có CSRF.
   - Session ID đổi sau khi đăng nhập (`changeSessionId`, mặc định).
   - Sau khi đăng nhập, người dùng về trang đã yêu cầu trước đó, nếu không có thì về `/`.
2. **Principal là `user.CurrentUser`, một class riêng, không phải entity `User`.**
   - Nó chỉ chứa `id`, `email`, `fullName`, `role`. Hash mật khẩu bị xóa (`eraseCredentials`) ngay sau khi đăng nhập thành công.
   - Các tính năng khác lấy người dùng hiện tại qua `@AuthenticationPrincipal CurrentUser`. Đây là kiểu public duy nhất mới thêm ở `user`.
3. **Email được chuẩn hóa ở một hàm duy nhất, `user.EmailNormalizer`** (bỏ khoảng trắng hai đầu rồi chuyển chữ thường theo `Locale.ROOT`). Cả đăng ký lẫn đăng nhập đều đi qua hàm này.
   - Email đăng ký chỉ nhận ký tự ASCII, để cách chuyển chữ thường của Java và của PostgreSQL không bao giờ lệch nhau.
   - DB bảo đảm cùng quy tắc bằng `ck_users_email_lowercase CHECK (email = lower(trim(email)))` (V2). Vì giá trị lưu đã là chữ thường, unique constraint thường `uq_users_email` đủ để chặn trùng không phân biệt hoa thường.
4. **Chống đăng ký trùng: kiểm tra trước, cộng với bắt lỗi unique constraint trong service.**
   - `UserService.register` gọi `saveAndFlush` để lỗi xảy ra ngay trong method.
   - Lỗi của đúng `uq_users_email` thành `EmailAlreadyRegisteredException`. Lỗi của ràng buộc khác vẫn được ném ra nguyên vẹn, vì đó là bug thật.
5. **Không khóa tài khoản, không giới hạn số lần đăng nhập sai trong MVP.** Đây là rủi ro đã biết, phải xử lý trước khi deploy (GĐ8), cùng với việc tắt dữ liệu mẫu.

## Hệ quả

- **Tích cực:**
  - Không có entity nào nằm trong session, nên không có lỗi lazy loading và không lộ hash mật khẩu.
  - Header không tốn truy vấn DB nào.
  - Quy tắc email nằm ở một chỗ trong Java, và DB vẫn bảo vệ được cả những đường ghi bỏ qua service.
  - Race khi đăng ký trùng được chặn bởi DB, nơi duy nhất làm được việc đó.
- **Tiêu cực / đánh đổi:**
  - Principal là bản chụp lúc đăng nhập: họ tên trên header, và cả **vai trò**, chỉ cập nhật ở lần đăng nhập sau. MVP không có chức năng sửa hồ sơ hay đổi vai trò nên chấp nhận được. Nếu sau này có, phải hủy phiên của người bị đổi.
  - BCrypt chạy bên trong transaction đăng ký, nên giữ một connection của pool khoảng 100 ms. Không đáng kể với lưu lượng của MVP.
  - Không nhận email có ký tự ngoài ASCII.
  - Nhận diện tên constraint phụ thuộc vào thông tin Hibernate trả về. H2 thêm tiền tố schema, nên code so bằng `contains`.

## Phương án đã cân nhắc

- **Dùng entity `User` làm principal:** entity bị serialize vào session, lazy loading lỗi khi không còn transaction, và hash mật khẩu đi theo mọi chỗ đọc principal.
- **Tra họ tên từ DB mỗi request bằng `@ControllerAdvice`:** thêm một truy vấn cho mọi trang mà không được gì thêm.
- **Kiểu `citext`, hoặc unique index trên `lower(email)`:** `citext` chỉ có trên PostgreSQL, còn H2 không hỗ trợ index trên biểu thức, nên cả hai đều làm hỏng profile `h2`.
- **JWT / đăng nhập không trạng thái:** đã loại ở ADR-0003. App render phía server không cần.
- **Tự đăng nhập ngay sau khi đăng ký:** tạo thêm một đường đăng nhập thứ hai. Việc gộp giỏ hàng (ADR-0006, GĐ4) khi đó phải gắn vào hai chỗ.

# ADR-0014: Ứng dụng desktop JavaFX thay cho ứng dụng web

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-26

## Bối cảnh

Đến `v0.8.0`, Mini Shop là ứng dụng web render phía server (Spring MVC + Thymeleaf, ADR-0003), chạy trên một máy và lưu dữ liệu trong H2 file (ADR-0013). Việc chia sẻ qua Cloudflare Tunnel đã bị hoãn đến sau MVP. Mục tiêu học tập của dự án chuyển sang **giao diện viết 100% bằng Java**, chạy như một ứng dụng desktop Windows, theo phong cách "soft modern" (thẻ trắng bo góc lớn, nút dạng viên thuốc, một màu nhấn cam).

Ràng buộc:
- Giữ nguyên các quy tắc nghiệp vụ và các bảo đảm về đồng thời đã có (ADR-0005, 0008, 0009, 0010) cùng bộ test đi kèm.
- Không cần server, không mở cổng mạng; chỉ cần JDK 25 để build.
- Giữ cấu trúc package theo tính năng (ADR-0002) và tính package-private của service.

## Quyết định

1. **Giao diện dùng JavaFX 25** (`javafx-controls`, `javafx-fxml` từ Maven Central), định kiểu bằng CSS của JavaFX với một bảng token màu, cỡ chữ, bo góc; phông Plus Jakarta Sans (OFL) đóng gói trong app.
2. **Một tiến trình duy nhất:** Spring Boot chạy với `web-application-type: none`, vẫn dùng JPA, Flyway, H2 file trong `data/`, Validation. JavaFX gọi thẳng các service; không có lớp REST.
3. **Khởi động:** `Launcher.main` (không kế thừa `Application`) gọi `Application.launch(MiniShopApp.class)`; `MiniShopApp.init()` dựng `ApplicationContext`, `start()` nạp FXML với `controllerFactory = context::getBean`, `stop()` đóng context.
4. **Vị trí code giao diện:** màn hình nằm trong package của tính năng, như controller web trước đây (`catalog`, `cart`, `order`, `user`, `admin`), để service vẫn package-private. Phần dùng chung (khung cửa sổ, điều hướng, theme, chạy nền, báo lỗi) nằm ở package mới `com.minishop.desktop`. Lớp JavaFX có hậu tố `*Screen`, `*Drawer`, `*Dialog`; logic màn không phụ thuộc JavaFX node nằm trong `*Presenter`. ArchUnit bảo đảm service và entity không import `javafx..`.
5. **Không gọi service trên luồng JavaFX:** mọi lời gọi đi qua `desktop.BackgroundTasks` (executor + `javafx.concurrent.Task`), kết quả về luồng giao diện bằng `Platform.runLater`.
6. **Phiên và quyền:**
   - Bean `AppSession` giữ `CurrentUser` đang đăng nhập (hoặc trạng thái khách).
   - `AuthService` kiểm mật khẩu bằng `PasswordEncoder` hiện có (chỉ còn `spring-security-crypto`; hash `{bcrypt}` cũ dùng tiếp).
   - Giỏ khách nằm trong bộ nhớ (`InMemoryGuestCartStore`, vẫn qua interface `GuestCartStore`) và được gộp vào giỏ DB ngay sau khi đăng nhập.
   - Service quản trị tự kiểm vai trò ADMIN, không chỉ dựa vào việc ẩn nút.
   - Mọi truy vấn đơn của khách vẫn lọc theo `user_id`.
7. **Bỏ phần web:** controller, Thymeleaf, WebJars/Bootstrap, Spring Security web (filter chain, CSRF, CSP, form login, giới hạn đăng nhập), xử lý lỗi web, actuator, H2 console, chế độ `public` và Cloudflare Tunnel.
8. **Lỗi:** lỗi nghiệp vụ hiện thành thông báo trên màn; lỗi không lường trước được `Thread.setDefaultUncaughtExceptionHandler` ghi log một dòng (giữ quy tắc không ghi dữ liệu cá nhân và thông điệp SQL) rồi hiện hộp thoại chung.
9. **Phát hành:** `jpackage` tạo thư mục ứng dụng có `MiniShop.exe` (thêm `.msi` khi máy build có WiX).

ADR này thay thế ADR-0003, ADR-0007, ADR-0011 và ADR-0012. Nó thay thế một phần ADR-0006 (giỏ khách trong session; giỏ DB và việc gộp khi đăng nhập vẫn giữ) và ADR-0010 (đường phục vụ `/uploads/**`; `StorageService`, kiểm tra ảnh và điều chỉnh tồn kho vẫn giữ).

## Hệ quả

- **Tích cực:**
  - Tầng nghiệp vụ, migration và phần lớn test giữ nguyên.
  - Chạy bằng một file, không cần trình duyệt hay cổng mạng.
  - Bỏ được cả một lớp phòng thủ web (CSRF, CSP, throttle, forwarded headers) vốn không còn ý nghĩa.
- **Tiêu cực / đánh đổi:**
  - Mất khả năng nhiều người dùng cùng lúc qua mạng; H2 chỉ cho một tiến trình mở `data/`, nên mỗi lần chỉ chạy được một cửa sổ app.
  - Đăng nhập không còn là ranh giới bảo mật thật: ai ngồi ở máy đọc được file DB. Đăng nhập chỉ để tách vai trò và để mỗi khách thấy đơn của mình.
  - Giỏ khách mất khi đóng app.
  - Test giao diện cần TestFX (Monocle để chạy headless), dễ chập chờn hơn MockMvc; vì vậy logic màn nằm trong `*Presenter` để test bằng JUnit thường.
  - JavaFX trên classpath cần lớp `Launcher` trung gian và cấu hình đóng gói riêng.

## Phương án đã cân nhắc

- **Swing + FlatLaf:** có sẵn trong JDK, nhưng bo góc, thẻ và hiệu ứng như thiết kế phải tự vẽ bằng Java2D, tốn công hơn nhiều.
- **JavaFX làm client gọi REST tới Spring Boot:** phải chạy hai tiến trình và thêm lớp API + JSON chỉ để nói chuyện trên cùng một máy.
- **Viết lại bằng Java thuần + JDBC, không Spring:** nhẹ hơn nhưng phải viết lại gần như toàn bộ nghiệp vụ, giao dịch và test.
- **Giữ web và bọc trong WebView:** giao diện vẫn là HTML/CSS, không đạt mục tiêu "giao diện 100% Java".

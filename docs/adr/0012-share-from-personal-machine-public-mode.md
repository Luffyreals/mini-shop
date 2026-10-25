# ADR-0012: Chia sẻ app từ máy cá nhân qua Cloudflare Quick Tunnel; chế độ công khai (`public`) và chạy PostgreSQL không Docker (`local`)

- **Trạng thái:** Bị thay thế bởi ADR-0014 (phần database đã bị thay thế bởi ADR-0013 trước đó)
- **Ngày:** 2026-10-25

## Bối cảnh

Brief §9 dự định GĐ8 đóng gói Docker image, deploy lên một PaaS miễn phí với PostgreSQL được quản lý, lưu ảnh trên đám mây và CD từ `main`. Tra cứu ngày 2026-10-25 cho thấy:
- PaaS miễn phí cho app ngủ sau một thời gian không có truy cập;
- Postgres miễn phí của Render tự xóa sau 30 ngày;
- ổ đĩa của PaaS bị xóa mỗi lần khởi động lại, nên ảnh upload sẽ mất.

Người dùng quyết định ([spec 008](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/008-deploy.md)): đây là dự án học tập, chỉ cần app truy cập được từ internet **trong lúc chủ repo đang chạy nó trên máy mình**, và muốn chạy được với PostgreSQL cài trên Windows, không cần Docker.

Mở một app ra internet, dù chỉ tạm thời, phải xử lý các rủi ro đã ghi từ trước:
- `CLAUDE.md`: seed tạo `admin@minishop.local` với mật khẩu dev đã biết;
- ADR-0007 điểm 5: chưa giới hạn số lần đăng nhập sai;
- ADR-0008, 0009, 0010: câu khóa dòng chưa có `lock_timeout`;
- ADR-0011: chưa giới hạn kích thước và tốc độ request ở phía trước app.

## Quyết định

**1. Chia sẻ bằng Cloudflare Quick Tunnel** (`cloudflared tunnel --url http://127.0.0.1:8080`): không cần tài khoản, HTTPS sẵn, URL `*.trycloudflare.com` ngẫu nhiên, tồn tại tới khi tắt lệnh. Chủ repo tự chạy lệnh này; không có PaaS, Docker image hay CD.

**2. Hai profile mới, xếp chồng được:**

| Profile | DB | Công khai |
|---|---|---|
| (mặc định) | Docker Compose tự bật | không |
| `h2` | H2 trong bộ nhớ | không |
| `local` | PostgreSQL cài trên máy (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) | không |
| `public` | Docker Compose | có |
| `local,public` | PostgreSQL cài trên máy | có |

`public` hoặc `local` đi cùng `h2` bị từ chối khi khởi động: H2 trong bộ nhớ mất dữ liệu, và profile `h2` có H2 console với chuỗi filter không CSRF. Ở `public`: Tomcat chỉ nghe trên `127.0.0.1` (`server.address`); tắt H2 console, LiveReload, tự khởi động lại và lưu phiên ra đĩa của DevTools. H2 console tắt ở mọi profile trừ `h2` (DevTools vốn bật nó ở mọi nơi).

**3. Kiểm điều kiện khởi động bằng một `EnvironmentPostProcessor`** (`config.StartupRequirements`), chạy trước khi bất kỳ bean, DB hay Docker Compose khởi động. Ở `public`, nó bắt buộc có `SEED_ADMIN_PASSWORD`, `SEED_USER_PASSWORD`, dài ít nhất 12 ký tự, khác mật khẩu mặc định của dev và **khác nhau**. Ở `local`, nó bắt buộc có thông tin kết nối DB. Thông báo lỗi nêu tên biến, không bao giờ in giá trị. Không dùng `@Validated` trên `@ConfigurationProperties`, vì báo cáo lỗi của Boot in giá trị bị từ chối (chính là mật khẩu).

**4. Đặt lại mật khẩu demo trước khi nhận request:** ở `public`, `config.PublicModeDemoPasswords` (`SmartInitializingSingleton`) gọi `user.DemoAccountService.resetDemoPasswords`. Một transaction, một câu `@Modifying` theo email, **chỉ** cho `admin@minishop.local` và `user@minishop.local`, băm BCrypt. Việc này chạy trước khi Tomcat nhận request. Không làm trong `DataInitializer` (một `ApplicationRunner`), vì runner chạy khi Tomcat đã phục vụ, để lại vài giây admin còn mật khẩu dev. DB trống thì seed hiện có tạo tài khoản bằng mật khẩu từ biến môi trường.

**5. Giới hạn đăng nhập sai, bật ở mọi profile:**
- **Ngưỡng:** 5 lần sai/email và 20 lần sai/IP trong 15 phút.
- **Cổng chặn và đếm là một bước nguyên tử:** một filter đứng trước `UsernamePasswordAuthenticationFilter` gọi `LoginThrottle.tryAcquire(email, ip)`. Nếu email hoặc IP đang bị chặn thì từ chối ngay, mật khẩu không được kiểm, kể cả mật khẩu đúng. Nếu không, lần thử được **tính ngay** (tạm thời) trước khi kiểm mật khẩu. Đăng nhập đúng thì success handler xóa bộ đếm của email và gỡ đúng lần thử đó khỏi bộ đếm của IP. Lý do: nếu chỉ kiểm "bị chặn chưa" ở cửa rồi đếm sau khi bcrypt xong, N request gửi song song cho cùng một email đều lọt qua cửa trước khi lần sai đầu tiên được đếm, nên kẻ tấn công đoán được N mật khẩu thay vì 5 (cùng kiểu lỗi "đọc rồi ghi" mà ADR-0005 tránh cho tồn kho).
- **Bộ đếm `user.LoginThrottle`:** trong bộ nhớ, cửa sổ trượt, giới hạn 10.000 khóa (LRU, xóa khóa không bị chặn trước), dùng `Clock` để test.
- **Phản hồi:** giống nhau cho email có và không có tài khoản.

**6. Header của tunnel:** `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`) với `internal-proxies` chỉ là loopback (`127.0.0.0/8`, `::1/128`). Header `X-Forwarded-*` chỉ được tin khi kết nối đến từ `cloudflared` chạy trên chính máy. Valve đọc `X-Forwarded-For` từ phải sang trái, nên IP giả mà client tự gửi không bao giờ được chọn. `X-Forwarded-Host` và `X-Forwarded-Port` không được dùng (`cloudflared` đã chuyển đúng `Host`), để client không chèn host giả vào URL chuyển hướng. `/actuator/health` chỉ trả `{"status":"UP"}` (tắt probe liveness/readiness mà Boot 4.1 bật sẵn). Cookie phiên `SameSite=Lax` ở mọi profile, `Secure` (đặt tường minh) chỉ ở `public`. HSTS do Spring Security gửi khi request là HTTPS. Cổng PostgreSQL của `compose.yaml` chỉ mở trên loopback.

**7. `lock_timeout` 5 giây cho mọi connection** bằng `spring.datasource.hikari.connection-init-sql` (`SET lock_timeout = '5s'`; H2 dùng `SET LOCK_TIMEOUT 5000`). Cách này phủ cả các câu `SELECT … FOR UPDATE/NO KEY UPDATE` lẫn các câu `UPDATE` có điều kiện trên `products`. Quá hạn thì `PessimisticLockingFailureException` làm transaction rollback, rồi tới trang 500 và một dòng `ERROR` của ADR-0011. Không thử lại tự động; thứ tự khóa của ADR-0009/0010 không đổi.

## Hệ quả

- **Tích cực:**
  - Chi phí 0 đồng, không cần thẻ hay tài khoản; ảnh và dữ liệu nằm trên ổ đĩa thật nên không mất.
  - Không bao giờ có lúc app công khai mà admin còn mật khẩu dev; mật khẩu `USER` gửi cho người khác không mở được admin.
  - Đoán mật khẩu bị chặn; không giả IP được để lách giới hạn.
  - Không còn câu khóa chờ vô hạn; app chạy được không cần Docker.
- **Tiêu cực / đánh đổi:**
  - URL đổi mỗi lần chạy; máy phải bật; Cloudflare ghi rõ Quick Tunnel chỉ dành cho thử nghiệm, không cam kết uptime.
  - Ai có link đều vào được; dữ liệu người khác nhập nằm trong DB trên máy chủ repo.
  - Sau khi chạy chế độ công khai, mật khẩu demo trong DB dev là mật khẩu đã đặt (không còn `Admin@123`).
  - Bộ đếm đăng nhập sai mất khi tắt app; một nhóm người dùng chung IP (cùng văn phòng) gõ sai tổng cộng 20 lần sẽ bị chặn chung.
  - Cloudflare đứng trước app: client gửi chậm bị tunnel giới hạn trước, thay cho reverse proxy mà ADR-0011 nhắc tới; thân request ở gói miễn phí giới hạn khoảng 100 MB.
  - Hướng "deploy lên PaaS" (cần Docker image, DB và lưu ảnh đám mây) để lại cho sau.

## Phương án đã cân nhắc

- **PaaS miễn phí (Render + Neon) với CD từ `main`:** người dùng không cần chạy 24/7; tốn thêm Dockerfile, hai tài khoản, một bản `StorageService` mới; app vẫn ngủ khi không dùng.
- **ngrok Free:** cho một domain dev cố định nhưng cần tài khoản, giới hạn khoảng 1 GB/tháng và 20.000 request.
- **Cloudflare Tunnel có tên:** URL cố định nhưng cần tài khoản và tên miền riêng.
- **Mở cổng trên router (port forwarding):** lộ IP nhà, phải tự lo HTTPS, IP nhà thay đổi.
- **Một profile `prod` gộp mọi thứ:** trộn việc chọn DB với việc mở ra internet.
- **Từ chối khởi động nếu admin còn mật khẩu dev** (thay vì đặt lại): người dùng phải tự xóa DB để seed lại.
- **`forward-headers-strategy: framework`:** tin header từ mọi kết nối.
- **Hint JPA `jakarta.persistence.lock.timeout`:** không áp cho các câu `UPDATE` có điều kiện trên `products`.

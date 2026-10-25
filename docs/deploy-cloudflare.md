# Chia sẻ app lên internet qua Cloudflare Tunnel

> **Sau MVP.** Phần này chưa thuộc phạm vi hiện tại của dự án. Code của chế độ `public` đã có từ GĐ8 và vẫn được test, nhưng cách deploy có thể thay đổi. Để chạy thử trên máy, chỉ cần làm theo [README](../README.md#chạy-thử).

Dùng khi muốn gửi link cho người khác (mentor, nhà tuyển dụng) thử app **trong lúc app đang chạy trên máy bạn**. Link là một địa chỉ HTTPS của [Cloudflare Quick Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/): không cần tài khoản, không mở cổng router, không tốn tiền. Lý do chọn cách này: [ADR-0012](adr/0012-share-from-personal-machine-public-mode.md).

> **Đọc trước khi bật:**
> - **Máy phải bật và app phải chạy.** Tắt app, tắt tunnel hoặc máy ngủ thì link ngừng hoạt động. Link đổi sau mỗi lần chạy tunnel.
> - Ai có link đều vào được, nên chỉ gửi cho người bạn tin. Dữ liệu họ nhập nằm trong `data/` trên máy bạn.
> - Chế độ công khai **đặt lại mật khẩu** của `admin@minishop.local` và `user@minishop.local` theo hai biến bên dưới. Sau đó, kể cả khi chạy lại bình thường, hai tài khoản này dùng mật khẩu mới (xóa `data/` để về `Admin@123`, `User@123`).
> - Cloudflare ghi rõ Quick Tunnel dành cho thử nghiệm, không cam kết luôn chạy.

1. **Cài `cloudflared`** (một lần): Windows `winget install --id Cloudflare.cloudflared`, macOS `brew install cloudflared`, Linux xem [trang tải của Cloudflare](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/).
2. **Chọn hai mật khẩu demo**: mỗi mật khẩu dài ít nhất 12 ký tự, có cả chữ và số, khác mật khẩu dev, và **hai mật khẩu phải khác nhau** (mật khẩu `USER` sẽ gửi cho người khác, mật khẩu `ADMIN` giữ riêng). Thiếu hoặc không đạt thì app dừng ngay và báo tên biến.
3. **Chạy app ở chế độ công khai** (profile `public`):

   Git Bash / macOS / Linux:
   ```bash
   export SEED_ADMIN_PASSWORD='Doi-mat-khau-admin-1' SEED_USER_PASSWORD='Doi-mat-khau-user-2'
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=public
   ```

   PowerShell:
   ```powershell
   $env:SEED_ADMIN_PASSWORD = "Doi-mat-khau-admin-1"; $env:SEED_USER_PASSWORD = "Doi-mat-khau-user-2"
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=public"
   ```
4. **Mở tunnel** ở một cửa sổ terminal khác:

   ```bash
   cloudflared tunnel --url http://127.0.0.1:8080
   ```

   Sau vài giây, `cloudflared` in ra địa chỉ dạng `https://<chuỗi-ngẫu-nhiên>.trycloudflare.com`. Gửi địa chỉ đó cùng tài khoản `user@minishop.local` và mật khẩu `SEED_USER_PASSWORD`. Không gửi tài khoản admin; muốn khoe trang quản trị thì chia sẻ màn hình hoặc dùng [ảnh chụp trong README](../README.md#ảnh-chụp).
5. **Dừng**: `Ctrl+C` ở cửa sổ tunnel, rồi `Ctrl+C` ở cửa sổ app.

Ở chế độ công khai:
- app chỉ nhận kết nối từ chính máy (`127.0.0.1`), nên máy khác trong cùng Wi-Fi không mở được `http://<IP-máy>:8080`; mọi người, kể cả bạn trên điện thoại, dùng link tunnel;
- H2 console tắt, DevTools không tự khởi động lại app, cookie phiên chỉ gửi qua HTTPS.

Ở mọi chế độ, đăng nhập sai 5 lần với cùng một email (hoặc 20 lần từ cùng một IP) trong 15 phút thì bị chặn 15 phút, kể cả khi gõ đúng mật khẩu. Tắt app là bộ đếm về 0.

**Xóa dữ liệu người khác đã nhập**: dừng app, xóa `data/`, chạy lại.

# Spec 008: Chia sẻ app đang chạy trên máy cá nhân lên internet (chế độ công khai qua tunnel)

- **Giai đoạn:** GĐ8 (tùy chọn)
- **Trạng thái:** Hoàn thành
- **Liên quan:** brief §4 (demo), §7 (bảo mật, dữ liệu cá nhân), §9 (triển khai), §10 (vận hành), §12 (link demo); ADR-0004, 0007, 0008, 0009, 0011; CLAUDE.md "The seed must be gated before any deployment"; spec 003 câu hỏi 7 (đăng nhập sai), spec 007 §2 (danh sách việc của GĐ8)

> **Thay đổi so với brief §9 (quyết định của người dùng, 2026-10-25):** dự án là dự án học tập, nên **không** deploy lên PaaS và không có CD. App chỉ cần truy cập được từ internet **trong lúc chủ repo đang chạy nó trên máy mình**. Vì vậy GĐ8 bỏ Docker image, PaaS, DB và lưu ảnh trên đám mây, và chỉ giữ những việc cần để một app chạy trên máy cá nhân được mở ra internet một cách an toàn. Brief §9, §11 sẽ được cập nhật theo quyết định này.

## 1. Mục tiêu

Chủ repo chạy Mini Shop trên máy mình, bật một lệnh tunnel, rồi gửi link HTTPS cho người khác (mentor, nhà tuyển dụng) dùng thử. Tắt app hoặc tunnel thì link ngừng hoạt động. Trước khi app ra internet, các rủi ro đã ghi từ GĐ1–GĐ7 được xử lý: không ai đăng nhập được bằng mật khẩu mặc định của dev, đăng nhập sai bị giới hạn, cookie phiên an toàn sau HTTPS. Ngoài ra, app chạy được với PostgreSQL cài trực tiếp trên Windows, không cần Docker.

## 2. Phạm vi

**Trong phạm vi**
- **Chế độ công khai:** một profile (tên do kế hoạch chốt, ví dụ `public`) dùng khi app được mở ra internet:
  - không H2 console;
  - mật khẩu tài khoản demo bắt buộc lấy từ biến môi trường, cấm mật khẩu mặc định của dev;
  - giới hạn số lần đăng nhập sai;
  - hiểu header của tunnel HTTPS (`X-Forwarded-*`): cookie phiên `Secure` + `SameSite=Lax`, chuyển hướng dùng đúng `https://` và host công khai.
- **`lock_timeout`** cho các câu khóa dòng (đã hẹn ở ADR-0008, 0009, 0010, "trước khi deploy").
- **Chạy với PostgreSQL cài trên máy, không Docker:** một cách cấu hình (profile hoặc biến môi trường) để app dùng PostgreSQL cài trực tiếp trên Windows và tắt việc tự bật Docker Compose. Cách chạy hiện tại với Docker giữ nguyên.
- **Tunnel:** hướng dẫn dùng một dịch vụ tunnel miễn phí để có URL HTTPS công khai trỏ tới `localhost:8080` (câu hỏi 1). Chủ repo tự cài và tự chạy công cụ tunnel.
- **Dòng nhắc "bản demo"** trên form đăng ký và trang thanh toán.
- **README:** mục "Chia sẻ app đang chạy trên máy" và mục "Chạy với PostgreSQL cài trên máy (không Docker)"; cập nhật brief §9, §11 theo quyết định mới.

**Ngoài phạm vi**
- Từ danh sách "Loại ra" của brief: **thanh toán online**, **email**, **quên mật khẩu**, **app mobile**, **REST API công khai**.
- Theo quyết định của người dùng: **Dockerfile / Docker image**, **deploy lên PaaS**, **CD từ `main`**, **DB được quản lý trên đám mây**, **lưu ảnh trên đám mây** (ảnh vẫn ở `uploads/` trên máy, không mất khi tắt app), chạy 24/7.
- Tên miền riêng, URL cố định cần tài khoản hoặc gói trả phí (câu hỏi 1), CDN, header cache cho tài nguyên tĩnh, giám sát, sao lưu tự động.
- Trang đổi mật khẩu cho người dùng; khóa vĩnh viễn tài khoản; CAPTCHA.
- **Việc Claude không làm thay người dùng:** cài công cụ tunnel, tạo tài khoản dịch vụ tunnel (nếu có), cài PostgreSQL trên Windows, đặt mật khẩu tài khoản demo, chạy lệnh mở app ra internet. Kế hoạch chỉ viết hướng dẫn cho các bước này.

## 3. User stories

- **US-008-1:** Là chủ repo, tôi muốn chạy app trên máy và có ngay một link HTTPS để gửi người khác dùng thử, rồi tắt là link hết hiệu lực.
- **US-008-2:** Là chủ repo, tôi muốn chắc chắn khi app ra internet thì không ai đăng nhập được bằng mật khẩu mặc định `Admin@123` hay đoán mật khẩu không giới hạn.
- **US-008-3:** Là người được gửi link, tôi muốn đăng nhập bằng tài khoản demo được cung cấp và đi hết luồng mua hàng như trên máy của chủ repo.
- **US-008-4:** Là chủ repo, tôi muốn chạy app với PostgreSQL cài trên Windows mà không cần bật Docker.
- **US-008-5:** Là chủ repo, tôi muốn làm theo README là mở được app ra internet mà không phải đoán bước nào, và biết rõ rủi ro khi làm vậy.

## 4. Tiêu chí chấp nhận

**Chế độ công khai: khởi động**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-008-1 | Chế độ công khai, thiếu biến môi trường mật khẩu admin hoặc user demo | khởi động app | app **dừng ngay** với một dòng log nêu tên biến còn thiếu (không in giá trị bí mật nào) | `PublicProfileStartupTest` |
| AC-008-2 | Chế độ công khai, mật khẩu demo đặt bằng đúng mật khẩu mặc định của dev (`Admin@123`, `User@123`) hoặc ngắn hơn 12 ký tự, hoặc mật khẩu admin trùng mật khẩu user | khởi động app | app dừng ngay với log `Public mode requires non-default demo passwords` (hoặc câu tương đương do kế hoạch chốt, tiếng Anh, không in mật khẩu) | `PublicProfileStartupTest` |
| AC-008-3 | DB đã có `admin@minishop.local`, `user@minishop.local` từ lúc chạy dev (mật khẩu dev), chế độ công khai với mật khẩu mới hợp lệ | khởi động app | mật khẩu của **đúng hai** tài khoản demo đó được đặt lại theo biến môi trường (băm BCrypt) trước khi app nhận request; `admin@minishop.local` không còn đăng nhập được bằng `Admin@123`; tài khoản khác không bị đụng; log ghi một dòng INFO "demo passwords updated" không kèm mật khẩu (câu hỏi 4) | `PublicProfileStartupTest` (PostgreSQL) |
| AC-008-4 | DB trống, chế độ công khai | khởi động app | dữ liệu mẫu danh mục, sản phẩm được tạo như dev; hai tài khoản demo được tạo với mật khẩu từ biến môi trường | `PublicProfileStartupTest` |
| AC-008-5 | Chế độ công khai | khởi động app | `/h2-console` không tồn tại (404); `/actuator` chỉ có `health` và health chỉ trả `status`; CSP và các header bảo mật của GĐ1–GĐ7 giữ nguyên | `PublicProfileStartupTest`, `SecurityConfigTest` |
| AC-008-6 | Profile dev, `h2` và cách chạy mặc định | khởi động app | hành vi như GĐ7: seed với mật khẩu dev, không giới hạn nào mới làm hỏng test hay luồng dev | test hiện có |

**Bảo mật khi mở ra internet**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-008-7 | Một email (có tài khoản hay không) | đăng nhập sai 5 lần trong 15 phút | lần thứ 6 trở đi trong 15 phút **không** được kiểm mật khẩu, kể cả mật khẩu đúng; trang đăng nhập hiện `Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.`; email có và không có tài khoản cho cùng phản hồi (câu hỏi 5) | `LoginThrottleTest`, `LoginFlowTest` |
| AC-008-8 | Một địa chỉ IP client (lấy từ header tunnel tin cậy) | đăng nhập sai 20 lần trong 15 phút với nhiều email | các lần tiếp theo từ IP đó bị chặn như AC-008-7 tới hết 15 phút | `LoginThrottleTest` |
| AC-008-9 | Email đang có 3 lần sai | đăng nhập đúng | đăng nhập thành công, bộ đếm sai của email đó về 0 | `LoginThrottleTest` |
| AC-008-10 | Giới hạn đăng nhập sai | chạy ở mọi profile (dev, `h2`, công khai) | luôn bật, cùng ngưỡng, để hành vi trên máy giống khi công khai và test được ở mọi nơi | `LoginThrottleTest` |
| AC-008-11 | Chế độ công khai, request qua tunnel HTTPS (`X-Forwarded-Proto: https`, `X-Forwarded-Host` là domain tunnel) | đăng nhập rồi bị chuyển hướng | cookie phiên có `Secure`, `HttpOnly`, `SameSite=Lax`; URL chuyển hướng dùng `https://` và đúng domain tunnel (không phải `localhost`); trang và form chạy bình thường qua tunnel | `ForwardedHeadersTest` + checklist |
| AC-008-12 | Chế độ công khai, request đi thẳng tới `http://localhost:8080` trên máy chủ repo | đăng nhập | vẫn dùng được (để chủ repo tự kiểm trên máy); header `X-Forwarded-*` từ client thường không làm app tin sai địa chỉ IP dùng để giới hạn đăng nhập | `ForwardedHeadersTest` |
| AC-008-13 | Hai transaction cùng chờ một dòng bị khóa (`users`, `orders`, `categories`, `products`) | transaction thứ hai chờ quá thời hạn khóa (câu hỏi 6) | transaction thứ hai rollback toàn bộ, không đổi dữ liệu; người dùng thấy trang lỗi 500 của GĐ7; đúng một dòng log `ERROR` không có dữ liệu cá nhân; transaction thứ nhất hoàn tất bình thường | `LockTimeoutIntegrationTest` (PostgreSQL) |

**Chạy không Docker**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-008-14 | PostgreSQL 17 cài trực tiếp trên Windows, đã tạo DB và user theo README; Docker **không** chạy | chạy app theo lệnh trong README (ví dụ profile `local` với biến môi trường URL, user, mật khẩu DB) | app khởi động, Flyway tạo schema, dữ liệu mẫu được tạo, không cố bật Docker Compose; dữ liệu còn nguyên sau khi tắt và chạy lại app | `LocalProfileTest` (cấu hình) + checklist |
| AC-008-15 | Thiếu biến môi trường kết nối DB ở cách chạy không Docker | khởi động | app dừng với log nêu tên biến còn thiếu, không âm thầm dùng H2 hay Docker | `LocalProfileTest` |
| AC-008-16 | Chế độ công khai | kết hợp với DB chạy bằng Docker (`compose.yaml`) **hoặc** PostgreSQL cài trên máy | đều chạy được; README ghi cả hai lệnh | checklist |

**Tunnel và trải nghiệm người được gửi link**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-008-17 | App đang chạy ở chế độ công khai trên máy, chủ repo chạy lệnh tunnel trong README | mở URL tunnel từ một thiết bị khác (điện thoại dùng 4G) | trang chủ hiện qua HTTPS; đi được luồng chính: xem, tìm, lọc → thêm giỏ khi chưa đăng nhập → đăng nhập tài khoản demo `USER` (giỏ được gộp) → đặt hàng COD → "Đơn hàng của tôi" → hủy đơn; ảnh sản phẩm và ảnh upload hiện; trang lỗi 404/403 hiện đúng; không lỗi CSP trong console | checklist |
| AC-008-18 | Chủ repo tắt app hoặc tunnel (`Ctrl+C`) | người khác mở lại URL | không truy cập được nữa (lỗi của dịch vụ tunnel); không có gì của app còn mở ra internet | checklist |
| AC-008-19 | Người dùng mở form đăng ký hoặc trang thanh toán | xem trang | có dòng `Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.` (mọi profile) (câu hỏi 7) | `AuthControllerTest`, `CheckoutControllerTest` |

**Tài liệu**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-008-20 | README | đọc mục `Chia sẻ app đang chạy trên máy` | có: cài công cụ tunnel trên Windows; đặt biến môi trường mật khẩu demo (Git Bash và PowerShell); lệnh chạy app ở chế độ công khai; lệnh mở tunnel và cách lấy URL; tài khoản nào gửi cho người khác (chỉ `USER`, câu hỏi 3); cách dừng; cảnh báo rõ: máy phải bật, URL đổi mỗi lần chạy, ai có link đều vào được, dữ liệu người khác nhập nằm trong DB trên máy bạn, chạy lại chế độ dev thì mật khẩu demo là mật khẩu đã đặt ở chế độ công khai | checklist |
| AC-008-21 | README | đọc mục `Chạy với PostgreSQL cài trên máy (không Docker)` | có: cài PostgreSQL 17 cho Windows, tạo DB và user, biến môi trường cần đặt, lệnh chạy, và lưu ý test Testcontainers bị bỏ qua khi không có Docker (CI vẫn chạy đủ) | checklist |
| AC-008-22 | Một người làm theo README trên máy Windows có JDK 25 và PostgreSQL đã cài, **không** Docker | chạy app không Docker rồi mở ở chế độ công khai qua tunnel | làm được trong khoảng 10 phút (không tính thời gian tải công cụ lần đầu), không phải đoán bước nào | checklist (`phase-verifier`, với phần tunnel do chủ repo xác nhận) |
| AC-008-23 | `docs/project-brief.md` | đọc §9, §11 | ghi đúng quyết định mới: GĐ8 = chia sẻ app từ máy cá nhân qua tunnel, không PaaS, không CD; PaaS/Docker image là hướng phát triển sau | checklist |
| AC-008-24 | Toàn bộ test GĐ1–GĐ7 | `./mvnw verify` sau GĐ8 (Docker bật) | vẫn xanh, 0 bỏ qua | CI |

## 5. Quy tắc nghiệp vụ

- **BR-008-1 (bí mật):** mật khẩu tài khoản demo ở chế độ công khai chỉ đến từ biến môi trường, không có giá trị mặc định, không bao giờ ghi vào log hay repo. Mật khẩu DB khi chạy không Docker cũng lấy từ biến môi trường.
- **BR-008-2 (mật khẩu demo ở chế độ công khai):** bắt buộc khác mật khẩu mặc định của dev, dài ít nhất 12 ký tự (vẫn theo quy tắc mật khẩu của spec 003), và **mật khẩu admin phải khác mật khẩu user** (nếu trùng, người được gửi mật khẩu `USER` sẽ đăng nhập được cả admin); khi khởi động, mật khẩu của **đúng** `admin@minishop.local` và `user@minishop.local` được đặt lại theo biến môi trường (câu hỏi 4). Không đụng tài khoản nào khác.
- **BR-008-3 (giới hạn đăng nhập sai):** đếm theo email đã chuẩn hóa (`EmailNormalizer`) và theo IP client. Ngưỡng 5 lần sai/email/15 phút và 20 lần sai/IP/15 phút; bị chặn thì không kiểm mật khẩu tới hết cửa sổ 15 phút; đăng nhập đúng xóa bộ đếm của email đó; phản hồi giống nhau cho email có và không có tài khoản. Bộ đếm nằm trong bộ nhớ, tắt app là về 0 (một máy, một instance).
- **BR-008-4 (IP client):** IP dùng để đếm lấy từ header của tunnel **chỉ khi** request đến từ tunnel chạy trên chính máy đó (kết nối từ `localhost`); request khác dùng địa chỉ kết nối thật, bỏ qua `X-Forwarded-*` do client tự gửi.
- **BR-008-5 (thời hạn khóa):** mọi câu khóa dòng và câu `UPDATE` có điều kiện trên `products` chờ khóa tối đa 5 giây; quá hạn thì cả transaction rollback, không thử lại tự động. Thứ tự khóa của ADR-0009/0010 không đổi.
- **BR-008-6 (dữ liệu cá nhân):** bản chia sẻ chỉ để thử; giao diện nhắc không nhập dữ liệu thật (AC-008-19); footer "Dự án học tập, không bán hàng thật." giữ nguyên. Dữ liệu người khác nhập nằm trong DB trên máy chủ repo; README hướng dẫn cách xóa.

## 6. Giao diện và luồng

Không có URL mới. Thay đổi giao diện: thông báo khi bị chặn đăng nhập, dòng nhắc trên form đăng ký và trang thanh toán.

**Chữ hiển thị chính xác:**

| Vị trí | Nội dung |
|---|---|
| Trang đăng nhập khi bị chặn | `Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.` |
| Form đăng ký, trang thanh toán | `Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.` |
| README | mục `Chia sẻ app đang chạy trên máy`, `Chạy với PostgreSQL cài trên máy (không Docker)` |

**Luồng của chủ repo** (Claude chỉ viết hướng dẫn, không làm thay):
1. (Một lần) cài công cụ tunnel; nếu muốn chạy không Docker thì cài PostgreSQL 17 và tạo DB.
2. Đặt biến môi trường mật khẩu demo (và kết nối DB nếu không Docker).
3. Chạy app ở chế độ công khai; chạy lệnh tunnel; copy URL HTTPS gửi cho người khác cùng tài khoản demo `USER`.
4. Xong thì `Ctrl+C` cả tunnel và app.

## 7. Dữ liệu

- **Không có migration, không đổi bảng hay cột.**
- Ảnh upload vẫn ở `uploads/` trên máy (ổ đĩa thật, không mất khi tắt app); không đổi `StorageService`.
- Bộ đếm đăng nhập sai giữ trong bộ nhớ, không có bảng.
- Chế độ công khai dùng chung DB với dev trên máy (Docker hoặc PostgreSQL cài trên máy); việc đặt lại mật khẩu demo (BR-008-2) là thay đổi dữ liệu duy nhất do GĐ8 gây ra.

## 8. Yêu cầu phi chức năng

- **Bảo mật:**
  - chỉ cổng 8080 của app được tunnel; H2 console, DevTools LiveReload, cổng PostgreSQL không bao giờ ra internet;
  - app tin `X-Forwarded-*` chỉ khi kết nối đến từ chính máy (tunnel chạy local), theo BR-008-4;
  - CSP và header bảo mật của GĐ1–GĐ7 giữ nguyên qua tunnel;
  - README cảnh báo: chỉ gửi link cho người tin cậy, tắt khi không dùng.
- **Kiến trúc:** giới hạn đăng nhập nằm trong `user`/`config` (tầng bảo mật), không lẫn vào nghiệp vụ; không đổi chiều phụ thuộc; `ArchitectureTest` cập nhật nếu cần. Quyết định "chia sẻ từ máy cá nhân qua tunnel thay vì PaaS" và các thiết lập bảo mật của chế độ công khai được ghi trong ADR mới (ADR-0012) trước khi code.
- **Hiệu năng:** đi qua tunnel sẽ chậm hơn chạy local một chút; không tối ưu riêng (header cache để sau).
- **Chi phí:** 0 đồng, không cần thẻ.
- **Thông tin dịch vụ tunnel thay đổi theo thời gian:** tra cứu ngày 2026-10-25 (nguồn ở mục 9); kế hoạch kiểm lại trước khi viết README.

## 9. Câu hỏi mở

> **Đã trả lời (2026-10-25):** người dùng duyệt cả 9 câu theo khuyến nghị.

Nguồn tra cứu (2026-10-25): [Cloudflare docs: Quick Tunnels (TryCloudflare)](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/index.md), [Hướng dẫn Cloudflare Quick Tunnels (flaviocopes)](https://flaviocopes.com/cloudflare-tunnel/), [ngrok: giới hạn gói Free](https://ngrok.com/docs/pricing-limits/free-plan-limits), [ngrok: pricing limits](https://ngrok.com/docs/pricing-limits).

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Dùng dịch vụ tunnel nào? | **Cloudflare Quick Tunnel** (`cloudflared tunnel --url http://localhost:8080`): **không cần tài khoản**, miễn phí, HTTPS sẵn, cài bằng `winget`. Đánh đổi: URL ngẫu nhiên `*.trycloudflare.com` đổi mỗi lần chạy, Cloudflare ghi rõ chỉ dành cho thử nghiệm/phát triển, không cam kết uptime, giới hạn khoảng 200 request đồng thời; đủ cho việc gửi link cho vài người. Phương án khác: **ngrok Free** cho một domain dev cố định nhưng **cần tài khoản**, giới hạn khoảng 1 GB/tháng và 20.000 request; **Cloudflare Tunnel có tên** cho URL cố định nhưng cần tài khoản và một tên miền riêng. |
| 2 | Có làm cách chạy với PostgreSQL cài trên Windows (không Docker) trong GĐ8 không? | **Có** (AC-008-14..16): bạn đã muốn bỏ Docker khi làm việc hằng ngày; thay đổi nhỏ (một profile + biến môi trường) và dữ liệu nằm trên máy. Cách chạy với Docker vẫn giữ. |
| 3 | Gửi tài khoản nào cho người khác? | **Chỉ tài khoản `USER`.** Tài khoản `ADMIN` giữ cho riêng bạn: admin sửa được sản phẩm, ảnh, đơn của mọi người. Muốn cho mentor xem trang quản trị thì bạn chia sẻ màn hình hoặc dùng ảnh chụp trong README. |
| 4 | DB trên máy đã có tài khoản demo với mật khẩu dev (`Admin@123`). Mở ra internet thì xử lý thế nào? | **Mỗi lần khởi động chế độ công khai, đặt lại mật khẩu của đúng hai tài khoản demo theo biến môi trường** (BR-008-2, AC-008-3). Không phải xóa DB hay sửa tay, và không bao giờ có lúc app công khai mà admin vẫn dùng `Admin@123`. Đánh đổi: sau đó chạy lại chế độ dev thì đăng nhập bằng mật khẩu đã đặt (README ghi rõ). Phương án khác: app từ chối khởi động nếu admin còn mật khẩu dev, bạn phải tự xóa DB để seed lại. |
| 5 | Giới hạn đăng nhập sai thế nào? | **5 lần sai/email/15 phút và 20 lần sai/IP/15 phút, lưu trong bộ nhớ, bật ở mọi profile** (BR-008-3, AC-008-10). Không khóa vĩnh viễn (kẻ xấu có thể cố ý khóa tài khoản người khác), không CAPTCHA. |
| 6 | Thời hạn chờ khóa? | **5 giây; quá hạn hiện trang 500 chung của GĐ7** (AC-008-13). Đây là việc đã hẹn "trước khi deploy" ở ADR-0008/0009/0010; ở quy mô demo, chờ khóa quá 5 giây gần như chỉ xảy ra khi có lỗi. |
| 7 | Có dòng nhắc "đây là bản demo" trên form đăng ký và thanh toán không? | **Có** (AC-008-19): ai có link đều vào được, có thể nhập dữ liệu thật; một dòng nhắc giảm rủi ro về dữ liệu cá nhân (brief §7). Hiện ở mọi profile cho đơn giản. |
| 8 | Có viết script một lệnh (bật app công khai + tunnel cùng lúc) không? | **Không**: README ghi hai lệnh riêng, chạy ở hai cửa sổ terminal. Script phải xử lý hai tiến trình, Windows và Git Bash khác nhau, dễ để lại tiến trình chạy ngầm; hai lệnh rõ ràng dễ hiểu và dễ dừng hơn. |
| 9 | Có giữ hướng "deploy lên PaaS" (Render + Neon, đã tra ở bản nháp trước) trong tài liệu không? | **Có, ở mục "Hướng phát triển" của README và brief** (một đoạn ngắn: PaaS miễn phí ngủ khi không dùng, Postgres miễn phí của Render hết hạn sau 30 ngày, nên dùng Neon; ổ đĩa của PaaS bị xóa nên phải đổi `StorageService`). Không làm trong GĐ8. |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-25 | Tạo bản nháp (deploy lên PaaS Render + Neon, CD từ `main`) |
| 2026-10-25 | Viết lại theo quyết định của người dùng: chỉ cần truy cập online khi chạy trên máy cá nhân → chế độ công khai + tunnel, chạy PostgreSQL không Docker; bỏ Dockerfile, PaaS, CD, DB và lưu ảnh đám mây. Vẫn `Nháp` |
| 2026-10-25 | Người dùng duyệt spec và 9 câu hỏi mở theo khuyến nghị; trạng thái `Đã duyệt` |
| 2026-10-25 | Người dùng duyệt kế hoạch [phase-8](../plans/phase-8.md): BR-008-2 và AC-008-2 thêm "mật khẩu admin phải khác mật khẩu user" (câu hỏi 1 của kế hoạch); README khuyến nghị PostgreSQL 17, người dùng thử bằng bản 18 đang có (câu hỏi 2); trạng thái `Đang làm` |
| 2026-10-25 | Sau review: giới hạn đăng nhập đếm mỗi lần thử ngay tại cổng, trước khi kiểm mật khẩu (request song song không vượt được ngưỡng); không tin `X-Forwarded-Host`/`-Port`; `/actuator/health` chỉ trả `{"status":"UP"}`. `phase-verifier` đạt mọi mục kiểm được trên máy (tunnel được giả lập bằng header), `./mvnw verify` 2174 test, 0 bỏ qua. Còn chờ chủ repo chạy tunnel thật: AC-008-17, AC-008-18 và phần tunnel của AC-008-22 (bước U2–U4 của kế hoạch). Vẫn `Đang làm` |
| 2026-10-25 | Người dùng nghiệm thu bằng việc merge PR #15. Các bước tunnel thật (U2–U4: AC-008-17, AC-008-18, phần tunnel của AC-008-22) do chủ repo tự chạy; kết quả không được ghi lại trong phiên làm việc. Trạng thái `Hoàn thành` |

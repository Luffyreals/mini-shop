# ADR-0013: Một database duy nhất: H2 nhúng lưu ra file trong `data/`, bỏ PostgreSQL, Docker và Testcontainers

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-25
- **Thay thế:** ADR-0004; phần database của ADR-0012

## Bối cảnh

Sau GĐ8, dự án được xác định lại là **dự án học tập tập trung vào UI/UX**, và repo sẽ chuyển sang **public** để ai cũng clone về chạy thử được. Cách chạy lúc đó cần một trong ba thứ:
- Docker Desktop (profile mặc định, `compose.yaml` bật PostgreSQL);
- PostgreSQL cài trên máy (profile `local` với `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`);
- profile `h2`, nhưng là H2 trong bộ nhớ nên tắt app là mất dữ liệu, và không được dùng với `public`.

Người mới clone repo thường vấp ở bước cài Docker hoặc PostgreSQL trước khi thấy được giao diện. 23 test tích hợp cần Docker (Testcontainers) và bị bỏ qua trên máy không có Docker. Người dùng muốn: chạy được trên máy chỉ với JDK, **dữ liệu lưu trên ổ cứng** (tắt app mở lại vẫn còn), chia sẻ qua Cloudflare Tunnel từ máy đang bật như ADR-0012.

## Quyết định

**1. H2 nhúng, chế độ file, là database duy nhất ở mọi chế độ chạy.** `application.yml` đặt:

```
jdbc:h2:file:${DATA_DIR:./data}/minishop;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
```

File là `data/minishop.mv.db`, tương đối với thư mục chạy app. Chế độ tương thích PostgreSQL giữ nguyên V1/V2 (migration đã commit không bao giờ sửa) và mọi truy vấn. Flyway vẫn quản lý schema, Hibernate vẫn `validate`. Toàn bộ nghiệp vụ, khóa dòng (`PESSIMISTIC_WRITE` thành `SELECT … FOR UPDATE` trên H2) và thứ tự khóa của ADR-0008/0009/0010 không đổi.

**2. Ảnh upload nằm cạnh database:** `app.storage.local.upload-dir` mặc định là `${DATA_DIR:./data}/uploads`. Mọi dữ liệu sinh ra khi chạy ở trong một thư mục `data/` (đã `.gitignore`), nên xóa `data/` là làm lại từ đầu: lần chạy sau Flyway tạo bảng và `DataInitializer` seed lại.

**3. Bỏ hẳn PostgreSQL và Docker:** xóa `compose.yaml`, `application-local.yml`, `application-h2.yml`; xóa dependency `postgresql`, `flyway-database-postgresql`, `spring-boot-docker-compose`, Testcontainers. `StartupRequirements` chỉ còn kiểm mật khẩu demo của `public`. Profile còn lại:

| Profile | Database | Công khai |
|---|---|---|
| (mặc định) | `data/minishop.mv.db` | không, H2 console mở tại `/h2-console` cho máy này |
| `public` | `data/minishop.mv.db` (cùng file) | có, H2 console tắt; các bảo vệ của ADR-0012 giữ nguyên |

**4. H2 console bật ở chế độ dev** (`spring.h2.console.enabled: true`), để người học xem bảng trực tiếp. Console tự từ chối máy khác (Boot mặc định `web-allow-others=false`). Chuỗi filter riêng của console đổi từ `@Profile("h2")` sang `@ConditionalOnBooleanProperty("spring.h2.console.enabled")`. `application-public.yml` tắt console, nên ở `public` không có servlet lẫn chuỗi filter.

**5. Test chạy trên H2 trong bộ nhớ**, không bao giờ đụng `data/`: `src/test/resources/config/application.yml` đặt `jdbc:h2:mem:test-${random.uuid};…`. `${random.uuid}` được tính một lần cho mỗi Spring context, nên mỗi context được cache có database riêng (như mỗi context từng có container PostgreSQL riêng). Các test khởi động app thật (`StandaloneApp`) dùng một file H2 riêng trong `target/standalone-db/`, nên kiểm được cả việc tắt app rồi mở lại trên cùng dữ liệu. CI chỉ cần JDK; không còn test nào bị bỏ qua vì thiếu Docker.

## Hệ quả

- **Tích cực:**
  - Clone xong chạy `./mvnw spring-boot:run` là có app với dữ liệu mẫu; không cần cài Docker, PostgreSQL hay đặt biến môi trường.
  - Dữ liệu (đơn hàng, tài khoản đăng ký, ảnh upload) còn sau khi tắt app; sao lưu hay xóa chỉ là chép hay xóa thư mục `data/`.
  - Mọi test chạy trên mọi máy, nhanh hơn vì không khởi động container.
  - Repo gọn hơn: ít profile, ít file cấu hình, ít điều kiện khởi động.
- **Tiêu cực / đánh đổi:**
  - Test không còn chứng minh hành vi trên PostgreSQL thật (khóa `FOR NO KEY UPDATE`, `lock_timeout`, collation). Hành vi đồng thời giờ là của H2 (MVStore, khóa dòng); với một app học tập chạy trên một máy thì đủ.
  - H2 file chỉ mở được bởi **một tiến trình** tại một thời điểm: không chạy hai bản app trên cùng `data/`. H2 console mở trong cùng tiến trình nên vẫn dùng được.
  - Bản H2 lớn mới có thể đổi định dạng file; khi nâng H2 mà không mở được file cũ, xóa `data/` để seed lại (dữ liệu demo, không có gì quý).
  - Thông báo lỗi của H2 (hết thời gian chờ khóa, vi phạm `UNIQUE`) chứa nguyên dòng dữ liệu, kể cả email và hash mật khẩu. pgjdbc `logServerErrorDetail=false` không còn tác dụng nên đã bỏ, thay bằng hai việc: tắt logger `org.hibernate.orm.jdbc.error` (Hibernate 7 ghi lại thông báo đó ở mức `WARN`), và `UnhandledExceptionResolver` ghi lỗi có `SQLException` trong chuỗi nguyên nhân bằng tên lớp và stack frame, bỏ mọi message. Log 500 vì thế ít chi tiết hơn (không còn câu SQL), nhưng không bao giờ chứa dữ liệu dòng.
  - Quay lại PostgreSQL sau này cần: driver, URL, Testcontainers cho test; V1/V2 vẫn chạy được vì viết cho cả hai.

## Phương án đã cân nhắc

- **H2 trong bộ nhớ (profile `h2` cũ làm mặc định):** đơn giản nhất nhưng tắt app là mất dữ liệu; người dùng muốn lưu trên ổ cứng.
- **Lưu bằng Java collections thuần, bỏ JPA:** viết lại mọi repository, mất transaction và khóa dòng đã làm ở GĐ4–GĐ6.
- **Giữ PostgreSQL làm tùy chọn bên cạnh H2:** thêm profile, cấu hình và test chỉ chạy khi có Docker, trong khi mục tiêu là repo dễ chạy; người dùng chọn bỏ hẳn.
- **SQLite:** Hibernate cần dialect cộng đồng, Flyway hỗ trợ hạn chế, và V1/V2 phải viết lại.

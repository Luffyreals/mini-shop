# Kế hoạch GĐ8: Chia sẻ app đang chạy trên máy cá nhân (chế độ công khai qua tunnel)

- **Spec:** [008 Chia sẻ app từ máy cá nhân](../specs/008-deploy.md) (Đã duyệt 2026-10-25; người dùng chấp nhận cả 9 khuyến nghị ở §9: Cloudflare Quick Tunnel, có cách chạy PostgreSQL không Docker, chỉ gửi tài khoản `USER`, đặt lại mật khẩu demo mỗi lần khởi động chế độ công khai, 5 lần sai/email và 20 lần sai/IP trong 15 phút ở mọi profile, `lock_timeout` 5 giây, dòng nhắc "bản demo", hai lệnh riêng thay vì script, giữ hướng PaaS ở "Hướng phát triển").
- **Thay đổi phạm vi so với brief §9:** không PaaS, không Dockerfile, không CD, không DB/ảnh trên đám mây. App chỉ ra internet trong lúc chủ repo chạy nó trên máy mình.
- **Nhánh:** `phase-8/deploy` (đã checkout). Spec 008 và dòng mới trong `docs/specs/README.md` (đang chưa commit) được commit trên nhánh này.
- **Đã có từ GĐ1–7, không làm lại:**
  - `SeedProperties` (`@Validated`, `toString()` che mật khẩu), `DataInitializer` với hai điều kiện "bảng trống" độc lập;
  - `LoginFailureHandler` giữ email đã gõ qua `FlashMapManager`, `GET /login` mở theo đường dẫn (nên `/login?blocked` tự mở, không phải sửa quy tắc);
  - `user.EmailNormalizer` (một chỗ chuẩn hóa email), `user.PasswordPolicy` (quy tắc mật khẩu spec 003);
  - mọi câu khóa dòng đã có và đúng thứ tự (ADR-0008, 0009, 0010); lỗi không lường trước đã thành trang 500 + đúng một dòng `ERROR` (`UnhandledExceptionResolver`, ADR-0011);
  - CSP và header bảo mật (GĐ1), `management.endpoints.web.exposure.include: health`;
  - khuôn test trên Tomcat thật (`support.LiveServerTest`, `LiveClient`), khuôn "một connection khác giữ khóa" (`OrderIntegrationTest.awaitBackendWaitingOnLock`).
- **Không đổi** `pom.xml`, `.github/`, entity, schema. **Không có migration** (spec §7), nên **không cần** `schema-guardian`. Câu `UPDATE` mới trên `users.password_hash` là JPQL trên cột đã có, không đổi mapping. Nếu task nào thấy buộc phải đổi `@Column` hay thêm migration thì dừng lại và báo phiên chính.
- **Đã kiểm phiên bản thật** (đọc jar trong `~/.m2` bằng `unzip -p`/`javap`, và tài liệu chính thức; không đoán):
  - **Spring Boot 4.1.1:** interface `org.springframework.boot.EnvironmentPostProcessor` (khóa `spring.factories` mà chính Boot dùng; bản cũ `org.springframework.boot.env.EnvironmentPostProcessor` vẫn còn nhưng không dùng). `org.springframework.boot.diagnostics.AbstractFailureAnalyzer`, `FailureAnalysis` có sẵn; khóa `spring.factories` `org.springframework.boot.diagnostics.FailureAnalyzer`.
  - **DevTools đặt mặc định** (file `META-INF/spring-devtools.properties` trong từng jar): `spring-boot-h2console` đặt `spring.h2.console.enabled=true`; `spring-boot-web-server` đặt `server.servlet.session.persistent=true`; `spring-boot-thymeleaf` tắt cache. `H2ConsoleAutoConfiguration` chỉ cần lớp H2 trên classpath + thuộc tính bật, **không** cần datasource là H2. Hệ quả: **hôm nay**, khi chạy `./mvnw spring-boot:run` với PostgreSQL, servlet `/h2-console` vẫn được đăng ký (sau lớp đăng nhập). GĐ8 tắt nó ngoài profile `h2` (QĐ1).
  - **`spring-boot-tomcat` 4.1.1:** `server.tomcat.remoteip.internal-proxies` nhận danh sách CIDR hoặc regex; mặc định gồm cả dải LAN (`192.168.0.0/16, 172.16.0.0/12, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, ::1/128`…). `host-header` mặc định `X-Forwarded-Host`, `port-header` mặc định `X-Forwarded-Port`; `remote-ip-header`, `protocol-header` **không** có mặc định trong metadata, nên đặt tường minh. `server.servlet.session.cookie.secure/same-site/http-only` nằm trong `spring-boot-web-server`.
  - **Spring Security 7.1.1:** `AntPathRequestMatcher` đã bị bỏ; dùng `PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/login")`. `SavedRequestAwareAuthenticationSuccessHandler` còn nguyên.
  - Tomcat 11.0.24, Hibernate 7.4.5, HikariCP 7.0.2, pgjdbc 42.7.13, Flyway 12.4.0.
  - **Cloudflare (tra 2026-10-25):** Quick Tunnel `cloudflared tunnel --url http://localhost:8080`, không cần tài khoản, tối đa 200 request đang xử lý (vượt thì `429`), không hỗ trợ SSE, không cam kết uptime. `X-Forwarded-For`: Cloudflare **nối thêm** IP kết nối vào cuối giá trị client gửi (giá trị client nằm bên trái, IP thật luôn ở cuối); `X-Forwarded-Proto` bị Cloudflare **ghi đè**. `cloudflared` `httpHostHeader` mặc định rỗng (không ghi đè `Host`).
  - **winget:** gói `PostgreSQL.PostgreSQL.17` có thật (bản 17.11).
  - **Máy của chủ repo:** đã có `cloudflared 2026.10.1` (`C:\Program Files (x86)\cloudflared`) và **PostgreSQL 18** cài bằng bộ cài EDB (`C:\Program Files\PostgreSQL\18`, `listen_addresses = '*'`, cổng 5432). Xem câu hỏi 2.

## 1. Mục tiêu

- Chủ repo chạy `./mvnw spring-boot:run -Dspring-boot.run.profiles=public` (DB Docker) hoặc `local,public` (PostgreSQL cài trên máy), rồi `cloudflared tunnel --url http://127.0.0.1:8080`, và có một link HTTPS cho người khác dùng thử. Tắt hai lệnh đó là link chết.
- Ở chế độ công khai, app **không khởi động** nếu thiếu mật khẩu demo hoặc mật khẩu yếu/mặc định; mật khẩu của đúng hai tài khoản demo được đặt lại trước khi nhận request; không còn H2 console, LiveReload hay cổng nào khác ngoài 8080 trên loopback; cookie phiên `Secure` + `HttpOnly` + `SameSite=Lax`; chuyển hướng dùng `https://` và domain tunnel; IP client chỉ lấy từ header khi kết nối đến từ chính máy.
- Ở **mọi** profile: đăng nhập sai bị giới hạn (5/email, 20/IP trong 15 phút, lần bị chặn không kiểm mật khẩu); mọi câu chờ khóa dòng chờ tối đa 5 giây rồi rollback và hiện trang 500 của GĐ7.
- App chạy được với PostgreSQL cài trên Windows qua profile `local` (thiếu biến môi trường thì dừng, không bao giờ âm thầm dùng H2 hay Docker). README có hai mục mới, brief §9/§11 và ADR-0012 ghi quyết định. `./mvnw verify` xanh với 0 test bị bỏ qua (Docker bật); mọi AC-008-1 đến AC-008-24 có test hoặc mục checklist (mục 5).

## 2. Quyết định thiết kế

**QĐ1. Hai profile mới, xếp chồng được: `public` (mở ra internet) và `local` (PostgreSQL cài trên máy). Chọn DB và chọn "có công khai hay không" là hai việc độc lập.**

| Lệnh (`-Dspring-boot.run.profiles=…`) | DB | Công khai |
|---|---|---|
| (không có) | Docker Compose tự bật | không (dev như GĐ7) |
| `h2` | H2 trong bộ nhớ, có `/h2-console` | không |
| `local` | PostgreSQL cài trên máy (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) | không |
| `public` | Docker Compose | **có** |
| `local,public` | PostgreSQL cài trên máy | **có** |
| `h2,public`, `h2,local` | **bị từ chối khi khởi động** (QĐ2) | – |

- **Vì sao xếp chồng:** mỗi file `application-<profile>.yml` lo đúng một việc. Có 2 cách chọn DB × 2 trạng thái công khai, nên nếu gộp thì phải có 4 profile và lặp cấu hình.
- **`public` chặn `h2`:** H2 trong bộ nhớ mất dữ liệu khi tắt app, và profile `h2` bật H2 console cùng một chuỗi filter **không có CSRF** (`SecurityConfig.h2ConsoleSecurityFilterChain`). Hai thứ đó không được ra internet.
- **Vẫn chạy bằng `spring-boot:run`, không chạy jar:** jar đóng gói bỏ cả DevTools lẫn `spring-boot-docker-compose`, nên với DB Docker người dùng sẽ phải tự khai báo URL. Thay vào đó, `application-public.yml` vô hiệu hóa những gì DevTools bật:
  - `spring.devtools.livereload.enabled: false` (cổng 35729 không mở);
  - `spring.devtools.restart.enabled: false` (không tự khởi động lại khi đang có người dùng);
  - `server.servlet.session.persistent: false` (tắt app là mọi phiên mất; DevTools mặc định ghi phiên ra đĩa);
  - `spring.h2.console.enabled: false`.
- **Phát hiện khi kiểm DevTools:** DevTools bật H2 console ở **mọi** profile (xem phần "Đã kiểm phiên bản thật"). GĐ8 đặt `spring.h2.console.enabled: false` trong `application.yml`; `application-h2.yml` đã đặt `true` nên profile `h2` không đổi. Giá trị trong file cấu hình thắng mặc định của DevTools, vì DevTools thêm nguồn thuộc tính của nó vào cuối danh sách (cơ chế đã dùng ở GĐ7 QĐ1).
- **`server.address: 127.0.0.1` ở chế độ công khai:** Tomcat chỉ nghe trên loopback. `cloudflared` và trình duyệt trên máy vẫn vào được; máy khác trong cùng Wi-Fi (quán cà phê, công ty) không mở được `http://<IP-máy>:8080` bằng HTTP trần, và Windows Firewall không hỏi "cho phép truy cập mạng". Lệnh tunnel dùng `http://127.0.0.1:8080` để `cloudflared` không thử `::1` trước.

*Phương án bị loại:*
- **Một profile `prod` gộp mọi thứ:** trộn việc chọn DB với việc mở ra internet.
- **Cờ biến môi trường `APP_PUBLIC=true`:** profile là cách gom cấu hình chuẩn của Spring và hiện ngay ở dòng log `The following profiles are active`.
- **Profile group (`spring.profiles.group`):** thêm tên phải nhớ, không thêm khả năng nào.

**QĐ2. Kiểm điều kiện khởi động bằng một `EnvironmentPostProcessor` (`config.StartupRequirements`), chạy trước khi Spring tạo bất kỳ bean nào.**
- **Thời điểm chạy:** sau khi Boot đọc xong file cấu hình và biết profile nào đang bật, nhưng **trước** Docker Compose, trước kết nối DB và trước Flyway. Lỗi cấu hình hiện ra ngay, chỉ trong vài trăm mili giây, và không để lại container nào.
- **Đăng ký:** trong `src/main/resources/META-INF/spring.factories`, khóa `org.springframework.boot.EnvironmentPostProcessor`. Lớp này **không** phải `@Component`, vì nó chạy trước khi có context.
- **Thứ tự kiểm** (dừng ở lỗi đầu tiên):
  1. `h2` cùng `public`, hoặc `h2` cùng `local` → từ chối.
  2. `local`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` phải có và không trống; `DB_URL` phải bắt đầu bằng `jdbc:postgresql:`. Nếu không chặn ở đây, `spring.datasource.url` trống sẽ khiến Boot **tự dùng H2 nhúng**, đúng điều AC-008-15 cấm.
  3. `public`: `SEED_ADMIN_PASSWORD`, `SEED_USER_PASSWORD` phải có và không trống. Mỗi giá trị phải:
     - dài ít nhất 12 ký tự (đếm theo code point);
     - đạt `user.PasswordPolicy` (8–64 ký tự, có chữ và số, ≤ 72 byte);
     - khác `Admin@123` và `User@123`.

     Nếu câu hỏi 1 được duyệt thì hai giá trị còn phải **khác nhau**.
- **Thông điệp** (tiếng Anh, chỉ nêu **tên biến**, không bao giờ in giá trị) nằm ở mục 3. Lớp ném `config.StartupRequirementException`. `config.StartupRequirementFailureAnalyzer` biến nó thành khối `APPLICATION FAILED TO START` quen thuộc của Boot, gồm `Description` (thông điệp) và `Action` (cần đặt biến gì, xem mục nào của README). Vì analyzer có thể chưa chạy ở giai đoạn này, B1 phải xác nhận bằng một lần chạy thật; nếu analyzer không hiện thì ít nhất dòng `Application run failed` vẫn mang thông điệp, và thế vẫn đạt AC.
- **Đọc biến:** dùng `environment.getProperty("SEED_ADMIN_PASSWORD")`. Biến môi trường thật, tham số `--…` và thuộc tính của test (`@SpringBootTest(properties = …)`, `SpringApplicationBuilder.properties(…)`) đều đi qua đây, nên test không phải đặt biến môi trường thật của tiến trình.
- `application-public.yml` đặt `app.seed.admin-password: ${SEED_ADMIN_PASSWORD}` (không có giá trị mặc định), nên giá trị được bind vào `SeedProperties` đúng là giá trị đã được kiểm (BR-008-1).

*Phương án bị loại:*
- **`@Validated` + `@Size(min = 12)` trên `SeedProperties`:** khi validation hỏng, failure analyzer của Boot in `Value: "<giá trị bị từ chối>"`, tức là in mật khẩu ra log. Ngoài ra nó không nêu được tên biến môi trường.
- **Bean `@Value("${DB_URL}")` hoặc `ApplicationRunner`:** không chắc chạy trước DataSource. Các controller trong `admin` (quét trước `config` theo thứ tự chữ cái) kéo theo repository → `EntityManagerFactory` → Flyway, nên DB đã được kết nối trước. `ApplicationRunner` còn chạy **sau** khi Tomcat đã nhận request.
- **Lưu ý cho người học:** Boot **bỏ qua** placeholder không giải được khi bind `@ConfigurationProperties`, nên `${SEED_ADMIN_PASSWORD}` thiếu biến sẽ được bind thành đúng chuỗi `"${SEED_ADMIN_PASSWORD}"` (21 ký tự). Đây là lý do không được dựa vào "thiếu biến thì Spring tự báo lỗi".

**QĐ3. Đặt lại mật khẩu demo ở `config.PublicModeDemoPasswords` (`@Profile("public")`, `SmartInitializingSingleton`), gọi `user.DemoAccountService.resetDemoPasswords` (một transaction, `@Modifying` JPQL theo email).**
- **Vì sao `SmartInitializingSingleton`:** `afterSingletonsInstantiated()` chạy khi mọi bean đã tạo xong (Flyway đã migrate, transaction manager sẵn sàng), nhưng **trước** `finishRefresh`. Boot tạo Tomcat sớm nhưng gỡ connector ra, và chỉ gắn lại trong `WebServerStartStopLifecycle.start()` lúc `finishRefresh`.
  - Vì vậy không có khoảnh khắc nào app nhận request mà admin vẫn dùng `Admin@123` (AC-008-3: "trước khi app nhận request").
  - `ApplicationRunner` (như `DataInitializer`) chạy **sau** khi Tomcat đã mở cổng.
- **Câu ghi:** `UserRepository.updatePasswordHash(email, hash)`, dạng `update User u set u.passwordHash = :hash where u.email = :email`. Nó được gọi đúng hai lần, với `DemoAccountService.ADMIN_EMAIL` và `USER_EMAIL`, nên không tài khoản nào khác bị đụng (BR-008-2). Hash là `{bcrypt}…` từ `PasswordEncoder` hiện có. `User` không có setter mật khẩu, và không cần thêm.
- **Log:** đúng một dòng INFO, không có mật khẩu hay email:
  - `Public mode: demo passwords updated for {n} account(s)` khi `n > 0`;
  - `Public mode: demo accounts not found yet; they will be seeded with the configured passwords` khi `n = 0`.
- **Phối hợp với `DataInitializer` (AC-008-4):**
  - DB trống: lần đặt lại cập nhật 0 dòng; sau khi Tomcat lên, `DataInitializer` seed hai tài khoản bằng `SeedProperties`, tức là chính các biến môi trường.
  - DB đã có người dùng: `DataInitializer` bỏ qua phần user như cũ.

  `DataInitializer` chỉ đổi hai chuỗi email thành hằng của `DemoAccountService`, để việc seed và việc đặt lại không bao giờ lệch nhau.
- **Hệ quả (README phải ghi, AC-008-20):** sau khi chạy chế độ công khai trên DB dev, chạy lại dev thì mật khẩu demo là mật khẩu đã đặt ở chế độ công khai. Dev không bao giờ đặt lại.

*Phương án bị loại:*
- **Đặt lại trong `DataInitializer`:** chạy sau khi Tomcat đã nhận request.
- **Từ chối khởi động khi admin còn mật khẩu dev:** spec §9 câu 4 đã loại.
- **Thêm `User.changePassword()`:** phải nạp entity rồi lưu lại, trong khi một câu `UPDATE` theo email là đủ và rõ hơn.

**QĐ4. Giới hạn đăng nhập sai: một "cổng" là filter đứng trước `UsernamePasswordAuthenticationFilter`, bộ đếm `user.LoginThrottle` nằm trong bộ nhớ và dùng `Clock`; việc đếm đặt ở failure handler và success handler của form login.**
- **Vị trí trong chuỗi filter:** `config.LoginThrottleFilter` được thêm bằng `addFilterBefore(…, UsernamePasswordAuthenticationFilter.class)`, nên nó đứng **sau** `CsrfFilter` (request thiếu CSRF bị chặn trước) và **trước** bước kiểm mật khẩu.
  - Filter chỉ xét `POST /login`. Nếu email (đã chuẩn hóa) **hoặc** IP client đang bị chặn, nó gọi thẳng `LoginFailureHandler` với `LoginBlockedException` rồi dừng; `UsernamePasswordAuthenticationFilter` không bao giờ chạy.
  - Đó là cách bảo đảm "không kiểm mật khẩu, kể cả mật khẩu đúng" (AC-008-7). Unit test chứng minh được trực tiếp bằng `verifyNoInteractions(chain)`.
- **Ba vai trò tách riêng:**
  - `config.CountingLoginFailureHandler` (decorator): chỉ khi exception là `BadCredentialsException` (sai mật khẩu, và cả email không tồn tại vì Spring giấu `UsernameNotFoundException`) thì gọi `throttle.recordFailure(email, request.getRemoteAddr())`, rồi giao cho `LoginFailureHandler`;
  - `config.LoginFailureHandler` (đã có): giữ email đã gõ; `LoginBlockedException` → `/login?blocked`, còn lại → `/login?error` như cũ. Constructor không đổi, nên `LoginFailureHandlerTest` hiện có vẫn biên dịch;
  - `config.LoginSuccessHandler` (kế thừa `SavedRequestAwareAuthenticationSuccessHandler`, `defaultTargetUrl = "/"`, giống hệt `defaultSuccessUrl("/")` hiện nay): gọi `throttle.recordSuccess(authentication.getName())` rồi chuyển hướng như cũ (AC-008-9).

  Filter dùng `LoginFailureHandler` **không** qua bộ đếm, nên lần bị chặn không bao giờ được tính thêm.
- **Quy tắc đếm** (`LoginThrottle`), cửa sổ trượt:
  - mỗi khóa (email đã chuẩn hóa; IP) giữ tối đa N mốc thời gian của các lần sai gần nhất; khi ghi một lần sai, bỏ các mốc cũ hơn 15 phút;
  - đủ 5 mốc (email) hoặc 20 mốc (IP) thì khóa bị chặn tới `now + 15 phút` và các mốc bị xóa; hết hạn chặn thì đếm lại từ 0;
  - đăng nhập đúng xóa khóa **email** (không xóa khóa IP, theo BR-008-3);
  - email trống thì chỉ đếm IP; IP `null`/trống thì chỉ đếm email; khóa email cắt ở 255 code point.

  Cửa sổ trượt khớp đúng chữ "5 lần sai trong 15 phút"; cửa sổ cố định cho phép tới 8 lần sai liên tiếp ở chỗ giáp hai cửa sổ.
- **Bộ nhớ có giới hạn:** mỗi bảng (email, IP) là `LinkedHashMap` theo thứ tự truy cập, tối đa 10 000 khóa, bỏ khóa ít dùng nhất (LRU); mọi method là `synchronized`. Ở quy mô một máy, vài người dùng thử, khóa đơn giản là đủ. Mỗi khóa giữ tối đa 20 mốc `Instant`.
- **Không có khóa cấu hình** cho ngưỡng hay thời gian: chúng là hằng trong `LoginThrottle`. Vì vậy hành vi giống nhau ở mọi profile (AC-008-10) và không ai tắt nhầm được.
- **Vì sao `LoginThrottle` nằm trong `user`, không có annotation, và được khai báo `@Bean` trong `SecurityConfig`:**
  - nó cần `EmailNormalizer` (package-private trong `user`, nên giữ nguyên);
  - mọi slice test `@Import(SecurityConfig.class)` (13 lớp) sẽ tự có bean này; nếu là `@Component` thì `@WebMvcTest` không quét được và cả 13 lớp đỏ;
  - `Clock` truyền qua constructor giúp unit test "tua giờ" mà không phải chờ 15 phút.
- **Filter không phải bean:** nó được tạo bằng `new` trong `SecurityConfig`. Nếu là `@Component`, Boot sẽ tự đăng ký nó thêm một lần nữa như servlet filter thường, chạy ngoài Spring Security. `ArchitectureTest` thêm rule cấm `@Component` trên lớp hiện thực `jakarta.servlet.Filter`.
- **Log:** một dòng `WARN` lúc một khóa **bắt đầu** bị chặn (`Login throttling: an email reached 5 failed attempts in 15 minutes; blocked for 15 minutes`, bản IP tương tự). Không ghi email hay IP, và không log mỗi lần bị chặn (để chống làm ngập log).
- **Giới hạn đã biết (mục 6):**
  - vài request song song có thể cùng lọt qua cổng trước khi lần sai thứ 5 được ghi, nên số lần thử thực tế có thể nhỉnh hơn 5 một chút;
  - tắt app là bộ đếm về 0 (BR-008-3 chấp nhận);
  - 20 lần sai/IP có thể chặn nhầm nhiều người dùng chung một NAT.

*Phương án bị loại:*
- **Bọc `AuthenticationProvider`:** phải thay `DaoAuthenticationProvider` mà Boot tự dựng từ `UserDetailsService`. Spring Security sẽ cảnh báo khi có bean `AuthenticationProvider`, và nếu nối sai parent `AuthenticationManager` thì mật khẩu vẫn bị kiểm ở manager cha.
- **Nghe event `AuthenticationFailureBadCredentialsEvent`:** vẫn cần một filter làm cổng, và logic bị tách ra nhiều chỗ.
- **Bảng DB hoặc thư viện (Bucket4j, Caffeine):** spec §7 chọn bộ nhớ; thêm thư viện là thêm phụ thuộc cho một việc 100 dòng.

**QĐ5. Header của tunnel: `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`), chỉ tin proxy loopback; cookie `SameSite=Lax` ở mọi profile, `Secure` chỉ ở chế độ công khai.**
- **Cách `RemoteIpValve` làm việc:** valve đọc `X-Forwarded-For`/`-Proto`/`-Host` **chỉ khi** địa chỉ kết nối thuộc `internal-proxies`. Plan đặt `internal-proxies` = `127.0.0.0/8, ::1/128`, chỉ loopback, vì `cloudflared` chạy trên chính máy (BR-008-4). Kết nối từ nơi khác thì header bị bỏ qua hoàn toàn.
- **Đọc `X-Forwarded-For` từ phải sang trái:** valve lấy địa chỉ đầu tiên không phải proxy tin cậy. Cloudflare luôn nối IP thật vào **cuối**, nên giá trị giả mà client tự gửi (nằm bên trái) không bao giờ được chọn. `request.getRemoteAddr()` thành IP thật của người dùng; đó là IP mà `LoginThrottle` đếm (AC-008-8).
- **Proto và host:**
  - `X-Forwarded-Proto: https` làm `request.isSecure()` thành `true` và cổng thành 443;
  - host lấy từ `X-Forwarded-Host` nếu có, không thì từ `Host` (mà `cloudflared` giữ nguyên là domain tunnel);
  - Tomcat dựng `Location` tuyệt đối theo các giá trị đó, nên chuyển hướng sau đăng nhập là `https://<tên>.trycloudflare.com/` (AC-008-11).
- **Đặt ở `application.yml` (mọi profile):** trong dev không ai gửi header này nên không có gì thay đổi. Nhờ vậy `@LiveServerTest` (profile `h2`) kiểm được cơ chế mà không cần PostgreSQL.
- **Cookie:**
  - `same-site: lax` cho mọi profile (trình duyệt vốn đã mặc định như vậy; ghi rõ cho chắc). `http-only` đã là mặc định của Tomcat.
  - `secure: true` chỉ ở `public`. Nếu đặt ở mọi profile, `HttpClient` của Java (`CookieManager`) và `curl` sẽ không gửi lại cookie qua `http://`, làm hỏng các test hiện có.
  - Đặt **tường minh** thay vì để Tomcat tự gắn `Secure` khi `request.isSecure()`: nếu người nhận link gõ `http://…trycloudflare.com` thì `X-Forwarded-Proto` là `http`, và cookie phiên sẽ đi qua HTTP trần. Với `secure: true`, trình duyệt không lưu cookie đó và người dùng thấy đăng nhập không được (lỗi lộ ra, không âm thầm rò). README dặn chỉ gửi link `https://`.
  - Trình duyệt coi `http://localhost` là ngữ cảnh an toàn, nên chủ repo mở thẳng `http://localhost:8080` vẫn đăng nhập được (AC-008-12, verifier kiểm bằng trình duyệt).
- **HSTS:** Spring Security chỉ gửi `Strict-Transport-Security` khi request "secure", tức là chỉ qua tunnel HTTPS, áp cho subdomain `*.trycloudflare.com` ngẫu nhiên đó. Không ảnh hưởng `localhost`.
- **`compose.yaml`:** đổi `'5432'` thành `'127.0.0.1::5432'` (cổng ngẫu nhiên, chỉ trên loopback). Docker Desktop mặc định mở cổng trên mọi card mạng, nên PostgreSQL dev (mật khẩu `minishop`) đang lộ ra mạng LAN (spec §8: cổng PostgreSQL không bao giờ ra ngoài).

*Phương án bị loại:*
- **`forward-headers-strategy: framework` (`ForwardedHeaderFilter`):** tin header từ **mọi** kết nối, không có khái niệm proxy tin cậy.
- **Giữ `internal-proxies` mặc định:** tin cả máy trong LAN.
- **Header `CF-Connecting-IP`:** gắn chặt vào Cloudflare, và tài liệu không nói rõ giá trị client gửi có bị ghi đè hay không.
- **`server.tomcat.use-relative-redirects`:** sửa được `Location: /` nhưng không sửa được URL tuyệt đối mà `SavedRequest` dựng (vẫn cần valve).

**QĐ6. `lock_timeout` 5 giây đặt cho mọi connection bằng `spring.datasource.hikari.connection-init-sql`.**
- **PostgreSQL:** `SET lock_timeout = '5s'`. Lệnh chạy một lần cho mỗi connection vật lý mà Hikari tạo, nên áp cho **mọi** câu chờ khóa:
  - `SELECT … FOR NO KEY UPDATE`/`FOR UPDATE` của `lockOwner`, `lockById`, `lockByIdAndUserId`;
  - các câu `UPDATE` có điều kiện trên `products` (`decrementStock`, `incrementStock`, `adjustStock`), vốn không đi qua `@Lock`;
  - Flyway (dùng chung DataSource; lúc khởi động không có ai tranh khóa).

  Docker Compose (`@ServiceConnection`), Testcontainers và profile `local` đều đi qua cùng `HikariDataSource`, nên cùng nhận khóa này.
- **H2** (`application-h2.yml`): `SET LOCK_TIMEOUT 5000` (cú pháp của H2, đơn vị ms). Khóa trong `application.yml` không chạy được trên H2, nên `h2` phải ghi đè.
- **Đường đi của lỗi:**
  1. PostgreSQL trả SQLState `55P03`;
  2. Hibernate/Spring dịch thành `PessimisticLockingFailureException` (hoặc lớp con `CannotAcquireLockException`), unchecked, nên `@Transactional` rollback toàn bộ;
  3. không controller nào bắt nó (ArchUnit cấm bắt `Exception`/`RuntimeException` trong controller);
  4. `UnhandledExceptionResolver` ghi đúng một dòng `ERROR` (method + đường dẫn), và trang 500 của GĐ7 hiện ra;
  5. log không có dữ liệu cá nhân: thông điệp chỉ có câu SQL với `?` và `Where: while locking tuple (…) in relation "users"`; `logServerErrorDetail=false` đã bỏ phần `Detail` từ GĐ7.
- **Ngoại lệ có chủ đích:** `cart.CartMergeOnLogin` vốn nuốt mọi lỗi để không làm hỏng việc đăng nhập (GĐ4). Nếu việc gộp giỏ chờ khóa quá hạn, người dùng vẫn đăng nhập được, giỏ khách được giữ lại, và log có `WARN` như cũ.
- **Không thử lại tự động** (BR-008-5). Thứ tự khóa của ADR-0009/0010 không đổi.

*Phương án bị loại:*
- **Hint JPA `jakarta.persistence.lock.timeout` trên từng `@Lock`:** không áp cho các câu `UPDATE` có điều kiện trên `products` (BR-008-5 bắt buộc), và cách Hibernate dịch hint sang PostgreSQL tùy phiên bản dialect.
- **`SET LOCAL lock_timeout` trong từng service:** rải ở nhiều chỗ, thêm một đường khóa mới là dễ quên.
- **`ALTER ROLE … SET lock_timeout`:** cần migration hoặc lệnh SQL tay khác nhau cho Docker, Testcontainers và PostgreSQL cài trên máy.
- **Tham số pgjdbc `options=-c lock_timeout=5s`:** tương đương, nhưng nằm ẩn trong một thuộc tính driver; `connection-init-sql` đọc vào là hiểu.

**QĐ7. Profile `local` cho PostgreSQL cài trên Windows.**
- **`application-local.yml`:**
  - `spring.docker.compose.enabled: false`;
  - `spring.datasource.url/username/password: ${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}`.

  Thiếu biến, URL trống hoặc URL không phải PostgreSQL đều bị `StartupRequirements` chặn (QĐ2, AC-008-15).
- **Phần còn lại giữ như dev:** Flyway, `ddl-auto: validate`, seed khi DB trống. Dữ liệu nằm trong DB trên máy, tắt app không mất (AC-008-14).
- **README:** cài PostgreSQL 17 (`winget install -e --id PostgreSQL.PostgreSQL.17` hoặc bộ cài EDB), tạo user và DB bằng `psql` (đường dẫn đầy đủ tới `psql.exe`, vì bộ cài không thêm vào `PATH`), đặt biến môi trường cho Git Bash và PowerShell, rồi chạy. Thêm hai lưu ý:
  - giữ `pg_hba.conf` chỉ cho `127.0.0.1/32` và `::1/128`;
  - test Testcontainers bị bỏ qua khi không có Docker, nhưng CI vẫn chạy đủ.

**QĐ8. Giao diện: một fragment `fragments/layout :: demoNotice` dùng chung cho đăng ký và thanh toán; thông báo bị chặn dùng `fragments/notice`.**
- **`demoNotice`:** `<p class="demo-notice" th:fragment="demoNotice">Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.</p>`.
  - Chữ chỉ nằm ở một chỗ. Fragment hiện ở mọi profile (spec §9 câu 7).
  - Đặt ngay dưới `h1` của `user/register.html` và `order/checkout.html`.
  - Không dùng `role="status"`/`alert`: đây là chú thích tĩnh, không phải thông báo thay đổi.
- **`user/login.html`:** `th:if="${param.blocked != null}"` → `notice('error', 'Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.')`. Khối `param.error` giữ nguyên; hai tham số không bao giờ đi cùng nhau.
- **CSS:** `.demo-notice` trong `static/css/app.css`, theo token có sẵn của "The specimen catalogue" (chữ mờ hoặc viền trái), không vẽ lại, không thêm khối. CSP không đổi; `TemplateCspComplianceTest` quét các file đã sửa.

**QĐ9. Không migration, không `schema-guardian`** (spec §7). `UserRepository.updatePasswordHash` dùng cột `password_hash` hiện có; không đổi entity.

**QĐ10. Chiến lược test và quy tắc cách ly** (test-writer phải theo; vi phạm sẽ gây lỗi chập chờn):
- **Bộ đếm sống cùng Spring context.** Context được cache và dùng chung giữa các lớp test, nên:
  - **không** làm đăng nhập sai bằng `user@minishop.local`/`admin@minishop.local` quá 2 lần trong một context (`LoginFlowTest` hiện có 2 lần cho `user@`). Bị chặn 15 phút sẽ làm đỏ mọi test đăng nhập sau đó;
  - test mới cần đăng nhập sai dùng **email riêng** (ví dụ `throttle-<UUID>@minishop.test`, tài khoản tạo bằng JDBC + `PasswordEncoder` hoặc `UserService`, dọn ở `finally`) **và IP riêng**:
    - MockMvc: `.with(r -> { r.setRemoteAddr("198.51.100.<n>"); return r; })`;
    - `LiveClient`: header `X-Forwarded-For: 203.0.113.<n>` (kết nối từ loopback nên được tin).

    Không bao giờ làm quá 19 lần sai từ `127.0.0.1` trong cùng context.
- **`LoginThrottleTest` (unit) tua giờ bằng `Clock`** mutable tự viết trong test. Test tích hợp không bao giờ chờ 15 phút.
- **Cookie `Secure` qua HTTP:** `CookieManager` của Java không gửi lại cookie `Secure` tới `http://`. Trong test chế độ công khai, đọc `Set-Cookie` rồi tự gửi header `Cookie`.
- **DevTools tự tắt trong test** (phát hiện JUnit trên stack), nên mặc định `spring.h2.console.enabled=true` của nó **không** tái hiện được trong test. Test chỉ kiểm thuộc tính đã resolve và bean không tồn tại; phần "chạy bằng `spring-boot:run` thì `/h2-console` là 404" do `phase-verifier` kiểm.
- **Khởi động app nhiều lần với cấu hình khác nhau** (`PublicProfileStartupTest`, `LocalProfileTest`): dùng `new SpringApplicationBuilder(MiniShopApplication.class).profiles(…).properties(…).run()`, với container `PostgreSQLContainer` **static** riêng của lớp test (`@Testcontainers(disabledWithoutDocker = true)` + `@Container`), `server.port=0`, và `spring.docker.compose.enabled=false`. Mọi context đóng ở `finally`.
  - Các ca thất bại ở `StartupRequirements` không cần DB. Đặt `spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none` để chứng minh app dừng **trước** khi kết nối DB: nếu chưa dừng, lỗi sẽ là "connection refused" chứ không phải thông điệp của ta.
- **Chi phí CI dự kiến:** khoảng +1,5 phút (bốn ca chờ khóa 5 giây, 4–6 lần khởi động app trên PostgreSQL, một context `@LiveServerTest` thứ hai). CI có giới hạn 20 phút, vẫn đủ.

**QĐ11. Kiểm tra cuối giai đoạn: `phase-verifier` kiểm mọi thứ chạy được trên máy; phần tunnel thật do chủ repo tự chạy và xác nhận.**
- **Claude không bao giờ chạy `cloudflared tunnel`** (spec §2: việc Claude không làm thay người dùng). Verifier giả lập tunnel bằng `curl` gửi `X-Forwarded-*` từ localhost.
- **Không đụng volume DB dev của bạn:** mọi lần chạy chế độ công khai trên Docker của verifier (và của B2 khi tự kiểm) đặt `COMPOSE_PROJECT_NAME=minishop-gd8-verify`. `docker compose` (do `spring-boot-docker-compose` gọi) khi đó tạo volume riêng, xóa được bằng `docker compose -p minishop-gd8-verify down -v`. Nếu không làm vậy, việc đặt lại mật khẩu demo sẽ đổi mật khẩu dev của bạn (QĐ3).
- **"PostgreSQL cài trên máy" được giả lập** bằng một container PostgreSQL độc lập, mở trên `127.0.0.1:55432`. Lúc đó `compose.yaml` không được bật, và đó là điều AC-008-14 cần chứng minh về cấu hình. Phần "máy không có Docker, PostgreSQL thật" do chủ repo xác nhận theo README (câu hỏi 2), vì cần mật khẩu superuser của PostgreSQL trên máy bạn.

**QĐ12. ADR-0012 "Chia sẻ app từ máy cá nhân qua Cloudflare Quick Tunnel; chế độ công khai"** được viết ở trạng thái `Đề xuất` **trước khi code** (A0, spec §8) và chuyển `Chấp nhận` sau khi V đạt.
- **Nội dung:** QĐ1–QĐ7 cùng các phương án bị loại.
- **Quan hệ với ADR cũ:** ADR-0012 bổ sung, không sửa ADR đã chấp nhận:
  - ADR-0007 điểm 5: nay có giới hạn đăng nhập sai;
  - ADR-0008/0009/0010: nay có `lock_timeout`;
  - ADR-0011: Cloudflare đứng trước app, lo phần client gửi chậm;
  - brief §9: không PaaS.
- **Hướng PaaS** (Render + Neon, `StorageService` đám mây) ghi ở mục "Hướng phát triển" (spec §9 câu 9).

## 3. Hợp đồng code, cấu hình và view (chốt trước để backend, devops, frontend, test làm song song)

Nếu cần thêm thuộc tính hay đổi tên, agent dừng lại và báo phiên chính; không tự đặt tên.

```java
// ── user ─────────────────────────────────────────────────────────────────────────
public final class PasswordPolicy {                       // trước đây package-private
    static Optional<String> violation(String password);    // giữ nguyên (chữ tiếng Việt cho form)
    public static boolean isValid(String password);         // = violation(password).isEmpty()
}
public final class LoginThrottle {                         // không annotation; SecurityConfig tạo @Bean
    public static final int MAX_FAILURES_PER_EMAIL = 5;
    public static final int MAX_FAILURES_PER_IP = 20;
    public static final Duration WINDOW = Duration.ofMinutes(15);   // vừa là cửa sổ đếm, vừa là thời gian chặn
    static final int MAX_TRACKED_KEYS = 10_000;                      // mỗi bảng (email, IP), LRU
    public LoginThrottle(Clock clock);
    public synchronized boolean isBlocked(String rawEmail, String clientIp);   // email qua EmailNormalizer
    public synchronized void recordFailure(String rawEmail, String clientIp);
    public synchronized void recordSuccess(String email);                      // chỉ xóa khóa email
}
@Service
public class DemoAccountService {
    public static final String ADMIN_EMAIL = "admin@minishop.local";
    public static final String USER_EMAIL = "user@minishop.local";
    DemoAccountService(UserRepository users, PasswordEncoder passwordEncoder);
    @Transactional public int resetDemoPasswords(String adminPassword, String userPassword); // 0..2; IllegalArgumentException nếu trống
}
public interface UserRepository extends JpaRepository<User, Long> {
    // ... như cũ
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.passwordHash = :hash where u.email = :email")
    int updatePasswordHash(@Param("email") String email, @Param("hash") String passwordHash);
}

// ── config ───────────────────────────────────────────────────────────────────────
public final class StartupRequirements implements EnvironmentPostProcessor, Ordered {  // public: SpringFactoriesLoader tạo bằng reflection
    static final Set<String> DEV_DEFAULT_PASSWORDS = Set.of("Admin@123", "User@123");
    static final int PUBLIC_MIN_PASSWORD_LENGTH = 12;
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application);
    public int getOrder();                                  // Ordered.LOWEST_PRECEDENCE (sau ConfigDataEnvironmentPostProcessor)
}
final class StartupRequirementException extends IllegalStateException {
    StartupRequirementException(String message, String action);
    String action();
}
public final class StartupRequirementFailureAnalyzer extends AbstractFailureAnalyzer<StartupRequirementException> {
    protected FailureAnalysis analyze(Throwable rootFailure, StartupRequirementException cause);  // description = message, action = action()
}
@Component @Profile("public")
class PublicModeDemoPasswords implements SmartInitializingSingleton {
    PublicModeDemoPasswords(SeedProperties seed, DemoAccountService demoAccounts);
    public void afterSingletonsInstantiated();             // reset + đúng một dòng INFO (QĐ3)
}
final class LoginBlockedException extends AuthenticationException { LoginBlockedException(); }  // message "Login temporarily blocked"
final class LoginThrottleFilter extends OncePerRequestFilter {      // KHÔNG phải bean
    LoginThrottleFilter(LoginThrottle throttle, AuthenticationFailureHandler blockedHandler);
    // chỉ PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/login");
    // bị chặn (theo request.getParameter("email"), request.getRemoteAddr()) -> blockedHandler.onAuthenticationFailure(…, new LoginBlockedException()); không gọi chain
}
final class CountingLoginFailureHandler implements AuthenticationFailureHandler {
    CountingLoginFailureHandler(LoginThrottle throttle, AuthenticationFailureHandler delegate);
    // BadCredentialsException -> throttle.recordFailure(request.getParameter("email"), request.getRemoteAddr()); luôn gọi delegate
}
final class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
    LoginSuccessHandler(LoginThrottle throttle);           // setDefaultTargetUrl("/"); recordSuccess(authentication.getName()) rồi super
}
final class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {
    LoginFailureHandler(FlashMapManager flashMapManager);  // KHÔNG đổi
    // LoginBlockedException -> "/login?blocked"; mọi exception khác -> "/login?error" (như cũ); giữ email flash cho cả hai
}
// SecurityConfig:
//   @Bean LoginThrottle loginThrottle() { return new LoginThrottle(Clock.systemUTC()); }
//   appSecurityFilterChain(HttpSecurity http, FlashMapManager flashMapManager, LoginThrottle throttle):
//     LoginFailureHandler redirect = new LoginFailureHandler(flashMapManager);
//     .addFilterBefore(new LoginThrottleFilter(throttle, redirect), UsernamePasswordAuthenticationFilter.class)
//     .formLogin(f -> f.loginPage("/login").usernameParameter("email")
//             .successHandler(new LoginSuccessHandler(throttle))
//             .failureHandler(new CountingLoginFailureHandler(throttle, redirect)).permitAll())
//   Không đổi quy tắc authorizeHttpRequests, CSP, logout, accessDeniedPage, h2ConsoleSecurityFilterChain.
// DataInitializer: dùng DemoAccountService.ADMIN_EMAIL / USER_EMAIL thay cho chuỗi; không inject DemoAccountService.
```

**`src/main/resources/META-INF/spring.factories`** (B1):
```properties
org.springframework.boot.EnvironmentPostProcessor=com.minishop.config.StartupRequirements
org.springframework.boot.diagnostics.FailureAnalyzer=com.minishop.config.StartupRequirementFailureAnalyzer
```

**Thông điệp của `StartupRequirements`** (đúng từng chữ; danh sách biến theo thứ tự trong câu; **không bao giờ** in giá trị):

| Điều kiện | `message` | `action` |
|---|---|---|
| `h2` + `public` | `Profiles 'h2' and 'public' cannot be active together` | `Run public mode with the default (Docker) database or with 'local,public'.` |
| `h2` + `local` | `Profiles 'h2' and 'local' cannot be active together` | `Choose one database: 'h2' (in memory) or 'local' (PostgreSQL installed on this machine).` |
| `local` thiếu biến | `Profile 'local' requires environment variables: DB_URL, DB_USERNAME, DB_PASSWORD` (chỉ liệt kê biến thiếu) | `Set them as described in README section 'Chạy với PostgreSQL cài trên máy (không Docker)'.` |
| `DB_URL` không phải PostgreSQL | `Profile 'local' requires DB_URL to start with jdbc:postgresql:` | như trên |
| `public` thiếu biến | `Public mode requires environment variables: SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD` (chỉ liệt kê biến thiếu) | `Set both demo passwords as described in README section 'Chia sẻ app đang chạy trên máy'.` |
| `public` mật khẩu yếu | `Public mode requires non-default demo passwords: SEED_ADMIN_PASSWORD` (liệt kê biến vi phạm) | `Use at least 12 characters with letters and digits, different from the dev defaults.` |
| `public` hai mật khẩu trùng (nếu câu hỏi 1 được duyệt) | `Public mode requires different passwords for SEED_ADMIN_PASSWORD and SEED_USER_PASSWORD` | `The USER password is shared with testers; the ADMIN password must stay private.` |

**Cấu hình** (DV1; mỗi khóa có một dòng comment tiếng Việt nêu lý do kèm "xem ADR-0012"; **gộp** vào các khối `server:`/`spring:`/`management:` hiện có, không tạo khóa gốc thứ hai):

```yaml
# application.yml (thêm)
server:
  forward-headers-strategy: native
  tomcat:
    remoteip:
      internal-proxies: "127.0.0.0/8, ::1/128"
      remote-ip-header: X-Forwarded-For
      protocol-header: X-Forwarded-Proto
  servlet:
    session:
      cookie:
        same-site: lax
spring:
  h2:
    console:
      enabled: false          # DevTools bật mặc định ở mọi profile; chỉ profile h2 bật lại
  datasource:
    hikari:
      connection-init-sql: "SET lock_timeout = '5s'"

# application-h2.yml (thêm)
spring:
  datasource:
    hikari:
      connection-init-sql: "SET LOCK_TIMEOUT 5000"

# application-public.yml (mới)
server:
  address: 127.0.0.1
  servlet:
    session:
      persistent: false
      cookie:
        secure: true
spring:
  devtools:
    livereload:
      enabled: false
    restart:
      enabled: false
  h2:
    console:
      enabled: false
management:
  endpoint:
    health:
      show-details: never
      show-components: never
app:
  seed:
    admin-password: ${SEED_ADMIN_PASSWORD}
    user-password: ${SEED_USER_PASSWORD}

# application-local.yml (mới)
spring:
  docker:
    compose:
      enabled: false
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```
`compose.yaml`: `ports: - '127.0.0.1::5432'` (comment: chỉ mở trên loopback, cổng host ngẫu nhiên như cũ).

**View và markup** (test và verifier dựa vào đây; chữ đúng spec §6):

| Template | Thay đổi |
|---|---|
| `fragments/layout.html` | fragment mới `demoNotice`: `<p class="demo-notice" th:fragment="demoNotice">Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.</p>` |
| `user/register.html` | `<p th:replace="~{fragments/layout :: demoNotice}"></p>` ngay sau `h1`, trước `form` |
| `order/checkout.html` | như trên, ngay sau `h1`, trước khối `notice` |
| `user/login.html` | thêm `<th:block th:if="${param.blocked != null}"><div th:replace="~{fragments/notice :: notice('error', 'Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.')}"></div></th:block>` ngay sau khối `param.error` |
| `static/css/app.css` | `.demo-notice` (dùng token có sẵn; không thêm màu mới) |

Trang đăng nhập **không** có dòng demo. Cấm như GĐ2–7: `<script>` nội tuyến, `style=`/`th:style`, `on…=`, `th:utext`.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static từ `src/main/resources/`; test từ `src/test/java/com/minishop/`.

| # | Task | Agent | File | AC / BR | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| A0 | ADR-0012 (`Đề xuất`), trạng thái spec | phiên chính | `docs/adr/0012-share-from-personal-machine-public-mode.md` (mới), `docs/adr/README.md`, `docs/specs/008-deploy.md`, `docs/specs/README.md` | spec §8 | câu hỏi 1 (nếu trả lời khác khuyến nghị) | – | Xem chi tiết A0 |
| DV1 | Cấu hình: header tunnel, cookie, H2 console, `lock_timeout`, profile `public`/`local`, compose loopback | `devops-engineer` | `application.yml`, `application-h2.yml`, `application-public.yml` (mới), `application-local.yml` (mới), `compose.yaml` | 008-5, 6, 11, 12, 13, 14, 16; BR-008-1, 4, 5 | A0 | B1, F1 | Xem chi tiết DV1 |
| B1 | Kiểm điều kiện khởi động | `backend-developer` | `config/StartupRequirements`, `config/StartupRequirementException`, `config/StartupRequirementFailureAnalyzer` (mới); `resources/META-INF/spring.factories` (mới); `user/PasswordPolicy` | 008-1, 2, 15; BR-008-1, 2 | A0 | DV1, F1 | Xem chi tiết B1 |
| F1 | Dòng nhắc demo, thông báo bị chặn | `frontend-developer` | `templates/fragments/layout.html` (chỉ thêm fragment), `templates/user/login.html`, `templates/user/register.html`, `templates/order/checkout.html`, `static/css/app.css` | 008-7, 19; BR-008-6 | A0 (dựng theo mục 3) | DV1, B1, B2, B3, TW4 | Xem chi tiết F1 |
| B2 | Đặt lại mật khẩu demo | `backend-developer` | `user/DemoAccountService` (mới), `user/UserRepository`, `config/PublicModeDemoPasswords` (mới), `config/DataInitializer` | 008-3, 4; BR-008-2 | B1 (cùng agent), DV1 (cho bước tự kiểm) | F1, TW4 | Xem chi tiết B2 |
| TW4 | Test `lock_timeout` | `test-writer` | `LockTimeoutIntegrationTest` (mới), `H2ProfileSmokeTest` (thêm) | 008-13; BR-008-5 | DV1 | B2, B3, F1, TW1 | Xem chi tiết TW4 |
| B3 | Giới hạn đăng nhập sai | `backend-developer` | `user/LoginThrottle` (mới); `config/LoginThrottleFilter`, `config/LoginBlockedException`, `config/CountingLoginFailureHandler`, `config/LoginSuccessHandler` (mới); `config/LoginFailureHandler`, `config/SecurityConfig`; `user/package-info.java`, `config/package-info.java` | 008-7, 8, 9, 10; BR-008-3, 4 | B2 (cùng agent) | F1, TW2, TW4 | Xem chi tiết B3 |
| TW1 | Unit test giới hạn đăng nhập; ArchUnit | `test-writer` | `user/LoginThrottleTest`, `config/LoginThrottleFilterTest`, `config/CountingLoginFailureHandlerTest`, `config/LoginSuccessHandlerTest` (mới); `config/LoginFailureHandlerTest`, `ArchitectureTest` (thêm) | 008-7, 9, 10; BR-008-3 | B3 | TW2, TW4 | Xem chi tiết TW1 |
| TW2 | Test khởi động chế độ công khai và profile `local` | `test-writer` | `config/StartupRequirementsTest`, `config/StartupRequirementFailureAnalyzerTest`, `user/DemoAccountServiceTest`, `PublicProfileStartupTest`, `LocalProfileTest` (mới) | 008-1–5, 11, 12, 14, 15, 16; BR-008-1, 2 | B1, B2, DV1 (đầy đủ chỉ sau B3, vì có bước đăng nhập) | TW1, TW4 | Xem chi tiết TW2 |
| TW3 | Test web và luồng đăng nhập | `test-writer` | `user/LoginFlowTest`, `config/SecurityConfigTest`, `user/AuthControllerTest`, `order/CheckoutControllerTest` (thêm); `config/ForwardedHeadersTest`, `config/UntrustedProxyTest` (mới) | 008-5, 7–12, 19; BR-008-3, 4 | B3, F1, DV1 | TW2 | Xem chi tiết TW3 |
| D | Tài liệu | phiên chính | `README.md`, `CLAUDE.md`, `CHANGELOG.md`, `docs/project-brief.md`, `docs/adr/0012-…md`, `docs/adr/README.md`, `docs/specs/008-deploy.md`, `docs/specs/README.md` | 008-20–23 | B1–B3, DV1 (lệnh và tên biến đã chốt) | TW1–TW4, R | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | mọi task trên | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận. Trọng tâm: không in bí mật, filter không là bean, `synchronized`/LRU đúng, không rò `127.0.0.1` trong test |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc; project Compose `minishop-gd8-verify`, container giả lập) | 008-1 đến 008-24 | R | – | Checklist mục 5 đạt (trừ các mục "Chủ repo", ghi rõ là chờ người dùng) |

**Thứ tự chạy (7 đợt):**
- **Đợt 0:** A0.
- **Đợt 1:** DV1, B1 (khác file; phiên chính trao lượt Maven theo thứ tự DV1 → B1). F1 bắt đầu cùng lúc (không chạy Maven).
- **Đợt 2:** B2 (sau B1, cùng agent; bước tự kiểm cần `application-public.yml` của DV1). TW4 (sau DV1).
- **Đợt 3:** B3 (sau B2). Viết trước phần unit của TW2 (`StartupRequirementsTest`, `StartupRequirementFailureAnalyzerTest`, `DemoAccountServiceTest`).
- **Đợt 4:** TW1 (sau B3). Phần tích hợp của TW2 (`PublicProfileStartupTest`, `LocalProfileTest`, sau B3). TW3 (sau B3, F1). Có thể là ba phiên `test-writer` tách file; lượt Maven nối tiếp nhau.
- **Đợt 5:** D (phần README, brief, CLAUDE.md có thể viết song song từ đợt 4). R.
- **Đợt 6:** V; rồi D chốt trạng thái (ADR `Chấp nhận`, spec `Hoàn thành`, Lộ trình). `CHANGELOG` chuyển `[0.8.0]` và gắn tag chỉ khi người dùng duyệt.

**Đường găng:** A0 → B1 → B2 → B3 → TW3 (dài nhất trong nhóm test) → R → V → D (chốt). DV1, F1, TW4 chạy song song và ngắn hơn.

**Không chạy hai lệnh Maven cùng lúc trong cùng thư mục** (bài học GĐ4):
- phiên chính trao lượt build; F1 không chạy Maven;
- `./mvnw verify` đầy đủ chỉ ở cuối DV1, B1, B2, B3, TW2, TW3 và V; TW1, TW4 chạy `-Dtest=…` của lớp mình rồi verify một lần khi xong;
- bước tự kiểm bằng `spring-boot:run` của DV1, B1, B2, B3 cũng giữ lượt Maven, và **tắt app** (kiểm không còn tiến trình `java` nghe cổng 8080) trước khi trả lượt.

**Build xanh sau mỗi task:**
- **Sau DV1:** chỉ đổi cấu hình. Rủi ro:
  - `connection-init-sql` sai cú pháp trên H2 (`H2ProfileSmokeTest`, mọi `@LiveServerTest` sẽ bắt);
  - `lock_timeout` 5 giây làm đỏ test đang cố ý giữ khóa: đã kiểm, `OrderIntegrationTest` giữ khóa chỉ tới khi thấy backend chờ rồi commit ngay, và các test race chỉ chờ mili giây;
  - compose loopback: chỉ ảnh hưởng `spring-boot:run`, không ảnh hưởng test.
- **Sau B1:** `StartupRequirements` không làm gì khi không có `public`/`local`/`h2` chồng nhau. Mọi test hiện có chỉ dùng profile mặc định hoặc `h2`.
- **Sau B2:** bean `@Profile("public")` không được nạp ở test nào hiện có; `DataInitializerTest` import `DataInitializer` trực tiếp, mà nó chỉ dùng **hằng** của `DemoAccountService`, không inject.
- **Sau B3:**
  - `LoginFailureHandler` giữ constructor nên `LoginFailureHandlerTest` vẫn biên dịch;
  - mọi slice `@Import(SecurityConfig.class)` tự có bean `LoginThrottle`;
  - `LoginFlowTest` (đăng nhập thành công, `/login?error`, chuyển về `/orders`) chứng minh `LoginSuccessHandler` giữ đúng hành vi `defaultSuccessUrl("/")`;
  - số lần đăng nhập sai từ `127.0.0.1` của test hiện có (3 ở `LoginFlowTest`) dưới ngưỡng 20.
- **Sau F1:** `TemplateCspComplianceTest`, `LayoutAccessibilityTest` quét các template đã sửa.

### Chi tiết A0 (phiên chính)

- **`docs/adr/0012-share-from-personal-machine-public-mode.md`** theo `template.md`, trạng thái `Đề xuất`:
  - **bối cảnh:** spec 008 (thay đổi phạm vi), brief §9, ADR-0007 điểm 5, ADR-0008/0009/0010 (`lock_timeout` hẹn trước khi deploy), ADR-0011 (giới hạn ở reverse proxy), ghi chú "seed must be gated" trong `CLAUDE.md`;
  - **quyết định:** QĐ1–QĐ7 (Quick Tunnel; profile `public`/`local`; kiểm khởi động bằng `EnvironmentPostProcessor`; đặt lại mật khẩu demo trước khi nhận request; giới hạn đăng nhập sai; `RemoteIpValve` chỉ tin loopback; cookie; `server.address` loopback; compose loopback; `lock_timeout` 5 s);
  - **hệ quả:** URL đổi mỗi lần; máy phải bật; ai có link đều vào được; dữ liệu nằm trên máy; mật khẩu dev bị đổi sau khi chạy chế độ công khai; bộ đếm mất khi tắt app; Cloudflare đứng trước app (lo client gửi chậm, giới hạn 100 MB thân request ở gói miễn phí); hướng PaaS để sau;
  - **phương án bị loại:** PaaS (Render + Neon), ngrok Free, Cloudflare Tunnel có tên, cộng các phương án bị loại ở QĐ1–QĐ6.

  Thêm dòng 0012 vào `docs/adr/README.md`.
- **Spec 008** và `docs/specs/README.md`: trạng thái `Đang làm`. Nếu câu hỏi 1 được duyệt, thêm vào BR-008-2 "hai mật khẩu demo phải khác nhau" và một dòng "Lịch sử thay đổi".

### Chi tiết DV1 (`devops-engineer`)

- Sửa đúng 5 file theo khối cấu hình ở mục 3. Comment đầu `application-public.yml` và `application-local.yml` nêu lệnh chạy (Git Bash và PowerShell) và "xem ADR-0012".
- **Không** đổi `pom.xml`, `.github/`, `spring.servlet.multipart`, các khóa của GĐ7.
- Đọc mã nguồn hoặc `javap` lớp `TomcatWebServerFactoryCustomizer` (hoặc tương đương trong `spring-boot-tomcat` 4.1.1) để xác nhận Boot chuyển `internal-proxies` dạng CIDR cho `RemoteIpValve`. Nếu Boot chỉ nhận regex, dùng `127\.\d{1,3}\.\d{1,3}\.\d{1,3}|0:0:0:0:0:0:0:1|::1` và báo phiên chính.

**Xong khi:**
- `./mvnw verify` xanh (đặc biệt `H2ProfileSmokeTest`, mọi `@LiveServerTest`, `OrderIntegrationTest`, `CartIntegrationTest`); `bash .github/scripts/check-no-skipped-tests.sh` exit 0;
- `./mvnw spring-boot:run` (dev, Docker) lên bình thường; `docker compose port postgres 5432` trả `127.0.0.1:<cổng>`; đăng nhập `admin@` (trình duyệt hoặc `curl` với cookie jar) rồi `GET /h2-console` → 404; tắt app;
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`: `/h2-console` vẫn mở;
- báo cáo: tên khóa đã xác nhận trong `spring-configuration-metadata.json`, kết quả câu hỏi CIDR, và output của hai lệnh trên.

### Chi tiết B1 (`backend-developer`)

- **`StartupRequirements`, `StartupRequirementException`, `StartupRequirementFailureAnalyzer`, `META-INF/spring.factories`** đúng mục 3, QĐ2.
  - Profile đọc bằng `environment.matchesProfiles(…)`; giá trị trống (`!StringUtils.hasText`) tính là thiếu; độ dài đếm theo code point.
  - Javadoc nêu vì sao là `EnvironmentPostProcessor` (chạy trước Docker Compose, DB và mọi bean) và vì sao không dùng `@Validated` (in giá trị bị từ chối).
  - Không log gì ngoài việc ném exception; không giữ giá trị mật khẩu trong field.
- **`PasswordPolicy`:** lớp thành `public`, thêm `public static boolean isValid(String)`; `violation` và các chữ tiếng Việt giữ nguyên.
- Câu hỏi 1: nếu được duyệt, thêm kiểm "hai mật khẩu khác nhau" sau kiểm độ mạnh.

**Xong khi:**
- `./mvnw verify` xanh;
- tự kiểm trong Git Bash và dán phần output vào báo cáo:
  1. `env -u SEED_ADMIN_PASSWORD -u SEED_USER_PASSWORD COMPOSE_PROJECT_NAME=minishop-gd8-verify ./mvnw spring-boot:run -Dspring-boot.run.profiles=public` → dừng trong vài giây, có `APPLICATION FAILED TO START` (nếu analyzer chạy được ở giai đoạn này; nếu không thì ghi rõ) và tên hai biến; `docker compose -p minishop-gd8-verify ps -a` không có container nào;
  2. `SEED_ADMIN_PASSWORD='Admin@123' SEED_USER_PASSWORD='Short1abc' … -Dspring-boot.run.profiles=public` → `Public mode requires non-default demo passwords: SEED_ADMIN_PASSWORD, SEED_USER_PASSWORD`; `grep` output không có `Admin@123`, `Short1abc`;
  3. `-Dspring-boot.run.profiles=local` không có `DB_*` → thông điệp nêu ba biến; `DB_URL=jdbc:h2:mem:x` (có đủ hai biến kia) → thông điệp `jdbc:postgresql:`;
  4. `-Dspring-boot.run.profiles=h2,public` → bị từ chối.

### Chi tiết F1 (`frontend-developer`)

- **Đọc trước:** spec 008 §6 (chữ chính xác), QĐ8 và mục 3 của plan này; mục "UI design skill" trong `CLAUDE.md`; comment token đầu `static/css/app.css`; `fragments/notice.html`.
- Sửa đúng bảng "View và markup" ở mục 3. Dùng lại hướng thiết kế cửa hàng có sẵn: không vẽ lại, không thêm khối, không icon mới. Ở 375px dòng nhắc xuống dòng được, không gây cuộn ngang.
- Kiểm hình: nhờ phiên chính lấy lượt Maven để tạo jar (`./mvnw -q -DskipTests package`), chạy `java -jar target/mini-shop-0.1.0-SNAPSHOT.jar --spring.profiles.active=h2`, rồi `inspect.mjs` ở 375px và 1280px cho `/register`, `/login?blocked`, `/login?error`. `/checkout` cần đăng nhập, nên kiểm tay bằng DevTools device mode như GĐ6/7. Tắt app và mọi Chrome headless khi xong.

**Xong khi:**
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả;
- `inspect.mjs` không có `FAIL` `contrast`, `side-scroll`, `overflow`; dòng nhắc đạt tương phản ≥ 4,5:1;
- báo cáo liệt kê file đã sửa và tỉ lệ tương phản của `.demo-notice`.

### Chi tiết B2 (`backend-developer`)

- **`DemoAccountService`, `UserRepository.updatePasswordHash`, `PublicModeDemoPasswords`** đúng mục 3, QĐ3. Javadoc của `PublicModeDemoPasswords` giải thích vì sao là `SmartInitializingSingleton` (trước khi Tomcat gắn connector) mà không phải `ApplicationRunner`.
- **`DataInitializer`:** chỉ thay hai chuỗi email bằng hằng; giữ nguyên mọi thứ khác.

**Xong khi:**
- `./mvnw verify` xanh;
- tự kiểm với `COMPOSE_PROJECT_NAME=minishop-gd8-verify` (không đụng volume dev):
  1. chạy dev (không profile), đăng ký một tài khoản `khac@minishop.test`, tắt;
  2. chạy `public` với `SEED_ADMIN_PASSWORD='Verify-Admin-2026'`, `SEED_USER_PASSWORD='Verify-User-2026'`: log có **đúng một** dòng `Public mode: demo passwords updated for 2 account(s)`, nằm **trước** dòng `Tomcat started on port`;
  3. `curl` đăng nhập `admin@` bằng `Admin@123` → `/login?error`; bằng mật khẩu mới → `/`;
  4. `docker compose -p minishop-gd8-verify exec -T postgres psql -U minishop -d minishop -c "select email, left(password_hash, 8) from users order by id" < /dev/null` → hash của `khac@` không đổi so với trước bước 2;
  5. `docker compose -p minishop-gd8-verify down -v`, chạy lại `public` → log `demo accounts not found yet…`, sau đó seed 2 user, 5 danh mục, 22 sản phẩm; đăng nhập bằng mật khẩu mới được;
  6. dọn: tắt app, `down -v`;
- báo cáo dán các dòng log và kết quả SQL (không có mật khẩu).

### Chi tiết TW4 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `LockTimeoutIntegrationTest` (mới, `@SpringBootTest @AutoConfigureMockMvc`, `@Import(TestcontainersConfiguration.class)`, `@Testcontainers(disabledWithoutDocker = true)`, `OutputCaptureExtension`; cùng cấu hình với `LoginFlowTest` để dùng chung context) | `SHOW lock_timeout` qua `JdbcTemplate` = `5s`. Khuôn cho mỗi ca: một `Connection` riêng lấy từ `DataSource` (`autoCommit=false`) giữ khóa; luồng chính gọi request MockMvc thật và **chờ**; đo thời gian. Bốn ca: (1) **users:** A `select … from users where id = ? for update` → người dùng đó `POST /cart/items` → 500, thời gian trong [4,5 s; 15 s), giỏ không đổi; (2) **orders:** đơn `PENDING` của người dùng riêng, A khóa dòng `orders` → `POST /orders/{id}/cancel` → 500, đơn vẫn `PENDING`, kho không đổi; (3) **categories:** danh mục trống riêng, A khóa → admin `POST /admin/categories/{id}/delete` → 500, danh mục còn; (4) **products:** người dùng có một dòng giỏ, A khóa dòng `products` → `POST /checkout` → 500, không có đơn mới, giỏ và kho không đổi. Sau mỗi ca, A ghi một thay đổi nhỏ (ví dụ `update … set … = … where id = ?`) rồi `commit()` thành công: transaction thứ nhất hoàn tất. Log: đúng **một** dòng `ERROR` mới cho mỗi ca, chứa `Unhandled exception: POST /…`, không chứa email, họ tên, số điện thoại, địa chỉ. Exception trong chuỗi cause là `PessimisticLockingFailureException`. Dữ liệu riêng, dọn ở `finally`; `rollback()` + `close()` connection A ở `finally` | 008-13; BR-008-5 |
| `H2ProfileSmokeTest` (thêm) | trên H2: `select setting_value from information_schema.settings where setting_name = 'LOCK_TIMEOUT'` (hoặc cách tương đương mà H2 2.x hỗ trợ; ghi lại câu đã dùng) = `5000`; `spring.h2.console.enabled` = `true` ở profile `h2` | 008-6, 13 |

**Xong khi:** `./mvnw test -Dtest='LockTimeoutIntegrationTest,H2ProfileSmokeTest'` xanh (Docker bật), rồi `./mvnw verify` xanh; báo cáo thời gian từng ca.

### Chi tiết B3 (`backend-developer`)

- **`LoginThrottle`** đúng QĐ4 và mục 3. Javadoc ghi quy tắc cửa sổ trượt, giới hạn LRU, và việc không có khóa cấu hình (AC-008-10).
- **Bốn lớp mới trong `config`** và **`SecurityConfig`** đúng mục 3. Javadoc của `LoginThrottleFilter` nêu:
  - vì sao đứng sau `CsrfFilter`, trước `UsernamePasswordAuthenticationFilter`;
  - vì sao **không** là bean (Boot sẽ đăng ký thêm như servlet filter);
  - vì sao lần bị chặn đi thẳng tới `LoginFailureHandler` (không đếm).
- **`LoginFailureHandler`:** thêm nhánh `LoginBlockedException` → `/login?blocked`; giữ nguyên phần flash email, cắt 255 code point, constructor.
- **`package-info.java`:** `user` thêm "giới hạn đăng nhập sai (`LoginThrottle`), tài khoản demo (`DemoAccountService`)"; `config` thêm "kiểm điều kiện khởi động (`StartupRequirements`), chế độ công khai (`PublicModeDemoPasswords`), cổng đăng nhập (`LoginThrottleFilter` và các handler)".
- Không đổi quy tắc `authorizeHttpRequests`, CSP, logout, `accessDeniedPage`.

**Xong khi:**
- `./mvnw verify` xanh **mà không sửa test nào**;
- tự kiểm bằng jar hoặc `spring-boot:run` profile `h2`, `curl` có cookie jar và CSRF:
  1. 5 lần sai cho `nobody@example.com` → lần 6 với bất kỳ mật khẩu nào → `302 /login?blocked`;
  2. tài khoản vừa đăng ký: 5 lần sai, rồi đúng mật khẩu → vẫn `/login?blocked`;
  3. 3 lần sai rồi đúng → `302 /`;
  4. 20 lần sai, mỗi lần một email khác, cùng `-H 'X-Forwarded-For: 203.0.113.9'` → lần 21 → `/login?blocked`; đổi sang `203.0.113.10` → `/login?error`;
- log có dòng `WARN` khi bắt đầu chặn, không có email hay IP;
- báo cáo dán các dòng `Location`.

### Chi tiết TW1 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `user/LoginThrottleTest` (mới, JUnit thuần, `Clock` mutable tự viết) | 4 lần sai → chưa chặn; lần sai thứ 5 → `isBlocked` true cho email đó; 14:59 sau đó vẫn chặn, 15:00 thì hết; hết chặn thì đếm lại từ 0 (4 lần sai không chặn); cửa sổ trượt: 4 lần sai ở phút 0, lần thứ 5 ở phút 16 → **không** chặn; `recordSuccess` sau 3 lần sai → 4 lần sai tiếp theo không chặn; `recordSuccess` không xóa khóa IP; email chuẩn hóa: `" USER@X.Y "` và `"user@x.y"` cùng một khóa; email trống chỉ đếm IP; IP `null` chỉ đếm email; 20 email khác nhau cùng IP → IP bị chặn và mọi email mới từ IP đó đều bị chặn; email 1 000 ký tự không làm nổ bộ nhớ (khóa bị cắt); đổ 10 001 khóa → kích thước bảng ≤ 10 000 (qua method package-private chỉ để đo, hoặc gián tiếp: khóa cũ nhất không còn bị chặn) | 008-7, 8, 9, 10; BR-008-3 |
| `config/LoginThrottleFilterTest` (mới, `MockHttpServletRequest`) | `POST /login` khi email bị chặn → `blockedHandler` nhận `LoginBlockedException`, `chain` **không** được gọi (`verifyNoInteractions`); khi IP bị chặn → như trên; không bị chặn → `chain.doFilter` được gọi đúng một lần; `GET /login`, `POST /register` → luôn đi tiếp | 008-7 |
| `config/CountingLoginFailureHandlerTest` (mới) | `BadCredentialsException` → `recordFailure(email, remoteAddr)` rồi delegate; `InternalAuthenticationServiceException`, `LoginBlockedException` → **không** đếm, vẫn delegate | 008-7; BR-008-3 |
| `config/LoginSuccessHandlerTest` (mới) | gọi `recordSuccess` với `authentication.getName()`; không có saved request → `302 /`; có saved request → về URL đã lưu | 008-9 |
| `config/LoginFailureHandlerTest` (thêm) | `LoginBlockedException` → `/login?blocked`, email vẫn vào flash; ca cũ không sửa | 008-7 |
| `ArchitectureTest` (thêm) | rule `servletFilters_areNotSpringComponents`: không lớp nào trong `com.minishop..` vừa hiện thực `jakarta.servlet.Filter` vừa có `@Component` (hoặc stereotype con); comment ghi QĐ4 | spec §8 |

**Xong khi:** `./mvnw test -Dtest='LoginThrottleTest,LoginThrottleFilterTest,CountingLoginFailureHandlerTest,LoginSuccessHandlerTest,LoginFailureHandlerTest,ArchitectureTest'` xanh. Thử tạm thêm `@Component` lên `LoginThrottleFilter` để chắc rule mới đỏ (không giữ lại), rồi báo cáo.

### Chi tiết TW2 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `config/StartupRequirementsTest` (mới, `MockEnvironment` + `setActiveProfiles`) | không profile, `h2`, `local` hợp lệ, `public` hợp lệ, `local,public` hợp lệ → không ném; mỗi dòng trong bảng thông điệp ở mục 3 → `StartupRequirementException` với **đúng** `message`, và `message` + `action` **không chứa** giá trị đã đặt; ranh giới: 11 ký tự → lỗi, 12 → được; 12 chữ không số → lỗi; `Admin@123` → lỗi; chỉ khoảng trắng → "thiếu"; emoji đếm là một ký tự; `DB_URL=jdbc:postgresql://localhost:5432/minishop` → được, `jdbc:h2:…`, `postgresql://…` → lỗi; `getOrder()` = `LOWEST_PRECEDENCE` | 008-1, 2, 15; BR-008-1, 2 |
| `config/StartupRequirementFailureAnalyzerTest` (mới) | `analyze` trả `description` = message, `action` = action, `cause` là exception | 008-1 |
| `user/DemoAccountServiceTest` (mới, `@DataJpaTest` + Testcontainers + `PasswordEncoderConfig`, như `DataInitializerTest`) | ba user (admin, user, `khac@`): `resetDemoPasswords` trả 2; hash của hai demo `matches` mật khẩu mới, không còn `matches` `Admin@123`/`User@123`; hash `khac@` không đổi từng byte; bảng trống → trả 0; tham số trống → `IllegalArgumentException`, không đổi gì | 008-3; BR-008-2 |
| `PublicProfileStartupTest` (mới, gốc `com.minishop`, `@Testcontainers(disabledWithoutDocker = true)`, container static riêng, `SpringApplicationBuilder`, `OutputCaptureExtension`) | **AC-008-1, 2** (không cần DB, datasource trỏ `127.0.0.1:1`): `public` thiếu một/cả hai biến, mật khẩu `Admin@123`, 11 ký tự → `run()` ném; chuỗi cause có `StartupRequirementException` với đúng thông điệp; output có tên biến, **không** có giá trị; output có `APPLICATION FAILED TO START` (nếu B1 báo analyzer chạy được); không có `Connection refused`. `h2,public` → bị từ chối. **AC-008-3** (DB của container): lần 1 chạy profile mặc định (seed `Admin@123`/`User@123`), thêm user `khac@` bằng JDBC, đóng; lần 2 chạy `public` với hai mật khẩu mới → `PasswordEncoder.matches` đúng mật khẩu mới cho hai demo, `khac@` không đổi; output có **đúng một** dòng `Public mode: demo passwords updated for 2 account(s)`, nằm trước `Tomcat started on port`, không có mật khẩu; qua HTTP: `admin@` + `Admin@123` → `Location` chứa `/login?error`, mật khẩu mới → không có `error`. **AC-008-4:** xóa sạch schema (`drop schema public cascade; create schema public`), chạy `public` → log `demo accounts not found yet…`; 2 user, 5 danh mục, 22 sản phẩm; đăng nhập bằng mật khẩu mới được. **AC-008-5:** trong app `public`: `Environment` có `spring.h2.console.enabled=false`; không có bean `h2ConsoleSecurityFilterChain`, không có `ServletRegistrationBean` cho H2; `GET /actuator/health` → thân **đúng** `{"status":"UP"}`; `GET /` có `Content-Security-Policy` giống hệt GĐ7, `X-Content-Type-Options`, `X-Frame-Options`; `server.address` = `127.0.0.1`. **AC-008-11, 12** (cookie): `POST /login` (đăng nhập được, CSRF lấy từ `GET /login`, tự gửi `Cookie`) với `X-Forwarded-Proto: https`, `X-Forwarded-Host: demo-test.trycloudflare.com` → `Location: https://demo-test.trycloudflare.com/`, `Set-Cookie` có `Secure`, `HttpOnly`, `SameSite=Lax`, có `Strict-Transport-Security`; không header → `Location` bắt đầu `http://127.0.0.1:`, cookie vẫn `Secure`, không có HSTS | 008-1–5, 11, 12; BR-008-1, 2 |
| `LocalProfileTest` (mới, gốc, như trên) | **AC-008-15:** `local` thiếu `DB_PASSWORD` → ném, thông điệp nêu `DB_PASSWORD`, không có `Connection refused`; `DB_URL=jdbc:h2:mem:x` → ném. **AC-008-14:** `local` với `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` của container → context lên; `Environment` có `spring.docker.compose.enabled=false`; `flyway_schema_history` có version 2; seed có 22 sản phẩm; thêm một user bằng JDBC, đóng, chạy lại → log `Seed skipped for users`, user vẫn còn. **AC-008-16:** `local,public` với `DB_*` + `SEED_*` → context lên, mật khẩu demo đã đặt lại | 008-14, 15, 16 |

**Xong khi:** `./mvnw verify` xanh; `bash .github/scripts/check-no-skipped-tests.sh` exit 0; báo cáo thời gian của `PublicProfileStartupTest` và `LocalProfileTest`, và ghi chỗ nào khác cột "Kiểm bằng" của spec.

### Chi tiết TW3 (`test-writer`)

Theo quy tắc cách ly ở QĐ10.

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `user/LoginFlowTest` (thêm, PostgreSQL) | **AC-008-7:** tài khoản riêng (email UUID), IP riêng: 5 lần sai → lần 6 **mật khẩu đúng** → `redirectedUrl("/login?blocked")`, `unauthenticated()`; `GET /login?blocked` (URL thật có query string) → `Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.`, **không** có `Email hoặc mật khẩu không đúng.`, ô email giữ chữ đã gõ; cùng kịch bản cho email không tồn tại (IP riêng khác) → trang giống hệt sau khi chuẩn hóa `value="…"` (như `failedLoginPage`). **AC-008-9:** tài khoản riêng: 3 lần sai → đúng → `authenticated()`; đăng xuất; 4 lần sai → lần sai thứ 5 vẫn `/login?error` (không phải `blocked`). **AC-008-10:** context PostgreSQL mặc định có bean `LoginThrottle` | 008-7, 9, 10; BR-008-3 |
| `config/ForwardedHeadersTest` (mới, `@LiveServerTest`, chung context h2) | `Environment.getProperty("server.tomcat.remoteip.internal-proxies")` = `127.0.0.0/8, ::1/128` (chặn ai đó nới rộng). Đăng nhập seed `user@` với `X-Forwarded-Proto: https`, `X-Forwarded-Host: demo-abc.trycloudflare.com`, `X-Forwarded-For: 203.0.113.70` → `Location: https://demo-abc.trycloudflare.com/`, có `Strict-Transport-Security`; đăng nhập sai (tài khoản riêng, IP riêng) với cùng header → `Location: https://demo-abc.trycloudflare.com/login?error`; không header → `Location: http://localhost:<port>/`, không HSTS; `Set-Cookie` có `HttpOnly`, `SameSite=Lax`, **không** có `Secure` (profile `h2`). **AC-008-8:** 20 lần sai, 20 email khác nhau, `X-Forwarded-For: 203.0.113.80` → lần 21 (email mới) → `/login?blocked`; `X-Forwarded-For: 203.0.113.81` → `/login?error`. **AC-008-10:** hành vi trên chạy ở profile `h2` | 008-8, 10, 11; BR-008-3, 4 |
| `config/UntrustedProxyTest` (mới, `@LiveServerTest` + `@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=192.0.2.1/32")`, context riêng) | kết nối từ loopback giờ **không** được tin: 20 lần sai, mỗi lần một email **và** một `X-Forwarded-For` khác nhau → lần 21 (email mới, XFF mới) → `/login?blocked` (mọi lần đều đếm vào `127.0.0.1`); `X-Forwarded-Proto: https` + `X-Forwarded-Host` → `Location` vẫn là `http://localhost:<port>/…` | 008-12; BR-008-4 |
| `config/SecurityConfigTest` (thêm) | khách `get("/login?blocked")` → 200, không chuyển hướng; `POST /login` thiếu CSRF vẫn 403 (cổng không chạy trước CSRF); context có đúng một bean `LoginThrottle`, và **không** có bean nào kiểu `LoginThrottleFilter` | 008-5, 7 |
| `user/AuthControllerTest` (thêm) | `GET /register` có đúng **một** lần chữ `Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.`; khi form có lỗi vẫn có; `GET /login`, `get("/login?error")` **không** có dòng demo; `get("/login?blocked")` có chữ bị chặn với `role="alert"` | 008-7, 19 |
| `order/CheckoutControllerTest` (thêm) | `GET /checkout` có đúng một dòng demo; khi POST có lỗi và trang hiện lại vẫn có | 008-19 |

**Xong khi:** `./mvnw verify` xanh; `bash .github/scripts/check-no-skipped-tests.sh` exit 0; số lớp `@LiveServerTest` dùng chung một context không đổi (`UntrustedProxyTest` là context thứ hai có chủ đích); báo cáo đếm banner Spring Boot trong log.

### Chi tiết D (phiên chính)

- **Tra lại** trang Quick Tunnel và gói winget `Cloudflare.cloudflared` ngay trước khi viết (spec §8: thông tin dịch vụ thay đổi theo thời gian).
- **`README.md`:**
  - **mục mới `Chia sẻ app đang chạy trên máy`** (AC-008-20), gồm:
    1. cài `cloudflared` (`winget install --id Cloudflare.cloudflared`), kiểm bằng `cloudflared --version`;
    2. đặt `SEED_ADMIN_PASSWORD`, `SEED_USER_PASSWORD` cho **Git Bash** (`export …`) và **PowerShell** (`$env:… = "…"`), kèm quy tắc (≥ 12 ký tự, có chữ và số, khác mật khẩu dev, hai mật khẩu khác nhau nếu câu hỏi 1 được duyệt);
    3. chạy app ở chế độ công khai: Docker `./mvnw spring-boot:run -Dspring-boot.run.profiles=public`; không Docker `-Dspring-boot.run.profiles=local,public`; PowerShell `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,public"`;
    4. ở cửa sổ thứ hai: `cloudflared tunnel --url http://127.0.0.1:8080`, lấy URL `https://….trycloudflare.com` trong khung log;
    5. gửi **chỉ** URL `https://` và tài khoản `user@minishop.local` + mật khẩu USER (không bao giờ gửi tài khoản admin);
    6. dừng: `Ctrl+C` ở cả hai cửa sổ.

    Cảnh báo:
    - máy phải bật, URL đổi mỗi lần chạy;
    - ai có link đều vào được, chỉ gửi cho người tin cậy, tắt khi không dùng;
    - dữ liệu người khác nhập nằm trong DB trên máy bạn; cách xóa: Docker `docker compose down -v` kèm xóa `uploads/`, hoặc tạo lại DB với PostgreSQL cài trên máy;
    - chạy lại dev thì mật khẩu demo là mật khẩu đã đặt ở chế độ công khai (cách về lại `Admin@123`: xóa DB để seed lại);
    - chế độ công khai chỉ nghe trên `127.0.0.1`: máy khác trong mạng không mở được bằng IP;
    - Quick Tunnel chỉ để thử nghiệm: tối đa 200 request đồng thời, không cam kết uptime.

    Bảng "Khi app không chạy": các thông điệp tiếng Anh của `StartupRequirements` → cách sửa.
  - **mục mới `Chạy với PostgreSQL cài trên máy (không Docker)`** (AC-008-21):
    - cài PostgreSQL 17 (`winget install -e --id PostgreSQL.PostgreSQL.17` hoặc bộ cài trên postgresql.org), nhớ mật khẩu `postgres`;
    - tạo user và DB bằng `"C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -c "CREATE USER minishop WITH PASSWORD '…'"` và `-c "CREATE DATABASE minishop OWNER minishop"`;
    - đặt `DB_URL=jdbc:postgresql://localhost:5432/minishop`, `DB_USERNAME`, `DB_PASSWORD` (Git Bash và PowerShell);
    - lệnh chạy `-Dspring-boot.run.profiles=local`;
    - lưu ý `pg_hba.conf` chỉ cho loopback; test Testcontainers bị bỏ qua khi không có Docker (CI vẫn chạy đủ);
    - ghi chú về bản 18 theo câu trả lời câu hỏi 2.
  - **"Tài khoản demo":** thêm một câu "ở chế độ công khai mật khẩu lấy từ biến môi trường, xem mục …".
  - **"Bắt đầu nhanh":** thêm dòng bảng cho `local`.
  - **"Xử lý sự cố":** ba mục mới:
    - app dừng với `Public mode requires…`/`Profile 'local' requires…`;
    - đăng nhập qua tunnel không giữ được phiên vì mở bằng `http://` (dùng `https://`);
    - chuyển hướng về `localhost` thay vì domain tunnel (kiểm lệnh tunnel; báo lỗi).
  - **"Quyết định thiết kế":** thêm dòng ADR-0012.
  - **"Hướng phát triển":** thay dòng "Deploy online (GĐ8)…" bằng một đoạn PaaS (spec §9 câu 9: gói miễn phí ngủ khi không dùng, Postgres miễn phí của Render hết hạn sau 30 ngày nên dùng Neon, ổ đĩa của PaaS bị xóa nên phải đổi `StorageService`, cần Dockerfile và CD).
  - **"Lộ trình":** GĐ8 → "Chia sẻ app từ máy cá nhân qua tunnel (`v0.8.0`)", đánh dấu sau khi V đạt.
- **`docs/project-brief.md`** (AC-008-23):
  - §9: "Online: chia sẻ từ máy cá nhân qua Cloudflare Quick Tunnel khi chủ repo đang chạy app; không PaaS, không CD; PaaS + Docker image là hướng phát triển sau"; "Tên miền: subdomain ngẫu nhiên `*.trycloudflare.com`"; "Cập nhật: CI giữ nguyên, không deploy tự động";
  - §10: `/actuator/health` "để kiểm app còn sống", bỏ chữ PaaS; chi phí 0 đồng;
  - §11: dòng GĐ8 "*(Tùy chọn)* Chế độ công khai + tunnel, chạy PostgreSQL không Docker";
  - §4 dòng **Demo**: thêm "ở chế độ công khai mật khẩu demo bắt buộc lấy từ biến môi trường";
  - §7: thêm "giới hạn đăng nhập sai; cookie `Secure`/`SameSite=Lax` sau HTTPS".

  Ghi một dòng lịch sử nếu brief có mục lịch sử.
- **`CLAUDE.md`:**
  - Project status (GĐ8 xong, `v0.8.0` sau khi duyệt);
  - **Commands:** lệnh `public`, `local`, `local,public`; biến `SEED_*`, `DB_*`; `COMPOSE_PROJECT_NAME` để thử mà không đụng volume dev;
  - **mục mới "Public mode patterns (GĐ8)":**
    - `StartupRequirements` là `EnvironmentPostProcessor` (không `@Validated`, vì nó in giá trị);
    - `PublicModeDemoPasswords` là `SmartInitializingSingleton`;
    - `LoginThrottle` là `@Bean` của `SecurityConfig`; filter không bao giờ là bean; quy tắc cách ly test (email/IP riêng, không quá 19 lần sai từ `127.0.0.1`);
    - `RemoteIpValve` chỉ tin loopback; cookie `Secure` chỉ ở `public`;
    - `lock_timeout` qua `connection-init-sql` (H2 ghi đè);
    - DevTools bật H2 console nên `application.yml` tắt nó;
    - Claude không bao giờ chạy `cloudflared`;
  - **sửa:** gạch dòng "The seed must be gated before any deployment…" (đã xử lý bằng `StartupRequirements` + đặt lại mật khẩu); "Domain invariants" thêm `lock_timeout` 5 s;
  - **"Visibility today":** `user` thêm `LoginThrottle`, `DemoAccountService`, `PasswordPolicy`.
- **`CHANGELOG.md`:** `[Unreleased]` gồm ba nhóm:
  - **Thêm:** chế độ công khai, profile `local`, dòng nhắc demo, ADR-0012, hướng dẫn tunnel;
  - **Thay đổi:** H2 console chỉ còn ở profile `h2`, PostgreSQL dev chỉ mở trên loopback;
  - **Bảo mật:** giới hạn đăng nhập sai, mật khẩu demo bắt buộc ở chế độ công khai, cookie `Secure`/`SameSite`, chỉ tin header của proxy loopback, `lock_timeout` 5 s.

  Chuyển thành `[0.8.0]` và gắn tag sau khi người dùng duyệt.
- **ADR-0012** `Chấp nhận`, **spec 008** `Hoàn thành`, `docs/specs/README.md`, `docs/adr/README.md`: sau khi V đạt.

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

**Bản đồ AC** (mỗi AC có ít nhất một test hoặc mục checklist):

| AC | Test | Mục checklist |
|---|---|---|
| 008-1, 2 | `StartupRequirementsTest`, `PublicProfileStartupTest` | 4, 5 |
| 008-3, 4 | `DemoAccountServiceTest`, `PublicProfileStartupTest` | 6 |
| 008-5 | `PublicProfileStartupTest`, `SecurityConfigTest` | 7 |
| 008-6 | toàn bộ test hiện có, `H2ProfileSmokeTest` | 1, 3 |
| 008-7, 9 | `LoginThrottleTest`, `LoginThrottleFilterTest`, `LoginFlowTest`, `AuthControllerTest` | 10 |
| 008-8 | `LoginThrottleTest`, `ForwardedHeadersTest` | 10 |
| 008-10 | `ForwardedHeadersTest` (h2), `LoginFlowTest` (PostgreSQL), `PublicProfileStartupTest` | 10 |
| 008-11 | `ForwardedHeadersTest`, `PublicProfileStartupTest` | 8, U3 |
| 008-12 | `UntrustedProxyTest`, `PublicProfileStartupTest` | 8, 9 |
| 008-13 | `LockTimeoutIntegrationTest` | 11 |
| 008-14, 15, 16 | `LocalProfileTest`, `PublicProfileStartupTest` | 12, U2 |
| 008-17, 18 | – | U3, U4 |
| 008-19 | `AuthControllerTest`, `CheckoutControllerTest` | 13 |
| 008-20, 21, 23 | – | 14 |
| 008-22 | – | 15, U2–U5 |
| 008-24 | CI | 1 |

**Chuẩn bị:** Docker **đang bật**. Mọi lần chạy chế độ công khai trên Docker đặt `COMPOSE_PROJECT_NAME=minishop-gd8-verify` (QĐ11), để **không đụng** volume dev `mini-shop_postgres-data`. Mật khẩu dùng để kiểm: `Verify-Admin-2026`, `Verify-User-2026`. Mọi lần "mở" là trình duyệt thật (DevTools mở tab Network và Console), trừ khi ghi `curl`. Không lệnh nào đọc stdin: `docker compose exec` luôn kèm `-T` và `< /dev/null`. **Không bao giờ chạy `cloudflared`.**

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build, AC-008-6, 24 | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh`; `git diff main --stat -- pom.xml .github src/main/resources/db` | BUILD SUCCESS; script exit 0; có đủ lớp test mới của TW1–TW4; mọi test GĐ1–7 xanh; diff rỗng (không đổi pom, CI, migration) |
| 2 | Không có migration | Sau mục 3: `docker compose exec -T postgres psql -U minishop -d minishop -c "select max(version) from flyway_schema_history" < /dev/null` | `2` |
| 3 | Dev không đổi (AC-008-6) | `./mvnw spring-boot:run` (volume dev **của bạn**, chỉ đọc): đăng nhập `admin@`/`Admin@123` (nếu volume dev đang có mật khẩu khác thì ghi lại, không sửa); `/h2-console` khi đã đăng nhập; `docker compose port postgres 5432`. Rồi `-Dspring-boot.run.profiles=h2`: `/h2-console` | Dev: đăng nhập được; `/h2-console` → 404 (DevTools không còn bật nó); cổng `127.0.0.1:<n>`. `h2`: H2 console mở được |
| 4 | AC-008-1 (âm) | `env -u SEED_ADMIN_PASSWORD -u SEED_USER_PASSWORD COMPOSE_PROJECT_NAME=minishop-gd8-verify ./mvnw spring-boot:run -Dspring-boot.run.profiles=public`; lặp lại chỉ thiếu `SEED_USER_PASSWORD`; `docker compose -p minishop-gd8-verify ps -a` | Dừng sau vài giây; output nêu đúng tên biến còn thiếu (một hoặc hai); không có giá trị bí mật; không có container nào được tạo |
| 5 | AC-008-2 (âm) | Như mục 4 với lần lượt: `SEED_ADMIN_PASSWORD=Admin@123`; `SEED_USER_PASSWORD=User@123`; một mật khẩu 11 ký tự `Abcdefghij1`; 12 chữ không số; hai mật khẩu giống nhau (nếu câu hỏi 1 được duyệt); `-Dspring-boot.run.profiles=h2,public` | Mỗi lần: dừng với thông điệp đúng mục 3 (`Public mode requires non-default demo passwords: …` …); `grep` output không có giá trị đã đặt |
| 6 | AC-008-3, 4 | Project `minishop-gd8-verify`: (a) chạy dev (không profile) → `/register` tạo `khac@minishop.test`; chụp `select email, password_hash from users`; tắt; (b) chạy `public` với hai mật khẩu kiểm → đọc log; chụp lại bảng `users`; trình duyệt: `admin@` + `Admin@123`, rồi + `Verify-Admin-2026`; (c) tắt; `docker compose -p minishop-gd8-verify down -v`; chạy `public` lại | (b) đúng **một** dòng INFO `Public mode: demo passwords updated for 2 account(s)`, nằm trước `Tomcat started`; không có mật khẩu trong log; hash của hai demo đổi, của `khac@` **không** đổi; `Admin@123` → `Email hoặc mật khẩu không đúng.`; mật khẩu mới vào được `/admin`. (c) log `demo accounts not found yet…`; trang chủ có 21 sản phẩm; đăng nhập bằng mật khẩu mới được |
| 7 | AC-008-5, spec §8 | App `public` của mục 6c đang chạy: `curl -i http://localhost:8080/h2-console`; trình duyệt (đã đăng nhập `admin@`) `/h2-console`, `/actuator/info`, `/actuator/env`; `curl -s http://localhost:8080/actuator/health`; `curl -sI http://localhost:8080/` so với GĐ7; `netstat -ano \| grep -E ':35729\|:8080'`; lấy IP LAN bằng `ipconfig`, rồi `curl -m 5 http://<IP-LAN>:8080/`; `docker compose -p minishop-gd8-verify port postgres 5432` | Khách: 302 `/login`; đã đăng nhập: trang 404 cho cả ba đường dẫn; health đúng `{"status":"UP"}`; CSP, `X-Content-Type-Options`, `X-Frame-Options` như GĐ7; không có cổng 35729; 8080 chỉ nghe trên `127.0.0.1`; `curl` tới IP LAN bị từ chối; PostgreSQL `127.0.0.1:<n>` |
| 8 | AC-008-11 (giả lập tunnel), AC-008-12 | `curl` có cookie jar: `GET /login` lấy `_csrf`; `POST /login` (`user@` + mật khẩu kiểm) với `-H 'X-Forwarded-Proto: https' -H 'X-Forwarded-Host: demo-verify.trycloudflare.com' -H 'X-Forwarded-For: 203.0.113.50'`; lặp lại không có header; lặp lại với mật khẩu sai | Có header: `Location: https://demo-verify.trycloudflare.com/` (sai: `…/login?error`), `Set-Cookie: JSESSIONID=…; Path=/; Secure; HttpOnly; SameSite=Lax`, có `Strict-Transport-Security`. Không header: `Location: http://localhost:8080/`, cookie vẫn `Secure`, không HSTS |
| 9 | AC-008-12 | Trình duyệt mở thẳng `http://localhost:8080` (app `public`): đăng nhập `user@`, thêm giỏ, thanh toán, đăng xuất; DevTools → Application → Cookies | Mọi bước chạy (trình duyệt coi `localhost` là ngữ cảnh an toàn); cookie `Secure`, `HttpOnly`, `SameSite=Lax`; Console không lỗi CSP. Nếu trình duyệt không lưu cookie `Secure` trên `http://localhost` thì **dừng lại và báo** (phương án dự phòng: để Tomcat tự gắn `Secure` theo `request.isSecure()`) |
| 10 | AC-008-7, 8, 9, 10 | Profile `h2` (`./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`): (a) trình duyệt: 5 lần sai cho `nobody-gd8@example.com`, lần 6; (b) đăng ký `tester-gd8@minishop.test`, 5 lần sai, lần 6 **đúng** mật khẩu; (c) đăng ký `tester2-gd8@…`, 3 sai, 1 đúng, đăng xuất, 4 sai, lần 5 sai; (d) `curl` 20 lần sai, 20 email khác nhau, `X-Forwarded-For: 203.0.113.60`, lần 21; rồi `203.0.113.61`; (e) khởi động lại app, lặp lại lần 6 của (a); (f) lặp lại (a) một lần ở app `public` (mục 6c) | (a), (b): `Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.`, ô email còn chữ đã gõ, hai trang giống nhau; (b) **không** vào được dù đúng mật khẩu; (c) lần 5 vẫn `Email hoặc mật khẩu không đúng.`; (d) lần 21 → `/login?blocked`, IP `.61` → `/login?error`; (e) không còn bị chặn; (f) như (a); log có `WARN` lúc bắt đầu chặn, không có email hay IP |
| 11 | AC-008-13 | App dev hoặc `public` trên PostgreSQL, `user@` đang đăng nhập ở trang một sản phẩm còn hàng. Chạy nền: `timeout 30 docker compose -p <project> exec -T postgres psql -U minishop -d minishop -c "BEGIN; SELECT id FROM users WHERE email='user@minishop.local' FOR UPDATE; SELECT pg_sleep(20); COMMIT;" < /dev/null &`; trong 2 giây bấm `Thêm vào giỏ`; đọc log; sau khi lệnh nền xong, bấm lại | Khoảng 5 giây sau: HTTP 500, tab `Đã có lỗi xảy ra \| Mini Shop`; log có **đúng một** dòng `ERROR` `Unhandled exception: POST /cart/items` với `lock timeout` trong stack, không có email, họ tên, số điện thoại; giỏ không đổi; lệnh nền kết thúc bình thường; lần bấm sau thêm được |
| 12 | AC-008-14, 15, 16 (giả lập "PostgreSQL cài trên máy") | `docker run --rm -d --name minishop-native-sim -p 127.0.0.1:55432:5432 -e POSTGRES_DB=minishop -e POSTGRES_USER=minishop -e POSTGRES_PASSWORD=sim-pass-123 postgres:17-alpine`. (a) `local` thiếu `DB_PASSWORD`; `DB_URL=jdbc:h2:mem:x`; (b) `DB_URL=jdbc:postgresql://localhost:55432/minishop DB_USERNAME=minishop DB_PASSWORD=sim-pass-123 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`: đăng ký một user, tắt, chạy lại; `docker compose ls -a`; (c) `local,public` với `SEED_*`; (d) một lần bằng PowerShell `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"` với `$env:DB_URL = …` | (a) dừng ngay, thông điệp đúng, không âm thầm dùng H2; (b) không có dòng log `Docker Compose`, không project Compose nào của repo được bật; Flyway version 2; seed; sau khi chạy lại: `Seed skipped for users`, user vẫn còn; (c) chạy, mật khẩu demo đã đặt lại; (d) lệnh PowerShell đúng như README |
| 13 | AC-008-19 | Device mode 375px và 1280px: `/register` (cả khi có lỗi), `/checkout`, `/login`, `/login?blocked` | Dòng `Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.` xuất hiện **đúng một lần** ở đăng ký và thanh toán, không có ở đăng nhập; không cuộn ngang; Console không lỗi CSP; tương phản ≥ 4,5:1 |
| 14 | AC-008-20, 21, 23 | Đọc `README.md`, `docs/project-brief.md` §4, §7, §9, §10, §11, ADR-0012 | Có đủ mọi ý liệt kê trong AC-008-20, 21 và chi tiết D; lệnh Git Bash và PowerShell đúng cú pháp (đặt nháy kép cho `-D…` trong PowerShell); không có tài khoản admin trong phần "gửi cho người khác"; brief ghi đúng quyết định mới; link ADR chạy được |
| 15 | AC-008-22 (phần verifier) | Tắt mọi app. Làm **đúng từng bước** hai mục README mới bằng Git Bash, thay bước "cài PostgreSQL và tạo DB" bằng container của mục 12 (đã tạo DB). Bấm giờ từ "đặt biến môi trường" tới khi `curl -I http://127.0.0.1:8080/` trả 200 ở chế độ `local,public`. **Dừng trước bước `cloudflared`** | ≤ khoảng 10 phút (không tính lần đầu tải Maven/ảnh); ghi lại mọi chỗ phải đoán; phần tunnel chuyển cho chủ repo (U2–U5) |
| 16 | Log (BR-008-1, 6) | Đọc log của mọi mục trên | Không có mật khẩu demo, mật khẩu DB, email, IP trong bất kỳ dòng log nào do app ghi (trừ dòng `Hibernate`/Tomcat hiện có không chứa dữ liệu); chỉ có dòng `ERROR` ở mục 11 |
| 17 | Kiến trúc | `grep -rn "@Component" src/main/java/com/minishop/config/LoginThrottleFilter.java`; `grep -rn "class .* implements .*Filter" src/main/java`; `git diff main -- src/main/java/com/minishop/config/SecurityConfig.java` | Filter không có annotation; `SecurityConfig` chỉ thêm `@Bean LoginThrottle`, filter, hai handler, không đổi quy tắc truy cập; rule ArchUnit mới nằm trong kết quả mục 1 |
| 18 | Dọn dẹp | Tắt mọi app; `docker compose -p minishop-gd8-verify down -v`; `docker stop minishop-native-sim`; kiểm `docker ps`; xóa file tạm, cookie jar | Không còn tiến trình `java` nghe 8080; không còn container do verifier tạo; volume dev **không** bị đụng |

**Phần chủ repo tự làm và xác nhận** (verifier liệt kê trong báo cáo dưới dạng "chờ người dùng"; Claude không làm thay):

| # | Việc | Mong đợi |
|---|---|---|
| U1 | `cloudflared --version` (máy đã có bản 2026.10.1) | In phiên bản |
| U2 | AC-008-14, 22: theo README, tạo DB `minishop` trên PostgreSQL cài trên máy (17, hoặc 18 có sẵn theo câu hỏi 2), đặt `DB_*` và `SEED_*`, chạy `local,public` **khi Docker Desktop tắt**; bấm giờ cả quá trình | App lên, không báo lỗi Docker; ≤ khoảng 10 phút; ghi lại chỗ phải đoán |
| U3 | AC-008-11, 17: cửa sổ thứ hai `cloudflared tunnel --url http://127.0.0.1:8080`; mở URL `https://…trycloudflare.com` trên điện thoại dùng 4G: xem, tìm, lọc → thêm giỏ khi chưa đăng nhập → đăng nhập `user@` (giỏ được gộp) → đặt hàng COD → "Đơn hàng của tôi" → hủy đơn; mở một ảnh sản phẩm có sẵn và một ảnh đã upload; mở `/khong-co` (404) và `/admin` bằng `user@` (403). Trên laptop, mở cùng URL với DevTools: Console, Application → Cookies, Network (`Location` sau đăng nhập) | Mọi bước chạy qua HTTPS; ảnh hiện; trang 404/403 tiếng Việt; không lỗi CSP; cookie `Secure`, `HttpOnly`, `SameSite=Lax`; chuyển hướng giữ domain `trycloudflare.com`, không về `localhost` |
| U4 | AC-008-18: `Ctrl+C` tunnel, mở lại URL trên điện thoại; `Ctrl+C` app | URL báo lỗi của Cloudflare; không còn gì của app mở ra ngoài |
| U5 | Sau khi xong: quyết định giữ hay xóa dữ liệu người khác đã nhập (README) | – |

## 6. Câu hỏi cho người dùng

Không câu nào chặn việc bắt đầu: không trả lời thì plan làm theo khuyến nghị.

1. **Có cấm hai mật khẩu demo trùng nhau ở chế độ công khai không?**
   - Nếu `SEED_ADMIN_PASSWORD` trùng `SEED_USER_PASSWORD`, người được bạn gửi mật khẩu `USER` sẽ đăng nhập được cả `admin@minishop.local`. Điều này đi ngược câu 3 của spec ("chỉ gửi tài khoản `USER`").
   - **Khuyến nghị:** có. Thêm một dòng vào BR-008-2 (A0) và một kiểm trong `StartupRequirements` (B1).
   - Cần trả lời trước B1 (đợt 1).
2. **PostgreSQL trên máy bạn là bản 18, README sẽ ghi bản nào?**
   - Máy đã có PostgreSQL 18 (bộ cài EDB) ở `C:\Program Files\PostgreSQL\18`. Compose và Testcontainers dùng 17, AC-008-14 ghi 17.
   - **Khuyến nghị:**
     - README khuyến nghị cài **17** cho khớp với test và CI;
     - bạn dùng luôn bản **18** sẵn có cho bước U2, không phải cài thêm. Flyway 12.4 hỗ trợ, và schema chỉ dùng SQL cơ bản;
     - nếu U2 chạy được, D thêm một dòng "bản 18 cũng chạy được (đã thử)".

     Lưu ý: bản 18 đang đặt `listen_addresses = '*'`. Hãy kiểm `pg_hba.conf` chỉ cho `127.0.0.1/32` và `::1/128`.
   - Cần trả lời trước D.
3. **Cuối giai đoạn có commit, push và mở PR `phase-8/deploy` → `main` không?**
   - **Khuyến nghị:** có, nhưng chưa merge, giống GĐ3–7. Các agent không tự commit.
4. **Chỉ để bạn biết** (các điểm spec chưa nói, plan đã chọn):
   - **Phát hiện khi kiểm DevTools:** hiện nay `./mvnw spring-boot:run` (dev, PostgreSQL) vẫn đăng ký `/h2-console` vì DevTools bật nó ở mọi profile. GĐ8 tắt nó ngoài profile `h2`, kể cả ở dev.
   - **Khách mở `/h2-console` ở chế độ công khai** bị chuyển `/login` (đường dẫn không công khai, quy tắc GĐ7); người đã đăng nhập thấy trang 404.
   - **Chế độ công khai chỉ nghe trên `127.0.0.1`:** máy khác cùng Wi-Fi không mở được `http://<IP-máy>:8080`. Mọi người, kể cả bạn trên điện thoại, dùng link tunnel. Lệnh tunnel trỏ `http://127.0.0.1:8080`.
   - **PostgreSQL của `compose.yaml`** nay chỉ mở trên loopback (trước đây mở trên mọi card mạng, kể cả Wi-Fi công cộng).
   - **Cookie phiên `Secure` ở chế độ công khai:** mở bằng `http://localhost:8080` vẫn đăng nhập được trên Chrome/Edge/Firefox; `curl` hay script thì không gửi lại cookie qua HTTP.
   - **Giới hạn đăng nhập sai:**
     - đếm theo cửa sổ trượt 15 phút; bị chặn 15 phút tính từ lần sai thứ 5 (hoặc thứ 20 theo IP); lần bị chặn không được tính thêm;
     - tắt app là bộ đếm về 0;
     - vài người dùng chung một mạng (một văn phòng) cùng gõ sai tổng cộng 20 lần sẽ bị chặn chung.
   - **`lock_timeout` áp cho mọi câu SQL,** kể cả Flyway lúc khởi động (không ai tranh khóa nên không ảnh hưởng). Riêng việc gộp giỏ khi đăng nhập: nếu chờ khóa quá hạn, bạn vẫn đăng nhập được và giỏ khách được giữ (GĐ4 cố ý không làm hỏng việc đăng nhập).
   - **Chạy chế độ công khai trên DB dev** sẽ đổi mật khẩu demo của DB dev (spec câu 4). `phase-verifier` dùng project Compose riêng (`minishop-gd8-verify`), nên volume dev của bạn không bị đụng.
   - **Không có khóa cấu hình** nào để tắt hay đổi ngưỡng đăng nhập sai (AC-008-10).
   - **Có ADR-0012 mới** (viết trước khi code).

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Số phiên bản trong `pom.xml` vẫn là `0.1.0-SNAPSHOT`:** nên tăng lúc phát hành `v0.8.0` (một commit `chore`) để tên jar khớp tag (đã đề xuất ở GĐ7).
- **`statement_timeout`** (chặn câu SQL chạy quá lâu, không chỉ chờ khóa): hợp với khi có nhiều người dùng thật.
- **Giới hạn số lần đăng ký** theo IP: hiện ai có link cũng tạo được tài khoản không giới hạn; ở quy mô gửi link cho vài người thì chấp nhận được.
- **Cloudflare Tunnel có tên + tên miền riêng** để URL cố định; **PaaS + Docker image + `StorageService` đám mây + CD** (spec §9 câu 9, ghi ở "Hướng phát triển").
- **Header cache cho tài nguyên tĩnh** (spec §2, ngoài phạm vi): qua tunnel trang tải chậm hơn một chút.
- **Đếm đăng nhập sai bền vững** (lưu DB hoặc Redis) khi có nhiều instance; hiện một máy, một instance nên bộ nhớ là đủ.

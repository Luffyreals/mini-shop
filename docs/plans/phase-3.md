# Kế hoạch GĐ3: Đăng ký, đăng nhập và phân quyền

- **Spec:** [003 Đăng ký, đăng nhập và phân quyền](../specs/003-authentication.md) (Đã duyệt 2026-10-24; người dùng chấp nhận cả 7 khuyến nghị: đăng ký xong chuyển sang `/login`, mật khẩu 8–64 ký tự có chữ và số và ≤ 72 byte UTF-8, không hỏi số điện thoại, có trang `/admin` tạm, migration V2 `CHECK (email = lower(email))`, đăng nhập xong về trang đã yêu cầu hoặc `/`, không khóa tài khoản)
- **Nhánh:** `phase-3/auth`, tạo từ `main` sau tag `v0.2.0`
- **Đã có từ GĐ1–2, không làm lại:**
  - entity `User`, enum `Role`, `UserRepository.findByEmail`, cột `users.email` có `uq_users_email`;
  - `PasswordEncoderConfig` (`DelegatingPasswordEncoder`, hash `{bcrypt}…`), `DataInitializer` với 2 tài khoản demo email chữ thường;
  - `SecurityConfig`: CSP bật thật, đường dẫn công khai **chỉ cho GET**, chuỗi filter riêng cho H2 console ở profile `h2`;
  - `thymeleaf-extras-springsecurity6` đã có trong `pom.xml` (3.1.5, Boot 4.1 tự đăng ký `SpringSecurityDialect`), `spring-security-test` đã có;
  - layout `fragments/layout.html` và hướng thiết kế "The specimen catalogue" trong `static/css/app.css`.
- **Không đổi `pom.xml`**, nên giai đoạn này không có task cho `devops-engineer`.
- **Có thay đổi schema (V2)**, nên có task `schema-guardian`.

## 1. Mục tiêu

- Khách đăng ký được tài khoản `USER` bằng họ tên, email, mật khẩu. Email luôn được lưu dạng chữ thường, đã bỏ khoảng trắng hai đầu, và DB cũng bảo đảm điều này (V2). Hai yêu cầu đăng ký cùng email gần như đồng thời không bao giờ gây trang 500.
- Đăng nhập bằng trang `/login` riêng, dùng người dùng trong DB (không còn tài khoản tự sinh). Đăng xuất bằng POST có CSRF. Sau khi đăng nhập, người dùng quay lại trang đã yêu cầu trước đó. Session ID đổi sau khi đăng nhập.
- Phân quyền theo đường dẫn: `/admin/**` chỉ cho `ADMIN` (người dùng `USER` nhận HTTP 403 với trang riêng); `/orders/**`, `/checkout/**` cần đăng nhập; trang sản phẩm vẫn công khai. Header hiển thị theo trạng thái đăng nhập.
- `./mvnw verify` xanh **với 0 test bị bỏ qua** (Docker đang bật). Mọi AC-003-1 đến AC-003-22 có test hoặc mục checklist tương ứng.

## 2. Quyết định thiết kế

**QĐ1. Principal là `user.CurrentUser`, một class riêng, không phải entity. Tên hiển thị ở header lấy từ principal, không truy vấn lại DB.**
- `CurrentUser` là `public final class` cài `UserDetails` và `CredentialsContainer`. Nó chứa `id`, `email`, `fullName`, `role` và hash mật khẩu (chỉ để Spring Security so sánh lúc đăng nhập).
- Sau khi đăng nhập thành công, `ProviderManager` gọi `eraseCredentials()`, nên hash bị xóa (`null`) trước khi principal được lưu vào session (BR-003-4). Vì vậy `CurrentUser` **không phải `record`**: record bất biến nên không xóa được hash.
- `toString()` không in mật khẩu. `equals`/`hashCode` theo `email`, giống `org.springframework.security.core.userdetails.User`. Có `serialVersionUID`, vì principal nằm trong session.
- Header đọc `${#authentication.principal.fullName}`. Không tốn truy vấn nào mỗi request.
- **Public ngay từ GĐ3** dù quy tắc mặc định là package-private. Lý do: đây là "hợp đồng" của module `user` cho câu hỏi "ai đang đăng nhập". Test ở package `admin`, `common` cần dựng nó, và GĐ4 (`cart`), GĐ5 (`order`) sẽ nhận `@AuthenticationPrincipal CurrentUser` để lấy `id`. Constructor public chỉ nhận giá trị thuần, không nhận entity.

*Phương án bị loại:*
- **Dùng entity `User` làm principal.** Entity sẽ nằm trong session: bị serialize, lazy loading lỗi khi không còn transaction, và lộ hash mật khẩu ở mọi chỗ đọc principal.
- **Principal là `org.springframework.security.core.userdetails.User` rồi tra họ tên bằng một `@ControllerAdvice` mỗi request.** Thêm một truy vấn cho mọi trang, mà vẫn cần id của người dùng. Đánh đổi của cách đã chọn: nếu họ tên đổi thì header chỉ cập nhật sau lần đăng nhập sau. MVP không có chức năng sửa hồ sơ nên chấp nhận được.

**QĐ2. `UserDetailsService` nằm trong `user`, package-private. Chuẩn hóa email ở một hàm duy nhất, dùng cho cả đăng ký và đăng nhập.**
- `user.EmailNormalizer.normalize(String)` (package-private): `strip()` rồi `toLowerCase(Locale.ROOT)`. Đầu vào `null` trả `""`. `Locale.ROOT` tránh lỗi "chữ i tiếng Thổ Nhĩ Kỳ" như QĐ3 của GĐ2.
- `user.ShopUserDetailsService` (`@Service`, `@Transactional(readOnly = true)`, package-private):
  - chuẩn hóa `username` rồi gọi `findByEmail`;
  - không tìm thấy thì ném `UsernameNotFoundException("User not found")`, message **không chứa email** (không đưa dữ liệu cá nhân vào log);
  - tìm thấy thì map sang `CurrentUser`, authority là `ROLE_<role>`.
- Chỉ có **một** bean `UserDetailsService`, nên Spring Boot tự bỏ `UserDetailsServiceAutoConfiguration` (hết dòng "Using generated security password", AC-003-20). Spring Security tự tạo `DaoAuthenticationProvider` dùng bean `PasswordEncoder` có sẵn. `SecurityConfig` không cần tham chiếu tới class này, nên nó giữ package-private được.
- **Không lộ email có tồn tại hay không (BR-003-5):**
  - `DaoAuthenticationProvider` mặc định đổi `UsernameNotFoundException` thành `BadCredentialsException`;
  - khi không tìm thấy người dùng, nó vẫn chạy BCrypt với một hash giả, nên thời gian phản hồi của hai trường hợp gần như nhau.
- Đã kiểm mã nguồn `BCrypt` của Spring Security 7.1.1: mật khẩu trên 72 byte chỉ bị chặn khi **băm mới** (`encode` ném `IllegalArgumentException`). Khi **so sánh** (`matches`) thì không bị chặn, nên đăng nhập bằng mật khẩu rất dài chỉ cho ra "sai mật khẩu", không phải 500. Riêng đăng ký thì bắt buộc phải validate trước khi `encode` (QĐ4).

*Phương án bị loại:* chuẩn hóa email trong một filter hoặc trong `SecurityConfig`. Như vậy quy tắc BR-003-1 bị tách làm hai nơi, ở hai package khác nhau.

**QĐ3. Bảo đảm email chữ thường ở DB bằng `CHECK` (V2), giữ nguyên unique constraint thường.**
- `V2__users_email_lowercase.sql`: `alter table users add constraint ck_users_email_lowercase check (email = lower(email));`
- Chạy được trên PostgreSQL và H2 (chế độ PostgreSQL). Dữ liệu mẫu đều là chữ thường, nên V2 áp được lên DB dev cũ mà không cần xóa volume.
- Java chuẩn hóa trước khi ghi; CHECK chặn mọi đường ghi bỏ qua service (SQL tay, seed sửa sai). Vì giá trị lưu đã là chữ thường, `uq_users_email` hiện có đủ để chặn trùng không phân biệt hoa thường.

*Phương án bị loại:*
- **Kiểu `citext`, hoặc unique index trên `lower(email)`.** `citext` chỉ có trên PostgreSQL, còn H2 không hỗ trợ index trên biểu thức. Cả hai đều làm hỏng profile `h2`.
- **Không có ràng buộc DB.** Một lệnh `insert` tay với `An@X.com` sẽ tạo ra tài khoản không bao giờ đăng nhập được.

**QĐ4. Form đăng ký: `record RegistrationForm` với Jakarta Validation. Quy tắc mật khẩu là một constraint tự viết, cho đúng một lỗi mỗi lần. So khớp mật khẩu nhập lại là constraint ở mức class.**
- `record RegistrationForm(String fullName, String email, String password, String confirmPassword)`:
  - **chống mass assignment (AC-003-7):** record không có trường `role` hay `id`. Spring bind record qua constructor, nên tham số lạ như `role=ADMIN` bị bỏ qua. Service luôn gán `Role.USER` (BR-003-3);
  - **compact constructor** gọi `strip()` cho `fullName` và `email` (`null` giữ `null`). Phải bỏ khoảng trắng **trước khi validate**, vì `@Email` coi `"  An@Example.COM "` là sai định dạng (AC-003-3). **Không bao giờ strip mật khẩu**: khoảng trắng là một phần của mật khẩu;
  - **`toString()` che hai trường mật khẩu**, như `SeedProperties` đã làm. `toString()` tự sinh của record sẽ in mật khẩu ra bất kỳ log nào nhắc tới form (BR-003-4).
- Annotation và chữ lỗi (đúng spec §6), viết thẳng trong thuộc tính `message`:
  - `fullName`: `@NotBlank` "Vui lòng nhập họ và tên.", `@Size(max = 100)` "Họ và tên tối đa 100 ký tự.";
  - `email`: `@NotBlank` "Vui lòng nhập email.", `@Email` "Email không hợp lệ.", `@Size(max = 255)` "Email tối đa 255 ký tự.";
  - `password`: `@ValidPassword` (xem dưới);
  - mức class: `@PasswordsMatch`, gắn lỗi vào ô `confirmPassword` bằng `addPropertyNode("confirmPassword")`, chữ "Mật khẩu nhập lại không khớp.".
- **`PasswordPolicy.violation(String)`** (class thuần, package-private) trả `Optional<String>`, kiểm **theo thứ tự** và dừng ở lỗi đầu tiên:
  1. số ký tự, đếm bằng `codePointCount`, ngoài khoảng 8–64 (kể cả `null` hoặc rỗng) → "Mật khẩu phải có từ 8 đến 64 ký tự.";
  2. không có ít nhất một `Character.isLetter` **và** một `Character.isDigit` → "Mật khẩu phải có cả chữ và số.";
  3. `getBytes(UTF_8).length > 72` → "Mật khẩu quá dài.".

  `PasswordPolicyValidator` (cho `@ValidPassword`) chỉ gọi hàm này rồi dựng violation với message trả về. Quy tắc nằm ở một chỗ và test đơn vị được.
- `PasswordsMatchValidator`: coi `null` như `""`. Hai ô đều rỗng thì không báo lỗi nhập lại, vì ô mật khẩu đã có lỗi rồi.

*Phương án bị loại:*
- **`@Size` + `@Pattern` cho mật khẩu.** Mật khẩu rỗng sẽ hiện hai lỗi cùng lúc, và thứ tự không cố định vì Hibernate Validator trả về một `Set`.
- **So khớp mật khẩu nhập lại trong service.** Lỗi này chỉ hiện sau khi mọi ô khác đã đúng, nên người dùng phải gửi form hai lần.

**QĐ5. Trùng email: kiểm tra trước, cộng với bắt lỗi unique constraint ngay trong transaction của service.**

`user.UserService.register(RegistrationForm)` (`@Service @Transactional`, package-private) trả `Long` id:
1. `email = EmailNormalizer.normalize(form.email())`;
2. nếu `users.existsByEmail(email)` thì ném `EmailAlreadyRegisteredException` (package-private, kế thừa `RuntimeException`, message tiếng Anh không chứa email);
3. `passwordEncoder.encode(form.password())`; họ tên đã được `strip()` từ form;
4. `users.saveAndFlush(new User(email, hash, fullName, Role.USER))`, bọc trong `try/catch (DataIntegrityViolationException e)`. Nếu lỗi đến từ constraint `uq_users_email` thì ném `EmailAlreadyRegisteredException`, nếu không thì ném lại nguyên lỗi.
   - Cách nhận diện: đi theo chuỗi `getCause()` tới `org.hibernate.exception.ConstraintViolationException` và so `getConstraintName()` với `uq_users_email`, không phân biệt hoa thường.
   - Lỗi ràng buộc khác (ví dụ CHECK của V2) là bug thật, nên phải ra 500 chứ không được báo nhầm thành "email đã đăng ký".

**Vì sao cần cả hai bước:**
- Bước 2 cho thông báo đẹp trong trường hợp thường gặp.
- Nhưng giữa bước 2 và bước 4 có một khoảng hở: ở mức `READ COMMITTED`, hai transaction cùng thấy "chưa có", rồi cùng `INSERT`. Transaction thứ hai bị unique index chặn lại, chờ transaction đầu commit, rồi nhận lỗi `23505`. **Chỉ DB mới chặn được race này (AC-003-5).**

**Vì sao `saveAndFlush` và bắt lỗi trong service:**
- Lỗi phải xảy ra *bên trong* method. Nếu để tới lúc commit (sau khi method đã trả về), exception sẽ bay thẳng ra controller dưới dạng lỗi persistence, và tầng web phải biết về JPA.
- Với `IDENTITY`, `save` đã `INSERT` ngay. Dù vậy `saveAndFlush` nói rõ ý định và không phụ thuộc chiến lược sinh id.
- Lỗi từ repository đã đánh dấu transaction là rollback-only. Ta ném tiếp một `RuntimeException` ra khỏi method `@Transactional`, nên Spring rollback bình thường, không gặp `UnexpectedRollbackException`.

**Đánh đổi đã chấp nhận:** trang đăng ký cho biết email đã có tài khoản. Spec yêu cầu điều này (AC-003-4), và chỉ trang đăng nhập phải giấu thông tin đó.

*Phương án bị loại:* bắt `DataIntegrityViolationException` trong controller. Như vậy là lộ chi tiết persistence lên tầng web, và controller phải hiểu tên constraint.

**QĐ6. `SecurityConfig`: login page riêng, các quy tắc theo thứ tự, trang 403 qua forward.**

Cấu hình đích (B3 viết đúng như sau, giữ nguyên CSP và chuỗi filter H2):

```java
http
    .headers(...CSP giữ nguyên...)
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
        .requestMatchers("/orders", "/orders/**", "/checkout", "/checkout/**").authenticated()
        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()        // vẫn chỉ GET
        .requestMatchers("/register", "/access-denied").permitAll()      // mọi method; CSRF vẫn áp dụng
        .anyRequest().authenticated())
    .formLogin(form -> form
        .loginPage("/login")                       // GET do AuthController, POST do Spring Security
        .usernameParameter("email")
        .defaultSuccessUrl("/")                    // không "always": trang đã lưu được ưu tiên (AC-003-10)
        .failureHandler(new LoginFailureHandler(flashMapManager))
        .permitAll())
    .logout(logout -> logout.logoutSuccessUrl("/?logout").permitAll())
    .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"));
```

- **Quy tắc theo vai trò đứng đầu**, để sau này có ai thêm đường dẫn công khai rộng hơn thì `/admin` vẫn không bị mở. Ghi cả `/admin` lẫn `/admin/**`, để không phụ thuộc vào việc `/**` có khớp đường dẫn gốc hay không.
- **Chưa đăng nhập vào `/admin/**`, `/orders/**`, `/checkout/**`:** chuyển sang `/login` (AC-003-15). Spring Security 7 mặc định redirect **tương đối**, nên test cũ `redirectedUrl("/login")` vẫn đúng.
- **Trang 403 (AC-003-14):**
  - `accessDeniedPage` dùng `AccessDeniedHandlerImpl`: đặt status 403 rồi **forward** sang `/access-denied`.
  - Spring Boot 4.1 đăng ký filter bảo mật cho **mọi dispatcher type** (đã kiểm `SecurityFilterProperties`: mặc định `EnumSet.allOf(DispatcherType.class)`), nên request forward cũng đi qua `AuthorizationFilter`. Vì vậy `/access-denied` phải `permitAll` **cho mọi method**: forward giữ nguyên method gốc (POST `/admin/x` thì forward là POST). Nếu không, lỗi CSRF của khách chưa đăng nhập sẽ biến thành redirect sang `/login` thay vì 403 (AC-003-18).
  - `CsrfFilter` là `OncePerRequestFilter`, nên không chạy lại trên forward.
  - Chọn mở **đúng một đường dẫn**, thay vì `dispatcherTypeMatchers(FORWARD, ERROR).permitAll()` (mở mọi forward), theo nguyên tắc quyền tối thiểu.
- **Saved request (AC-003-10):** dùng `HttpSessionRequestCache` mặc định. Spring Security 6+ redirect về `/orders?continue` (tham số `continue` báo cho filter đọc lại request đã lưu). Test chỉ được assert rằng URL **bắt đầu bằng** `/orders`.
- **Session fixation (AC-003-19):** giữ mặc định `changeSessionId`, không cấu hình gì thêm. CSRF token cũng được thay mới sau khi đăng nhập (`CsrfAuthenticationStrategy`).
- **Đăng xuất (AC-003-13):**
  - Khi CSRF bật, `LogoutFilter` chỉ khớp `POST /logout`. Vì dùng trang đăng nhập riêng, `DefaultLogoutPageGeneratingFilter` không được thêm, nên `GET /logout` không đăng xuất. Với người đã đăng nhập, nó chỉ là một đường dẫn không có handler (404).
  - Session bị hủy rồi chuyển sang `/?logout`.
- **CSRF:** vẫn bật cho mọi form (mặc định). Form phải dùng `th:action` thì Thymeleaf mới tự chèn ô ẩn `_csrf` (qua `CsrfRequestDataValueProcessor`).
- **Bộ phận phụ trợ:** `LoginFailureHandler` là class package-private trong `config` (QĐ7). `SecurityConfig` nhận bean `FlashMapManager` qua tham số của method `@Bean`.

*Phương án bị loại:*
- **`.requestMatchers("/login", "/register").anonymous()`** cho AC-003-17. Người đã đăng nhập sẽ nhận 403, trong khi spec yêu cầu chuyển về `/` (xem QĐ8).
- **`failureForwardUrl`.** Forward giữ lại request POST chứa mật khẩu, và bấm F5 sẽ gửi lại mật khẩu.

**QĐ7. Thông báo một lần, tương thích CSP: query param cho các redirect do Spring Security tạo, flash attribute cho redirect do controller tạo.**

| Tình huống | Ai redirect | Cơ chế | Template đọc |
|---|---|---|---|
| Đăng ký thành công | `AuthController` | `RedirectAttributes.addFlashAttribute("registered", true)` → `redirect:/login` | `${registered}` |
| Đăng nhập sai | `LoginFailureHandler` | Redirect `/login?error`, **cộng** một `FlashMap` chứa `email` | `${param.error}`, `${email}` |
| Đăng xuất | `LogoutFilter` | Redirect `/?logout` | `${param.logout}` trong `product/list` |

- **Vì sao đăng xuất dùng query param:** `LogoutFilter` hủy session *trước khi* redirect, nên một flash attribute lưu trong session cũ sẽ mất. `?logout` là quy ước sẵn của Spring Security, và không lộ gì (ai gõ `/?logout` cũng chỉ thấy một dòng thông báo).
- **Giữ lại email khi đăng nhập sai (AC-003-11):**
  - `LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler` (đích `/login?error`).
  - Trước khi gọi `super`, nó lấy tham số `email`, `strip()`, cắt còn tối đa 255 ký tự, rồi đặt vào `new FlashMap()` với `setTargetRequestPath("/login")`, và gọi `flashMapManager.saveOutputFlashMap(map, request, response)`.
  - Lần GET `/login` kế tiếp, `DispatcherServlet` tự đưa `email` vào model.
  - Đây chính là cơ chế mà `RedirectAttributes` dùng bên trong. Chỉ khác là handler chạy trong filter của Spring Security, bên ngoài Spring MVC, nên phải gọi `FlashMapManager` trực tiếp.
  - Hiện lại chuỗi người dùng vừa gõ không làm lộ email có tồn tại hay không. Không bao giờ hiện message của exception (BR-003-5); không log email hay mật khẩu.
- **CSP:** mọi thông báo là HTML do server render, không cần JS. Fragment `fragments/notice :: notice(kind, text)` dùng chung.

*Phương án bị loại:*
- **Dùng flash cho cả ba trường hợp.** Đăng xuất không làm được (session đã bị hủy).
- **`/login?registered` cho đăng ký.** Ai cũng gõ được URL đó để hiện "Đăng ký thành công", gây hiểu nhầm. Với đăng ký, controller đã có sẵn `RedirectAttributes`.

**QĐ8. `AuthController` (package `user`) chỉ bind, validate, gọi service và chọn view. Người đã đăng nhập bị chuyển về `/`.**
- `GET /login` → `user/login`; `GET /register` → `user/register` với `form` rỗng; `POST /register` → xem chi tiết B3.
- **Cả ba handler** nhận `java.security.Principal principal`. Nếu `principal != null` thì trả `redirect:/` (AC-003-17).
  - Với người chưa đăng nhập, Spring Security trả `getUserPrincipal() == null`, nên khách vãng lai (anonymous) không bị nhầm là đã đăng nhập.
  - Cách này không phụ thuộc kiểu principal, nên test dùng `@WithMockUser` cũng chạy đúng.
- **Trùng email:** bắt `EmailAlreadyRegisteredException` rồi gọi `bindingResult.rejectValue("email", "duplicate", "Email này đã được đăng ký.")`. Đây là việc của tầng web: chuyển lỗi nghiệp vụ thành lỗi gắn với ô nhập.
- **Không điền lại mật khẩu (AC-003-6, BR-003-4):** hai ô mật khẩu trong template **không dùng `th:field` hay `th:value`**, chỉ có `name`/`id`. Lý do: khi có lỗi, `th:field` đọc *giá trị bị từ chối* trong `BindingResult` và in nó ra HTML. Lỗi vẫn hiện được bằng `th:errors="*{password}"`.
- Validation lỗi thì trả lại view với HTTP 200, như cách làm phổ biến của form render phía server.

**QĐ9. Trang 403 và trang `/admin` tạm.**
- **`common.AccessDeniedController`** (package-private): `@RequestMapping("/access-denied")` cho **mọi method** (QĐ6), có `@ResponseStatus(FORBIDDEN)`, trả view `error/403`. Mở trực tiếp `/access-denied` cũng nhận 403, nhất quán. Đặt trong `common` vì đây là xử lý lỗi dùng chung, đúng như `common/package-info.java` mô tả.
- **Đặt tên template `error/403.html`.** Trang lỗi mặc định của Spring Boot (`BasicErrorController`) cũng tự dùng template này cho lỗi 403, và GĐ7 chỉ cần thêm `error/404.html`, `error/500.html` cạnh nó.
- **`admin.AdminController`** (package-private): `@GetMapping("/admin")` trả `admin/index`, không có model. `admin` được phép phụ thuộc mọi package (không ai phụ thuộc `admin`), và ở đây nó không cần service nào.
- **Trang admin tạm dùng layout storefront.** Hướng thiết kế app-screen cho `/admin/**` sẽ được vẽ ở GĐ6, khi có dashboard thật.
  - Vẽ một hướng thiết kế chỉ cho một dòng chữ sẽ khóa sớm các lựa chọn mà GĐ6 cần, và vi phạm quy tắc "vẽ một lần cho mỗi khu vực, rồi dùng lại".
  - Trang này bị thay hẳn ở GĐ6.

**QĐ10. Giao diện: dùng lại hướng "The specimen catalogue", không vẽ lại. Template tuân thủ CSP.**
- `frontend-developer` dùng skill `design-taste-frontend` ở chế độ **dùng lại hướng đã vẽ**:
  - đọc token và comment đầu `app.css`;
  - **không** chạy `draw.mjs`;
  - chạy gate `lint.mjs` / `inspect.mjs` cho các trang mới.
- **Header** (`fragments/layout.html`) khai báo `xmlns:sec="http://www.thymeleaf.org/extras/spring-security"`:
  - khi chưa đăng nhập (`sec:authorize="!isAuthenticated()"`): link `Đăng nhập`, `Đăng ký`;
  - khi đã đăng nhập (`sec:authorize="isAuthenticated()"`): `th:text="|Xin chào, ${#authentication.principal.fullName}|"`, luôn escape (AC-003-21); link `Quản trị` chỉ khi `sec:authorize="hasRole('ADMIN')"`; nút `Đăng xuất` là `<form th:action="@{/logout}" method="post">`.
  - Khách vãng lai không thấy form nào ở header, nên duyệt sản phẩm không tạo session hay CSRF token.
- **Form** có `novalidate` và không đặt `required`, `maxlength`, `pattern` trên ô nhập:
  - server là nơi quyết định, và chữ lỗi phải đúng spec §6 thay vì thông báo của trình duyệt (theo ngôn ngữ của trình duyệt);
  - nhờ vậy checklist kiểm được validation phía server ngay trên trình duyệt;
  - vẫn giữ `type="email"`, `autocomplete` (`name`, `email`/`username`, `new-password`/`current-password`).
- **Họ tên dài 100 ký tự liền nhau** không được làm header tràn ngang ở 375px. Hai cách được chấp nhận: cho xuống dòng bằng `overflow-wrap: anywhere`, hoặc cắt một dòng bằng `text-overflow: ellipsis` (khi đó có thể thêm `th:title` lặp lại họ tên, không bắt buộc). F1 chọn, miễn đạt checklist mục 19.

**QĐ11. Chiến lược test.**
- **Phiên đăng nhập trong test slice phải dùng `CurrentUser` thật.** Header đọc `principal.fullName`, mà `@WithMockUser` tạo principal kiểu `User` của Spring, không có thuộc tính này, nên trang sẽ lỗi khi render.
  - TW3 tạo fixture `src/test/java/com/minishop/user/TestUsers.java` (public): `CurrentUser user()`, `admin()`, `withName(String, Role)`. Test gọi `.with(user(TestUsers.admin()))`.
  - Test **không render view** (`SecurityConfigTest`) thì vẫn dùng `@WithMockUser(roles = …)` được.
- **Security 7 có thể thêm authority loại "factor"** (ví dụ `FACTOR_PASSWORD`) sau khi đăng nhập bằng mật khẩu. Không assert danh sách authority chính xác; chỉ assert nó **chứa** `ROLE_USER` / `ROLE_ADMIN`.
- **`SecurityMockMvcRequestBuilders.formLogin("/login").userParameter("email")`**: phải khai báo, vì đã đổi tên tham số.
- **Test cần PostgreSQL** (race, CHECK của V2, đăng nhập bằng dữ liệu mẫu) dùng `@Import(TestcontainersConfiguration.class)` + `@Testcontainers(disabledWithoutDocker = true)`.
  - Email test dùng đuôi `@reg.test` và được xóa ở `@AfterEach`. **Không** xóa hay sửa 2 tài khoản mẫu.
- **Race (AC-003-5) có hai test:**
  - **chắc chắn đi qua nhánh bắt lỗi:** `@MockitoSpyBean UserRepository`, `doReturn(false).when(users).existsByEmail(anyString())`. Đăng ký lần hai cùng email phải ném `EmailAlreadyRegisteredException`, và DB chỉ có 1 dòng;
  - **đồng thời thật:** 2 luồng, `CountDownLatch` cho cùng xuất phát, lặp 20 vòng với 20 email khác nhau. Mỗi vòng: đúng 1 thành công, 1 `EmailAlreadyRegisteredException`, không có exception loại khác, DB có đúng 1 dòng.
  - Test class **không** mang `@Transactional`, để mỗi luồng có transaction thật của riêng nó.
- **Không đổi `TestcontainersConfiguration`.** Spy làm thay đổi khóa cache của context, nên `RegistrationIntegrationTest` có container riêng. Chấp nhận được.

**QĐ12. Một ADR mới: ADR-0007 "Xác thực bằng form login trên session, principal riêng, email chuẩn hóa chữ thường".** Nội dung gồm:
- QĐ1, QĐ2, QĐ3, QĐ5 (đây là bất biến miền mới: email luôn chữ thường, kể cả ở DB);
- không khóa tài khoản trong MVP, rủi ro ghi cho GĐ8.

`CLAUDE.md` yêu cầu ADR cho mọi thay đổi bất biến miền. ADR-0003 đã chọn "session + CSRF", nên ADR-0007 chỉ bổ sung, không thay thế.

## 3. Hợp đồng code và view (chốt trước để backend, frontend, test làm song song)

Nếu cần thêm thuộc tính hay đổi tên, agent dừng lại và báo phiên chính; không tự đặt tên.

```java
// user
public final class CurrentUser implements UserDetails, CredentialsContainer {
    public CurrentUser(Long id, String email, String passwordHash, String fullName, Role role)
    public Long getId(); public String getEmail(); public String getFullName(); public Role getRole();
    // getUsername() = email; getPassword() = hash (null sau eraseCredentials); getAuthorities() = [ROLE_<role>]
}
final class EmailNormalizer { static String normalize(String raw); }          // strip + lowercase(ROOT); null -> ""
final class ShopUserDetailsService implements UserDetailsService { ... }       // @Service, @Transactional(readOnly = true)
record RegistrationForm(String fullName, String email, String password, String confirmPassword) {
    static RegistrationForm empty();                                           // 4 chuỗi rỗng, cho GET /register
}
final class PasswordPolicy { static Optional<String> violation(String password); }
@interface ValidPassword {} / final class PasswordPolicyValidator
@interface PasswordsMatch {} / final class PasswordsMatchValidator           // ConstraintValidator<PasswordsMatch, RegistrationForm>
class EmailAlreadyRegisteredException extends RuntimeException {}
class UserService { Long register(RegistrationForm form); }                   // @Service @Transactional
class AuthController { ... }                                                   // @Controller
public interface UserRepository { Optional<User> findByEmail(String email); boolean existsByEmail(String email); }
// config
final class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler { LoginFailureHandler(FlashMapManager m); }
// common
class AccessDeniedController { ... }                                          // /access-denied, mọi method, 403
// admin
class AdminController { ... }                                                 // GET /admin
```

| View | URL | Status | Model / tham số |
|---|---|---|---|
| `user/login` | `GET /login` | 200 | `registered` (Boolean, flash, có thể không có), `email` (String, flash, có thể không có), `param.error` |
| `user/register` | `GET /register`, `POST /register` khi có lỗi | 200 | `form` (`RegistrationForm`) + `BindingResult` |
| `error/403` | forward từ `accessDeniedPage`, hoặc `/access-denied` | 403 | (không có) |
| `admin/index` | `GET /admin` | 200 | (không có) |
| `product/list` (đã có) | `GET /?logout` | 200 | thêm `param.logout` |

Quy ước markup để test và verifier dựa vào (chữ đúng spec §6):

- **Tiêu đề tab:** `Đăng ký | Mini Shop`, `Đăng nhập | Mini Shop`, `Không có quyền truy cập | Mini Shop`, `Quản trị | Mini Shop`.
- **`user/login`:**
  - `<form method="post" th:action="@{/login}" novalidate>`;
  - ô `id="email" name="email" type="email" autocomplete="username" th:value="${email}"` có `<label for="email">Email</label>`;
  - ô `id="password" name="password" type="password" autocomplete="current-password"`, **không có value**, nhãn `Mật khẩu`;
  - nút `Đăng nhập`;
  - link `th:href="@{/register}"` với chữ `Chưa có tài khoản? Đăng ký`;
  - khi có `param.error`: `Email hoặc mật khẩu không đúng.` với `role="alert"`;
  - khi có `registered`: `Đăng ký thành công. Vui lòng đăng nhập.` với `role="status"`;
  - `h1`: `Đăng nhập`.
- **`user/register`:**
  - `<form method="post" th:action="@{/register}" th:object="${form}" novalidate>`;
  - các ô:
    - `fullName`: `th:field`, `autocomplete="name"`, nhãn `Họ và tên`;
    - `email`: `th:field`, `type="email"`, `autocomplete="email"`, nhãn `Email`;
    - `password`: `name`/`id` thôi, `type="password"`, `autocomplete="new-password"`, nhãn `Mật khẩu`, gợi ý `Ít nhất 8 ký tự, gồm chữ và số.` qua `aria-describedby`;
    - `confirmPassword`: như trên, nhãn `Nhập lại mật khẩu`;
  - lỗi của mỗi ô hiện ngay dưới ô đó bằng `th:errors="*{…}"`, phần tử lỗi có `id` để ô nhập trỏ tới bằng `aria-describedby`. Ô lỗi có `aria-invalid="true"` (dùng `th:aria-invalid`) và class `is-invalid`;
  - nút `Đăng ký`; link `th:href="@{/login}"` với chữ `Đã có tài khoản? Đăng nhập`;
  - `h1`: `Đăng ký`.
- **`error/403`:** `h1` là `Bạn không có quyền truy cập trang này.`; link `th:href="@{/}"` với chữ `Về trang chủ`.
- **`admin/index`:** `h1` là `Trang quản trị`; đoạn `Khu quản trị sẽ được xây dựng ở giai đoạn sau.`.
- **Header:** như QĐ10.
  - Link `Đăng nhập` → `/login`, `Đăng ký` → `/register`, `Quản trị` → `/admin`.
  - Nút `<button type="submit">Đăng xuất</button>` nằm trong form POST `/logout`.
- **`product/list`:** khi `${param.logout != null}` thì hiện `Bạn đã đăng xuất.` (`role="status"`) phía trên danh sách.
- **Cấm** (như GĐ2): `<script>` inline, `<style>`, `style=`/`th:style`, `on…=`/`th:on…`, `javascript:`, `th:utext`. Mọi form POST dùng `th:action`, không dùng `action`.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static tính từ `src/main/resources/`; test tính từ `src/test/java/com/minishop/`.

| # | Task | Agent | File | AC / BR | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| B1 | V2 và phía đăng nhập của `user`: chuẩn hóa email, `CurrentUser`, `UserDetailsService` | `backend-developer` | `db/migration/V2__users_email_lowercase.sql` (mới); `user/EmailNormalizer`, `user/CurrentUser`, `user/ShopUserDetailsService` (mới); `user/package-info.java` | 003-3, 9, 12, 20; BR-003-1, 4 | – | F1 | Xem chi tiết B1 |
| S | Đối chiếu entity với migration | `schema-guardian` | (chỉ đọc) | BR-003-1 | B1 | B2, F1, TW2 | Không có sai lệch giữa `User` và V1+V2; V2 tương thích H2 |
| B2 | Đăng ký phía service: form, quy tắc mật khẩu, `UserService` | `backend-developer` | `user/RegistrationForm`, `user/PasswordPolicy`, `user/ValidPassword`, `user/PasswordPolicyValidator`, `user/PasswordsMatch`, `user/PasswordsMatchValidator`, `user/EmailAlreadyRegisteredException`, `user/UserService` (mới); `user/UserRepository` (sửa) | 003-2, 3, 4, 5, 6, 7; BR-003-1–4 | B1 | S, F1 | Xem chi tiết B2 |
| TW2 | Test đơn vị của `user` | `test-writer` | `user/RegistrationFormValidationTest`, `user/UserServiceTest`, `user/ShopUserDetailsServiceTest` (mới) | 003-2, 3, 4, 5, 6, 12; BR-003-1–4 | B2 | B3, F1, S | Xem chi tiết TW2 |
| B3 | Tầng web và bảo mật | `backend-developer` | `config/SecurityConfig` (sửa), `config/LoginFailureHandler` (mới); `user/AuthController` (mới); `common/AccessDeniedController` (mới), `common/package-info.java`; `admin/AdminController` (mới) | 003-1, 2, 4, 7, 8, 10, 11, 13–19; BR-003-5 | B2 | TW2, F1 | Xem chi tiết B3 |
| F1 | Template, header, CSS; gate của skill | `frontend-developer` | `templates/fragments/layout.html` (sửa), `templates/fragments/notice.html`, `templates/user/login.html`, `templates/user/register.html`, `templates/error/403.html`, `templates/admin/index.html` (mới); `templates/product/list.html` (sửa); `static/css/app.css` | 003-1, 2, 4, 6, 8, 9, 11, 13, 14, 16, 21, 22 | Dựng markup ngay từ đầu theo mục 3; **gate chạy sau B3** | B1, B2, B3, TW2 | Xem chi tiết F1 |
| TW1 | Ma trận bảo mật và kiến trúc | `test-writer` | `config/SecurityConfigTest` (sửa), `ArchitectureTest` (sửa) | 003-14, 15, 16, 18; 001-6, 001-16 (giữ xanh) | B3 | F1, TW3, TW4 | Xem chi tiết TW1 |
| TW3 | Test web slice, fixture, quét CSP | `test-writer` | `user/TestUsers` (mới, fixture), `user/AuthControllerTest`, `common/AccessDeniedTest`, `admin/AdminControllerTest` (mới); `TemplateCspComplianceTest` (sửa) | 003-1, 2, 4, 6, 7, 8, 11, 14, 16, 17, 21, 22 | B3, F1 | TW1, TW4 | Xem chi tiết TW3 |
| TW4 | Test tích hợp trên PostgreSQL và smoke H2 | `test-writer` | `user/RegistrationIntegrationTest`, `user/LoginFlowTest` (mới); `SchemaMigrationTest`, `H2ProfileSmokeTest` (sửa) | 003-2, 3, 5, 7, 9–13, 18–21; BR-003-1 | B3, F1 | TW1, TW3 | Xem chi tiết TW4 |
| D | Tài liệu | phiên chính | `docs/adr/0007-session-form-login-current-user.md` (mới), `docs/adr/README.md`, `CLAUDE.md`, `README.md`, `CHANGELOG.md`, `docs/specs/003-authentication.md` (trạng thái, lịch sử), `docs/specs/README.md` | – | B3, F1 | TW1, TW3, TW4 | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | B1–B3, F1, TW1–TW4, D | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc) | 003-1 đến 003-22 | R | – | Checklist mục 5 đạt |

**Thứ tự chạy:**
- **Đợt 1:** B1 và F1 (F1 dựng markup và CSS theo mục 3, chưa chạy gate).
- **Đợt 2:** B2 (sau B1), S (sau B1).
- **Đợt 3:** B3 (sau B2), TW2 (sau B2).
- **Đợt 4:** F1 chạy gate (sau B3), TW1 (sau B3).
- **Đợt 5:** TW3 và TW4 (sau B3 và F1), D.
- **Cuối cùng:** R, rồi V.

**Đường găng:** B1 → B2 → B3 → F1 (gate) → TW4 → R → V. TW4 dài nhất trong nhóm test vì có test race và khởi động hai context Testcontainers.

**Build xanh sau mỗi task:**
- Sau B1: chỉ thêm bean `UserDetailsService`. Form login mặc định vẫn chạy, nhưng giờ dùng người dùng trong DB; V2 áp được trên H2 (`H2ProfileSmokeTest`) và PostgreSQL (`SchemaMigrationTest`, `MiniShopApplicationTests`).
- Sau B2: chỉ thêm class, chưa có ai gọi.
- Sau B3: `SecurityConfigTest` hiện có vẫn đúng (redirect `/login`, CSP, GET-only). Nếu F1 chưa xong thì `GET /login` trả 500 khi chạy app (thiếu template). Đây là trạng thái trung gian có chủ đích như GĐ2; không push giữa các đợt.
- F1 có thể xong trước B3: template chưa ai render thì không ảnh hưởng test. `ProductControllerTest` render header khi chưa đăng nhập, nên phần `sec:authorize` phải đúng cú pháp ngay từ đầu.

### Chi tiết B1 (`backend-developer`)

- **`V2__users_email_lowercase.sql`:** comment đầu file ghi "BR-003-1, spec 003 §7", rồi một lệnh `alter table users add constraint ck_users_email_lowercase check (email = lower(email));`. Không đổi gì khác. Không sửa V1.
- **`EmailNormalizer`, `CurrentUser`, `ShopUserDetailsService`:** theo QĐ1, QĐ2 và mục 3.
  - `CurrentUser` có Javadoc giải thích vì sao không phải record và vì sao public.
  - Mapping từ `User` sang `CurrentUser` nằm trong `ShopUserDetailsService` (private method), để `CurrentUser` không tham chiếu entity.
- **`user/package-info.java`:** cập nhật mô tả: người dùng, vai trò, đăng ký, đăng nhập (`UserDetailsService`), `CurrentUser` là kiểu public cho các tính năng khác; không phụ thuộc tính năng nào.
- **Không sửa `User`, `DataInitializer`, `SecurityConfig`** ở task này.

**Xong khi:**
- `./mvnw verify` xanh, và `ArchitectureTest` vẫn đạt;
- chạy app profile `h2`: log **không** có `Using generated security password`; đăng nhập ở trang mặc định của Spring bằng `USER@MINISHOP.LOCAL` / mật khẩu demo thì thành công;
- báo cáo liệt kê chữ ký thực tế so với mục 3.

### Chi tiết B2 (`backend-developer`)

- **`UserRepository`:** thêm `boolean existsByEmail(String email);`.
- **`RegistrationForm`, `PasswordPolicy`, hai annotation và hai validator:** theo QĐ4 và mục 3.
  - Annotation có đủ `message`, `groups`, `payload` theo chuẩn Jakarta Validation.
  - `@ValidPassword` có `@Target(FIELD, RECORD_COMPONENT, …)` sao cho áp được lên thành phần của record. **Kiểm bằng test nhỏ khi làm**, vì annotation trên record component chỉ lan sang field/accessor nếu `@Target` cho phép.
- **`UserService`, `EmailAlreadyRegisteredException`:** theo QĐ5.
  - Không log form, email hay mật khẩu ở mức `info` trở lên.
  - Có thể log `debug` "Registration rejected: duplicate email" (không kèm email).

**Xong khi:**
- `./mvnw verify` xanh;
- báo cáo nêu rõ:
  - cách nhận diện constraint trong `catch` (lấy tên constraint từ đâu);
  - thứ tự kiểm tra của `PasswordPolicy`.

### Chi tiết B3 (`backend-developer`)

- **`SecurityConfig`:** đúng cấu hình ở QĐ6.
  - Method `@Bean` nhận thêm tham số `FlashMapManager flashMapManager`.
  - Cập nhật Javadoc của class: GĐ3 thêm trang đăng nhập riêng, phân quyền, trang 403; đường dẫn công khai vẫn chỉ GET.
  - Giữ nguyên hằng `CONTENT_SECURITY_POLICY` và bean `h2ConsoleSecurityFilterChain`.
- **`LoginFailureHandler`:** theo QĐ7. Không log tham số `password`; không đưa message của exception vào flash.
- **`AuthController`** (package-private, constructor injection `UserService`):
  ```java
  @GetMapping("/login")    String login(Principal principal)                       // != null -> "redirect:/"; else "user/login"
  @GetMapping("/register") String registerForm(Principal principal, Model model)  // model "form" = RegistrationForm.empty()
  @PostMapping("/register")
  String register(Principal principal, @Valid @ModelAttribute("form") RegistrationForm form,
                  BindingResult result, RedirectAttributes redirect)
  // 1. principal != null -> "redirect:/"   (không gọi service)
  // 2. result.hasErrors() -> "user/register"
  // 3. try userService.register(form) catch EmailAlreadyRegisteredException -> rejectValue(...) -> "user/register"
  // 4. redirect.addFlashAttribute("registered", true); return "redirect:/login"
  ```
- **`AccessDeniedController`, `AdminController`:** theo QĐ9.
- **`common/package-info.java`:** thêm "trang 403 dùng chung".
- **Không sửa** `admin/package-info.java` (mô tả hiện tại vẫn đúng).

**Xong khi:**
- `./mvnw verify` xanh;
- app profile `h2` khởi động được; bằng `curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n"`:
  - `/admin` khi chưa đăng nhập → `302 …/login`;
  - `/products` → `200`;
  - `POST /register` không có CSRF token → `403`;
- báo cáo xác nhận view name và model attribute khớp mục 3.

### Chi tiết F1 (`frontend-developer`)

- **Đọc trước:**
  - spec 003 §4 và §6 (bảng "Chữ hiển thị chính xác");
  - mục 3 và QĐ7, QĐ8, QĐ10 của plan này;
  - mục "UI design skill" trong `CLAUDE.md`;
  - comment đầu `static/css/app.css` (hướng "The specimen catalogue").
- **Skill `design-taste-frontend`, chế độ dùng lại hướng đã vẽ:**
  - **không** chạy `draw.mjs`, không đổi token, font hay palette;
  - **không** thêm "one live thing", scroll scene hay chữ tự nghĩ. Chữ mới duy nhất được thêm là nhãn ẩn cho khả năng truy cập, nếu cần;
  - trang `/admin` tạm dùng layout storefront (QĐ9).
- **Dựng các file theo mục 3:**
  - `fragments/notice.html`: fragment `notice(kind, text)`, với `kind` là `success`, `error` hoặc `info`. `error` có `role="alert"`, hai loại còn lại có `role="status"`;
  - header: theo QĐ10;
  - **form** dùng component có sẵn trong `app.css` (ô nhập có viền nhìn thấy được, nút chính màu nhấn); một cột, rộng tối đa khoảng 28rem trên desktop; lỗi màu `--ms-danger` kèm chữ (không chỉ dựa vào màu);
  - CSS mới viết ở **cuối** `app.css`, trong một khối có comment `/* GĐ3: tài khoản (form, header, thông báo) */`.
- **Gate (chạy sau khi B3 xong):**
  - chạy app với `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`;
  - `node <skill>/scripts/lint.mjs src/main/resources/templates src/main/resources/static/css`;
  - `node <skill>/scripts/inspect.mjs` cho `http://localhost:8080/login`, `/register`, `/access-denied` (mở trực tiếp được khi chưa đăng nhập, render đúng trang 403) và `/products`. Lưu kết quả vào `.taste-skill/inspect/`, tự xem mọi ảnh chụp, tối đa 3 vòng sửa;
  - **kiểm tay** (inspector không đăng nhập được):
    - gửi form đăng ký rỗng để thấy lỗi;
    - đăng ký một tài khoản có họ tên 100 ký tự liền nhau, đăng nhập, rồi xem header ở 375px trên `/products` và `/products/{id}`;
    - đăng nhập `admin@minishop.local` để xem link `Quản trị` và trang `/admin`;
    - DevTools Console: không có lỗi CSP;
  - tắt app khi xong.

**Xong khi:**
- `inspect.mjs` không còn `FAIL`, trừ các mục được ghi là "sai lệch có chủ đích" (mỗi mục một câu lý do);
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả nào;
- `grep -rn 'action="' src/main/resources/templates` không có form POST nào dùng `action` thay vì `th:action`;
- hai ô mật khẩu không có `th:field` hay `th:value`;
- báo cáo liệt kê template, model attribute đã dùng, và critique của vòng cuối.

### Chi tiết TW1 (`test-writer`)

**`SecurityConfigTest`:**
- giữ `@WebMvcTest(useDefaultFilters = false)` + `@Import(SecurityConfig.class)` và mọi test hiện có;
- thêm các test dưới đây. Slice không có controller, nên chỉ assert phần do chuỗi filter quyết định (status 403, redirect, forward), không assert nội dung trang.

| Kiểm | AC |
|---|---|
| Chưa đăng nhập: `GET /admin`, `/admin/products`, `/orders/1`, `/checkout`, `/checkout/x` đều redirect `/login` | 003-15 |
| `@WithMockUser(roles = "USER")`: `GET /admin` và `/admin/x` → **403** và `forwardedUrl("/access-denied")`; `POST /admin/x` có CSRF → 403 | 003-14 |
| `@WithMockUser(roles = "ADMIN")`: `GET /admin` không phải 403 và không redirect (slice không có controller nên là 404) | 003-16 |
| Chưa đăng nhập: `GET /login`, `GET /register`, `POST /register` (có CSRF), `GET /access-denied` không bị redirect sang `/login` | spec §6 |
| Không có CSRF token: `POST /register`, `POST /login` → 403; `POST /logout` khi đã đăng nhập (`@WithMockUser`) → 403 | 003-18 |
| `POST /products/1` có CSRF, chưa đăng nhập → vẫn redirect `/login` (hồi quy GET-only, test đã có) | 001-6 |

**`ArchitectureTest`:** thêm rule `admin_doesNotUseRepositories`: class trong `com.minishop.admin..` không phụ thuộc lớp gán được cho `Repository`. Đây là quy tắc "admin chỉ là tầng web" trong `CLAUDE.md`; nay `admin` đã có class đầu tiên nên rule có ý nghĩa.

**Xong khi:** `./mvnw test -Dtest='SecurityConfigTest,ArchitectureTest'` xanh.

### Chi tiết TW2 (`test-writer`)

**`RegistrationFormValidationTest`** (Jakarta `Validator` thuần, không Spring). Mỗi case assert **đúng chữ** và **đúng ô**:

| Đầu vào | Mong đợi | AC |
|---|---|---|
| `fullName` là `""` / `"   "` / 101 ký tự | `Vui lòng nhập họ và tên.` / như trên / `Họ và tên tối đa 100 ký tự.`; đúng 100 ký tự thì hợp lệ | 003-6 |
| `email` là `""` / `"abc"` / 256 ký tự hợp lệ về dạng | `Vui lòng nhập email.` / `Email không hợp lệ.` / có `Email tối đa 255 ký tự.` | 003-6 |
| `email` là `"  An@Example.COM "` | Hợp lệ; `form.email()` là `"An@Example.COM"` (đã strip, chưa hạ chữ) | 003-3 |
| Mật khẩu `"abcdef1"` (7) / `"abcdefg1"` (8) / 64 ký tự / 65 ký tự / `null` | lỗi độ dài / hợp lệ / hợp lệ / lỗi độ dài / lỗi độ dài; **mỗi case đúng một lỗi** trên `password` | BR-003-2 |
| `"12345678"` / `"abcdefgh"` | `Mật khẩu phải có cả chữ và số.` | BR-003-2 |
| `"ệ".repeat(23) + "a12"` (26 ký tự, 72 byte) / `"ệ".repeat(25) + "1"` (26 ký tự, 76 byte) | hợp lệ / `Mật khẩu quá dài.` | BR-003-2 |
| Mật khẩu có khoảng trắng hai đầu `" abc12345 "` | Hợp lệ và **không bị strip** | BR-003-4 |
| `confirmPassword` khác `password` | `Mật khẩu nhập lại không khớp.` gắn với ô `confirmPassword` | 003-6 |
| `form.toString()` | Không chứa chuỗi mật khẩu | BR-003-4 |
| Tên các record component | Đúng `fullName, email, password, confirmPassword` (không có `role`) | 003-7 |

**`UserServiceTest`** (Mockito; dùng `PasswordEncoderFactories.createDelegatingPasswordEncoder()` thật):

| Kiểm | AC |
|---|---|
| Email `"  An@Example.COM "` → `existsByEmail("an@example.com")`, `User` được lưu có email `an@example.com` | 003-3, BR-003-1 |
| `User` lưu có `Role.USER`, hash bắt đầu `{bcrypt}` và `encoder.matches(raw, hash)` | 003-2, BR-003-3 |
| `existsByEmail` trả `true` → ném `EmailAlreadyRegisteredException`, không gọi `saveAndFlush` | 003-4 |
| `saveAndFlush` ném `DataIntegrityViolationException` có nguyên nhân `org.hibernate.exception.ConstraintViolationException(…, "uq_users_email")` → `EmailAlreadyRegisteredException` | 003-5 |
| Như trên nhưng tên constraint khác (`ck_users_email_lowercase`) → ném lại đúng `DataIntegrityViolationException` | QĐ5 |

**`ShopUserDetailsServiceTest`** (Mockito):

| Kiểm | AC |
|---|---|
| `loadUserByUsername("  USER@MiniShop.LOCAL ")` → `findByEmail("user@minishop.local")` | 003-12 |
| Không tìm thấy → `UsernameNotFoundException`, message không chứa email | BR-003-5 |
| Tìm thấy → `CurrentUser` có id, email, họ tên, vai trò; authority chứa `ROLE_ADMIN` khi là admin | 003-9, 003-16 |
| `eraseCredentials()` → `getPassword()` là `null`; `toString()` không chứa hash | BR-003-4 |

**Xong khi:** `./mvnw test -Dtest='RegistrationFormValidationTest,UserServiceTest,ShopUserDetailsServiceTest'` xanh.

### Chi tiết TW3 (`test-writer`)

**`user/TestUsers`:** fixture public theo QĐ11:
- `user()` cho ra "Khách hàng mẫu", `USER`;
- `admin()` cho ra "Quản trị viên", `ADMIN`;
- `withName(String fullName, Role role)`;
- hash giả `{noop}x`.

**`AuthControllerTest`** (`@WebMvcTest(AuthController.class)` + `@Import(SecurityConfig.class)` + `@MockitoBean UserService`):

| Kiểm | AC |
|---|---|
| `GET /register`: 200, view `user/register`, title `Đăng ký \| Mini Shop`, 4 nhãn, nút `Đăng ký`, link `Đã có tài khoản? Đăng nhập` tới `/login`, gợi ý mật khẩu | 003-1 |
| `GET /login`: 200, title `Đăng nhập \| Mini Shop`, nhãn `Email`, `Mật khẩu`, nút `Đăng nhập`, link `Chưa có tài khoản? Đăng ký` tới `/register`; header khi chưa đăng nhập có `Đăng nhập`, `Đăng ký`, không có `Quản trị` và `Đăng xuất` | 003-8 |
| `GET /login?error` kèm `.flashAttr("email", "a@b.vn")`: có `Email hoặc mật khẩu không đúng.` và `value="a@b.vn"` | 003-11 |
| `GET /login` kèm `.flashAttr("registered", true)`: có `Đăng ký thành công. Vui lòng đăng nhập.` | 003-2 |
| `POST /register` hợp lệ (có CSRF): redirect `/login`, `flash().attribute("registered", true)`, service được gọi một lần | 003-2 |
| Service ném `EmailAlreadyRegisteredException`: 200, `model().attributeHasFieldErrors("form", "email")`, có `Email này đã được đăng ký.`, họ tên và email vẫn còn trong ô, **không** có chuỗi mật khẩu đã gửi | 003-4 |
| Dữ liệu sai (họ tên rỗng, email sai, mật khẩu `abc`, nhập lại khác; mật khẩu dùng chuỗi dễ nhận như `Zx9SecretPw`): 200, mỗi ô có đúng chữ lỗi, HTML **không** chứa mật khẩu đã gửi, service không được gọi | 003-6, BR-003-4 |
| `POST /register` có thêm `role=ADMIN`, `id=1`: `ArgumentCaptor<RegistrationForm>` nhận đúng 4 trường, không lỗi bind | 003-7 |
| Đã đăng nhập (`.with(user(TestUsers.user()))`): `GET /login`, `GET /register`, `POST /register` → redirect `/`; service không được gọi | 003-17 |

**`common/AccessDeniedTest`** (`@WebMvcTest(AccessDeniedController.class)` + `@Import(SecurityConfig.class)`):

| Kiểm | AC |
|---|---|
| `GET /access-denied` với `TestUsers.user()`: **403**, title `Không có quyền truy cập \| Mini Shop`, `Bạn không có quyền truy cập trang này.`, link `Về trang chủ` tới `/`, header có `Xin chào, Khách hàng mẫu`, **không** có link `Quản trị` | 003-14, 003-16 |
| `POST /access-denied` có CSRF → 403 (vì forward giữ method POST) | QĐ6 |
| Chưa đăng nhập `GET /access-denied` → 403 (không redirect) | QĐ6 |

**`admin/AdminControllerTest`** (`@WebMvcTest(AdminController.class)` + `@Import(SecurityConfig.class)`):

| Kiểm | AC |
|---|---|
| `TestUsers.admin()`: 200, view `admin/index`, title `Quản trị \| Mini Shop`, `Trang quản trị`, `Khu quản trị sẽ được xây dựng ở giai đoạn sau.`, header có `Xin chào, Quản trị viên`, link `Quản trị` tới `/admin`, nút `Đăng xuất` trong form POST `/logout` có ô `_csrf` | 003-16, 003-9 |
| `TestUsers.withName("<script>alert(1)</script>", ADMIN)`: HTML chứa `Xin chào, &lt;script&gt;alert(1)&lt;/script&gt;` và không chứa chuỗi thô | 003-21 |
| `TestUsers.user()` → 403 và forward `/access-denied` | 003-14 |

**`TemplateCspComplianceTest`:** thêm `login.html`, `register.html`, `403.html`, `index.html`, `notice.html` vào danh sách file bắt buộc phải quét được (chống "xanh giả", AC-003-22).

**Xong khi:** các class trên xanh. Ghi chú trong báo cáo các chỗ khác cột "Kiểm bằng" của spec:
- AC-003-16 (link header) và AC-003-21 (bản slice) nằm ở `AdminControllerTest`, vì `AuthController` chuyển người đã đăng nhập đi nơi khác, nên không có trang nào để render header khi đã đăng nhập;
- AC-003-21 bản đầu-cuối nằm ở TW4.

### Chi tiết TW4 (`test-writer`)

**`user/RegistrationIntegrationTest`:**
- `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers theo QĐ11;
- `@MockitoSpyBean UserRepository`;
- `JdbcTemplate` để kiểm DB;
- `@AfterEach` xóa `users` có email kết thúc bằng `@reg.test`.

| Kiểm | AC |
|---|---|
| `POST /register` hợp lệ: redirect `/login`; DB có 1 dòng, `role = 'USER'`, `password_hash like '{bcrypt}%'` | 003-2 |
| Email `"  An@Reg.TEST "`: DB lưu `an@reg.test`; rồi `formLogin` với `an@reg.test` → `authenticated()` | 003-3 |
| Gửi kèm `role=ADMIN`: DB có `role = 'USER'` | 003-7 |
| Đăng ký lần hai bằng `AN@reg.test`: 200, `Email này đã được đăng ký.`, DB vẫn 1 dòng | 003-4 |
| Race chắc chắn (spy, QĐ11) và race đồng thời 2 luồng × 20 vòng, gọi thẳng `UserService` | 003-5 |
| `POST /register` không có CSRF: 403, số dòng `users` không đổi | 003-18 |
| Họ tên `<script>alert(1)</script>`: đăng ký, đăng nhập, `GET /` cùng session: có bản đã escape, không có chuỗi thô | 003-21 |

**`user/LoginFlowTest`:**
- `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers;
- mật khẩu demo lấy bằng `@Value("${app.seed.user-password}")`;
- **không** dùng `@MockitoSpyBean`, để dùng chung context với `MiniShopApplicationTests`.

| Kiểm | AC |
|---|---|
| `formLogin("/login").userParameter("email")` với `user@minishop.local`: redirect `/`, authenticated, authority chứa `ROLE_USER`; `GET /` cùng session có `Xin chào, Khách hàng mẫu` và `Đăng xuất` | 003-9 |
| `GET /orders` → `/login`; đăng nhập cùng session → redirect URL bắt đầu bằng `/orders` | 003-10 |
| Sai mật khẩu và email không tồn tại: cả hai redirect `/login?error`; `GET /login?error` cùng session có `Email hoặc mật khẩu không đúng.` và `value="<email vừa nhập>"`; hai trang giống hệt nhau sau khi bỏ phần email và token CSRF | 003-11, BR-003-5 |
| Mật khẩu 100 ký tự (vượt 72 byte): `/login?error`, không phải 500 | QĐ2 |
| `"  USER@MiniShop.LOCAL "` → đăng nhập thành công | 003-12 |
| `POST /logout` có CSRF: redirect `/?logout`, session cũ `isInvalid()`; `GET /?logout` có `Bạn đã đăng xuất.`; `GET /logout` khi đang đăng nhập: không redirect `/?logout`, request sau đó vẫn authenticated | 003-13 |
| ID session trước đăng nhập (sau `GET /login`) khác ID sau đăng nhập | 003-19 |
| Context có đúng 1 bean `UserDetailsService`, và nó **không** phải `InMemoryUserDetailsManager` | 003-20 |

**`SchemaMigrationTest`:**
- thêm `users_uppercaseEmail_isRejected`, với `insert … 'A@x.local'`;
- thêm assert `flyway_schema_history` có version `2` thành công.

**`H2ProfileSmokeTest`:**
- thêm "V2 có hiệu lực trên H2": `insert` email chữ hoa ném `DataIntegrityViolationException`;
- `GET /login` trả 200.
- **Không** đăng ký người dùng ở đây: H2 in-memory dùng chung trong JVM, và `seed_populatesProductsAndUsers` đang assert đúng 2 người dùng.

**Xong khi:**
- `./mvnw verify` xanh;
- `bash .github/scripts/check-no-skipped-tests.sh` exit 0;
- báo cáo liệt kê test theo từng AC, kèm thời gian chạy của test race.

### Chi tiết D (phiên chính)

- **`docs/adr/0007-session-form-login-current-user.md`** (theo `template.md`): nội dung như QĐ12; cập nhật bảng trong `docs/adr/README.md`.
- **`CLAUDE.md`:**
  - **Project status:** GĐ3;
  - **"Visibility today":** thêm `user.CurrentUser` (public);
  - **mục mới "Auth patterns (GĐ3)":**
    - `CurrentUser` là principal (không phải entity, hash bị xóa sau khi đăng nhập), GĐ4/5 lấy id qua `@AuthenticationPrincipal CurrentUser`;
    - email đi qua `EmailNormalizer`, DB có CHECK chữ thường;
    - thông báo dùng flash (controller) hoặc query param (redirect của Spring Security);
    - ô mật khẩu không dùng `th:field`;
    - form POST phải dùng `th:action` để có CSRF;
    - `/access-denied` + `error/403.html`;
    - test có render view mà cần đăng nhập thì dùng `TestUsers`, không dùng `@WithMockUser`;
  - **ghi chú "Trước khi deploy (GĐ8)":** thêm "không giới hạn số lần đăng nhập sai" (câu hỏi mở 7) vào cạnh mục gate dữ liệu mẫu;
  - **ghi chú cho GĐ4:** gắn việc gộp giỏ vào sự kiện đăng nhập thành công (`AuthenticationSuccessEvent` hoặc bọc success handler), và giữ nguyên hành vi saved request.
- **`README.md`:**
  - trạng thái GĐ3;
  - mục "Tài khoản": đăng ký ở `/register`, quy tắc mật khẩu, tài khoản demo;
  - đánh dấu GĐ3 ở Lộ trình **sau khi V đạt**.
- **`CHANGELOG.md`:** ghi các mục vào `[Unreleased]` (Thêm / Thay đổi / Bảo mật). Việc chuyển thành `[0.3.0]` và gắn tag để sau khi người dùng duyệt giai đoạn.
- **Spec 003 và `docs/specs/README.md`:** chuyển sang `Đang làm` khi bắt đầu triển khai; chuyển sang `Hoàn thành` sau khi V đạt; ghi lịch sử thay đổi.

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

Docker **đang bật**. Mọi test, kể cả Testcontainers, phải chạy ở local.

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh` | BUILD SUCCESS; script exit 0; có đủ `RegistrationFormValidationTest`, `UserServiceTest`, `ShopUserDetailsServiceTest`, `AuthControllerTest`, `AccessDeniedTest`, `AdminControllerTest`, `RegistrationIntegrationTest`, `LoginFlowTest` |
| 2 | V2 trên DB cũ (nâng cấp) | **Không** xóa volume. `./mvnw spring-boot:run` trên DB dev từ GĐ2 | Flyway áp V2 thành công; app lên |
| 3 | AC-003-20 | Đọc log khởi động | Không có `Using generated security password` |
| 4 | Header khách | Mở `/` khi chưa đăng nhập | Có `Đăng nhập`, `Đăng ký`; không có `Quản trị`, `Đăng xuất` |
| 5 | AC-003-1, 6 | Mở `/register`, bấm `Đăng ký` khi form rỗng; rồi lần lượt thử email `abc`, mật khẩu `abc`, `12345678`, `ệệệệệệệệệệệệệệệệệệệệệệệệệ1` (25 chữ `ệ` + `1`), nhập lại khác | Mỗi ô hiện đúng chữ lỗi spec §6; **hai ô mật khẩu luôn trống** sau mỗi lần gửi; họ tên và email giữ nguyên |
| 6 | AC-003-2, 3 | Đăng ký `Người Thử` / `  Tester@Example.COM ` / `matkhau123` | Về `/login` với `Đăng ký thành công. Vui lòng đăng nhập.`; `docker compose exec postgres psql -U minishop -d minishop -c "select email, role, left(password_hash, 8) from users"` cho thấy `tester@example.com`, `USER`, `{bcrypt}` |
| 7 | AC-003-4 (âm) | Đăng ký lại `TESTER@example.com` | `Email này đã được đăng ký.` dưới ô Email; không có dòng mới |
| 8 | AC-003-7 (âm) | DevTools: thêm `<input name="role" value="ADMIN">` vào form đăng ký rồi gửi với email mới | Tài khoản tạo ra có `role = USER` (psql) |
| 9 | AC-003-18 (âm) | DevTools: xóa ô ẩn `_csrf` của form đăng ký rồi gửi; làm tương tự với form đăng nhập và nút `Đăng xuất` | Cả ba trả 403; không có người dùng mới; vẫn còn đăng nhập sau thử nghiệm đăng xuất |
| 10 | AC-003-11 (âm) | Đăng nhập sai mật khẩu, rồi đăng nhập bằng email không tồn tại | Cả hai: ở `/login`, `Email hoặc mật khẩu không đúng.`, ô email giữ giá trị vừa gõ; hai trang không khác nhau về chữ |
| 11 | AC-003-9, 12, 19 | Ghi lại cookie `JSESSIONID` (DevTools > Application), đăng nhập bằng `  USER@MiniShop.LOCAL ` / mật khẩu demo | Về `/`; header `Xin chào, Khách hàng mẫu` và `Đăng xuất`; `JSESSIONID` đã đổi |
| 12 | AC-003-17 | Đang đăng nhập, gõ `/login`, rồi `/register` | Cả hai chuyển về `/` |
| 13 | AC-003-14 (âm) | Đang là `USER`, mở `/admin` và `/admin/abc` | Tab Network: **403**; trang `Bạn không có quyền truy cập trang này.`, link `Về trang chủ` hoạt động; header không có `Quản trị` |
| 14 | AC-003-13 | Bấm `Đăng xuất`; rồi bấm Back của trình duyệt và tải lại một trang cần đăng nhập (`/orders`) | Về `/` với `Bạn đã đăng xuất.`; `/orders` chuyển sang `/login` |
| 15 | AC-003-13 (âm) | Đăng nhập lại, gõ `/logout` vào thanh địa chỉ (GET) | Không đăng xuất: mở `/` vẫn thấy `Xin chào` |
| 16 | AC-003-15, 10 | Đăng xuất. Mở `/admin`, `/checkout`, `/orders` | Cả ba chuyển sang `/login`. Đăng nhập từ lần `/orders` thì được đưa tới `/orders?continue` (lúc này là trang lỗi 404 mặc định, vì `/orders` làm ở GĐ5; đó là kết quả đúng) |
| 17 | AC-003-16 | Đăng nhập `admin@minishop.local` | Về `/` (không phải `/admin`); header có `Quản trị`; bấm vào thì `/admin` trả 200 với `Trang quản trị` và dòng giới thiệu; tiêu đề tab `Quản trị \| Mini Shop` |
| 18 | AC-003-21 (âm) | Đăng ký với họ tên `<script>alert(1)</script>`, rồi đăng nhập | Header hiện đúng chuỗi dạng văn bản; không có hộp alert; Console không lỗi |
| 19 | AC-003-22 | Device mode ở 375px, 768px, 1440px: `/login`, `/register` (cả khi có lỗi), `/admin/abc` khi là `USER`, `/admin` khi là `ADMIN`, `/products` khi đăng nhập bằng tài khoản họ tên 100 ký tự liền nhau | Không có thanh cuộn ngang; header không vỡ; form dùng được bằng ngón tay |
| 20 | CSP, AC-001-16 | DevTools Console và Network trên các trang mới | Không có lỗi CSP; mọi tài nguyên từ `localhost`; có header `Content-Security-Policy` |
| 21 | Truy cập | Xem mã HTML của form | Mọi ô có `label for`; ô lỗi có `aria-invalid` và `aria-describedby` trỏ tới chữ lỗi; thông báo có `role` |
| 22 | Hồi quy GĐ2 | Chưa đăng nhập: `/products`, `/products/{id}`, `/products/abc`; `curl -X POST` tới `/products/1` | Hai trang đầu 200, trang thứ ba 404 như GĐ2; POST bị chuyển sang `/login` |
| 23 | Profile `h2` | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`: đăng ký, đăng nhập, `/admin` với cả hai vai trò; mở `/h2-console` | Giống PostgreSQL; H2 console vẫn mở được |
| 24 | Dọn dẹp | Tắt mọi app đã chạy | Không còn tiến trình; container Postgres đã dừng |

## 6. Câu hỏi cho người dùng

> **Người dùng đã trả lời (2026-10-24):** duyệt kế hoạch, bắt đầu triển khai; cuối giai đoạn được commit, push và mở PR `phase-3/auth` → `main`, **chưa merge**. Các mục "chỉ để biết" được ghi nhận.

Không câu nào chặn việc bắt đầu triển khai.

1. **Cuối giai đoạn có commit, push và mở PR `phase-3/auth` → `main` không?**
   - Khuyến nghị: **có, nhưng chưa merge**, giống GĐ2.
   - Các agent không tự commit. Nếu đồng ý, phiên chính commit theo Conventional Commits sau khi V đạt.
2. **Chỉ để bạn biết:**
   - Trang `/admin` tạm dùng layout của khu khách hàng. Hướng thiết kế app-screen của khu quản trị được vẽ ở GĐ6, khi có dashboard thật (QĐ9).
   - `CurrentUser` được để public ngay từ GĐ3 (QĐ1).
3. **Chỉ để bạn biết:**
   - Ở GĐ3, đăng nhập từ `/orders` sẽ đưa bạn tới `/orders?continue` và thấy trang lỗi 404 mặc định, vì trang đơn hàng làm ở GĐ5.
   - Ngoài ra, nếu để trang đăng nhập mở quá thời gian của session (30 phút) rồi mới gửi, CSRF token hết hạn và bạn nhận trang 403 "Bạn không có quyền truy cập trang này.". Hai điều này đúng như thiết kế; cách làm đẹp hơn nằm ở mục 7.

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Trang riêng cho CSRF token hết hạn:** bắt `InvalidCsrfTokenException` / `MissingCsrfTokenException`, chuyển về `/login` kèm thông báo "Phiên làm việc đã hết hạn, vui lòng thử lại.". Hợp với GĐ7 (trang lỗi).
- **Cookie phiên `SameSite=Lax` và `Secure`:** `server.servlet.session.cookie.same-site=lax`, `secure=true` khi chạy HTTPS. Nên làm ở GĐ8 (deploy), kèm giới hạn số lần đăng nhập sai (câu hỏi mở 7).
- **Điền sẵn email vào trang đăng nhập sau khi đăng ký:** chỉ cần thêm một flash attribute `email`, vì template đã đọc `${email}`. Spec không yêu cầu nên không làm.
- **Đổi `<version>` trong `pom.xml` từ `0.1.0-SNAPSHOT`** cho khớp với tag đã phát hành (`v0.2.0`): một việc `chore` nhỏ cho `devops-engineer`.

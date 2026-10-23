# Mini Shop: Bản mô tả dự án (Project Brief)

> Trạng thái: **v2, chờ duyệt** · Cập nhật: 2026-10-23
> Dự án **học tập / portfolio**. Yêu cầu ban đầu của bạn chỉ là tham khảo; công nghệ và kiến trúc bên dưới là lựa chọn tối ưu cho bối cảnh này, kèm lý do.
> Mục ⏳ là những gì còn mở.

---

## 0. Tóm tắt các quyết định chính

| Hạng mục | Yêu cầu ban đầu | **Chốt** | Lý do đổi |
|---|---|---|---|
| Java | 17 | **Java 25 (LTS)** | Bản LTS mới nhất và máy đã cài sẵn. Có record, pattern matching, virtual threads. Spring Boot 4 hỗ trợ đầy đủ. |
| Spring Boot | 3.x | **4.1.x** | start.spring.io chỉ còn tạo được 4.0.x và 4.1.x; dòng 3.x đã hết hỗ trợ miễn phí. Dự án mới bắt đầu bằng phiên bản sắp hết hạn thì vừa thiệt thòi khi phỏng vấn, vừa không còn nhận bản vá bảo mật. |
| Database | H2, chuyển MySQL bằng profile | **PostgreSQL** ở mọi môi trường (dev chạy qua Docker Compose, test qua Testcontainers). Giữ profile `h2` làm phương án dự phòng khi không có Docker | Dev và prod dùng **cùng một loại DB** nên không có lỗi kiểu "chạy trên H2 nhưng hỏng trên DB thật". Máy đã có Docker. PostgreSQL có nhiều gói host miễn phí hơn MySQL khi cần demo online. |
| Quản lý schema | (Hibernate tự tạo) | **Flyway** | `ddl-auto=update` không an toàn cho dữ liệu thật. Migration bằng SQL là chuẩn của ngành, nhẹ và dễ đọc. |
| Giao diện | Thymeleaf + Bootstrap 5 | **Giữ nguyên** | Xem mục 6.2. |
| Kiến trúc | Phân lớp, chia package theo tính năng hoặc theo lớp | **Modular monolith**: chia package theo tính năng, mỗi tính năng có các lớp controller → service → repository | Xem mục 6.1. |
| Test | Unit test cho service | Unit test (Mockito) **+ test tích hợp với PostgreSQL thật (Testcontainers)** | Câu SQL trừ kho phải được test trên đúng DB sẽ chạy thật. |
| CI / Deploy | Không đề cập | **GitHub Actions CI từ GĐ1**; Docker image và deploy ở GĐ8 (tùy chọn) | CI chạy ngay từ commit code đầu tiên để `main` luôn xanh. Link demo trực tuyến tăng giá trị portfolio. |
| Khách tự hủy đơn | Không có | **Có, chỉ khi đơn đang PENDING** | Tính năng tối thiểu của mọi trang bán hàng; dùng lại logic hoàn kho của admin nên tốn rất ít code. |

---

## 1. Sản phẩm

| Câu hỏi | Trả lời |
|---|---|
| **Ai dùng?** | Có hai nhóm. (1) **Người dùng mô phỏng**: khách mua hàng (vãng lai hoặc có tài khoản) và quản trị viên. (2) **Người dùng thật**: nhà tuyển dụng, mentor, người review. Họ mở link demo, hoặc clone về và chạy bằng một lệnh. |
| **Vấn đề gì?** | Với người học: làm trọn vẹn một ứng dụng web Java theo chuẩn công nghiệp (MVC, JPA, Security, transaction, migration, test, CI). Trong kịch bản mô phỏng: cửa hàng nhỏ cần bán online và quản lý đơn. |
| **Thành công đo bằng gì?** | Chạy được bằng một lệnh. Luồng *xem → giỏ → đăng nhập → đặt hàng → admin đổi trạng thái* chạy trọn vẹn. User thường không vào được `/admin`. `mvnw verify` xanh trên CI. Người lạ đọc README là chạy được trong khoảng 5 phút. Bạn giải thích được mọi quyết định thiết kế. |
| **MVP gồm** | **Khách:** danh sách sản phẩm (phân trang, tìm kiếm, lọc danh mục, sắp xếp giá), chi tiết, giỏ hàng, đăng ký/đăng nhập, checkout COD, lịch sử đơn, tự hủy đơn PENDING. **Admin:** dashboard, CRUD danh mục và sản phẩm (có upload ảnh), quản lý đơn và đổi trạng thái, hoàn kho khi hủy. |
| **Loại ra** | Thanh toán online, quên mật khẩu, email, đánh giá, mã giảm giá, wishlist, biến thể sản phẩm (size/màu), phí ship, đa ngôn ngữ, REST API công khai, app mobile. |

## 2. Trải nghiệm

| Câu hỏi | Trả lời |
|---|---|
| **Luồng chính** | **Khách:** Trang chủ → tìm/lọc → chi tiết → *Thêm vào giỏ* → Giỏ → *Đặt hàng* → (đăng nhập nếu chưa; giỏ trong session được gộp vào giỏ trong DB) → thông tin giao hàng → xác nhận → *Đơn hàng của tôi*. **Admin:** Dashboard → Đơn *Chờ xử lý* → chi tiết → đổi trạng thái. |
| **Giao diện** | Thymeleaf + Bootstrap 5, tiếng Việt, giá dạng `1.250.000 ₫`. Mỗi thao tác có flash message; có trang lỗi 404/403/500. Admin có layout riêng với sidebar. |
| **Thiết bị** | Ưu tiên desktop, đặc biệt trang admin. Trang khách responsive cho điện thoại. |

## 3. Dữ liệu

| Câu hỏi | Trả lời |
|---|---|
| **Lưu gì?** | `User` (vai trò là enum `USER`/`ADMIN`), `Category`, `Product`, `Cart`/`CartItem`, `Order`/`OrderItem`. `OrderItem` lưu **bản chụp tên và giá** tại thời điểm mua. Sản phẩm **ẩn** bằng `active=false` chứ không xóa cứng. Tiền dùng `BigDecimal`/`numeric`. Giỏ của khách vãng lai nằm trong HTTP session. |
| **Ai xem/sửa?** | **Khách vãng lai:** xem hàng, giỏ trong session. **USER:** giỏ trong DB, đặt hàng, xem và hủy **chỉ đơn của mình** (kiểm tra quyền sở hữu ở service). **ADMIN:** toàn quyền với sản phẩm, danh mục, đơn hàng. |
| **Dung lượng** | DB chỉ vài MB, ngay cả khi có hàng nghìn đơn. Ảnh giới hạn 2 MB/ảnh. |
| **Sao lưu** | Dev: không cần, vì Flyway và seed dựng lại DB bất cứ lúc nào. Prod: dùng tính năng sao lưu của dịch vụ Postgres được quản lý, hoặc `pg_dump`. Ảnh sản phẩm đi theo dịch vụ lưu trữ (xem mục 9). |

## 4. Tài khoản

| Câu hỏi | Trả lời |
|---|---|
| **Đăng nhập** | Email + mật khẩu băm bằng BCrypt (qua `DelegatingPasswordEncoder` của Spring Security). Đăng nhập dựa trên session, logout bằng POST, bật CSRF. Chỉ checkout và lịch sử đơn mới bắt buộc đăng nhập. |
| **Phân quyền** | Enum `Role` trên `User`; với 2 vai trò cố định thì không cần bảng riêng. `/admin/**` dành cho ADMIN, `/checkout/**` và `/orders/**` cần đăng nhập. Đăng ký mới luôn nhận vai trò USER; tài khoản admin có sẵn từ seed. |
| **Quên mật khẩu** | Không làm, vì cần gửi email. Ghi vào phần "Hướng phát triển". |
| **Demo** | `admin@minishop.local` / `user@minishop.local`; mật khẩu ghi trong README, chỉ dùng cho dev và demo. |

## 5. Tính năng ngoài

Chỉ thanh toán **COD** (vẫn có trường `paymentMethod` để mở rộng sau). Không có email, thông báo đẩy, bản đồ, AI, đăng nhập mạng xã hội.

## 6. Công nghệ & kiến trúc

### 6.1 Kiến trúc: modular monolith

```
                    ┌─────────────── Một ứng dụng Spring Boot (một file JAR) ───────────────┐
 Trình duyệt ──HTTP──▶│ web (Controller + Thymeleaf)   ← chỉ nhận request, validate form, chọn view │
                    │        │                                                               │
                    │        ▼                                                               │
                    │ service (@Transactional)      ← toàn bộ nghiệp vụ nằm ở đây            │
                    │        │                                                               │
                    │        ▼                                                               │
                    │ repository (Spring Data JPA)  ← truy cập DB                            │
                    └────────┼───────────────────────────────────────────────────────────────┘
                             ▼
                        PostgreSQL  (+ thư mục / dịch vụ lưu ảnh qua StorageService)
```

- **Monolith thay vì microservices:** chỉ một người phát triển, một miền nghiệp vụ nhỏ. Microservices sẽ thêm network, triển khai và dữ liệu phân tán mà không giải quyết vấn đề nào của dự án này.
- **Chia package theo tính năng** (`catalog`, `cart`, `order`, `user`, `admin`, `storage`, `common`, `config`): mỗi tính năng là một "module" có ranh giới rõ, phụ thuộc theo một chiều `order → cart → catalog`. Sau này muốn tách service hay thêm REST API thì chỉ cần thêm tầng web mới, tầng service giữ nguyên.
- **Entity tách khỏi DTO/form:** form dùng `record` kèm Jakarta Validation, view nhận DTO. Entity không bao giờ được bind thẳng từ request, tránh lỗi mass assignment (kẻ xấu gửi thêm `role=ADMIN` khi đăng ký).
- **`spring.jpa.open-in-view=false`:** buộc phải load đủ dữ liệu trong service, nhờ đó lộ sớm các lỗi N+1 và lazy loading.
- **Trừ kho bằng câu UPDATE nguyên tử:** `UPDATE product SET stock = stock - :q WHERE id = :id AND stock >= :q`. Nếu không có dòng nào bị ảnh hưởng thì báo hết hàng và rollback cả đơn. Cách này chống bán vượt kho khi nhiều người đặt cùng lúc.
- **Luồng trạng thái đơn nằm trong enum** `OrderStatus.canTransitionTo()`: `PENDING → CONFIRMED → SHIPPING → DELIVERED`; được hủy từ `PENDING`/`CONFIRMED` (khách chỉ hủy được khi `PENDING`), và hủy thì hoàn kho trong cùng transaction.
- **`StorageService` là interface:** bản local lưu file ra đĩa. Khi deploy lên host có ổ đĩa tạm (bị xóa sau mỗi lần deploy) thì chỉ cần thay bằng bản dùng dịch vụ lưu trữ đám mây, không phải sửa code nghiệp vụ.

### 6.2 Vì sao vẫn render phía server (Thymeleaf) thay vì REST API + React?

| | Thymeleaf SSR (**chọn**) | REST + React SPA |
|---|---|---|
| Khối lượng | 1 dự án, 1 lần deploy | 2 dự án; phải xử lý CORS, JWT/refresh token, quản lý state |
| Bảo mật | Session + CSRF có sẵn, đơn giản và an toàn | JWT dễ làm sai (lưu token ở đâu, thu hồi thế nào) |
| Trọng tâm học | Đi sâu vào Spring | Chia sức cho cả JavaScript |
| SEO trang sản phẩm | Tốt sẵn | Cần SSR hoặc pre-render |

Nếu sau này cần thể hiện kỹ năng REST, có thể thêm `/api/products` dùng lại service hiện có (khoảng một buổi làm) mà không phải viết lại gì.

### 6.3 Công nghệ cụ thể

| Thành phần | Lựa chọn |
|---|---|
| Ngôn ngữ / build | Java 25, Maven + Maven Wrapper (`mvnw`) |
| Framework | Spring Boot 4.1.x: Web MVC, Data JPA (Hibernate 7), Security 7, Validation, Actuator (chỉ dùng `/actuator/health`) |
| View | Thymeleaf + thymeleaf-extras-springsecurity, Bootstrap 5.3 (không cần build JS) |
| DB | PostgreSQL 17; Flyway; `spring-boot-docker-compose` tự bật Postgres khi chạy `mvnw spring-boot:run`; profile `h2` cho máy không có Docker |
| Test | JUnit 5, AssertJ, Mockito, MockMvc + spring-security-test, Testcontainers (PostgreSQL) |
| Không dùng | Lombok (dùng record và code tường minh), MapStruct (mapping viết tay, ít và rõ), Layout Dialect, framework JS, microservices, JWT |

> Ghi chú: phiên bản chính xác của từng thư viện (đặc biệt là thymeleaf-extras cho Security 7) sẽ được kiểm tra lại khi tạo project ở GĐ1.

## 7. Bảo mật & pháp lý

| Hạng mục | Cách xử lý |
|---|---|
| Dữ liệu cá nhân | Họ tên, email, SĐT, địa chỉ giao hàng: chỉ chủ đơn và admin xem được. Mật khẩu chỉ lưu bản băm. Seed dùng dữ liệu giả. |
| Bảo mật ứng dụng | CSRF; Thymeleaf tự escape (chống XSS); truy vấn tham số hóa (chống SQL injection); kiểm tra quyền sở hữu đơn (chống IDOR); không bind entity trực tiếp (chống mass assignment); kiểm tra file upload (đuôi, content-type, kích thước, tên UUID); H2 console chỉ có ở profile `h2`; mọi secret lấy từ biến môi trường, không commit. |
| Điều khoản / bản quyền | Footer ghi "Dự án học tập, không bán hàng thật". Ảnh mẫu tự tạo hoặc có giấy phép miễn phí. Mã nguồn dùng giấy phép **MIT**. |
| Quy định ngành | Không áp dụng vì không có giao dịch thật. Nếu kinh doanh thật thì phải tuân thủ quy định về thương mại điện tử và bảo vệ dữ liệu cá nhân (Nghị định 13/2023/NĐ-CP). |

## 8. Chất lượng

| Loại test | Phạm vi |
|---|---|
| Unit (Mockito) | `OrderService`: đặt thành công, hết hàng, snapshot giá, chuyển trạng thái, hủy thì hoàn kho. `CartService`: thêm, gộp, sửa, xóa, chặn vượt tồn kho, gộp giỏ khi đăng nhập. |
| Tích hợp (Testcontainers Postgres) | Câu UPDATE trừ kho; truy vấn tìm/lọc/phân trang; migration Flyway chạy được trên DB trống. |
| Web / Security (MockMvc) | Khách vãng lai bị chuyển sang trang đăng nhập; USER vào `/admin` nhận 403; USER không xem được đơn của người khác. |
| Thủ công | Checklist thao tác trên trình duyệt ở cuối mỗi giai đoạn. |

**Definition of Done:** chạy được; `mvnw verify` xanh; controller không chứa nghiệp vụ; form có validation với thông báo tiếng Việt; README được cập nhật.

## 9. Triển khai

| Câu hỏi | Trả lời |
|---|---|
| **Chạy ở đâu?** | **Local:** `mvnw spring-boot:run` (Docker tự bật Postgres) tại `http://localhost:8080`. **Online (GĐ8, tùy chọn):** đóng gói Docker image, chạy trên một PaaS có gói miễn phí hoặc giá rẻ, dùng Postgres được quản lý và dịch vụ lưu ảnh đám mây. Chọn nhà cung cấp cụ thể vào lúc làm GĐ8, vì giá và gói miễn phí thay đổi thường xuyên. |
| **Tên miền** | Dùng subdomain miễn phí của PaaS. |
| **Cập nhật** | Git; đẩy lên `main` thì GitHub Actions chạy `mvnw verify`, xanh thì deploy. Mỗi giai đoạn gắn một tag `v0.x`. Flyway tự áp migration mới khi ứng dụng khởi động. |

## 10. Vận hành

| Câu hỏi | Trả lời |
|---|---|
| **Theo dõi lỗi** | Log của Spring Boot; `@ControllerAdvice` ghi log lỗi 500; `/actuator/health` để PaaS kiểm tra ứng dụng còn sống. Không dùng APM, vì quá mức cần thiết. |
| **Ai sửa?** | Chủ repo. README có mục xử lý sự cố thường gặp. |
| **Chi phí** | Local: 0 đồng. Online: cố gắng giữ ở mức 0 bằng gói miễn phí; gói miễn phí thường "ngủ" khi không có truy cập, chấp nhận được với một bản demo. |

## 11. Phát triển

| GĐ | Nội dung | Ước tính* |
|---|---|---|
| 1 | Project (Boot 4.1, Java 25), GitHub Actions CI + Dependabot, Docker Compose Postgres, Flyway schema, entity, repository, seed, test Testcontainers đầu tiên | 2 buổi |
| 2 | Danh sách và chi tiết sản phẩm (tìm, lọc, phân trang, sắp xếp), layout Bootstrap | 1–2 buổi |
| 3 | Đăng ký, đăng nhập, Security, phân quyền, test bảo mật | 1–2 buổi |
| 4 | Giỏ hàng (session + DB, gộp khi đăng nhập) | 2 buổi |
| 5 | Checkout COD, lịch sử đơn, khách tự hủy đơn PENDING | 2 buổi |
| 6 | Admin: dashboard, CRUD, upload ảnh, quản lý đơn, hoàn kho | 2–3 buổi |
| 7 | Hoàn thiện validation, trang lỗi, giao diện, README | 1–2 buổi |
| 8 | *(Tùy chọn)* Dockerfile, deploy online, CD từ `main` | 1 buổi |
| | **Tổng** | **khoảng 12–16 buổi (3 tuần làm bán thời gian)** |

\* Một buổi khoảng 2–3 giờ, gồm cả thời gian bạn đọc hiểu và chạy thử.

## 12. Tăng trưởng (portfolio)

| Câu hỏi | Trả lời |
|---|---|
| **Có người dùng bằng cách nào?** | README có ảnh chụp, GIF luồng đặt hàng, sơ đồ kiến trúc và entity, badge CI, link demo. Ghim repo trên GitHub, đưa vào CV và LinkedIn. Mục "Quyết định thiết kế" trong README là chất liệu để trả lời phỏng vấn. |
| **Phản hồi** | Nhờ mentor review qua PR, mở GitHub Issues, ghi lại câu hỏi của nhà tuyển dụng để cải thiện dự án. |

---

## ⏳ Còn mở

| # | Câu hỏi | Mặc định nếu bạn không đổi |
|---|---|---|
| 1 | Có làm GĐ8 (CI + deploy online) không? | Quyết định sau khi xong GĐ7 |
| 2 | Package gốc `com.minishop` | Giữ nguyên |

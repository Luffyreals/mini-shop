# Kế hoạch GĐ6: Trang quản trị (dashboard, danh mục, sản phẩm, ảnh, tồn kho, đơn hàng)

- **Spec:** [006 Trang quản trị](../specs/006-admin.md) (Đã duyệt 2026-10-24; người dùng chấp nhận cả 14 khuyến nghị ở §9: điều chỉnh tồn kho bằng lượng ±, giá 1–999.999.999 ₫ và tồn kho 0–100.000, ảnh JPG/PNG/WebP ≤ 2 MB kiểm cả magic bytes, xóa ảnh cũ sau commit, `/uploads/**` công khai qua resource handler, tên danh mục không phân biệt hoa thường, không xóa danh mục còn sản phẩm đã ẩn, "sắp hết hàng" ≤ 5, một con số doanh thu, lọc đơn theo trạng thái, hộp xác nhận cho ba thao tác, không lý do hủy, "lưu sau thắng" cho thông tin sản phẩm, 404 trong layout quản trị)
- **Nhánh:** `phase-6/admin` (đã checkout). Spec 006 và dòng mới trong `docs/specs/README.md` (đang chưa commit) được commit trên nhánh này.
- **Đã có từ GĐ1–5, không làm lại:**
  - bảng `categories`, `products`, `orders`, `order_items` (V1) với đủ cột, `uq_categories_name`, `fk_products_category`, `ck_products_stock_non_negative`, `idx_orders_status`, `idx_orders_created_at`;
  - `Product.stock` là `updatable = false`; `ProductRepository.decrementStock`/`incrementStock` (`clearAutomatically`); `ProductSpecifications.nameContains` (escape `%` `_` `\`); `ProductFilter` (chuẩn hóa `q`, trang);
  - `OrderStatus.canTransitionTo()`, `label()`; `Order.transitionTo()`; `OrderRepository.lockByIdAndUserId`; `OrderService.cancelByCustomer` (khóa `orders` → cộng kho theo `product_id` tăng dần, ADR-0009);
  - `SecurityConfig`: `/admin/**` chỉ `ADMIN`, CSRF bật, trang 403; `PUBLIC_PATHS` chỉ GET;
  - `common.PageNav`, `fragments/pagination`, `fragments/notice`, `#vnd`, `#vnDateTime`, `NotFoundException`; `static/js/confirm.js` (`data-confirm`); `user.CurrentUser`, `TestUsers`, `CartHeaderTestConfig`, `OrderTestData`;
  - `application.yml` đã có `spring.servlet.multipart` (2MB / 10MB) và `uploads/` đã nằm trong `.gitignore`.
- **Không đổi `pom.xml`** (kiểm magic bytes bằng vài dòng Java, không cần thư viện ảnh). `devops-engineer` chỉ có một task nhỏ về khóa cấu hình multipart (QĐ6).
- **Không có migration** (QĐ12). Entity chỉ thêm method (`Category.update`, `Product.updateDetails`), không đổi `@Column`/quan hệ; vẫn chạy `schema-guardian` một lần (task S) vì có entity và truy vấn mới.

## 1. Mục tiêu

- Admin đăng nhập vào `/admin` thấy dashboard (số đơn theo trạng thái, doanh thu đơn đã giao, sản phẩm sắp hết hàng, 5 đơn mới nhất) trong một layout quản trị riêng có sidebar; quản lý được danh mục (thêm, sửa, xóa khi trống), sản phẩm (thêm có ảnh, sửa, thay ảnh, ẩn/hiện) và tồn kho (nhập ban đầu, rồi chỉ điều chỉnh ± bằng một câu UPDATE nguyên tử).
- Admin xem mọi đơn (lọc theo trạng thái, phân trang), đưa đơn đi hết `Chờ xử lý → Đã xác nhận → Đang giao → Đã giao`, hoặc hủy đơn `PENDING`/`CONFIRMED` và hoàn kho đúng một lần; mọi nút được suy từ `OrderStatus.canTransitionTo()`. Admin và khách đổi cùng một đơn được xếp hàng (khóa dòng `orders`, ADR-0009).
- Ảnh đi qua interface `StorageService` (bản local): kiểm đuôi + content type + magic bytes, ≤ 2 MB, tên UUID, phục vụ công khai tại `/uploads/**`, không bao giờ ra ngoài thư mục upload; file mồ côi bị dọn sau commit/rollback. Tồn kho không bao giờ bị ghi đè hay âm, kể cả khi khách đang đặt hàng cùng lúc.
- `./mvnw verify` xanh **với 0 test bị bỏ qua** (Docker bật). Mọi AC-006-1 đến AC-006-52 có test hoặc mục checklist tương ứng (cột "AC / BR" ở mục 4 và checklist mục 5).

## 2. Quyết định thiết kế

**QĐ1. `order` mở một API quản trị hẹp: class `OrderAdminService` và vài record công khai. `OrderService`, `Order`, `OrderStatus`, `OrderRepository` vẫn package-private.**
- `public class OrderAdminService` (`@Service @Transactional`) chỉ có bốn method mà admin cần: `listOrders(rawStatus, rawPage)`, `getOrder(orderId)`, `dashboard()`, `changeStatus(adminId, orderId, rawTargetStatus)`. Tham số trạng thái và trang đến **dạng chuỗi thô**, được parse bên trong `order` bằng `OrderInput` (thêm `status(raw)`), giống `OrderService.listOrders(userId, rawPage)` của GĐ5.
- **Không lộ enum `OrderStatus` ra ngoài:** admin chỉ nhận chuỗi (`"PENDING"` làm giá trị bộ lọc và giá trị gửi lên, nhãn tiếng Việt, `statusKey` cho CSS). Lý do: nếu `OrderStatus` thành public, `admin` có thể tự viết `if (status == SHIPPING) …` và quy tắc chuyển trạng thái sẽ bị chép ra tầng web. Giữ enum trong `order` thì quy tắc chỉ sống ở một chỗ.
- **Nút trạng thái suy từ bảng (BR-006-12):** `getOrder` dựng `List<StatusAction>` bằng `Arrays.stream(OrderStatus.values()).filter(current::canTransitionTo)`. Thứ tự khai báo của enum (`PENDING, CONFIRMED, SHIPPING, DELIVERED, CANCELLED`) cho sẵn thứ tự "bước tiếp theo trước, `Hủy đơn` sau". Chữ trên nút lấy từ method mới `OrderStatus.actionLabel()` (nhãn của hành động *đi tới* trạng thái đó: `Xác nhận đơn`, `Bắt đầu giao hàng`, `Xác nhận đã giao`, `Hủy đơn`; `PENDING` không bao giờ là đích nên ném `IllegalStateException`). Template chỉ lặp danh sách, không biết bảng chuyển trạng thái.
- **Khóa theo id, không lọc chủ đơn, chỉ cho admin:** `OrderRepository.lockById(id)` (`@Lock(PESSIMISTIC_WRITE)`, `select o from Order o where o.id = :id`). Javadoc ghi rõ "chỉ `OrderAdminService` gọi; đường của khách luôn dùng `lockByIdAndUserId` (IDOR)".
- **Một chỗ duy nhất cho "hủy và hoàn kho":** tách đoạn bước 3–5 của `cancelByCustomer` thành `final class OrderCancellation` (package-private) với `static int cancelAndRestoreStock(Order lockedOrder, ProductRepository products)`: chép `(productId, quantity)` → `transitionTo(CANCELLED)` → `incrementStock` theo `productId` tăng dần, trả khác 1 thì `IllegalStateException`. `cancelByCustomer` và `changeStatus(…CANCELLED)` cùng gọi nó. Là hàm static nhận repository, nên constructor của `OrderService` không đổi và `OrderServiceTest` hiện có vẫn xanh.
- `changeStatus` chạy đúng thứ tự ADR-0009: parse đích (sai → `InvalidStatus`, không khóa, không ghi) → `lockById` (không có → `NotFoundException`) → `!current.canTransitionTo(target)` → `NotAllowed(nhãn hiện tại, nhãn đích)` → đích là `CANCELLED` thì `OrderCancellation`, còn lại `order.transitionTo(target)` → log INFO → `Changed`/`Cancelled`.
- `StatusChangeResult` là **sealed interface** với bốn record lồng (`Changed`, `Cancelled`, `NotAllowed`, `InvalidStatus`): controller `switch` theo pattern, trình biên dịch bắt đủ trường hợp (như `PlaceOrderException` ở GĐ5). Ở đây trả kết quả thay vì ném exception vì **không có gì cần rollback**: các nhánh "không cho phép" đều chưa ghi gì.
- `OrderLineView` (đã có) chuyển thành public để chi tiết đơn của admin dùng lại, không chép record.

*Phương án bị loại:*
- **Cho `OrderService` thành public.** `admin` sẽ thấy cả `placeOrder`, `cancelByCustomer`, `prefilledForm`: API rộng hơn nhiều so với cái admin cần (cùng lý do với `CartCheckout` ở GĐ5).
- **Admin tự gọi `OrderRepository`.** Vi phạm `admin_doesNotUseRepositories`, và quy tắc chuyển trạng thái sẽ bị viết lại ở tầng web.
- **Chép đoạn hoàn kho vào `OrderAdminService`.** Hai bản sẽ lệch nhau khi sửa một bên; hoàn kho là invariant, phải có một bản.

**QĐ2. Nghiệp vụ quản trị danh mục và sản phẩm nằm trong `catalog` (`CategoryAdminService`, `ProductAdminService`), không trong `admin`. Service nhận record "draft" của `catalog`, không nhận form của `admin`.**
- `admin` chỉ có controller, form, parse đầu vào và chọn view (BR-006-15). Mọi quy tắc (trùng tên, xóa danh mục, lưu ảnh, điều chỉnh kho) nằm trong service `@Transactional` của `catalog`, `order`, `storage`.
- **Chiều phụ thuộc:** `admin → catalog`, `admin → order`, `admin → storage`; `catalog → storage`. `catalog` không thể nhận `NewProductForm` của `admin` (sẽ thành vòng `catalog → admin`, ArchUnit cấm), nên form của `admin` đổi sang `CategoryDraft` / `ProductDraft` (record public của `catalog`) bằng `toDraft()`. Form giữ chuỗi thô để hiện lại khi lỗi; draft giữ giá trị đã parse (`BigDecimal price`, `long categoryId`).
- Hai service tách riêng khỏi `ProductService` hiện có (storefront, `readOnly`, package-private): storefront không cần thấy API ghi, và `ProductService` không phải thành public.
- **Log thao tác admin (spec §8):** mọi method ghi dữ liệu nhận `long adminId` làm tham số đầu, chỉ để log một dòng INFO: `Admin action: admin={}, {đối tượng}={id}, action={…}` (có thể kèm `delta`, trạng thái). Không bao giờ log tên khách, số điện thoại, địa chỉ, ghi chú, email. `catalog` không phụ thuộc `user`: chỉ nhận số `long`.

*Phương án bị loại:* **service đặt trong `admin`** (`admin.ProductAdminService` gọi repository của `catalog`): vi phạm quy tắc "admin không dùng repository" và kéo nghiệp vụ ra khỏi module sở hữu dữ liệu.

**QĐ3. Điều chỉnh tồn kho: một câu `@Modifying` mới `adjustStock`; 0 dòng thì đọc lại để phân loại lỗi. Chỉ `ProductAdminService` được gọi nó.**

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Product p set p.stock = p.stock + :delta "
     + "where p.id = :id and :delta <> 0 and p.stock + :delta between 0 and 100000")
int adjustStock(@Param("id") Long id, @Param("delta") int delta);
```

- `ProductAdminService.adjustStock(adminId, id, delta)`:
  1. chặn `delta == 0` hoặc `|delta| > 100_000` bằng `IllegalArgumentException` (controller đã kiểm trước, đây là chốt chặn);
  2. `adjustStock(id, delta)`;
  3. **luôn** đọc lại `productRepository.findById(id)` (context đã bị xóa nên đây là câu SELECT mới):
     - không có → `NotFoundException` (404);
     - trả 1 → `Adjusted(tên, stock)`. Giá trị đọc được **chắc chắn là của mình**: câu UPDATE vừa rồi giữ khóa dòng tới khi commit, nên không ai đổi được `stock` giữa UPDATE và SELECT;
     - trả 0 và `delta < 0` → `NotEnough(tên, -delta, stock hiện tại)` (AC-006-33);
     - trả 0 và `delta > 0` → `WouldExceed(tên)` (AC-006-34).
- **Vì sao đúng khi chạy đồng thời (AC-006-35, 36):** câu điều chỉnh và `decrementStock` của khách đều là `UPDATE` trên cùng dòng; PostgreSQL xếp hàng chúng ở khóa dòng và **đánh giá lại `WHERE`** trên bản ghi mới nhất (READ COMMITTED), giống ADR-0005. Không có bước "đọc rồi ghi" trong Java nên không mất cập nhật; `ck_products_stock_non_negative` là lớp cuối. Câu này chỉ khóa **một** dòng `products` và không khóa gì khác, nên không thêm cạnh nào vào thứ tự khóa của ADR-0009.
- Lúc 0 dòng, `stock` đọc lại có thể đã khác lúc UPDATE (một khách vừa mua xong): thông báo phản ánh số **hiện tại**, đúng ý nghĩa "tồn kho hiện tại chỉ còn n". Chấp nhận được.
- Không lọc `active` (AC-006-37: sản phẩm đã ẩn vẫn điều chỉnh được).
- **`ArchitectureTest`:** thay rule `onlyOrderChangesStock` bằng hai rule chặt hơn:
  - `onlyOrderDecrementsOrRestoresStock`: ngoài `com.minishop.order..` không lớp nào gọi `decrementStock`/`incrementStock` (trước đây cho cả `catalog`, nay không cần);
  - `onlyProductAdminServiceAdjustsStock`: chỉ `com.minishop.catalog.ProductAdminService` gọi `adjustStock(Long, int)`.
  - `cart_neverDecrementsStock` giữ nguyên; `admin` không bao giờ tới được repository (`admin_doesNotUseRepositories`).

*Phương án bị loại:*
- **Ô "tồn kho" tuyệt đối trong form sửa.** Spec §9 câu 1: đọc 10, khách mua 2, admin lưu 10 là mất 2.
- **`RETURNING stock` của PostgreSQL** để biết ngay giá trị mới: không có trong JPQL và không chạy trên H2.
- **`SELECT … FOR UPDATE` rồi tính trong Java rồi ghi:** đúng nhưng thừa; câu UPDATE có điều kiện đã nguyên tử, và đó là cách ADR-0005 đã chọn.

**QĐ4. Danh mục: kiểm trùng tên không phân biệt hoa thường trong Java; `uq_categories_name` là lưới an toàn; xóa danh mục và thêm/sửa sản phẩm cùng khóa dòng `categories` (AC-006-16).**
- **So trùng trong Java, không bằng `lower()` của DB:** `lower('Đ')` trên PostgreSQL phụ thuộc locale của DB (image Alpine dùng musl), còn H2 dùng Java; hai bên có thể cho kết quả khác nhau. Bảng `categories` rất nhỏ, nên `categoryRepository.findAll()` rồi so `name.toLowerCase(Locale.ROOT)` (loại trừ chính nó khi sửa) là rẻ và chạy giống nhau ở mọi nơi. `FormText.clean` chuẩn hóa NFC trước, để `Đồ chơi` gõ kiểu tổ hợp (NFD) vẫn trùng với bản dựng sẵn.
- **Đua cùng một tên** (AC-006-13): cả hai qua bước kiểm, cả hai INSERT; PostgreSQL bắt INSERT thứ hai chờ ở unique index rồi báo vi phạm. `saveAndFlush` trong `try`, bắt `DataIntegrityViolationException` có tên ràng buộc chứa `uq_categories_name` (helper mới `common.ConstraintViolations.isViolationOf(e, name)`, cùng cách `UserService.register` vì H2 thêm tiền tố schema) → ném lại `CategoryNameTakenException` (unchecked, nên transaction vẫn rollback; **không nuốt** exception trong service). Controller bắt, gắn lỗi `Tên danh mục đã tồn tại.` vào ô tên.
- **Giới hạn đã biết:** hai admin tạo `Áo` và `áo` *đúng cùng lúc* có thể cùng thành công, vì ràng buộc DB phân biệt hoa thường. Spec §9 câu 6 đã chấp nhận (chỉ có một tài khoản admin seed); ghi ở mục 6.
- **Xóa danh mục và thêm sản phẩm cùng lúc:** cả hai đường **khóa dòng `categories` trước** bằng `CategoryRepository.lockById` (`PESSIMISTIC_WRITE`, PostgreSQL sinh `for no key update`, H2 `for update`):
  - `delete`: `lockById` → `countByCategoryId` → còn sản phẩm thì `InUse(tên, n)`, không thì `delete` + `flush` → `Deleted(tên)`;
  - `ProductAdminService.create`/`update`: `lockById(categoryId)` của danh mục đích → không có thì `CategoryNotFoundException` → lỗi `Danh mục không tồn tại.` dưới ô danh mục.
  - Xóa trước: yêu cầu thêm sản phẩm chờ ở khóa; sau khi xóa commit, câu khóa không còn thấy dòng → "Danh mục không tồn tại.". Thêm trước: yêu cầu xóa chờ; câu đếm (snapshot mới của READ COMMITTED) thấy sản phẩm vừa commit → "Không thể xóa…". Không có exception khóa ngoại, không trang 500, không sản phẩm mồ côi.
  - **Vì sao cần khóa riêng:** `for no key update` **không** chặn `INSERT products` (khóa ngoại chỉ lấy `FOR KEY SHARE` trên dòng danh mục). Nếu chỉ đếm rồi xóa, xóa sẽ va khóa ngoại và ra 500 hoặc phải bắt exception, mà sau exception transaction PostgreSQL đã hỏng nên không đếm lại được `n` cho thông báo.
  - **Thứ tự khóa mới (ghi vào ADR-0010):** `categories` (một dòng) → `products`. Không đường nào khóa `products` rồi mới tới `categories`; đặt hàng, giỏ và hủy đơn không chạm `categories`.
- `list()`: một câu JPQL `select new …CategoryRow(c.id, c.name, c.description, count(p.id)) from Category c left join Product p on p.category = c group by c.id, c.name, c.description`, đếm **cả sản phẩm đã ẩn** (AC-006-10); sắp bằng `Collator` tiếng Việt trong Java như GĐ2 (helper package-private `CategoryOptions` dùng chung với `ProductService`).

*Phương án bị loại:*
- **Index `unique (lower(name))` (migration V3):** chặn được cả đua khác hoa thường, nhưng `lower()` khác nhau giữa PostgreSQL/Alpine và H2 (spec §9 câu 6).
- **Đếm rồi xóa, bắt lỗi khóa ngoại:** xem trên; thông báo cần `n` mà transaction đã hỏng.

**QĐ5. `storage`: interface `StorageService` + bản `LocalStorageService`; ảnh hợp lệ được biểu diễn bằng kiểu `ImageUpload` (chỉ tạo được qua bước kiểm tra); file mới bị xóa khi rollback, file cũ bị xóa sau commit.**
- **API công khai** (mục 3): `StorageService` (`store`, `deleteAfterCommit`, `toUrl`), `ImageUpload`, `ImageFormat`, `InvalidImageException`. Bản cài đặt `LocalStorageService` và `StorageWebConfig` package-private. `storage` không phụ thuộc tính năng nào (rule `commonAndStorage_doNotDependOnFeatures` đã có) và chỉ dùng `spring-core`/`spring-tx`/`spring-webmvc`.
- **"Parse, don't validate":** `ImageUpload.from(originalFilename, contentType, size, InputStreamSource)` là cách duy nhất tạo `ImageUpload` (constructor private). Nó trả `Optional.empty()` khi không chọn file (size 0 và tên trống), ném `InvalidImageException(TOO_LARGE | UNSUPPORTED_TYPE)` khi sai, và chỉ trả đối tượng khi:
  1. `size ≤ 2 MiB` (2 097 152 byte), kiểm **trước khi đọc** nội dung;
  2. đuôi (sau dấu `.` cuối, thường hóa `Locale.ROOT`): `jpg`/`jpeg` → JPEG, `png` → PNG, `webp` → WEBP; khác (kể cả không đuôi, `.gif`, `.svg`, `.html`, `.exe`) → sai;
  3. content type khai báo (bỏ tham số, thường hóa): `image/jpeg`, `image/png`, `image/webp`;
  4. magic bytes (đọc tối đa `MAX + 1` byte, quá `MAX` → `TOO_LARGE`): JPEG `FF D8 FF`; PNG `89 50 4E 47 0D 0A 1A 0A`; WebP `RIFF` ở byte 0–3 và `WEBP` ở byte 8–11;
  5. **cả ba cùng chỉ một `ImageFormat`**; lệch nhau → `UNSUPPORTED_TYPE`.
  - `admin` gọi `ImageUpload.from(file.getOriginalFilename(), file.getContentType(), file.getSize(), file)` (`MultipartFile` là một `InputStreamSource`, nên `storage` không phụ thuộc Servlet API). Nhờ kiểu này, `ProductAdminService` và `StorageService.store` không thể nhận ảnh chưa kiểm.
- **`LocalStorageService`:**
  - thư mục gốc từ `@Value("${app.storage.local.upload-dir}")` (`application.yml`: `${UPLOAD_DIR:uploads}`), `toAbsolutePath().normalize()`, `Files.createDirectories` lúc khởi tạo;
  - `store`: tên `UUID.randomUUID() + "." + format.extension()` (`jpg`, `png`, `webp`); `target = root.resolve(name).normalize()` và kiểm `target.getParent().equals(root)`; `Files.write(target, bytes, CREATE_NEW, WRITE)` (không bao giờ ghi đè); tên gốc không được dùng, không được lưu, không được log; trả `"/uploads/" + name` (< 60 ký tự, vừa `image_path varchar(255)`);
  - **rollback:** nếu `TransactionSynchronizationManager.isSynchronizationActive()`, đăng ký `TransactionSynchronization.afterCompletion(status)`: `status != STATUS_COMMITTED` → xóa file vừa ghi (BR-006-11);
  - `deleteAfterCommit(imagePath)`: chỉ nhận chuỗi khớp `^/uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)$`; còn lại (ảnh seed `/images/products/…`, `null`, chuỗi lạ có `..`) bỏ qua (log debug). Có transaction → đăng ký `afterCommit`; không có → xóa ngay. Lỗi xóa chỉ log `warn`, không ném;
  - `toUrl(imagePath)`: trống/`null` → `null`, còn lại chính `imagePath` (bản local). GĐ8 thay bản cài đặt thì chỉ đổi ở đây.
- **Phục vụ ảnh:** `StorageWebConfig implements WebMvcConfigurer` (`@Configuration`, package-private) đăng ký `addResourceHandler("/uploads/**").addResourceLocations(<uri của thư mục gốc, luôn kết thúc bằng "/">)`. `PathResourceResolver` của Spring tự từ chối đường dẫn chứa `..` hoặc `%2e%2e` và kiểm file nằm trong location; `StrictHttpFirewall` của Spring Security chặn trước ở tầng filter (400). `SecurityConfig.PUBLIC_PATHS` thêm `"/uploads/**"` (chỉ GET, BR-006-1). Header `X-Content-Type-Options: nosniff` đã có mặc định; CSP `img-src 'self'` đã cho phép.
  - **Bẫy với slice test:** `@WebMvcTest` tự nạp mọi `WebMvcConfigurer`. Vì vậy `StorageWebConfig` **chỉ** được phụ thuộc `@Value` (luôn có, vì slice cũng đọc `application.yml`), không được inject `LocalStorageService` hay bean nào khác, nếu không mọi slice test sẽ hỏng. Hai lớp dùng chung một hàm static `UploadRoot.resolve(String)`.
- **Thứ tự trong `ProductAdminService`:** khóa danh mục (có thể ném `CategoryNotFoundException`) **trước** `store` ảnh, nên lỗi danh mục không bao giờ để lại file. Thay ảnh: `newPath = store(img)`; `old = p.getImagePath()`; `p.setImagePath(newPath)`; `deleteAfterCommit(old)`. Commit thì file cũ bị xóa; rollback thì file mới bị xóa; ảnh seed không bao giờ bị xóa (AC-006-30).
- `package-info.java` của `storage` ghi API, phụ thuộc (không tính năng nào) và câu "GĐ8 thay `LocalStorageService` bằng bản đám mây".

*Phương án bị loại:*
- **Phục vụ ảnh qua controller** (`GET /uploads/{file}` đọc file, tự kiểm tên): thêm code tự viết cho việc Spring đã làm đúng (Range, Last-Modified, chặn traversal); spec §9 câu 5 chọn resource handler.
- **Kiểm ảnh bằng `ImageIO.read`:** chậm, không đọc được WebP nếu không có plugin, và giải mã ảnh không tin cậy là bề mặt tấn công lớn hơn; spec chỉ đòi đuôi + content type + magic bytes.
- **Xóa file cũ ngay trong method:** nếu transaction rollback sau đó, sản phẩm vẫn trỏ tới file đã mất.

**QĐ6. Giới hạn multipart: nâng trần của Tomcat lên 10 MB, kiểm 2 MB trong ứng dụng (task DV1).**
- Đổi `spring.servlet.multipart.max-file-size` từ `2MB` thành `10MB` (`max-request-size` giữ `10MB`). Giới hạn 2 MB thật nằm ở `ImageUpload.from` (QĐ5) và cho đúng chữ `Ảnh tối đa 2 MB.` dưới ô ảnh, kèm mọi giá trị đã nhập (AC-006-29).
- **Vì sao không để `max-file-size: 2MB`:** Tomcat parse multipart ngay khi `CsrfFilter` của Spring Security gọi `request.getParameter("_csrf")`. File quá giới hạn làm việc parse thất bại, mọi tham số (kể cả `_csrf`) biến mất, nên admin nhận **trang 403** thay vì thông báo; `@ExceptionHandler(MaxUploadSizeExceededException)` không bao giờ được gọi vì request bị chặn ở filter trước DispatcherServlet. Với file 9 MB, phần thân còn lại vượt `server.tomcat.max-swallow-size` (mặc định 2MB, đã kiểm trong tài liệu Spring Boot 4.1.2) nên Tomcat còn **đóng kết nối** (trình duyệt báo "connection reset").
- Với trần 10 MB, request 2,5 MB hay 9 MB được parse trọn vẹn (file ghi tạm ra đĩa vì `file-size-threshold` mặc định 0), CSRF qua, controller nhận `MultipartFile` và trả form với lỗi. **Không đổi `max-swallow-size`:** nó chỉ có tác dụng với request vượt 10 MB, ngoài phạm vi AC; nâng nó chỉ đổi "ngắt kết nối" thành "trang 403", không tốt hơn (mục 6, câu 1).
- `server.tomcat.max-part-count` mặc định 50, form sản phẩm có 8 phần: không cần đổi.
- Đây là khóa hạ tầng nên thuộc `devops-engineer`; khóa `app.storage.local.upload-dir` là khóa tính năng nên thuộc `backend-developer` (B2). Cùng file `application.yml`, nên DV1 chạy **trước** B2.

*Phương án bị loại:*
- **Để 2 MB + `@ControllerAdvice` cho `MaxUploadSizeExceededException`:** không tới được (xem trên), và kể cả tới được cũng không còn giá trị form để hiện lại.
- **Đưa CSRF token lên query string của `action`** (một cách Spring Security gợi ý cho multipart): token lộ trong log và lịch sử trình duyệt.
- **`MultipartFilter` đặt trước Spring Security:** lỗi kích thước thành exception trong filter, ra trang lỗi chung (500), không hiện lại form.

**QĐ7. `catalog.ProductImages` thành bean gọi `StorageService.toUrl`; `cart` vẫn chỉ biết `catalog`.**
- Spec §7 chuyển việc "đường dẫn → URL" sang `StorageService`. `ProductImages` hiện là lớp static dùng bởi `ProductService` và `CartService`. Nó trở thành `@Component public class ProductImages` với constructor nhận `StorageService` và method `public String toUrl(String imagePath)`; `ProductService`, `CartService` inject nó qua constructor (task B4).
- **Vì sao không để `cart` gọi thẳng `StorageService`:** `cart` hiện bị cấm phụ thuộc `storage` (rule `cart_dependsOnlyOnAllowedPackages`), và giỏ không cần biết ảnh được lưu ở đâu. Đi qua `catalog` giữ đúng chiều `cart → catalog → storage`.
- Hệ quả: constructor của `ProductService` và `CartService` thêm một tham số, nên `ProductServiceTest`, `CartServiceTest` phải sửa ngay sau (task TW1). Đây là cặp task liền nhau có chủ đích (mục 4, "Build xanh").

**QĐ8. Form và đầu vào: form là `record` + Jakarta Validation, giữ chuỗi thô; id, trang, trạng thái, lượng điều chỉnh là chuỗi thô được parse riêng.**
- `common.FormText` (public, mới): `clean(value, multiline)`: `null` giữ `null`; chuẩn hóa NFC; `strip()`; bỏ ký tự điều khiển (ô nhiều dòng giữ `\n`, `\r`, `\t`); `blankToNull(value)`. Cùng quy tắc với `CheckoutForm` của GĐ5 (PostgreSQL từ chối byte `0x00`), nhưng đặt ở `common` để `admin` dùng; `CheckoutForm` không bị sửa ở GĐ6.
- **Giá (BR-006-5):** `admin.PriceInput.parse(raw) → Optional<BigDecimal>`: sau `strip()`, chấp nhận đúng một trong ba dạng `[0-9]+`, `[0-9]{1,3}(\.[0-9]{3})+`, `[0-9]{1,3}( [0-9]{3})+` (không trộn dấu chấm và dấu cách); bỏ dấu phân cách; tối đa 9 chữ số có nghĩa; giá trị 1–999 999 999; `BigDecimal.valueOf(long)` (scale 0). Ràng buộc `@VndPrice` coi `null`/trống là hợp lệ để `@NotBlank` báo "Vui lòng nhập giá." (một ô chỉ một lỗi, như `@PhoneNumber` ở GĐ5).
- **Tồn kho ban đầu (BR-006-6):** `@WholeNumber(min = 0, max = 100_000)`: `[0-9]{1,6}` trong khoảng; trống để `@NotBlank` báo.
- **Danh mục:** ô `categoryId` là chuỗi; `@NotBlank` "Vui lòng chọn danh mục."; `@Pattern(regexp = "|[0-9]{1,18}")` "Danh mục không tồn tại." (cho phép chuỗi rỗng để không ra hai lỗi). Danh mục có số nhưng không tồn tại do service báo (QĐ4).
- **Lượng điều chỉnh (BR-006-7):** `AdminInput.stockDelta(raw) → Optional<Integer>`: `strip()`, `[+-]?[0-9]{1,6}`, khác 0, `|delta| ≤ 100 000`; sai → flash `Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.`, **không gọi service**.
- **Id:** `AdminInput.id(raw)` (`[0-9]{1,18}`), sai → `NotFoundException` trước khi gọi service. Trang và `status` được chuyển nguyên chuỗi cho service (`catalog`/`order` tự parse, sai → trang 1 / "Tất cả").
- **Mass assignment (AC-006-24):** `NewProductForm` không có `id`, `stock`, `imagePath`, `createdAt`; `EditProductForm` còn không có `initialStock`, `active`. Spring bỏ qua tham số thừa. Id luôn lấy từ URL; `image_path` chỉ đổi qua upload; `active` chỉ qua ẩn/hiện.
- **Ô "Đang bán":** `Boolean active`; `null` (không tick, trình duyệt không gửi gì) là `false`. Template dùng `<input type="checkbox" name="active" value="true" th:checked="${form.active}">`, **không** dùng `th:field` (để không phụ thuộc trường ẩn `_active` của Spring). Form trống ở `GET /admin/products/new` có `active = true` (mặc định bật, AC-006-18).
- **Ảnh nằm trong form record** (`MultipartFile image`): constructor binding của Spring 7 lấy được `MultipartFile` cho tham số của record (đã kiểm mã nguồn `ServletRequestDataBinder`), nên controller dùng `result.rejectValue("image", …)` và template dùng `#fields.hasErrors('image')` như mọi ô khác. `toString()` của form không in file. Ô file không bao giờ được điền lại (trình duyệt không cho).

**QĐ9. Tầng web của `admin`: bốn controller, PRG + flash, 404 trong layout quản trị bằng `@ExceptionHandler` cục bộ.**
- `AdminDashboardController` (`GET /admin`, **thay** `AdminController`), `AdminCategoryController` (`/admin/categories`), `AdminProductController` (`/admin/products`), `AdminOrderController` (`/admin/orders`). Package-private, constructor injection, nhận `@AuthenticationPrincipal CurrentUser` khi ghi (lấy `getId()` cho log).
- Sau mỗi `POST` thành công: `redirect:` + flash `notice`, `noticeKind` (`success` | `error` | `info`), đúng quy ước GĐ4–5. Lỗi validate: render lại form, HTTP 200. Redirect luôn dựng từ id kiểu `long` đã parse; ẩn/hiện từ danh sách giữ `q` và `page` bằng `UriComponentsBuilder` (đường dẫn cố định `/admin/products`, chỉ thêm query đã encode), không bao giờ đọc `Referer`.
- **404 (AC-006-5):** mỗi controller có một method `@ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})` + `@ResponseStatus(NOT_FOUND)`, nhận `Model` (Spring cho phép, model rỗng) và trả `admin/not-found` với `notFoundTitle`, `backUrl`, `backLabel`, `section`. Handler đặt **trong controller** luôn được ưu tiên hơn mọi `@ControllerAdvice`, nên không cần `@Order` như các advice ở GĐ2–5. Dashboard không có id nên không cần.
- `CategoryNameTakenException`, `CategoryNotFoundException`, `InvalidImageException` được bắt trong controller **sau khi** transaction của service đã kết thúc, rồi chuyển thành lỗi dưới ô (`rejectValue`) và render lại form (200).
- Chữ hiển thị là hằng `private static final` trong controller, đúng từng chữ spec §6.
- `SecurityConfig` chỉ thêm `/uploads/**` (B2). `/admin/**` đã là `ADMIN`; CSRF mặc định bật nên mọi POST (kể cả multipart) thiếu token là 403.

**QĐ10. Dashboard: bốn câu SQL cố định, không phụ thuộc số đơn hay số sản phẩm (AC-006-9).**
- `OrderAdminService.dashboard()` (`readOnly`), ba câu:
  1. `select o.status, count(o) from Order o group by o.status` → map đủ năm trạng thái theo thứ tự enum, thiếu thì 0;
  2. `select coalesce(sum(o.totalAmount), 0) from Order o where o.status = :status` với `DELIVERED`;
  3. `findTop5ByOrderByCreatedAtDescIdDesc()` có `@EntityGraph("user")`: một câu có `LIMIT 5`, không có câu count (khác `Page`).
- `ProductAdminService.lowStock()` (`readOnly`), một câu: `findTop10ByActiveTrueAndStockLessThanEqualOrderByStockAscIdAsc(5)`.
- Controller gọi hai method, tức **hai transaction chỉ đọc**. Các số của đơn (đếm, doanh thu, đơn mới nhất) nằm cùng một transaction nên khớp nhau; danh sách sắp hết hàng đọc ngay sau. Không có cache (BR-006-14). Gom cả bốn câu vào một transaction thì phải để `order` gọi `catalog` cho một việc không phải của đơn hàng, hoặc thêm nghiệp vụ vào `admin`; không đáng cho một trang tổng quan (mục 6, "chỉ để bạn biết").

**QĐ11. Danh sách sản phẩm và đơn của admin: phân trang ở DB, 20 dòng, không N+1, trang sai hoặc vượt quá về trang 1.**
- **Sản phẩm:** dùng lại `productRepository.findAll(spec, pageable)` (đã có `@EntityGraph("category")`), **không** thêm `isActive()` (gồm cả sản phẩm đã ẩn). `q` chuẩn hóa bằng `ProductFilter.normalizeQuery` (đổi từ `private` thành package-private static), lọc bằng `ProductSpecifications.nameContains` (escape sẵn). Sắp `createdAt DESC, id DESC`. Hai câu SQL (trang + count).
- Phân biệt trống: `totalElements == 0` và không có `q` → `Chưa có sản phẩm nào.`; có `q` → `Không tìm thấy sản phẩm phù hợp.`. Không cần câu đếm thứ ba.
- **Đơn:** `@EntityGraph("user")` trên `findByStatus(status, pageable)` và `findAllBy(pageable)` (tên khác `findAll` để không đổi hành vi của `findAll` mặc định). `user` là `@ManyToOne` nên LIMIT/OFFSET vẫn chạy trong SQL; câu count không join. Sắp `createdAt DESC, id DESC`. Hai câu SQL.
- Trang vượt quá (nội dung rỗng mà `totalElements > 0`) → truy vấn lại trang 1, như GĐ5. Trang kẹp 1..10 000.
- Link phân trang giữ `q`/`status`: controller dựng bằng `UriComponentsBuilder` (encode `q`), đưa vào `PageNav.of`; template không tự ghép URL.
- **Chi tiết đơn:** `@EntityGraph(attributePaths = {"items", "user"})` trên `findAdminDetailById(id)`: một câu SQL.

**QĐ12. Không có migration V3.**
- `idx_orders_status` và `idx_orders_created_at` đủ cho `where status = ? order by created_at desc, id desc limit 20` ở quy mô cửa hàng nhỏ; index ghép `(status, created_at)` là tối ưu chưa đo được (để ở mục 7).
- Không thêm `@Version` (spec §9 câu 13), không thêm index `lower(name)` (QĐ4), không thêm cột lý do hủy (spec §9 câu 12).
- Nếu task nào thấy buộc phải đổi `@Column`, quan hệ, hay thêm migration, thì **dừng lại và báo phiên chính** (khi đó thêm vòng `schema-guardian`).

**QĐ13. Giao diện: layout quản trị riêng theo nhánh "app screen" của skill `design-taste-frontend`; CSP giữ nguyên.**
- Vẽ hướng thiết kế **một lần** cho khu `/admin`, giữ palette và chữ của storefront: `node <skill>/scripts/draw.mjs --app --kind <kind phù hợp, xem --list kinds> --brief "Trang quản trị cửa hàng mini: đơn hàng, sản phẩm, danh mục, tồn kho" --keep palette,type --out .taste-skill/app`. **Không** ghi đè `.taste-skill/direction.json` của storefront.
- Ràng buộc của dự án đè lên skill (CLAUDE.md): Thymeleaf + Bootstrap 5.3; không biểu đồ (spec loại), không số liệu bịa, không thêm khối hay tính năng; không font hay CDN ngoài; không `<script>` nội tuyến, `style=`, `on…=`; mọi chữ người dùng nhập qua `th:text`; tiền qua `#vnd.format`, ngày qua `#vnDateTime.format`.
- File: `templates/fragments/admin.html` (fragment `head(title)`, `sidebar(section)`), `static/css/admin.css` (mới, mọi selector nằm dưới `.admin-body`; `head` nạp Bootstrap, `app.css` để dùng lại font, token, `.notice`, `.field-error`, rồi `admin.css`). Storefront không đổi.
- Sidebar gập trên điện thoại **chỉ bằng CSS** (thành thanh ngang ở đầu trang dưới breakpoint `md`), không có nút JS. Bảng nằm trong khung cuộn ngang riêng; trang không cuộn ngang ở 375px (AC-006-50).
- `confirm.js` dùng lại, chỉ nạp ở `admin/category-list.html` và `admin/order-detail.html` (như GĐ5: chỉ trang cần).
- Tiêu đề tab nối chuỗi trong biểu thức: `head(${'Đơn hàng #' + order.id + ' | Quản trị Mini Shop'})`, không dùng literal `|…|` (bài học GĐ5).

**QĐ14. Chiến lược test.**
- **Slice web** của `admin`: `@WebMvcTest(XController.class)` + `@Import({SecurityConfig.class, CartHeaderTestConfig.class})` (dialect `#cartHeader` được nạp vào mọi slice dù layout quản trị không gọi nó) + `@MockitoBean` các service public (`OrderAdminService`, `ProductAdminService`, `CategoryAdminService`). Người dùng `TestUsers.admin()` / `user()`; POST luôn `.with(csrf())` trừ khi ghi rõ. Multipart bằng `multipart(...)` + `MockMultipartFile`.
- **PostgreSQL** theo khuôn `OrderIntegrationTest`: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)` + `@Testcontainers(disabledWithoutDocker = true)`, không `@Transactional`, không `@MockitoSpyBean`. Race: hai luồng, `CountDownLatch`, 20 vòng, dữ liệu mới mỗi vòng. Đếm truy vấn bằng `Statistics` của Hibernate như GĐ5.
- **Dữ liệu test:** fixture public mới `com.minishop.support.AdminTestData` (người dùng `@admin.test`, danh mục và sản phẩm tên bắt đầu `Admin test `, dọn `order_items → orders → cart_items → carts → products → categories → users` của chính nó, và xóa các file upload nó ghi nhận) và `com.minishop.support.TestImages` (byte của PNG/JPEG/WebP hợp lệ theo magic bytes với kích thước tùy chọn, GIF, SVG, HTML đổi tên). Không sửa tài khoản hay sản phẩm seed.
- **Dashboard trên DB dùng chung:** các lớp test khác cũng tạo đơn, nên `AdminDashboardIntegrationTest` so **chênh lệch** trước/sau khi tạo dữ liệu của mình (+3, +1, +0, +2, +1, doanh thu +450 000), không so số tuyệt đối. Số tuyệt đối của AC-006-6 được kiểm ở slice test với service mock.
- **Thư mục upload của test:** file mới `src/test/resources/config/application.yml` đặt `app.storage.local.upload-dir: target/test-uploads`. Spring Boot đọc `classpath:/config/application.yml` **sau** `classpath:/application.yml` nên khóa này thắng cho mọi test mà **không** đổi khóa cache của Spring context (khác `@DynamicPropertySource` ở từng lớp, vốn tạo context mới, và với `TestcontainersConfiguration` là một container mới). `LocalStorageServiceTest` và `UploadsServingTest` dùng `@TempDir` riêng. `mvn clean` dọn `target/`.
- **Giới hạn upload trên Tomcat thật (AC-006-29):** MockMvc bỏ qua Tomcat, nên thêm `admin.AdminUploadLimitTest`: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("h2")` + URL H2 riêng (`spring.datasource.url=jdbc:h2:mem:upload-limit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`, để không chung DB với `H2ProfileSmokeTest`), đăng nhập thật bằng `java.net.http.HttpClient` có cookie, lấy `_csrf` từ form, gửi multipart 2,5 MB và 9 MB. Chạy được cả khi không có Docker.
- **H2:** thêm một test vào `H2ProfileSmokeTest` cho luồng admin (AC-006-51), tự dọn ở `finally`.

**QĐ15. ADR-0010 viết trước khi code (task A0), hoàn tất ở cuối giai đoạn (task D).**
- Tên: "Ảnh sản phẩm qua `StorageService` phục vụ tại `/uploads/**`, và admin điều chỉnh tồn kho bằng câu UPDATE có điều kiện".
- Nội dung: QĐ3 (câu `adjustStock`, phân loại sau 0 dòng, chỉ `ProductAdminService` gọi, không có ô tồn kho tuyệt đối), QĐ5 (interface, kiểm ba lớp, UUID, phục vụ bằng resource handler, dọn file sau commit/rollback), QĐ6 (trần multipart 10 MB, kiểm 2 MB trong ứng dụng), QĐ4 phần khóa (bổ sung bảng thứ tự khóa của ADR-0009 với dòng `categories → products`), các phương án bị loại tương ứng.
- A0 tạo file với trạng thái `Đề xuất` và thêm dòng vào `docs/adr/README.md`. D thêm SQL thật mà TW5 báo cáo và đổi thành `Chấp nhận` sau khi V đạt. ADR-0005, 0008, 0009 giữ nguyên (không bị thay thế: ADR-0010 bổ sung, không đổi quyết định cũ).

## 3. Hợp đồng code và view (chốt trước để backend, frontend, test làm song song)

Nếu cần thêm thuộc tính hay đổi tên, agent dừng lại và báo phiên chính; không tự đặt tên.

```java
// ── storage (public) ─────────────────────────────────────────────────────────────
public interface StorageService {
    String store(ImageUpload image);          // "/uploads/{uuid}.{jpg|png|webp}"; trong transaction: rollback thì xóa file
    void deleteAfterCommit(String imagePath); // chỉ "/uploads/{uuid}.{ext}"; ảnh seed, null, chuỗi lạ: bỏ qua
    String toUrl(String imagePath);           // null/trống -> null; bản local: chính imagePath
}
public enum ImageFormat { JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp");
    public String extension(); public String contentType(); }
public final class ImageUpload {
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static Optional<ImageUpload> from(String originalFilename, String contentType, long size,
                                             InputStreamSource content);   // InvalidImageException
    public ImageFormat format(); public long size();
    byte[] bytes();                                // package-private, cho LocalStorageService
    // toString(): chỉ format và size
}
public final class InvalidImageException extends RuntimeException {
    public enum Reason { UNSUPPORTED_TYPE, TOO_LARGE } public Reason reason(); }
// storage (package-private)
final class UploadRoot { static Path resolve(String configured); }          // toAbsolutePath().normalize()
@Service class LocalStorageService implements StorageService {
    LocalStorageService(@Value("${app.storage.local.upload-dir}") String uploadDir); Path root(); }
@Configuration class StorageWebConfig implements WebMvcConfigurer {
    StorageWebConfig(@Value("${app.storage.local.upload-dir}") String uploadDir); }   // KHÔNG inject bean nào khác

// ── common (public, mới) ─────────────────────────────────────────────────────────
public final class FormText { public static String clean(String value, boolean multiline); public static String blankToNull(String value); }
public final class ConstraintViolations { public static boolean isViolationOf(Throwable error, String constraintName); } // contains, không phân biệt hoa thường

// ── catalog ──────────────────────────────────────────────────────────────────────
class Category { void update(String name, String description); }                           // thêm method
class Product  { void updateDetails(Category category, String name, String description, BigDecimal price); } // thêm method, KHÔNG đụng stock

interface CategoryRepository {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Category c where c.id = :id")
    Optional<Category> lockById(@Param("id") Long id);
    @Query("select new com.minishop.catalog.CategoryRow(c.id, c.name, c.description, count(p.id)) "
         + "from Category c left join Product p on p.category = c group by c.id, c.name, c.description")
    List<CategoryRow> findAllWithProductCount();
}
interface ProductRepository {
    int adjustStock(Long id, int delta);                    // xem QĐ3 (câu JPQL đầy đủ)
    long countByCategoryId(Long categoryId);
    List<Product> findTop10ByActiveTrueAndStockLessThanEqualOrderByStockAscIdAsc(int maxStock);
}
// public, mới
public record CategoryOption(Long id, String name) {}         // đã có, đổi thành public
public record CategoryRow(long id, String name, String description, long productCount) {}
public record CategoryEditView(long id, String name, String description) {}
public record CategoryDraft(String name, String description) {}   // compact: name không trống, ≤ 100; description ≤ 1000 hoặc null
public sealed interface CategoryDeleteResult { record Deleted(String name) …; record InUse(String name, long productCount) …; }
public final class CategoryNameTakenException extends RuntimeException {}
public final class CategoryNotFoundException extends RuntimeException {}   // danh mục chọn trong form sản phẩm không còn
public record ProductDraft(String name, long categoryId, BigDecimal price, String description) {}
public record SavedProduct(long id, String name) {}
public record AdminProductRow(long id, String name, String categoryName, BigDecimal price, int stock,
                              boolean active, String imageUrl) {}
public record AdminProductPage(List<AdminProductRow> products, String q, int page, int totalPages,
                               long totalElements) { public boolean isEmpty(); }        // totalElements == 0
public record ProductEditView(long id, String name, long categoryId, BigDecimal price, String description,
                              int stock, boolean active, String imageUrl) {}
public record LowStockRow(long id, String name, int stock) {}
public sealed interface StockAdjustResult {
    record Adjusted(String name, int stock) …; record NotEnough(String name, int requested, int current) …;
    record WouldExceed(String name) …; }
@Component public class ProductImages { public ProductImages(StorageService storage); public String toUrl(String imagePath); }
final class CategoryOptions { static List<CategoryOption> sortedByName(List<Category> categories); }   // Collator "vi", package-private

@Service @Transactional public class CategoryAdminService {
    @Transactional(readOnly = true) public List<CategoryRow> list();                       // sắp Collator vi
    @Transactional(readOnly = true) public CategoryEditView get(long id);                 // NotFoundException
    @Transactional(readOnly = true) public List<CategoryOption> options();
    public String create(long adminId, CategoryDraft draft);                              // CategoryNameTakenException; trả tên đã lưu
    public String update(long adminId, long id, CategoryDraft draft);                     // NotFoundException, CategoryNameTakenException
    public CategoryDeleteResult delete(long adminId, long id);                            // NotFoundException
}
@Service @Transactional public class ProductAdminService {
    static final int PAGE_SIZE = 20, MAX_STOCK = 100_000, LOW_STOCK_THRESHOLD = 5, LOW_STOCK_LIMIT = 10;
    @Transactional(readOnly = true) public AdminProductPage list(String rawQ, String rawPage);
    @Transactional(readOnly = true) public ProductEditView getForEdit(long id);           // NotFoundException
    @Transactional(readOnly = true) public List<LowStockRow> lowStock();
    public SavedProduct create(long adminId, ProductDraft draft, int initialStock, boolean active,
                               ImageUpload image /* null = không có ảnh */);               // CategoryNotFoundException
    public String update(long adminId, long id, ProductDraft draft, ImageUpload image);  // NotFoundException, CategoryNotFoundException
    public String setActive(long adminId, long id, boolean active);                       // NotFoundException; trả tên
    public StockAdjustResult adjustStock(long adminId, long id, int delta);              // NotFoundException
}

// ── order ────────────────────────────────────────────────────────────────────────
enum OrderStatus { …; String actionLabel(); }   // CONFIRMED "Xác nhận đơn", SHIPPING "Bắt đầu giao hàng",
                                                // DELIVERED "Xác nhận đã giao", CANCELLED "Hủy đơn", PENDING -> IllegalStateException
final class OrderInput { …; static Optional<OrderStatus> status(String raw); }   // strip, khớp đúng name(); sai -> rỗng
final class OrderCancellation { static int cancelAndRestoreStock(Order lockedOrder, ProductRepository products); }
record StatusTotal(OrderStatus status, long count) {}   // package-private; nếu Hibernate không gọi được constructor thì dùng projection, báo lại
interface OrderRepository {
    @EntityGraph(attributePaths = "user") Page<Order> findAllBy(Pageable pageable);
    @EntityGraph(attributePaths = "user") Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    @EntityGraph(attributePaths = {"items", "user"}) @Query("select o from Order o where o.id = :id")
    Optional<Order> findAdminDetailById(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);                                     // CHỈ OrderAdminService
    @Query("select new com.minishop.order.StatusTotal(o.status, count(o)) from Order o group by o.status")
    List<StatusTotal> countByStatus();
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status = :status")
    BigDecimal sumTotalByStatus(@Param("status") OrderStatus status);
    @EntityGraph(attributePaths = "user") List<Order> findTop5ByOrderByCreatedAtDescIdDesc();
}
// public, mới
public record OrderLineView(…) {}                        // đã có, đổi thành public
public record AdminOrderRow(long id, Instant createdAt, String customerName, String customerEmail,
                            String receiverName, BigDecimal totalAmount, String statusLabel, String statusKey) {}
public record StatusFilterOption(String value /* null = Tất cả */, String label, boolean selected) {}
public record AdminOrderPage(List<AdminOrderRow> orders, String status /* null = Tất cả */, String statusLabel,
                             List<StatusFilterOption> filters, int page, int totalPages, long totalElements) {
    public boolean isEmpty(); }
public record StatusAction(String targetStatus, String label, String confirmMessage /* null = không hỏi */, boolean danger) {}
public record AdminOrderDetail(long id, Instant createdAt, Instant updatedAt, String statusLabel, String statusKey,
                               String paymentMethodLabel, String customerName, String customerEmail,
                               String receiverName, String phone, String shippingAddress, String note,
                               List<OrderLineView> lines, BigDecimal totalAmount, List<StatusAction> actions) {
    public boolean isFinalStatus(); }                     // actions.isEmpty()
public record StatusCount(String status, String label, long count) {}
public record OrderDashboard(List<StatusCount> counts, BigDecimal deliveredRevenue, List<AdminOrderRow> latest) {}
public sealed interface StatusChangeResult {
    record Changed(String statusLabel) …; record Cancelled() …;
    record NotAllowed(String fromLabel, String toLabel) …; record InvalidStatus() …; }
@Service @Transactional public class OrderAdminService {
    static final int PAGE_SIZE = 20;
    @Transactional(readOnly = true) public AdminOrderPage listOrders(String rawStatus, String rawPage);
    @Transactional(readOnly = true) public AdminOrderDetail getOrder(long orderId);       // NotFoundException
    @Transactional(readOnly = true) public OrderDashboard dashboard();
    public StatusChangeResult changeStatus(long adminId, long orderId, String rawTargetStatus);   // NotFoundException
}
// confirmMessage: CANCELLED -> "Hủy đơn hàng #{id} và hoàn lại tồn kho?";
//                 DELIVERED -> "Đánh dấu đơn hàng #{id} đã giao? Không thể hoàn tác."; còn lại null. danger = (đích là CANCELLED)

// ── admin (tất cả package-private) ───────────────────────────────────────────────
final class AdminInput { static Optional<Long> id(String raw); static Optional<Integer> stockDelta(String raw); static int page(String raw); }
final class PriceInput { static Optional<BigDecimal> parse(String raw); }
@interface VndPrice { … }    @interface WholeNumber { long min(); long max(); … }       // null/trống: hợp lệ
record CategoryForm(String name, String description) {
    static CategoryForm empty(); static CategoryForm of(CategoryEditView v); CategoryDraft toDraft(); }
record NewProductForm(String name, String categoryId, String price, String initialStock, String description,
                      Boolean active, MultipartFile image) {
    static NewProductForm empty();            // active = TRUE
    ProductDraft toDraft(); int initialStockValue(); boolean isActiveChecked(); /* toString: không in image */ }
record EditProductForm(String name, String categoryId, String price, String description, MultipartFile image) {
    static EditProductForm of(ProductEditView v);   // price = v.price().toPlainString()
    ProductDraft toDraft(); }
@Controller class AdminDashboardController { … }                         // GET /admin
@Controller @RequestMapping("/admin/categories") class AdminCategoryController { … }
@Controller @RequestMapping("/admin/products")   class AdminProductController { … }
@Controller @RequestMapping("/admin/orders")     class AdminOrderController { … }
```

Ràng buộc và chữ lỗi của form (đúng spec §6):

| Form, ô | Ràng buộc |
|---|---|
| `CategoryForm.name` | `@NotBlank` "Vui lòng nhập tên danh mục."; `@Size(max = 100)` "Tên danh mục tối đa 100 ký tự."; trùng (service) "Tên danh mục đã tồn tại." |
| `CategoryForm.description` | `@Size(max = 1000)` "Mô tả tối đa 1000 ký tự."; trống → `null` |
| `*ProductForm.name` | `@NotBlank` "Vui lòng nhập tên sản phẩm."; `@Size(max = 200)` "Tên sản phẩm tối đa 200 ký tự." |
| `*ProductForm.categoryId` | `@NotBlank` "Vui lòng chọn danh mục."; `@Pattern("\|[0-9]{1,18}")` và service: "Danh mục không tồn tại." |
| `*ProductForm.price` | `@NotBlank` "Vui lòng nhập giá."; `@VndPrice` "Giá phải là số nguyên từ 1 đến 999.999.999 ₫." |
| `NewProductForm.initialStock` | `@NotBlank` "Vui lòng nhập tồn kho ban đầu."; `@WholeNumber(min = 0, max = 100000)` "Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000." |
| `*ProductForm.description` | `@Size(max = 4000)` "Mô tả tối đa 4000 ký tự."; ô nhiều dòng; trống → `null` |
| `*ProductForm.image` | controller: `UNSUPPORTED_TYPE` "Ảnh chỉ được là JPG, PNG hoặc WebP."; `TOO_LARGE` "Ảnh tối đa 2 MB." |

| View | URL | Status | Model / flash |
|---|---|---|---|
| `admin/index` | `GET /admin` | 200 | `orderStats` (`OrderDashboard`), `lowStock` (`List<LowStockRow>`) |
| `admin/category-list` | `GET /admin/categories` | 200 | `categories` (`List<CategoryRow>`); flash |
| `admin/category-form` | `GET /admin/categories/new`, `/{id}/edit`; POST lỗi | 200 | `form`, `mode` (`new` \| `edit`), `categoryId` (khi sửa) |
| redirect `/admin/categories` | `POST /admin/categories`, `/{id}`, `/{id}/delete` | 302 | flash `notice`, `noticeKind` |
| `admin/product-list` | `GET /admin/products?q=&page=` | 200 | `products` (`AdminProductPage`), `pageNav`; flash |
| `admin/product-new` | `GET /admin/products/new`; POST lỗi | 200 | `form` (`NewProductForm`), `categories` (`List<CategoryOption>`) |
| `admin/product-edit` | `GET /admin/products/{id}/edit`; POST lỗi | 200 | `product` (`ProductEditView`), `form` (`EditProductForm`), `categories`; flash |
| redirect `/admin/products/{id}/edit` | `POST /admin/products`, `/{id}`, `/{id}/stock`, ẩn/hiện với `from=edit` | 302 | flash |
| redirect `/admin/products?q=&page=` | ẩn/hiện với `from=list` (mặc định) | 302 | flash |
| `admin/order-list` | `GET /admin/orders?status=&page=` | 200 | `orders` (`AdminOrderPage`), `pageNav` |
| `admin/order-detail` | `GET /admin/orders/{id}` | 200 | `order` (`AdminOrderDetail`); flash |
| redirect `/admin/orders/{id}` | `POST /admin/orders/{id}/status` | 302 | flash |
| `admin/not-found` | id sai / không có (GET và POST) | 404 | `notFoundTitle`, `backUrl`, `backLabel`, `section` |
| (static) `/uploads/{uuid}.{ext}` | GET, công khai | 200 / 404 | – |

Thông báo flash (`noticeKind`):

| Hành động | `success` | `error` |
|---|---|---|
| Danh mục | `Đã thêm danh mục {tên}.` / `Đã cập nhật danh mục {tên}.` / `Đã xóa danh mục {tên}.` | `Không thể xóa danh mục {tên} vì còn {n} sản phẩm (kể cả sản phẩm đã ẩn).` |
| Sản phẩm | `Đã thêm sản phẩm {tên}.` / `Đã cập nhật sản phẩm {tên}.` / `Đã ẩn sản phẩm {tên}. Khách sẽ không thấy sản phẩm này nữa.` / `Đã hiện lại sản phẩm {tên}.` | – |
| Tồn kho | `Đã cập nhật tồn kho sản phẩm {tên}. Tồn kho hiện tại: {n}.` | `Không thể giảm {x} vì tồn kho hiện tại chỉ còn {n}.` / `Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.` / `Tồn kho sau điều chỉnh không được vượt quá 100.000.` |
| Đơn | `Đã chuyển đơn hàng #{id} sang trạng thái {nhãn}.` / `Đã hủy đơn hàng #{id} và hoàn lại tồn kho.` | `Không thể chuyển đơn hàng #{id} từ {nhãn hiện tại} sang {nhãn đích}.` / `Trạng thái không hợp lệ.` |

Quy ước markup để test và verifier dựa vào (chữ đúng spec §6):

- **`fragments/admin.html`:**
  - `head(title)`: `<title th:text="${title}">`, Bootstrap qua WebJars, `/css/app.css`, `/css/admin.css`; không script;
  - `sidebar(section)`: `<nav aria-label="Quản trị">` có đúng năm link theo thứ tự `Tổng quan` (`/admin`), `Đơn hàng` (`/admin/orders`), `Sản phẩm` (`/admin/products`), `Danh mục` (`/admin/categories`), `Về cửa hàng` (`/`); link của `section` (`dashboard` \| `orders` \| `products` \| `categories`) có `aria-current="page"`; `Xin chào, {fullName}` bằng `th:text`; `<form th:action="@{/logout}" method="post">` + nút `Đăng xuất`;
  - mọi trang admin: `<body class="admin-body">`, sidebar, `<main>` có đúng một `h1`, notice (`fragments/notice`) khi có `notice`. **Không** có header/footer storefront.
- **Tiêu đề tab và `h1`:** `{Tên trang} | Quản trị Mini Shop` với `Tổng quan`, `Danh mục`, `Thêm danh mục`, `Sửa danh mục`, `Sản phẩm`, `Thêm sản phẩm`, `Sửa sản phẩm`, `Đơn hàng`, `Đơn hàng #{id}`; 404: `Không tìm thấy sản phẩm` / `Không tìm thấy đơn hàng` / `Không tìm thấy danh mục`. `h1` trùng tên trang.
- **Form:** mọi POST dùng `th:action` (thêm `_csrf`), `novalidate`; ô dùng `th:field`, `label for` khớp `id`; lỗi `<p class="field-error" id="{ô}-error" th:errors="*{ô}">`, ô lỗi có `aria-invalid="true"` và `aria-describedby="{ô}-error"`; form sản phẩm `enctype="multipart/form-data"`, ô ảnh `<input type="file" id="image" name="image" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" aria-describedby="image-hint">` + `<p id="image-hint">JPG, PNG hoặc WebP, tối đa 2 MB.</p>` (khi lỗi, `aria-describedby="image-hint image-error"`); ô `Đang bán` theo QĐ8; nút `Lưu`, link `Hủy` về danh sách.
- **`admin/index.html`:** `h1` `Tổng quan`; năm ô theo `orderStats.counts` (nhãn + số, link `/admin/orders?status={status}`); ô `Doanh thu (đơn đã giao)` = `#vnd.format(orderStats.deliveredRevenue)`; mục `Sắp hết hàng` (tên link `/admin/products/{id}/edit`, tồn kho) hoặc `Không có sản phẩm nào sắp hết hàng.`; mục `Đơn hàng mới nhất` (bảng như danh sách đơn, mã `#{id}` link `/admin/orders/{id}`) hoặc `Chưa có đơn hàng nào.`; link `Xem tất cả đơn hàng` (`/admin/orders`). Không biểu đồ.
- **`admin/category-list.html`:** nút `Thêm danh mục` (`/admin/categories/new`); bảng có `caption` ẩn, `<th scope="col">` `Tên`, `Mô tả`, `Số sản phẩm`, `Thao tác`; mỗi dòng link `Sửa` và `<form method="post" th:action="@{/admin/categories/{id}/delete(id=…)}" th:attr="data-confirm=|Xóa danh mục ${c.name}? Không thể hoàn tác.|">` + nút `Xóa`; trống: `Chưa có danh mục nào.`, không `<table`; cuối body `<script th:src="@{/js/confirm.js}" defer></script>`.
- **`admin/category-form.html`:** `h1` `Thêm danh mục` / `Sửa danh mục`; action `/admin/categories` hoặc `/admin/categories/{id}`; nhãn `Tên danh mục` (`name`, `maxlength="100"`), `Mô tả (không bắt buộc)` (`<textarea>` `description`, `maxlength="1000"`).
- **`admin/product-list.html`:** nút `Thêm sản phẩm`; form tìm `GET /admin/products`, `<input type="search" name="q" placeholder="Tìm theo tên sản phẩm…">` có `label` (ẩn được), nút `Tìm`; bảng cột `Ảnh`, `Tên`, `Danh mục`, `Giá`, `Tồn kho`, `Trạng thái` (`Đang bán` / `Đã ẩn`, có chữ), `Thao tác` (`Sửa`, và form POST `…/hide` hoặc `…/show` với `from=list`, `q`, `page` ẩn, nút `Ẩn` / `Hiện lại`); ảnh `alt` = tên, `null` thì `/images/placeholder-product.svg`; trống: `Chưa có sản phẩm nào.` / `Không tìm thấy sản phẩm phù hợp.`; `fragments/pagination :: pagination(${pageNav})`.
- **`admin/product-new.html`:** khi `categories` rỗng: chỉ `Cần tạo danh mục trước khi thêm sản phẩm.` + link `Thêm danh mục` (`/admin/categories/new`), **không** `<form` sản phẩm. Ngược lại form `action="/admin/products"`: `Tên sản phẩm` (`name`, `maxlength="200"`), `Danh mục` (`<select id="categoryId" name="categoryId">` có option rỗng `Chọn danh mục` đầu tiên rồi danh mục theo thứ tự đã sắp), `Giá (₫)` (`price`, `inputmode="numeric"`), `Tồn kho ban đầu` (`initialStock`, `inputmode="numeric"`), `Mô tả (không bắt buộc)`, `Ảnh sản phẩm`, `Đang bán`.
  - Option rỗng là chữ mới duy nhất ngoài spec (nhãn cho lựa chọn trống của `<select>`); mục 6 ghi lại.
- **`admin/product-edit.html`:** form `action="/admin/products/{id}"` như trên nhưng **không** có `initialStock`, `active`; nhãn ảnh `Ảnh mới (không bắt buộc)` kèm ảnh hiện tại (`product.imageUrl` hoặc ảnh giữ chỗ); trạng thái + form `…/hide` hoặc `…/show` với `from=edit`; link `Xem trên cửa hàng` (`/products/{id}`) chỉ khi `product.active`; mục `Điều chỉnh tồn kho`: `Tồn kho hiện tại: {n}`, form `POST /admin/products/{id}/stock`, `<input type="text" id="delta" name="delta" inputmode="numeric">` nhãn `Số lượng thay đổi`, gợi ý `Số dương để nhập thêm, số âm để giảm (ví dụ: 20 hoặc -3).` (`aria-describedby`), nút `Cập nhật tồn kho`.
- **`admin/order-list.html`:** bộ lọc là danh sách link: `Tất cả` (`/admin/orders`) + năm nhãn (`/admin/orders?status=…`), mục đang chọn có `aria-current="page"`; bảng cột `Mã đơn` (`#{id}` link `/admin/orders/{id}`), `Ngày đặt` (`#vnDateTime`), `Khách hàng` (họ tên, email), `Người nhận`, `Tổng tiền`, `Trạng thái` (`order-status order-status--{statusKey}`); trống: `Chưa có đơn hàng nào.` hoặc `|Không có đơn hàng nào ở trạng thái ${orders.statusLabel}.|`; phân trang.
- **`admin/order-detail.html`:** `h1` `|Đơn hàng #${order.id}|`; `Ngày đặt`, `Trạng thái`, `Cập nhật lần cuối` (`#vnDateTime`), `Tài khoản đặt hàng: {họ tên} ({email})`; `Thông tin nhận hàng` (người nhận, số điện thoại, địa chỉ `white-space: pre-line`, `Ghi chú` chỉ khi có); `Phương thức thanh toán`; bảng dòng hàng (`Sản phẩm`, `Đơn giá`, `Số lượng`, `Thành tiền`) và `Tổng cộng`; mỗi `StatusAction` là một `<form method="post" th:action="@{/admin/orders/{id}/status(id=…)}">` với `<input type="hidden" name="status" th:value="${a.targetStatus}">`, `data-confirm` chỉ khi `a.confirmMessage != null`, nút chữ `a.label`; không có action: `Đơn hàng đã ở trạng thái cuối.`; link `Quay lại danh sách đơn`; cuối body `confirm.js`.
- **`admin/not-found.html`:** layout quản trị, `h1` = `notFoundTitle`, link `backUrl` chữ `backLabel` (`Quay lại danh sách sản phẩm` / `Quay lại danh sách đơn` / `Quay lại danh sách danh mục`).
- **Cấm** (như GĐ2–5): `<script>` nội tuyến, `<style>`, `style=`/`th:style`, `on…=`/`th:on…`, `javascript:`, `th:utext`.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static từ `src/main/resources/`; test từ `src/test/java/com/minishop/`.

| # | Task | Agent | File | AC / BR | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| A0 | ADR-0010 (bản `Đề xuất`), trạng thái spec | phiên chính | `docs/adr/0010-product-images-and-admin-stock-adjustment.md` (mới), `docs/adr/README.md`, `docs/specs/006-admin.md`, `docs/specs/README.md` | spec §8 (ADR trước code) | – | TW0 | Xem chi tiết A0 |
| TW0 | Gỡ test của trang admin tạm | `test-writer` | `admin/AdminControllerTest.java` (xóa) | – | – | A0 | Xem chi tiết TW0 |
| DV1 | Trần multipart 10 MB | `devops-engineer` | `src/main/resources/application.yml` (chỉ khối `spring.servlet.multipart`) | 006-29; QĐ6 | A0 | B1, B6, F1 | Xem chi tiết DV1 |
| B1 | Entity method, repository của `catalog` | `backend-developer` | `catalog/Category`, `catalog/Product` (thêm method), `catalog/CategoryRepository`, `catalog/ProductRepository`, `catalog/CategoryOption` (public), `catalog/CategoryRow` (mới), `catalog/ProductFilter` (`normalizeQuery` package-private), `catalog/package-info.java` | 006-10, 16, 32–37; BR-006-3, 7, 8 | A0 | DV1, B6, F1 | Xem chi tiết B1 |
| B6 | API quản trị của `order` | `backend-developer` | `order/OrderAdminService`, `order/OrderCancellation`, `order/StatusTotal`, `order/AdminOrderRow`, `order/AdminOrderPage`, `order/StatusFilterOption`, `order/AdminOrderDetail`, `order/StatusAction`, `order/StatusCount`, `order/OrderDashboard`, `order/StatusChangeResult` (mới); `order/OrderStatus`, `order/OrderInput`, `order/OrderRepository`, `order/OrderService` (chỉ `cancelByCustomer` gọi `OrderCancellation`), `order/OrderLineView` (public), `order/package-info.java` | 006-6, 8, 9, 38–47; BR-006-12, 13, 14 | A0 | DV1, B1, F1 | Xem chi tiết B6 |
| S | Kiểm entity, truy vấn và schema | `schema-guardian` | (chỉ đọc) | QĐ12 | B1, B6 | B2, B3, F1, F2 | Không có lệch giữa entity và V1/V2; xác nhận không cần V3; mọi JPQL mới tham chiếu đúng thuộc tính |
| B2 | `storage`: interface, ảnh, bản local, phục vụ `/uploads/**` | `backend-developer` | `storage/StorageService`, `storage/ImageFormat`, `storage/ImageUpload`, `storage/InvalidImageException`, `storage/UploadRoot`, `storage/LocalStorageService`, `storage/StorageWebConfig` (mới), `storage/package-info.java`; `config/SecurityConfig` (`PUBLIC_PATHS`); `application.yml` (khóa `app.storage.local.upload-dir`) | 006-27, 28, 30, 31; BR-006-1, 10, 11 | DV1 | B3, S, F1, F2 | Xem chi tiết B2 |
| B3 | `CategoryAdminService` | `backend-developer` | `catalog/CategoryAdminService`, `catalog/CategoryEditView`, `catalog/CategoryDraft`, `catalog/CategoryDeleteResult`, `catalog/CategoryNameTakenException`, `catalog/CategoryOptions` (mới); `common/ConstraintViolations` (mới) | 006-10, 11, 13–16; BR-006-2, 3 | B1 | B2, S, F1, F2 | Xem chi tiết B3 |
| B4 | `ProductImages` thành bean | `backend-developer` | `catalog/ProductImages`, `catalog/ProductService` (constructor, `CategoryOptions`), `cart/CartService` (constructor, `toLine`) | spec §7; QĐ7 | B2, B3 | F2, TW2 (phần đã sẵn) | Compile được; **chỉ** chạy `./mvnw -q compile`, rồi bàn giao ngay cho TW1 |
| TW1 | Sửa hai test theo constructor mới | `test-writer` | `cart/CartServiceTest`, `catalog/ProductServiceTest` | QĐ7 | B4 | B5, F2 | `./mvnw verify` xanh (mốc "build xanh" của cặp B4–TW1) |
| B5 | `ProductAdminService` | `backend-developer` | `catalog/ProductAdminService`, `catalog/ProductDraft`, `catalog/SavedProduct`, `catalog/AdminProductRow`, `catalog/AdminProductPage`, `catalog/ProductEditView`, `catalog/LowStockRow`, `catalog/StockAdjustResult`, `catalog/CategoryNotFoundException` (mới) | 006-7, 17, 19, 21–26, 30, 32–37; BR-006-4, 6–11, 14 | B1, B2, B3, TW1 | F2, TW2 | Xem chi tiết B5 |
| B7 | Tầng web của `admin` | `backend-developer` | `admin/AdminController` (xóa); `admin/AdminDashboardController`, `admin/AdminCategoryController`, `admin/AdminProductController`, `admin/AdminOrderController`, `admin/AdminInput`, `admin/PriceInput`, `admin/VndPrice`, `admin/VndPriceValidator`, `admin/WholeNumber`, `admin/WholeNumberValidator`, `admin/CategoryForm`, `admin/NewProductForm`, `admin/EditProductForm` (mới); `admin/package-info.java`; `common/FormText` (mới) | 006-1–5, 12, 18, 20, 21, 24, 28, 29, 34, 39, 41, 45, 49; BR-006-5, 15 | B3, B5, B6, F2 (cho bước kiểm bằng curl) | TW2 | Xem chi tiết B7 |
| F1 | Hướng thiết kế admin, layout, dashboard, 404 | `frontend-developer` | `.taste-skill/app/` (gitignore); `templates/fragments/admin.html` (mới), `templates/admin/index.html` (viết lại), `templates/admin/not-found.html` (mới), `static/css/admin.css` (mới) | 006-4, 5, 6–8, 50 | A0 (dựng theo mục 3) | DV1, B1–B6 | Xem chi tiết F1 |
| F2 | Trang danh mục, sản phẩm, đơn hàng | `frontend-developer` | `templates/admin/category-list.html`, `category-form.html`, `product-list.html`, `product-new.html`, `product-edit.html`, `order-list.html`, `order-detail.html` (mới); `static/css/admin.css` | 006-10–12, 17, 18, 21, 22, 25, 26, 28, 29, 32–34, 38–41, 49, 50 | F1 | B2–B6, TW1, TW2 | Xem chi tiết F2 |
| F3 | Gate của skill, kiểm tay giao diện | `frontend-developer` | sửa nhỏ trong file của F1, F2 (chỉ class/CSS, **không đổi markup ở mục 3**) | 006-4, 49, 50 | B7, F2 | TW3, TW4, TW5, D | Xem chi tiết F3 |
| TW2 | Test đơn vị: ảnh, lưu trữ, service quản trị, trạng thái | `test-writer` | `storage/ImageUploadTest`, `storage/LocalStorageServiceTest`, `catalog/CategoryAdminServiceTest`, `catalog/ProductAdminServiceTest`, `order/OrderAdminServiceTest`, `support/TestImages` (mới); `order/OrderStatusTest` (thêm) | 006-6–8, 10, 11, 13–16, 19, 22, 25–28, 30, 32–34, 37, 39, 41–45; BR-006-2–14 | B2, B3, B5, B6 (từng lớp khi phần của nó xong) | B5, B7, F2 | Xem chi tiết TW2 |
| TW3 | Form, đầu vào, bảo mật, kiến trúc | `test-writer` | `admin/CategoryFormValidationTest`, `admin/ProductFormValidationTest`, `admin/AdminInputTest`, `admin/PriceInputTest`, `storage/UploadsServingTest` (mới); `config/SecurityConfigTest`, `ArchitectureTest` (sửa) | 006-1–3, 12, 20, 21, 27, 31, 34; spec §8 (kiến trúc) | B7 | F3, TW4, TW5, D | Xem chi tiết TW3 |
| TW4 | Slice web của `admin`, quét CSP | `test-writer` | `admin/AdminDashboardControllerTest`, `admin/AdminCategoryControllerTest`, `admin/AdminProductControllerTest`, `admin/AdminOrderControllerTest` (mới); `TemplateCspComplianceTest`, `catalog/ProductControllerTest` (thêm link `Quản trị`) | 006-3–8, 10–12, 14, 15, 17–19, 21, 22, 24–26, 28, 29, 32–34, 38–43, 45, 49, 50 | B7, F2 | F3, TW3, TW5, D | Xem chi tiết TW4 |
| TW5 | Tích hợp PostgreSQL, race, Tomcat thật, smoke H2 | `test-writer` | `support/AdminTestData`, `catalog/CategoryAdminIntegrationTest`, `catalog/ProductAdminIntegrationTest`, `order/OrderAdminIntegrationTest`, `admin/AdminDashboardIntegrationTest`, `admin/AdminFlowTest`, `admin/AdminUploadLimitTest` (mới); `src/test/resources/config/application.yml` (mới); `H2ProfileSmokeTest` (thêm) | 006-6, 7, 9, 11, 13, 16, 17, 19, 22, 23, 25, 27, 29, 32, 33, 35–38, 42, 44, 46–48, 51, 52 | B7, F2 | F3, TW3, TW4, D | Xem chi tiết TW5 |
| D | Tài liệu | phiên chính | `docs/adr/0010-…md`, `docs/adr/README.md`, `CLAUDE.md`, `README.md`, `CHANGELOG.md`, `docs/specs/006-admin.md`, `docs/specs/README.md` | – | B7, F2, TW5 (lấy SQL thật) | F3, TW3, TW4 | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | mọi task trên | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc) | 006-1 đến 006-52 | R | – | Checklist mục 5 đạt |

**Thứ tự chạy:**
- **Đợt 0:** A0 (phiên chính viết ADR trước code). TW0 (xóa một file, chạy `verify`).
- **Đợt 1:** DV1, B1, B6 (file không giao nhau; phiên chính trao lượt build: DV1 → B1 → B6). F1 bắt đầu (không chạy Maven).
- **Đợt 2:** B2 (sau DV1, cùng `application.yml` nên không song song với DV1) và B3 (sau B1), hai phiên `backend-developer` tách file. S (sau B1, B6, chỉ đọc). F1 xong thì F2.
- **Đợt 3:** B4 (sau B2, B3) rồi **ngay** TW1. B5 (sau TW1 xanh). TW2 bắt đầu với các lớp đã sẵn (`ImageUploadTest`, `LocalStorageServiceTest`, `CategoryAdminServiceTest`, `OrderAdminServiceTest`, `OrderStatusTest`).
- **Đợt 4:** B7 (sau B5, B6; bước kiểm bằng curl cần F2). TW2 làm nốt `ProductAdminServiceTest` (sau B5).
- **Đợt 5:** TW3, TW4, TW5 (sau B7, F2; cùng agent: TW5 trước vì dài nhất, rồi TW4, TW3). F3 (sau B7). D bắt đầu; phần SQL của ADR-0010 chờ báo cáo TW5.
- **Cuối cùng:** R, rồi V.

**Đường găng:** B1 → B3 → (B2 → B4 → TW1) → B5 → B7 → TW5 → D (ADR có SQL thật) → R → V. TW5 dài nhất trong nhóm test (sáu test race 20 vòng, Tomcat thật, luồng đầy đủ).

**Không chạy hai lệnh Maven cùng lúc trong cùng thư mục** (bài học GĐ4):
- mỗi đợt có tối đa hai agent dùng Maven, phiên chính trao lượt build cho từng agent;
- F1, F2 không chạy Maven; F3 chỉ dùng `spring-boot:run` ở bước gate, không chạy cùng lúc với `verify` của agent khác;
- `./mvnw verify` đầy đủ chỉ chạy ở cuối mỗi task backend (trừ B4), ở TW1, TW5 và V; TW2–TW4 chạy `-Dtest=…` của lớp mình.

**Build xanh sau mỗi task:**
- **Sau TW0:** chỉ bớt một lớp test; `AdminController` vẫn còn, các test khác không nhắc tới nó (đã kiểm: `SecurityConfigTest` dùng `useDefaultFilters = false`).
- **Sau DV1:** chỉ đổi một con số cấu hình.
- **Sau B1, B6:** chỉ thêm method/query/class chưa ai gọi; Spring Data kiểm cú pháp JPQL lúc khởi động nên `MiniShopApplicationTests` và `H2ProfileSmokeTest` bắt lỗi query ngay. `OrderService.cancelByCustomer` gọi `OrderCancellation` nhưng hành vi không đổi; `OrderServiceTest`, `OrderIntegrationTest` vẫn xanh.
- **Sau B2:** `StorageWebConfig` vào mọi slice test, nên **chỉ** phụ thuộc `@Value` (QĐ5). `/uploads/**` công khai mới; `SecurityConfigTest` hiện có không bị ảnh hưởng.
- **Sau B3:** service mới chưa ai gọi.
- **B4 + TW1 là một cặp:** B4 đổi constructor của `ProductService`, `CartService`, nên `ProductServiceTest`, `CartServiceTest` không compile cho tới khi TW1 sửa. B4 chỉ chạy `compile` (mã chính), không `verify`; TW1 chạy ngay sau và là mốc "xanh". Không push giữa B4 và TW1.
- **Sau B5:** service mới chưa ai gọi.
- **Sau B7:** `AdminController` bị xóa (test của nó đã gỡ ở TW0). Nếu F2 chưa xong thì vài trang admin trả 500 khi chạy app; chưa test nào render chúng (trạng thái trung gian có chủ đích như GĐ5, không push giữa các đợt).
- **F1:** `admin/index.html` mới dùng `orderStats`, `lowStock`; trước B7, `/admin` trả 500 khi chạy app, nhưng không test nào render nó (TW0 đã gỡ test). `TemplateCspComplianceTest` quét mọi template nên F1, F2 phải tuân thủ CSP ngay từ đầu.

### Chi tiết A0 (phiên chính)

- **`docs/adr/0010-product-images-and-admin-stock-adjustment.md`** theo `template.md`, trạng thái `Đề xuất`, nội dung theo QĐ15: bối cảnh (spec 006 §8; ADR-0005, 0009), ba quyết định (ảnh qua `StorageService`; điều chỉnh kho bằng `adjustStock`; khóa `categories` trước `products`, kèm bảng thứ tự khóa bổ sung), hệ quả (gồm giới hạn đã biết: tên danh mục khác hoa thường đúng cùng lúc; request > 10 MB; ảnh của sản phẩm đã ẩn vẫn mở được bằng URL; `lock_timeout` vẫn để GĐ8), phương án bị loại.
- Thêm dòng 0010 vào bảng của `docs/adr/README.md` với trạng thái `Đề xuất`.
- Spec 006 và `docs/specs/README.md`: `Đang làm`.

### Chi tiết TW0 (`test-writer`)

- Xóa `src/test/java/com/minishop/admin/AdminControllerTest.java` (kiểm trang tạm của GĐ3, sẽ bị thay ở B7). Ba điều nó kiểm sẽ có lại: USER → 403 ở `SecurityConfigTest` (đã có), escape họ tên trong layout quản trị ở TW4, link `Quản trị` trên header storefront ở TW4 (`ProductControllerTest`).

**Xong khi:** `./mvnw verify` xanh; báo cáo liệt kê ba điều trên và nơi chúng sẽ được kiểm lại.

### Chi tiết DV1 (`devops-engineer`)

- `application.yml`: `spring.servlet.multipart.max-file-size: 10MB` (giữ `max-request-size: 10MB`), kèm comment một dòng: "Trần của Tomcat; giới hạn ảnh 2 MB kiểm trong ứng dụng (ImageUpload) để hiện lỗi trên form thay vì 403/ngắt kết nối, xem ADR-0010". **Không** đổi `server.tomcat.max-swallow-size` (QĐ6).
- Không đổi `pom.xml`, `compose.yaml`, CI.

**Xong khi:** `./mvnw verify` xanh; báo cáo xác nhận Spring Boot 4.1 vẫn dùng đúng tên khóa (khởi động với `--debug` hoặc đọc `MultipartProperties` trong jar).

### Chi tiết B1 (`backend-developer`)

- **`Category.update(name, description)`**, **`Product.updateDetails(category, name, description, price)`**: chỉ gán field; không đụng `stock`, `active`, `imagePath`. Không thêm setter nào khác, không đổi annotation.
- **`CategoryRepository.lockById`**, **`findAllWithProductCount`** theo mục 3. Javadoc của `lockById`: "khóa dòng danh mục trước mọi thao tác có thể làm danh mục mất hoặc có thêm sản phẩm (xóa danh mục, thêm/sửa sản phẩm), QĐ4, ADR-0010".
- **`ProductRepository.adjustStock`** đúng câu JPQL ở QĐ3, Javadoc đối xứng với `decrementStock` (nguyên tử, không lọc `active`, 0 dòng nghĩa là gì, chỉ `ProductAdminService` gọi). **`countByCategoryId`**, **`findTop10ByActiveTrueAndStockLessThanEqualOrderByStockAscIdAsc`**.
- **`CategoryOption`** thành public; **`CategoryRow`** public mới.
- **`ProductFilter.normalizeQuery`**: `private` → package-private static (không đổi hành vi).
- **`catalog/package-info.java`:** "Được phép dùng: `storage`, `common`. Chỉ `order` trừ/cộng kho; chỉ `ProductAdminService` điều chỉnh kho (`adjustStock`). Thêm/sửa sản phẩm và xóa danh mục khóa dòng `categories` trước (ADR-0010). API công khai cho `admin`: `CategoryAdminService`, `ProductAdminService` và các record của chúng."

**Xong khi:** `./mvnw verify` xanh; báo cáo câu SQL Hibernate sinh cho `adjustStock` và `lockById` trên H2 (chạy `H2ProfileSmokeTest` với `-Dlogging.level.org.hibernate.SQL=debug` trên dòng lệnh) và xác nhận không đổi mapping.

### Chi tiết B6 (`backend-developer`)

- **`OrderStatus.actionLabel()`** theo mục 3 (`switch` không `default`).
- **`OrderInput.status(raw)`**: `strip()`, so đúng `name()` của enum (phân biệt hoa thường; `pending` là sai), không dùng `valueOf` trong `try/catch`.
- **`OrderCancellation`** theo QĐ1; `OrderService.cancelByCustomer` gọi nó ở bước 3–5 (giữ nguyên log, kết quả, thứ tự). Javadoc: "chỉ gọi sau khi đã khóa dòng `orders` trong cùng transaction (ADR-0009)".
- **`OrderRepository`:** sáu query mới theo mục 3, Javadoc từng cái (đặc biệt `lockById`: "không lọc chủ đơn, chỉ cho admin").
- **`OrderAdminService`** (constructor injection `OrderRepository`, `ProductRepository`):
  - `listOrders`: QĐ11; `statusKey` = `name().toLowerCase(Locale.ROOT)`; `filters` = `Tất cả` + năm trạng thái theo thứ tự enum, đúng một mục `selected`;
  - `getOrder`: `findAdminDetailById`; dòng hàng sắp theo `OrderItem.id`; `actions` theo QĐ1 và lời xác nhận ở mục 3;
  - `dashboard`: QĐ10 (ba câu);
  - `changeStatus`: QĐ1; log INFO `Admin order status: admin={}, order={}, from={}, to={}`; `debug` khi từ chối.
- **`OrderLineView`** public. **`order/package-info.java`:** thêm "API công khai hẹp cho `admin`: `OrderAdminService` và các record trả về; `OrderStatus`, `OrderService`, entity, repository vẫn package-private. Admin đổi trạng thái theo cùng quy tắc khóa (ADR-0009)".

**Xong khi:** `./mvnw verify` xanh; báo cáo thứ tự câu lệnh thật của `changeStatus(…CANCELLED)` (đánh dấu chỗ persistence context bị xóa), và xác nhận `OrderServiceTest` không phải sửa.

### Chi tiết B2 (`backend-developer`)

- Các lớp theo QĐ5 và mục 3. `ImageUpload.from` đọc nội dung bằng `content.getInputStream()` trong `try-with-resources`, `readNBytes(MAX_BYTES + 1)`; `IOException` → `UncheckedIOException`.
- `LocalStorageService`: log `info` "Stored image {name} ({size} bytes)" (tên UUID, không có tên gốc); `warn` khi xóa thất bại.
- `StorageWebConfig`: location luôn kết thúc bằng `/` (kể cả khi thư mục chưa tồn tại lúc khởi động); không đặt cache header (mục 7).
- `SecurityConfig.PUBLIC_PATHS` thêm `"/uploads/**"`; cập nhật Javadoc lớp ("GĐ6 mở `GET /uploads/**`, đường dẫn công khai duy nhất của GĐ6, BR-006-1").
- `application.yml`: `app.storage.local.upload-dir: ${UPLOAD_DIR:uploads}` (comment: tương đối với thư mục chạy app, đã gitignore).
- `storage/package-info.java` theo QĐ5.

**Xong khi:**
- `./mvnw verify` xanh;
- app profile `h2` khởi động; `curl -i http://localhost:8080/uploads/khong-co.png` → 404 (không phải 302 về `/login`); chép tay một PNG vào `uploads/` rồi `curl -i` → 200, `Content-Type: image/png`, `X-Content-Type-Options: nosniff`; `curl -i --path-as-is http://localhost:8080/uploads/../application.yml` và `…/uploads/%2e%2e/application.yml` → 400 hoặc 404; xóa file thử khi xong.

### Chi tiết B3 (`backend-developer`)

- **`CategoryAdminService`** theo QĐ4 và mục 3 (constructor injection `CategoryRepository`, `ProductRepository`).
  - `nameTaken(name, excludeId)`: so `toLowerCase(Locale.ROOT)` trong Java trên `findAll()`;
  - `create`/`update`: kiểm trước, `saveAndFlush` trong `try`, map `uq_categories_name` qua `ConstraintViolations` → `CategoryNameTakenException` (kèm cause); exception khác ném lại nguyên vẹn;
  - `delete`: `lockById` → `countByCategoryId` → `InUse` hoặc `delete` + `flush` → `Deleted`;
  - log INFO mỗi thao tác ghi (admin id, category id, action).
- **`CategoryOptions.sortedByName`** (Collator `vi`, tạo mới mỗi lần gọi vì không thread-safe); `options()` dùng nó. **Không** sửa `ProductService` (B4 làm).
- **`CategoryDraft`** compact constructor: chốt chặn `IllegalArgumentException` nếu tên trống hoặc dài quá; mô tả trống → `null`.
- **`common.ConstraintViolations`**: đi theo chuỗi cause tới `org.hibernate.exception.ConstraintViolationException`, so tên bằng `contains` không phân biệt hoa thường (Javadoc: vì sao `contains`). `UserService` không bị sửa.

**Xong khi:** `./mvnw verify` xanh; báo cáo thứ tự câu lệnh của `delete` và giải thích ngắn hai kịch bản đua của QĐ4.

### Chi tiết B4 (`backend-developer`)

- `ProductImages` → `@Component public class` (constructor `StorageService`), method instance `toUrl`. Javadoc: "chỗ duy nhất `catalog` và `cart` đổi `image_path` thành URL; ủy quyền cho `StorageService`".
- `ProductService`: inject `ProductImages`; `categoryOptions()` dùng `CategoryOptions.sortedByName`. `CartService`: inject `ProductImages`, `toLine` gọi instance method. Không đổi gì khác.

**Xong khi:** `./mvnw -q compile` thành công; báo cáo chữ ký constructor mới của `ProductService`, `CartService` cho TW1. **Không** chạy `verify` (hai test cũ chưa compile cho tới TW1).

### Chi tiết TW1 (`test-writer`)

- `CartServiceTest`, `ProductServiceTest`: truyền `new ProductImages(storage)` với `storage` là mock `StorageService` có `toUrl` trả nguyên chuỗi (hoặc `null` khi trống), để mọi assert về `imageUrl` hiện có vẫn đúng. Không đổi assert nào khác.

**Xong khi:** `./mvnw verify` xanh.

### Chi tiết B5 (`backend-developer`)

- **`ProductAdminService`** theo QĐ2, QĐ3, QĐ5, QĐ11 và mục 3 (constructor injection `ProductRepository`, `CategoryRepository`, `StorageService`, `ProductImages`):
  - `list`: QĐ11;
  - `getForEdit`: `findById` + category (EntityGraph hoặc truy cập trong transaction); `imageUrl` qua `ProductImages`;
  - `lowStock`: QĐ10;
  - `create`: chốt `0 ≤ initialStock ≤ 100 000`; `lockById(categoryId)` → `CategoryNotFoundException`; `new Product(...)`, `setActive`; ảnh: `setImagePath(storage.store(image))`; `save`; log INFO; trả `SavedProduct`;
  - `update`: `findById` → `NotFoundException`; `lockById` danh mục đích; `updateDetails`; ảnh theo QĐ5 (store → set → `deleteAfterCommit(old)`); log INFO;
  - `setActive`: `findById` → `setActive`; log INFO; trả tên (gọi lại khi đã ở trạng thái đó vẫn thành công);
  - `adjustStock`: QĐ3; log INFO `admin, product, delta, kết quả`.
- Javadoc của lớp: thứ tự khóa (`categories` → `products`), vì sao `update` không bao giờ ghi đè `stock` (`updatable = false`, BR-006-8), vì sao `adjustStock` đọc lại sau UPDATE.

**Xong khi:** `./mvnw verify` xanh; báo cáo thứ tự câu lệnh thật của `create` có ảnh và `update` thay ảnh (đánh dấu lúc file được ghi, lúc callback được đăng ký).

### Chi tiết B7 (`backend-developer`)

- **Xóa `AdminController`**. Bốn controller theo QĐ9, bảng view ở mục 3 và bảng flash.
  - `AdminProductController.create`/`update`: `@Valid @ModelAttribute("form")` + `BindingResult`; đọc ảnh bằng `ImageUpload.from(...)` (bắt `InvalidImageException` → `rejectValue("image", …)`) **dù** ô khác có lỗi, để mọi lỗi hiện cùng lúc; có lỗi → render lại (nạp lại `categories`, và `product` khi sửa) và **không gọi service**; bắt `CategoryNotFoundException` → lỗi ô danh mục; thành công → redirect + flash.
  - `hide`/`show`: `from=edit` → trang sửa; còn lại → danh sách giữ `q` (đã `FormText.clean`) và `page` (`AdminInput.page`), dựng bằng `UriComponentsBuilder` (QĐ9).
  - `stock`: `AdminInput.stockDelta` → sai thì flash lỗi; đúng thì `switch` trên `StockAdjustResult`.
  - `AdminOrderController.status`: `switch` trên `StatusChangeResult`.
  - `AdminCategoryController.delete`: `switch` trên `CategoryDeleteResult`; `create`/`update` bắt `CategoryNameTakenException` → `rejectValue("name", …)`.
  - `@ExceptionHandler` 404 trong từng controller (trừ dashboard), model theo mục 3.
- Form, `PriceInput`, `AdminInput`, ràng buộc theo QĐ8 và mục 3; `common.FormText` theo QĐ8.
- **`admin/package-info.java`:** "Chỉ tầng web: controller, form, parse đầu vào. Được phép dùng: `catalog`, `order`, `storage` (chỉ API công khai), `user` (`CurrentUser`), `common`. Không dùng repository, không thấy entity. Không ai phụ thuộc vào `admin`."
- Không sửa `SecurityConfig` (B2 đã làm). Nếu thấy cần, dừng lại và báo phiên chính.

**Xong khi:**
- `./mvnw verify` xanh;
- app profile `h2` (sau F2) và bằng `curl` có cookie: chưa đăng nhập `GET /admin/products` → 302 `/login`; đăng nhập admin, lấy `_csrf` từ `/admin/products/new`, gửi multipart **thật** với file 2,5 MB và 9 MB → HTTP 200, trang có `Ảnh tối đa 2 MB.`, không có sản phẩm mới, thư mục `uploads/` không thêm file; gửi multipart thiếu `_csrf` → 403;
- báo cáo xác nhận view name, model attribute, flash khớp mục 3.

### Chi tiết F1 (`frontend-developer`)

- **Đọc trước:** spec 006 §4, §6 (bảng "Chữ hiển thị chính xác"), §8; mục 3, QĐ13 của plan này; mục "UI design skill" trong `CLAUDE.md`; `SKILL.md` và **toàn bộ** `references/app-screens.md` của skill; comment đầu `static/css/app.css`; `fragments/layout.html`, `order/detail.html`, `user/register.html` (khuôn form và bảng đã có).
- **Vẽ hướng một lần** theo QĐ13 vào `.taste-skill/app`; ghi tóm tắt hướng (kind, style, token chính, ngày vẽ) vào comment đầu `static/css/admin.css`, như `app.css` đã làm.
- Dựng `fragments/admin.html`, `admin/index.html` (dashboard), `admin/not-found.html`, nền của `admin.css` (sidebar, khung trang, panel, bảng, ô số, nhãn trạng thái dùng lại `order-status--*`, form). Có thể bắt đầu từ `<skill>/assets/app.css` rồi chỉnh theo token đã giữ; không có `charts.js` (không biểu đồ).
- Trạng thái trống của dashboard (không có sản phẩm sắp hết, chưa có đơn) phải được dựng và xem trong trình duyệt (`app-screens.md` §2.6).
- **Không** sửa `fragments/layout.html`, `app.css` hay template storefront.

**Xong khi:**
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả;
- mọi chữ hiển thị so khớp spec §6 (báo cáo liệt kê từng chuỗi và file chứa nó); báo cáo model attribute đã dùng (đối chiếu mục 3).

### Chi tiết F2 (`frontend-developer`)

- Bảy template theo mục 3, cùng hướng thiết kế của F1; CSS thêm vào `admin.css`.
- Mô tả, địa chỉ, ghi chú hiển thị bằng `th:text` + `white-space: pre-line`; tên dài dùng `overflow-wrap: anywhere`.
- Form sản phẩm: ảnh hiện tại ở trang sửa hiển thị bằng `<img>` có `alt` là tên sản phẩm, kích thước cố định qua CSS.
- Nút nguy hiểm (`Hủy đơn`, `Xóa`) khác biệt bằng chữ và màu, không chỉ bằng màu.

**Xong khi:** hai grep của F1 vẫn sạch (`grep -rn 'action="' src/main/resources/templates` không có form POST nào dùng `action` thay vì `th:action`); báo cáo liệt kê chữ và model attribute như F1.

### Chi tiết F3 (`frontend-developer`)

- **Gate (sau B7, F2):**
  - `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`;
  - `node <skill>/scripts/lint.mjs --app src/main/resources/templates/admin src/main/resources/templates/fragments/admin.html src/main/resources/static/css/admin.css`;
  - các trang `/admin/**` cần đăng nhập. Nếu `inspect.mjs --app` không giữ được phiên, **kiểm tay** (như GĐ5) trong DevTools device mode 390px, 768px, 1440px: dashboard (có dữ liệu và trống), danh sách/form danh mục (form lỗi), danh sách sản phẩm (tên dài, sản phẩm đã ẩn), form thêm (mọi lỗi), trang sửa (có ảnh upload, mục tồn kho), danh sách đơn (lọc), chi tiết đơn (mỗi trạng thái), 404; Console không lỗi CSP; hộp xác nhận (Hủy bỏ / OK);
  - viết critique app-screen của skill (`app-screens.md` §2.7) cho vòng cuối; chụp màn hình lưu `.taste-skill/inspect/phase-6/`; tối đa 3 vòng sửa; tắt app khi xong.
- Chỉ được sửa class và CSS; **không đổi chữ, cấu trúc form, `name`, `id`, `action`** của mục 3.

**Xong khi:** `lint.mjs --app` không còn `FAIL` (trừ mục ghi "sai lệch có chủ đích" kèm một câu lý do); không có thanh cuộn ngang cả trang ở 390px/375px; sidebar gập trên điện thoại; báo cáo có critique.

### Chi tiết TW2 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `support/TestImages` | (fixture, public) `png(int bytes)`, `jpeg(int)`, `webp(int)` (magic bytes đúng + đệm), `gif()`, `svg()`, `htmlNamedJpg()` | – |
| `storage/ImageUploadTest` | JPG/`.jpeg`/PNG/WebP hợp lệ → đúng `ImageFormat`; tên `ảnh sản phẩm (1).PNG`, `../../x.png` hợp lệ (tên gốc không ảnh hưởng); `.gif`, `.svg`, `.html`, `.exe`, không đuôi → `UNSUPPORTED_TYPE`; HTML đổi tên `.jpg` (content type `image/jpeg`) → `UNSUPPORTED_TYPE`; PNG có đuôi `.jpg` → `UNSUPPORTED_TYPE`; content type `text/html` với file PNG đúng → `UNSUPPORTED_TYPE`; đúng 2 097 152 byte hợp lệ, 2 097 153 và 2,5 MB → `TOO_LARGE` **mà không đọc nội dung** (`InputStreamSource` mock không được gọi); size khai báo nhỏ nhưng nội dung lớn hơn → `TOO_LARGE`; size 0 + tên trống → rỗng; `toString()` không chứa byte | 006-27, 28, 29; BR-006-10 |
| `storage/LocalStorageServiceTest` (`@TempDir`) | `store` tạo đúng một file tên UUID + đuôi chuẩn hóa trong thư mục, trả `/uploads/{tên}`; nội dung khớp; tên gốc không xuất hiện ở đâu; không có transaction → file giữ; có synchronization (`TransactionSynchronizationManager.initSynchronization()`, `clearSynchronization()` ở `finally`): gọi `afterCompletion(STATUS_ROLLED_BACK)` → file mất, `STATUS_COMMITTED` → file còn; `deleteAfterCommit` của file vừa lưu: trước `afterCommit` vẫn còn, sau thì mất; `/images/products/ao-thun.jpg`, `null`, `/uploads/../x.png`, `/uploads/abc.png` → không xóa gì, không lỗi; xóa file không tồn tại → chỉ log; `toUrl` (null, trống, đường dẫn) | 006-27, 30; BR-006-10, 11 |
| `catalog/CategoryAdminServiceTest` (Mockito) | `list`: sắp theo tiếng Việt (`Áo`, `Bàn`, `Đèn`, `Ô`), giữ `productCount`; `create("Đồ chơi", null)` → lưu đúng, trả tên; trùng `đồ chơi`/`ĐỒ CHƠI` → `CategoryNameTakenException`, không `saveAndFlush`; `update` đổi tên của chính nó, đổi hoa thường của chính nó → được; đổi thành tên danh mục khác → exception; `saveAndFlush` ném `DataIntegrityViolationException` có cause `ConstraintViolationException("…UQ_CATEGORIES_NAME…")` → `CategoryNameTakenException`; ràng buộc khác → ném lại nguyên vẹn; `delete`: `InOrder` `lockById` → `countByCategoryId`; n = 2 → `InUse(tên, 2)`, không `delete`; n = 0 → `delete` + `flush`, `Deleted`; không có → `NotFoundException` | 006-10, 11, 13–16; BR-006-2, 3 |
| `catalog/ProductAdminServiceTest` (Mockito) | `create` hợp lệ có ảnh: `InOrder` `lockById(cat)` → `storage.store` → `save`; `Product` bắt bằng captor (giá 1 250 000, kho 30, `active`, `imagePath`); danh mục không có → `CategoryNotFoundException`, **không** `store`, không `save`; `initialStock` −1 / 100 001 → `IllegalArgumentException`; `update` không ảnh: tên, giá, mô tả, danh mục đổi, `imagePath` giữ, không `store`/`deleteAfterCommit`; có ảnh: `store` rồi `deleteAfterCommit(cũ)`; `update` không bao giờ gọi `adjustStock`/`decrementStock`/`incrementStock`; `setActive(false/true)` trả tên; `adjustStock`: trả 1 → `Adjusted(tên, kho đọc lại)`; trả 0 với −5, kho 3 → `NotEnough(tên, 5, 3)`; trả 0 với +10 → `WouldExceed`; không có → `NotFoundException`; delta 0 / 100 001 → `IllegalArgumentException`, không gọi repository; sản phẩm đã ẩn vẫn điều chỉnh (không lọc `active`); `list("áo", "abc")` → trang 0, cỡ 20, sắp `createdAt` DESC rồi `id` DESC; `lowStock` gọi đúng ngưỡng 5 | 006-17, 19, 21–26, 30, 32–34, 37; BR-006-4, 6–9, 11 |
| `order/OrderAdminServiceTest` (Mockito) | `changeStatus`: `PENDING→CONFIRMED`, `CONFIRMED→SHIPPING`, `SHIPPING→DELIVERED` → `Changed(nhãn)`, không `incrementStock`; `PENDING`/`CONFIRMED → CANCELLED` với {B (id 9): 1, A (id 3): 2} → `Cancelled`, `incrementStock(3, 2)` rồi `(9, 1)`; `SHIPPING→CANCELLED`, `DELIVERED→*`, `PENDING→SHIPPING`, `PENDING→PENDING` → `NotAllowed(nhãn cũ, nhãn đích)`, trạng thái không đổi, không `incrementStock`; `"abc"`, `""`, `null`, `"pending"` → `InvalidStatus`, **không** gọi `lockById`; không có đơn → `NotFoundException`; luôn `lockById`, không bao giờ `lockByIdAndUserId`; `getOrder` → `actions` đúng thứ tự, nhãn và `confirmMessage` cho từng trạng thái, `isFinalStatus` ở `DELIVERED`/`CANCELLED`, có họ tên/email tài khoản, `updatedAt`; `listOrders("PENDING", "2")` → `findByStatus(PENDING, trang 1, cỡ 20)`; `"abc"` → `findAllBy`, `filters` có đúng một `selected`; `dashboard`: năm `StatusCount` theo thứ tự (thiếu → 0), doanh thu từ `sumTotalByStatus(DELIVERED)` | 006-6, 8, 39, 41–45; BR-006-12, 13, 14 |
| `order/OrderStatusTest` (thêm) | `actionLabel()` đúng bốn chữ; `PENDING.actionLabel()` → `IllegalStateException`; `OrderInput.status` | 006-41 |

**Xong khi:** `./mvnw test -Dtest='ImageUploadTest,LocalStorageServiceTest,CategoryAdminServiceTest,ProductAdminServiceTest,OrderAdminServiceTest,OrderStatusTest,OrderServiceTest'` xanh; báo cáo ghi chỗ khác cột "Kiểm bằng" của spec.

### Chi tiết TW3 (`test-writer`)

| Lớp | Kiểm | AC / BR |
|---|---|---|
| `admin/CategoryFormValidationTest` (Validator thuần) | tên trống/`"   "` → đúng một lỗi "Vui lòng nhập tên danh mục."; 100/101 ký tự; mô tả 1000/1001; `"  Đồ chơi  "` → `Đồ chơi`; NFD → NFC; `\u0000` bị bỏ; mô tả `"  "` → `null`, giữ xuống dòng | 006-11, 12; BR-006-2 |
| `admin/ProductFormValidationTest` | tên trống/201 ký tự; mô tả 4001; `categoryId` trống → "Vui lòng chọn danh mục." (một lỗi), `abc` → "Danh mục không tồn tại."; giá `1250000`, `1.250.000`, `1 250 000`, `999.999.999` hợp lệ và `toDraft().price()` = 1 250 000 (scale 0); giá trống → "Vui lòng nhập giá." (một lỗi); `0`, `-5`, `12,5`, `1.5`, `abc`, `1.000.000.000`, `1.250 000`, `12.50.000` → "Giá phải là số nguyên từ 1 đến 999.999.999 ₫."; tồn kho ban đầu trống, `-1`, `1.5`, `100001`, `abc` → chữ tương ứng; `0`, `100000` hợp lệ; `active` `null` → `isActiveChecked()` `false`; `NewProductForm.empty().active()` `true`; `toString()` không có file | 006-20, 21; BR-006-4–6 |
| `admin/AdminInputTest`, `admin/PriceInputTest` | `id`: `"7"`, `" 7 "` hợp lệ; `""`, `null`, `"abc"`, `"-1"`, 20 chữ số rỗng. `stockDelta`: `"5"`, `"+5"`, `"-2"`, `"100000"`, `"-100000"` hợp lệ; `"0"`, `""`, `"abc"`, `"1.5"`, `"100001"`, `"-100001"`, `"+-1"` rỗng. `page`: như GĐ5. `PriceInput`: bảng như trên | 006-20, 34; BR-006-5, 7 |
| `storage/UploadsServingTest` (`@WebMvcTest` + `@Import({SecurityConfig.class, CartHeaderTestConfig.class})`, `@TempDir` static + `@DynamicPropertySource` cho `app.storage.local.upload-dir`) | chưa đăng nhập `GET /uploads/{uuid}.png` (file có trong temp dir) → 200, `Content-Type` `image/png`, `X-Content-Type-Options: nosniff`, đúng byte; `.webp` → `image/webp`; file không tồn tại → 404; `/uploads/../application.yml`, `/uploads/%2e%2e/application.yml`, `/uploads/..%2fx` → 4xx và body không chứa nội dung file nằm ngoài temp dir (đặt một file "bí mật" cạnh temp dir để chứng minh) | 006-27, 31 |
| `config/SecurityConfigTest` (thêm) | chưa đăng nhập: `GET` mọi URL mục 6 của spec (`/admin`, `/admin/categories`, `…/new`, `/admin/categories/1/edit`, `/admin/products`, `get("/admin/products?q=a&page=2")`, `…/new`, `…/1/edit`, `/admin/orders`, `get("/admin/orders?status=PENDING")`, `/admin/orders/1`) → redirect `/login`; `POST` có CSRF mọi URL POST (kể cả `multipart("/admin/products")`) → redirect `/login`. `@WithMockUser` (USER): mọi URL GET và POST có CSRF → 403, `forwardedUrl("/access-denied")`. `@WithMockUser(roles = "ADMIN")` không CSRF: mọi POST, kể cả `multipart(...)` → 403. Chưa đăng nhập `GET /uploads/x.png` → không bị chuyển `/login` (404 vì slice không có handler); `POST /uploads/x.png` → redirect `/login` (chỉ GET công khai) | 006-1, 2, 3, 27; BR-006-1 |
| `ArchitectureTest` (sửa) | thay `onlyOrderChangesStock` bằng `onlyOrderDecrementsOrRestoresStock` và `onlyProductAdminServiceAdjustsStock` (QĐ3); thêm `admin_doesNotDependOnEntities` (lớp trong `admin` không phụ thuộc lớp `@Entity`, vì `Product`, `Category`, `User` đang public); thêm `catalog_dependsOnlyOnStorageAndCommon` (`catalog` không phụ thuộc `cart`, `order`, `user`, `admin`, `config`: mở rộng rule cũ thêm `config`); giữ mọi rule khác, comment cập nhật chiều mới `catalog → storage`, `admin → catalog, order, storage` | spec §8 |

**Xong khi:** `./mvnw test -Dtest='CategoryFormValidationTest,ProductFormValidationTest,AdminInputTest,PriceInputTest,UploadsServingTest,SecurityConfigTest,ArchitectureTest'` xanh.

### Chi tiết TW4 (`test-writer`)

Cấu hình QĐ14. Mỗi lớp kiểm thêm: tiêu đề tab, `h1`, sidebar năm link với `aria-current="page"` đúng mục, `Xin chào, Quản trị viên`, form `Đăng xuất` có `_csrf`; họ tên `<script>alert(1)</script>` được escape (AC-006-4).

| Lớp | Kiểm | AC |
|---|---|---|
| `AdminDashboardControllerTest` | service trả 3/1/0/2/1 và 450 000 → năm ô đúng nhãn, đúng số, link `/admin/orders?status=PENDING`…; `Doanh thu (đơn đã giao)` `450.000 ₫`; `lowStock` 0, 3, 5 → ba dòng đúng thứ tự, link `/admin/products/{id}/edit`; rỗng → `Không có sản phẩm nào sắp hết hàng.`; 5 đơn mới nhất (mã, ngày giờ VN, khách, tổng, trạng thái); rỗng → `Chưa có đơn hàng nào.`; link `Xem tất cả đơn hàng`; không có `<canvas`/`<svg` biểu đồ | 006-4, 6, 7, 8 |
| `AdminCategoryControllerTest` | danh sách: cột, `Số sản phẩm`, `Sửa`, form `Xóa` có `_csrf` và `data-confirm="Xóa danh mục Đồ chơi? Không thể hoàn tác."`, `confirm.js`; trống: `Chưa có danh mục nào.`, không `<table`; `POST` hợp lệ → redirect, flash "Đã thêm danh mục Đồ chơi."; service nhận draft đã strip; lỗi từng ô → 200, chữ lỗi, `aria-invalid`, `aria-describedby`, giá trị giữ nguyên, service không được gọi; `CategoryNameTakenException` → 200, "Tên danh mục đã tồn tại." dưới ô tên; sửa → "Đã cập nhật danh mục …"; xóa `Deleted` / `InUse(…, 2)` → đúng flash; `/admin/categories/x/edit`, `/999/edit` (service ném `NotFoundException`), `POST /x/delete` → 404, `Không tìm thấy danh mục`, link quay lại, layout quản trị | 006-4, 5, 10–12, 14, 15, 49 |
| `AdminProductControllerTest` | danh sách: cột, `Đang bán`/`Đã ẩn`, ảnh giữ chỗ khi `imageUrl` null, nút `Ẩn`/`Hiện lại` trong form POST có `from=list`, `q`, `page`; `q`, `page` chuyển nguyên chuỗi cho service; hai câu trống; link phân trang giữ `q` đã encode; `new`: đủ nhãn, gợi ý ảnh, `Đang bán` tick sẵn, `enctype="multipart/form-data"`; không danh mục → chữ + link, không `<form` sản phẩm; `POST` hợp lệ (multipart PNG 500 KB, giá `1.250.000`) → redirect `/admin/products/42/edit`, flash "Đã thêm sản phẩm Bình nước 1L."; service nhận `ImageUpload` PNG, draft giá 1 250 000, kho 30, `active` true; từng ô sai → 200, lỗi đúng ô, giá trị chữ giữ, service **không** được gọi; ảnh `.gif`, HTML đổi tên → "Ảnh chỉ được là JPG, PNG hoặc WebP."; 2,5 MB và 9 MB → "Ảnh tối đa 2 MB."; ảnh sai + ô khác sai → cả hai lỗi; `CategoryNotFoundException` → lỗi ô danh mục; sửa: không có ô `initialStock`, `active`; có `Tồn kho hiện tại: 10`, form `…/stock`, nhãn và gợi ý; `Xem trên cửa hàng` chỉ khi đang bán; thêm `stock=999&id=5&active=false&imagePath=/x&createdAt=2020-01-01&initialStock=7` → service nhận id từ URL, draft chỉ có bốn ô, `image` null; `hide`/`show` → flash đúng, redirect `from=edit` / danh sách giữ `q`, `page`; `stock` `+5` → `Adjusted(…, 15)` → flash; `0`, `abc`, `1.5`, `100001` → flash lỗi, service không được gọi; `NotEnough`, `WouldExceed` → đúng chữ; `/admin/products/abc/edit`, `POST /abc/stock`, service `NotFoundException` → 404 `Không tìm thấy sản phẩm` | 006-4, 5, 17–19, 21, 22, 24–26, 28, 29, 32–34, 49 |
| `AdminOrderControllerTest` | danh sách: cột, `#{id}` link, ngày giờ VN, khách (họ tên, email), người nhận, tổng, nhãn; bộ lọc `Tất cả` + năm nhãn, mục chọn có `aria-current`; link phân trang giữ `status`; `rawStatus`, `rawPage` chuyển nguyên chuỗi; trống không lọc / có lọc → hai câu; chi tiết: tiêu đề `Đơn hàng #12 \| Quản trị Mini Shop`, mọi mục AC-005-23 + `Tài khoản đặt hàng: …`, `Cập nhật lần cuối`; với `actions` của từng trạng thái → đúng nút, đúng `data-confirm` (chỉ hủy và đã giao), mỗi form có `_csrf` và `name="status"`; không action → `Đơn hàng đã ở trạng thái cuối.`, không form trạng thái; `POST …/status`: bốn kết quả → đúng flash và redirect `/admin/orders/12`; `/admin/orders/abc`, `/999999` → 404 `Không tìm thấy đơn hàng`; địa chỉ, ghi chú, tên sản phẩm chứa `<script>` được escape | 006-4, 5, 38–43, 45, 49 |
| `TemplateCspComplianceTest` (thêm) | bắt buộc quét được `fragments/admin.html` và mười template `admin/*` | 006-50 |
| `catalog/ProductControllerTest` (thêm) | `TestUsers.admin()` → header storefront có `href="/admin">Quản trị</a>`; `TestUsers.user()` → không có | (thay phần của `AdminControllerTest` cũ) |

**Xong khi:** các lớp trên xanh; báo cáo ghi chỗ khác cột "Kiểm bằng" của spec.

### Chi tiết TW5 (`test-writer`)

**`src/test/resources/config/application.yml`:** chỉ `app.storage.local.upload-dir: target/test-uploads` (QĐ14), comment vì sao đặt ở `config/`.

**`support/AdminTestData`** (public, như `OrderTestData`): `createUser(role)`, `createCategory(suffix)`, `createProduct(categoryId, suffix, price, stock, active)`, `stock`, `setStock`, `setActive`, `productRow`, `categoryExists`, `orderRow`, `insertOrder(userId, status, total)` (JDBC, cho dashboard và danh sách), `trackUpload(path)`, `cleanUp()` theo thứ tự QĐ14.

| Lớp (PostgreSQL trừ khi ghi khác) | Kiểm | AC |
|---|---|---|
| `catalog/CategoryAdminIntegrationTest` | tạo `  Đồ chơi  ` qua MockMvc (admin) → dòng `categories` `Đồ chơi`, mô tả `null`; `GET /products` có danh mục trong bộ lọc; **race cùng tên** (20 vòng, hai luồng `create`) → đúng một dòng, luồng kia `CategoryNameTakenException`, không exception khác; danh mục có 1 sản phẩm đang bán + 1 đã ẩn → `delete` `InUse(…, 2)`; **race xóa và thêm sản phẩm** (20 vòng: `delete(cat)` ‖ `productAdminService.create(…cat…)`) → hoặc (`Deleted` và `CategoryNotFoundException`) hoặc (`InUse(…, 1)` và sản phẩm tồn tại), không bao giờ cả hai thành công, không exception khác, không sản phẩm có `category_id` không tồn tại | 006-11, 13, 16, 51 |
| `catalog/ProductAdminIntegrationTest` | `POST /admin/products` multipart PNG 500 KB, giá `1.250.000` → dòng đúng (giá 1 250 000, kho 30, `active`, `image_path` khớp `/uploads/{uuid}.png`), file tồn tại trong `target/test-uploads`; `GET /products/{id}` có `src="/uploads/…png"`; `GET` ảnh đó (chưa đăng nhập) → 200 đúng byte; danh sách 25 sản phẩm (2 ẩn): trang 1 có 20, gồm cả sản phẩm ẩn, mới nhất trước; `q=áo` và `q=%` đúng; **AC-006-23 tất định:** trong `TransactionTemplate` gọi `update` (sản phẩm kho 10) nhưng trước commit, một connection JDBC riêng trừ 2 và commit → sau commit kho là 8, tên đã đổi; thay ảnh → file cũ (upload) mất sau commit, file mới còn; thay ảnh của sản phẩm có `image_path` seed → không file nào trong `static` bị đụng; rollback (trong `TransactionTemplate` gọi `update` có ảnh rồi `setRollbackOnly`) → file mới không còn, `image_path` không đổi; sửa tên, giá → `GET /products/{id}` và trang giỏ của một user test có tên, giá mới; đơn cũ (tạo qua JDBC với snapshot) vẫn tên, giá cũ ở `/orders/{id}` của chủ đơn; ẩn → `/products/{id}` 404, giỏ "Ngừng bán"; hiện lại → 200; điều chỉnh kho `+5` (10 → 15), `-2` (3 → 1), `-5` (kho 3 → không đổi, `NotEnough`), `+1` khi kho 100 000 → `WouldExceed`; sản phẩm ẩn vẫn điều chỉnh được | 006-17, 19, 22, 23, 25–27, 30, 32, 33, 37, 51 |
| `order/OrderAdminIntegrationTest` | đếm truy vấn `listOrders` với 3 đơn của 3 khách → đúng 2 câu; danh sách 25 đơn → 20/trang, sắp `created_at` DESC rồi `id` DESC; admin hủy đơn `CONFIRMED` A: 2 (kho 3) qua MockMvc → `CANCELLED`, kho 5, flash; A đã ẩn vẫn hoàn kho; `PENDING → CONFIRMED` → khách `GET /orders/{id}` thấy `Đã xác nhận`, không có `Hủy đơn hàng`; **race kho cộng và hai đơn** (20 vòng: kho 5, `adjustStock(+10)` ‖ `placeOrder` U1 (A: 2) ‖ `placeOrder` U2 (A: 2)) → kho cuối 11, ba thao tác đều thành công, không exception; **race bên thua** (20 vòng: kho 2, `adjustStock(-2)` ‖ `placeOrder` (A: 2)) → đúng một thành công, kho 0, bên thua nhận `NotEnough` hoặc `InsufficientStock`; **race admin xác nhận và khách hủy** (20 vòng, đơn `PENDING`) → đúng một bên thắng: hoặc `CANCELLED` + kho hoàn đúng một lần + admin `NotAllowed`, hoặc `CONFIRMED` + khách `NOT_CANCELLABLE`, kho không đổi; **race hai admin hủy** (20 vòng, đơn `CONFIRMED`) → một `Cancelled`, một `NotAllowed`, kho hoàn đúng một lần; admin principal mở `/orders/{đơn của U1}` → 404 (AC-006-48, giữ AC-005-24) | 006-35–38, 42, 44, 46–48, 51 |
| `admin/AdminDashboardIntegrationTest` | chụp số liệu → tạo 3 `PENDING`, 1 `CONFIRMED`, 2 `DELIVERED` (300 000 và 150 000), 1 `CANCELLED` → `GET /admin` có số mỗi ô tăng đúng (+3, +1, +0, +2, +1), doanh thu tăng 450 000 (đọc từ `dashboard()`); sản phẩm test đang bán kho 0, 3, 5, 6 và ẩn kho 1 → `lowStock()` chứa ba cái 0, 3, 5 theo thứ tự tương đối, không chứa 6 và cái ẩn, tối đa 10; **đếm truy vấn** `dashboard()` + `lowStock()` = 4 câu với 1 đơn và với 30 đơn (không đổi) | 006-6, 7, 9 |
| `admin/AdminFlowTest` | khách test đặt hàng qua MockMvc (giỏ + `/checkout`) → admin `GET /admin` (ghi doanh thu) → `GET /admin/orders?status=PENDING` có mã đơn → chi tiết → `CONFIRMED` → `SHIPPING` → `DELIVERED` → khách `GET /orders` thấy `Đã giao` → doanh thu dashboard tăng đúng tổng đơn | 006-52 |
| `admin/AdminUploadLimitTest` (H2, `RANDOM_PORT`, QĐ14) | đăng nhập thật; multipart 2,5 MB và 9 MB có `_csrf` → HTTP 200 (không `IOException` "connection reset"), body có `Ảnh tối đa 2 MB.` và giá trị tên đã nhập; không thêm sản phẩm; thư mục upload không thêm file; multipart 500 KB hợp lệ → 302 (đối chứng rằng đường upload thật chạy) | 006-29 |
| `H2ProfileSmokeTest` (thêm) | admin tạo danh mục, tạo sản phẩm có PNG, `+5` kho; user seed đặt 1 cái; admin `CONFIRMED` → `SHIPPING` → `DELIVERED`; đơn thứ hai admin hủy → kho về đúng; `finally`: xóa đơn, sản phẩm, danh mục, file đã tạo; số user seed vẫn 2 | 006-51 |

**Xong khi:**
- `./mvnw verify` xanh; `bash .github/scripts/check-no-skipped-tests.sh` exit 0;
- báo cáo liệt kê test theo AC và thời gian chạy của từng test race;
- báo cáo có câu SQL thật Hibernate sinh trên PostgreSQL cho `CategoryRepository.lockById`, `ProductRepository.adjustStock`, `OrderRepository.lockById`, `countByStatus`, `sumTotalByStatus`, `findTop5…`, `findTop10…` (chạy một lần với `-Dlogging.level.org.hibernate.SQL=debug` trên dòng lệnh, **không** sửa file cấu hình), để ghi vào ADR-0010.

### Chi tiết D (phiên chính)

- **ADR-0010:** thêm mục SQL thật từ TW5; đổi trạng thái thành `Chấp nhận` sau khi V đạt; cập nhật dòng ở `docs/adr/README.md`.
- **`CLAUDE.md`:**
  - **Project status:** GĐ6 xong, chờ `v0.6.0`; tiếp theo GĐ7;
  - **"Visibility today":** thêm `catalog.CategoryAdminService`, `ProductAdminService` và các record, exception của chúng, `catalog.ProductImages` (nay là bean), `CategoryOption`; `order.OrderAdminService` và các record, `OrderLineView`; `storage.StorageService`, `ImageUpload`, `ImageFormat`, `InvalidImageException`; `common.FormText`, `ConstraintViolations`; bỏ câu "GĐ6 will need a narrow public order API";
  - **mục mới "Admin patterns (GĐ6)":** API hẹp `OrderAdminService` (chuỗi thô, không lộ `OrderStatus`, nút suy từ `canTransitionTo`, `lockById` chỉ cho admin, `OrderCancellation` dùng chung); service quản trị ở `catalog` nhận draft; `adjustStock` và cách phân loại 0 dòng; khóa `categories` trước `products`; trùng tên danh mục so trong Java; `ImageUpload` là cách duy nhất có ảnh hợp lệ; `store` dọn khi rollback, `deleteAfterCommit`; `/uploads/**` qua resource handler và bẫy `WebMvcConfigurer` trong slice test; trần multipart 10 MB và lý do; 404 bằng `@ExceptionHandler` trong controller; layout `fragments/admin` + `admin.css` + hướng `.taste-skill/app`; thư mục upload của test `config/application.yml`;
  - **"Domain invariants":** thêm "chỉ `ProductAdminService` điều chỉnh kho, bằng `adjustStock`" và dòng thứ tự khóa `categories → products`;
  - **Commands:** `UPLOAD_DIR`.
- **`README.md`:** trạng thái GĐ6, cách vào trang quản trị bằng `admin@minishop.local`, thư mục `uploads/` và `UPLOAD_DIR`; đánh dấu GĐ6 ở Lộ trình **sau khi V đạt**.
- **`CHANGELOG.md`:** `[Unreleased]` (Thêm / Thay đổi / Bảo mật: upload kiểm ba lớp, `/uploads/**` công khai, CSRF trên multipart). Chuyển thành `[0.6.0]` và gắn tag sau khi người dùng duyệt giai đoạn.
- **Spec 006 và `docs/specs/README.md`:** `Hoàn thành` sau khi V đạt; ghi lịch sử thay đổi nếu người dùng chọn khác khuyến nghị ở mục 6.

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

Docker **đang bật**. Trước mục 2, chụp `select id, name, stock, price, active, image_path, category_id from products order by id`, `select id, name from categories`, `select coalesce(max(id), 0) from orders` và danh sách file trong `uploads/`, để so và khôi phục ở mục 41. Dùng `admin@minishop.local` và `user@minishop.local` (mật khẩu trong README).

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh` | BUILD SUCCESS; script exit 0; có đủ các lớp test mới của TW1–TW5 |
| 2 | Không có migration mới | `./mvnw spring-boot:run` trên DB dev cũ; `select max(version) from flyway_schema_history` | App lên; version vẫn `2` |
| 3 | AC-006-1 (âm) | Cửa sổ ẩn danh mở mọi URL GET ở spec §6 (`/admin`, `/admin/categories`, `/admin/products/1/edit`, `/admin/orders?status=PENDING`…); `curl -i -X POST http://localhost:8080/admin/categories -d name=x` | GET chuyển `/login`; POST 302 `/login` hoặc 403; `categories` không đổi |
| 4 | AC-006-2 (âm) | Đăng nhập `user@`: mở `/admin`, `/admin/orders/1`; từ một trang storefront có form, DevTools đổi `action` sang `/admin/categories/1/delete` rồi gửi | 403, trang `Bạn không có quyền truy cập trang này.`; không đổi dữ liệu |
| 5 | AC-006-3 (âm) | Admin: DevTools xóa ô `_csrf` ở form thêm danh mục, form thêm sản phẩm (multipart, có chọn ảnh), form điều chỉnh kho, form đổi trạng thái; gửi | Cả bốn: 403; không đổi dữ liệu; `uploads/` không thêm file |
| 6 | AC-006-4 | Admin mở lần lượt mọi trang `/admin/**` | Layout quản trị (không header/footer storefront); sidebar `Tổng quan`, `Đơn hàng`, `Sản phẩm`, `Danh mục`, `Về cửa hàng`; mục đang mở có `aria-current="page"` (xem mã HTML); `Xin chào, Quản trị viên`, nút `Đăng xuất` hoạt động; tiêu đề tab dạng `{Tên trang} \| Quản trị Mini Shop`; link `Quản trị` trên header storefront dẫn tới dashboard |
| 7 | AC-006-5 (âm) | Mở `/admin/products/abc/edit`, `/admin/products/999999/edit`, `/admin/orders/999999`, `/admin/categories/x/edit`; `POST /admin/products/abc/stock` (DevTools) | HTTP 404 trong layout quản trị, tiêu đề đúng loại, link quay lại danh sách; không trang 500 |
| 8 | AC-006-6 | psql: `select status, count(*), sum(total_amount) from orders group by status`; mở `/admin` | Năm ô khớp số psql theo nhãn tiếng Việt; mỗi ô link `/admin/orders?status=…`; `Doanh thu (đơn đã giao)` = tổng `DELIVERED`, dạng `1.250.000 ₫`; tiêu đề `Tổng quan \| Quản trị Mini Shop` |
| 9 | AC-006-7 | psql đặt kho 4 sản phẩm đang bán là 0, 3, 5, 6 và một sản phẩm đã ẩn kho 1; tải `/admin` | `Sắp hết hàng` có ba sản phẩm 0, 3, 5 theo thứ tự tăng (cùng các sản phẩm khác ≤ 5 nếu có), không có 6 và cái ẩn, tối đa 10 dòng, mỗi dòng link trang sửa |
| 10 | AC-006-8, 9 | Xem `Đơn hàng mới nhất`; đọc kết quả test đếm truy vấn ở mục 1 | 5 đơn mới nhất với mã link, ngày giờ VN, khách, tổng, trạng thái; test đếm truy vấn xanh (4 câu) |
| 11 | AC-006-10 | `/admin/categories` | `h1` `Danh mục`; cột `Tên`, `Mô tả`, `Số sản phẩm` (khớp `select category_id, count(*) from products group by category_id`, tính cả sản phẩm ẩn); thứ tự tiếng Việt; nút `Thêm danh mục` |
| 12 | AC-006-11 | Thêm tên `  Đồ chơi  `, mô tả trống | Về danh sách với `Đã thêm danh mục Đồ chơi.`; psql `name = 'Đồ chơi'`, `description is null`; `/products` có `Đồ chơi` trong bộ lọc |
| 13 | AC-006-12 (âm) | Thêm/sửa với tên trống; DevTools gỡ `maxlength`, tên 101 ký tự, mô tả 1001 ký tự | HTTP 200, lỗi tiếng Việt dưới đúng ô, giá trị giữ nguyên; không lưu |
| 14 | AC-006-13 (âm) | Thêm `đồ chơi`, ` ĐỒ CHƠI `; sửa danh mục khác thành `Đồ Chơi`; đọc test race ở mục 1 | Cả ba: `Tên danh mục đã tồn tại.` dưới ô tên, không lưu; test race xanh |
| 15 | AC-006-14 | Sửa `Đồ chơi` → `Đồ chơi trẻ em`; lưu lại không đổi tên | `Đã cập nhật danh mục Đồ chơi trẻ em.`; lần hai cũng lưu được; sản phẩm thuộc danh mục vẫn ở đó |
| 16 | AC-006-15, 49 | `Xóa` danh mục trống: chọn **Hủy bỏ** ở hộp xác nhận, rồi **OK**; tắt JavaScript, xóa một danh mục trống khác | Lần 1 không gì xảy ra; lần 2 `Đã xóa danh mục {tên}.`; tắt JS: không hộp thoại, vẫn xóa được |
| 17 | AC-006-16 (âm) | Xóa danh mục có sản phẩm (psql ẩn hết sản phẩm của nó trước); đọc test race | `Không thể xóa danh mục {tên} vì còn {n} sản phẩm (kể cả sản phẩm đã ẩn).`; danh mục còn; test race xanh |
| 18 | AC-006-17 | psql thêm sản phẩm cho đủ > 20 (có 2 cái `active=false`); mở `/admin/products`, `?page=2`, `?page=abc`, `?page=999`, `?q=áo`, `?q=%`, `?q=zzz` | 20/trang, mới nhất trước, có cả `Đã ẩn`; `abc`, `999` hiện trang 1; `áo` đúng kết quả; `%` không trả tất cả; `zzz`: `Không tìm thấy sản phẩm phù hợp.`; link phân trang giữ `q` |
| 19 | AC-006-18 | `/admin/products/new` | Đủ ô và nhãn, gợi ý `JPG, PNG hoặc WebP, tối đa 2 MB.`, `Đang bán` tick sẵn, `Lưu`, `Hủy`; danh mục sắp theo tên. (Trường hợp chưa có danh mục: dựa vào `AdminProductControllerTest`) |
| 20 | AC-006-19, 27 | Thêm `Bình nước 1L`, danh mục `Nhà cửa`, giá `1.250.000`, kho `30`, ảnh PNG ~500 KB tên `ảnh sản phẩm (1).PNG` | Về trang sửa với `Đã thêm sản phẩm Bình nước 1L.`; psql giá `1250000`, kho 30, `active`, `image_path` `/uploads/{uuid}.png`; `uploads/` có đúng file đó, không có tên gốc; storefront hiện ảnh; cửa sổ ẩn danh mở `/uploads/{uuid}.png` → ảnh; DevTools Network: `Content-Type: image/png`, `X-Content-Type-Options: nosniff` |
| 21 | AC-006-20 | Thêm với giá `1250000`, `1 250 000`; rồi `0`, `-5`, `12,5`, `1.5`, `abc`, `1.000.000.000` | Hai cái đầu lưu `1250000`; còn lại lỗi dưới ô giá, không lưu |
| 22 | AC-006-21 (âm) | Gửi form thêm với mọi ô trống nhưng **có** chọn ảnh hợp lệ; rồi tồn kho `-1`, `1.5`, `100001` | 200, lỗi dưới từng ô, chữ đã nhập giữ, ô file trống; `uploads/` không thêm file; không có sản phẩm mới |
| 23 | AC-006-22 | Sản phẩm A đang trong giỏ của `user@` và trong một đơn cũ: sửa tên, giá, mô tả, danh mục, không chọn ảnh | `Đã cập nhật sản phẩm {tên mới}.`; ảnh giữ; form sửa không có ô tồn kho; storefront và giỏ hiện tên, giá mới; đơn cũ vẫn tên, giá lúc mua |
| 24 | AC-006-23 | Mở trang sửa A (kho 10); psql `update products set stock = stock - 2 where id = A`; bấm `Lưu` form sửa | psql kho A là 8 (không về 10) |
| 25 | AC-006-24 (âm) | DevTools thêm vào form sửa các ô ẩn `stock=999`, `id={id khác}`, `active=false`, `imagePath=/x.png`, `createdAt=2000-01-01`; lưu | Chỉ sản phẩm trên URL đổi; kho, `active`, `image_path`, `created_at` không đổi |
| 26 | AC-006-25, 26 | Ẩn A từ danh sách (đang ở trang 2 có `q`) và từ trang sửa; xem storefront, `/products/{A}`, giỏ có A, đơn cũ có A; hủy đơn đó; hiện lại A | Về đúng trang vừa đứng (giữ `q`, `page`) với `Đã ẩn sản phẩm {tên}. Khách sẽ không thấy sản phẩm này nữa.`; storefront mất A, `/products/{A}` 404; giỏ "Ngừng bán", không đặt được; đơn cũ xem được, hủy hoàn kho; hiện lại: `Đã hiện lại sản phẩm {tên}.`, A trở lại |
| 27 | AC-006-28 (âm) | Upload `.gif`, `.svg`, `.html`, `.exe`, file không đuôi, file HTML đổi tên `x.jpg` | Mỗi lần: `Ảnh chỉ được là JPG, PNG hoặc WebP.`; không sản phẩm mới; `uploads/` không đổi |
| 28 | AC-006-29 (âm) | Trình duyệt thật: thêm sản phẩm với ảnh JPG 2,5 MB, rồi 9 MB | Cả hai: trang form hiện `Ảnh tối đa 2 MB.`, chữ đã nhập giữ; không trang 500/403, không "connection reset"; không sản phẩm mới; `uploads/` không đổi |
| 29 | AC-006-30 | Sửa sản phẩm ở mục 20, chọn ảnh WebP mới; rồi sửa một sản phẩm seed (ảnh `/images/products/…`), chọn ảnh mới | Lần 1: `image_path` mới `.webp`, file PNG cũ biến mất khỏi `uploads/`; lần 2: ảnh seed trong `src/main/resources/static/images/products/` vẫn còn |
| 30 | AC-006-31 (âm) | `curl -i --path-as-is http://localhost:8080/uploads/../application.yml`, `…/uploads/%2e%2e/application.yml`, `…/uploads/..%2f..%2fpom.xml`, `…/uploads/khong-co.png` | 400 hoặc 404; không bao giờ trả nội dung file ngoài `uploads/` |
| 31 | AC-006-32, 33, 37 | Trang sửa A (kho 10): `+5`; rồi `5`; psql đặt kho 3: `-2`, rồi `-5`; ẩn A rồi `+1` | 15 với đúng chữ `… Tồn kho hiện tại: 15.`; 20; 1; `Không thể giảm 5 vì tồn kho hiện tại chỉ còn 1.` (kho không đổi); sản phẩm ẩn vẫn điều chỉnh được |
| 32 | AC-006-34 (âm) | Nhập `0`, trống, `abc`, `1.5`, `100001`, `-100001`; psql kho 99 999 rồi `+5` | Chữ `Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.`; lần cuối `Tồn kho sau điều chỉnh không được vượt quá 100.000.`; kho không đổi |
| 33 | AC-006-35, 36, 46, 47 | Đọc kết quả các test race ở mục 1; psql `select id, stock from products where stock < 0` | Mọi test race xanh (20 vòng); truy vấn không trả dòng nào |
| 34 | AC-006-38, 39 | psql thêm 25 đơn cho vài user (như mục 18 của plan GĐ5, nhiều trạng thái); mở `/admin/orders`, `?page=2`, `?page=abc`, `?status=PENDING`, `?status=abc`, lọc trạng thái không có đơn | 20/trang, mới nhất trước; cột đủ, ngày giờ khớp `to_char(created_at at time zone 'Asia/Ho_Chi_Minh', …)`; lọc đúng, phân trang giữ `status`; `abc` như `Tất cả`; mục lọc được đánh dấu; câu trống đúng |
| 35 | AC-006-40, 41 | Mở chi tiết một đơn ở mỗi trạng thái | Tiêu đề `Đơn hàng #{id} \| Quản trị Mini Shop`; đủ thông tin AC-005-23 + `Tài khoản đặt hàng: {họ tên} ({email})`, `Cập nhật lần cuối`; nút: `PENDING` `Xác nhận đơn`, `Hủy đơn`; `CONFIRMED` `Bắt đầu giao hàng`, `Hủy đơn`; `SHIPPING` `Xác nhận đã giao`; cuối: `Đơn hàng đã ở trạng thái cuối.` |
| 36 | AC-006-42, 43 | Đơn `PENDING` → `Xác nhận đơn`; khách xem `/orders/{id}`; → `Bắt đầu giao hàng` → `Xác nhận đã giao` (hộp xác nhận: Hủy bỏ rồi OK) | Đúng chữ thông báo; kho không đổi suốt quá trình; khách thấy `Đã xác nhận`, không còn nút hủy; cuối `Đã giao` |
| 37 | AC-006-44 | Đơn `CONFIRMED` có A: 2 (ghi kho A; psql ẩn A) → `Hủy đơn` (hộp xác nhận OK) | `Đã hủy đơn hàng #{id} và hoàn lại tồn kho.`; trạng thái `Đã hủy`; kho A +2 |
| 38 | AC-006-45, 46, 47 (âm) | DevTools sửa ô `status` thành `SHIPPING` trên đơn `PENDING`, thành `abc`; mở cùng một đơn `CONFIRMED` ở hai tab, bấm `Hủy đơn` ở tab 1 rồi tab 2; đơn `PENDING`: admin mở chi tiết, khách hủy ở cửa sổ khác, admin bấm `Xác nhận đơn` | `Không thể chuyển đơn hàng #{id} từ Chờ xử lý sang Đang giao.`; `Trạng thái không hợp lệ.`; tab 2: `Không thể chuyển … từ Đã hủy sang Đã hủy.`, kho chỉ cộng một lần; admin: `Không thể chuyển … từ Đã hủy sang Đã xác nhận.`; không trang 500 |
| 39 | AC-006-48 (âm) | Admin mở `/orders/{đơn của user@}` (đường dẫn của khách) | 404 `Không tìm thấy đơn hàng` (trang storefront) |
| 40 | AC-006-49, 50 | Device mode 375px và 1280px mọi trang admin; Console, Network; xem mã HTML; nhập `<script>alert(1)</script>` vào tên danh mục, tên và mô tả sản phẩm, rồi (bằng `user@`) vào địa chỉ, ghi chú của một đơn và xem ở admin | 1280px dùng thoải mái; 375px không cuộn ngang cả trang (bảng cuộn trong khung), sidebar gập; không lỗi CSP; mọi tài nguyên từ `localhost`; mọi ô có `label`, lỗi gắn `aria-describedby`; chuỗi script hiện đúng chữ, không chạy |
| 41 | AC-006-51 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`: thêm danh mục, thêm sản phẩm có ảnh, điều chỉnh kho, `user@` đặt đơn, admin đổi trạng thái tới `Đã giao`, một đơn khác admin hủy | Giống PostgreSQL; kho trừ rồi cộng lại đúng; ảnh hiển thị |
| 42 | AC-006-52 | Trên PostgreSQL: `user@` đặt đơn → admin `/admin` (ghi doanh thu) → bấm ô `Chờ xử lý` → mở đơn → `Xác nhận đơn` → `Bắt đầu giao hàng` → `Xác nhận đã giao` | `user@` thấy `Đã giao` ở `/orders`; doanh thu dashboard tăng đúng tổng đơn |
| 43 | Log (spec §8) | Đọc log console của các mục trên | Mỗi thao tác ghi của admin có một dòng INFO (id admin, đối tượng, hành động); không có số điện thoại, địa chỉ, ghi chú, email, tên file gốc |
| 44 | Dọn dẹp | psql: xóa `order_items`, `orders` có `id` lớn hơn giá trị chụp đầu; khôi phục `products`, `categories` theo ảnh chụp (xóa sản phẩm, danh mục mới); xóa file mới trong `uploads/`; tắt mọi app | Dữ liệu seed như cũ; `uploads/` như cũ; không còn tiến trình; container Postgres đã dừng |

## 6. Câu hỏi cho người dùng

Không câu nào chặn việc bắt đầu triển khai: plan làm theo khuyến nghị.

1. **Ảnh lớn hơn 10 MB.** Spec chỉ đòi file 2,5 MB và 9 MB phải nhận `Ảnh tối đa 2 MB.` trên form. Plan đạt điều đó bằng cách cho Tomcat nhận tới 10 MB rồi tự kiểm 2 MB trong ứng dụng (QĐ6). Với file **lớn hơn 10 MB** (hiếm khi admin chọn nhầm), trình duyệt sẽ báo lỗi kết nối hoặc trang 403, không phải thông báo đẹp.
   - **Khuyến nghị:** chấp nhận ở GĐ6, ghi vào ADR-0010; GĐ7 (trang lỗi) có thể thêm trang "File quá lớn".
   - Phương án khác: nâng trần lên 20–50 MB. Đẩy ngưỡng xa hơn nhưng server phải nhận (ghi tạm ra đĩa) file lớn hơn trước khi từ chối.
2. **Cuối giai đoạn có commit, push và mở PR `phase-6/admin` → `main` không?** Khuyến nghị: **có, nhưng chưa merge**, giống GĐ3–5. Các agent không tự commit.
3. **Chỉ để bạn biết** (các điểm spec chưa nói, plan đã chọn):
   - link 404 quản trị dùng chữ `Quay lại danh sách sản phẩm` / `Quay lại danh sách đơn` / `Quay lại danh sách danh mục`; ô chọn danh mục có lựa chọn trống đầu tiên `Chọn danh mục`. Đây là chữ mới duy nhất ngoài spec;
   - hai admin tạo `Áo` và `áo` **đúng cùng một lúc** có thể cùng thành công (ràng buộc DB phân biệt hoa thường; spec §9 câu 6 đã chấp nhận). Tạo lần lượt thì luôn bị chặn;
   - dashboard đọc trong hai transaction chỉ đọc: số đơn, doanh thu và đơn mới nhất đi cùng nhau; danh sách sắp hết hàng đọc ngay sau (QĐ10);
   - ẩn/hiện từ danh sách quay về đúng trang và từ khóa đang tìm;
   - ảnh của sản phẩm đã ẩn vẫn mở được nếu ai đó có đúng URL `/uploads/{uuid}…` (tên ngẫu nhiên, không đoán được);
   - giá trong form sửa hiện dạng số liền (`1250000`); ô nhận cả `1.250.000`;
   - `GET /admin/products/new` khi chưa có danh mục không có form, chỉ có link tạo danh mục;
   - có **ADR-0010** mới (viết trước khi code): lưu và phục vụ ảnh, điều chỉnh tồn kho, và quy tắc khóa `categories` trước `products`.

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Trang "File quá lớn" (413) và trang lỗi chung** (500, CSRF hết hạn khi form để lâu): GĐ7, gom luôn các advice 404 của `catalog`, `cart`, `order` và handler 404 của `admin`.
- **Index ghép `orders (status, created_at)`** (migration V3): chỉ đáng làm khi có hàng chục nghìn đơn.
- **`lock_timeout` cho các câu khóa** (`users`, `orders`, `products`, `categories`): đã ghi ở ADR-0008/0009 cho GĐ8.
- **Cache header dài hạn cho `/uploads/**`** (tên UUID không bao giờ đổi nội dung): tối ưu nhỏ, cân nhắc ở GĐ8 cùng CDN/đám mây.
- **Bản `StorageService` dùng dịch vụ đám mây** và `app.seed.enabled`: GĐ8 (đã ghi trong `CLAUDE.md`).
- **Cho `CheckoutForm` dùng `common.FormText`** để bỏ đoạn code trùng: dọn dẹp nhỏ, GĐ7.
- **Tìm đơn theo email, số điện thoại, mã đơn; lọc sản phẩm theo danh mục; nhật ký thao tác admin trong DB; khóa lạc quan khi nhiều admin; nhiều ảnh/cắt ảnh; quản lý người dùng; email báo khách; biểu đồ doanh thu:** ngoài phạm vi spec 006 hoặc nằm trong "Loại ra" của brief.

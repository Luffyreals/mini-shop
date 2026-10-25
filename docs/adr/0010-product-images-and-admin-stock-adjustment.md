# ADR-0010: Ảnh sản phẩm qua `StorageService` phục vụ tại `/uploads/**`; admin điều chỉnh tồn kho bằng câu UPDATE có điều kiện; khóa `categories` trước `products`

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-24

## Bối cảnh

GĐ6 ([spec 006](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/006-admin.md)) cho admin ba quyền ghi mới, mỗi quyền chạm vào một invariant:
- **Upload ảnh sản phẩm.** File do người dùng gửi lên là bề mặt tấn công: một file HTML đổi đuôi thành `.jpg` vẫn có đuôi và content type "hợp lệ"; tên file như `../../x.png` có thể ghi ra ngoài thư mục; SVG có thể chứa script. Brief §6.1 yêu cầu ảnh đi qua interface `StorageService` để GĐ8 thay bằng dịch vụ đám mây mà không sửa nghiệp vụ.
- **Đổi tồn kho sau khi tạo sản phẩm.** `Product.stock` đã `updatable = false` (ADR-0005), vì form sửa đọc 10, khách mua 2, admin lưu 10 là mất 2 sản phẩm đã bán.
- **Xóa danh mục.** Spec cấm xóa danh mục còn sản phẩm (kể cả sản phẩm đã ẩn). Nếu admin thêm sản phẩm vào danh mục đúng lúc đang xóa, cách "đếm rồi xóa" hoặc ra lỗi khóa ngoại (trang 500), hoặc phải bắt exception sau khi transaction PostgreSQL đã hỏng.

ADR-0005, 0008 và 0009 vẫn đúng; ADR này bổ sung, không thay thế.

## Quyết định

**1. Ảnh đi qua `storage.StorageService`, bản cài đặt `LocalStorageService` ghi ra đĩa.**
- `ImageUpload.from(originalFilename, contentType, size, InputStreamSource)` là **cách duy nhất** tạo một ảnh hợp lệ ("parse, don't validate"). Nó kiểm, theo thứ tự: kích thước ≤ 2 MiB (trước khi đọc nội dung); đuôi `jpg`/`jpeg`/`png`/`webp`; content type khai báo `image/jpeg`/`image/png`/`image/webp`; chữ ký đầu file (JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, WebP `RIFF….WEBP`). Cả ba phải chỉ **cùng một** định dạng. Không nhận SVG, GIF.
- File được lưu với tên `UUID.{jpg|png|webp}` bằng `CREATE_NEW` (không bao giờ ghi đè), sau khi kiểm đường dẫn đã chuẩn hóa vẫn nằm trong thư mục gốc. Tên gốc không được dùng, lưu hay ghi log. `products.image_path` lưu `/uploads/{uuid}.{ext}`; ảnh seed giữ `/images/products/…`.
- **Dọn file theo transaction:** file mới bị xóa nếu transaction lưu sản phẩm không commit (`TransactionSynchronization.afterCompletion`); file cũ bị xóa **sau khi** commit (`afterCommit`), và chỉ khi đường dẫn khớp đúng mẫu `/uploads/{uuid}.{ext}`, nên ảnh seed không bao giờ bị xóa. Lỗi xóa chỉ ghi log.
- **Phục vụ ảnh:** `GET /uploads/**` là đường dẫn công khai (thêm vào `SecurityConfig.PUBLIC_PATHS`, chỉ GET), map tới thư mục upload bằng resource handler của Spring MVC. `PathResourceResolver` và `StrictHttpFirewall` chặn `..`/`%2e%2e`; `X-Content-Type-Options: nosniff` có sẵn. Thư mục cấu hình bằng `app.storage.local.upload-dir` (biến môi trường `UPLOAD_DIR`, mặc định `uploads`).
- **Giới hạn multipart:** `spring.servlet.multipart.max-file-size` là **10 MB**; giới hạn 2 MB thật nằm trong `ImageUpload`. Nếu để `max-file-size` là 2 MB, việc parse multipart thất bại ngay khi bộ lọc đầu tiên đọc tham số (`CsrfFilter` đọc `_csrf`), trước khi tới controller: đo trên Tomcat thật (`AdminUploadLimitTest`), ảnh 2,5 MB nhận **HTTP 413**, không có form nào được hiện lại; file lớn hơn `max-swallow-size` còn có thể làm Tomcat đóng kết nối.

**2. Sau khi tạo sản phẩm, tồn kho chỉ đổi bằng một lượng điều chỉnh, trong một câu UPDATE có điều kiện:**

```sql
update products set stock = stock + :delta
where id = :id and :delta <> 0 and stock + :delta between 0 and 100000
```

- 0 dòng bị ảnh hưởng thì `ProductAdminService` đọc lại sản phẩm để phân loại: không có → 404; `delta < 0` → "không đủ để giảm"; `delta > 0` → "vượt 100.000". 1 dòng thì giá trị đọc lại chắc chắn là kết quả của chính câu đó, vì câu UPDATE giữ khóa dòng tới khi commit.
- Câu này và `decrementStock` của khách là hai `UPDATE` trên cùng dòng: PostgreSQL xếp hàng chúng và đánh giá lại `WHERE` trên bản ghi mới nhất (READ COMMITTED), nên không mất cập nhật và tồn kho không bao giờ âm. Nó chỉ khóa **một** dòng `products` và không giữ khóa nào khác.
- Chỉ `catalog.ProductAdminService` được gọi `adjustStock`; chỉ `order` được gọi `decrementStock`/`incrementStock` (`ArchitectureTest`).

**3. Xóa danh mục và thêm/sửa sản phẩm cùng khóa dòng `categories` trước** (`CategoryRepository.lockById`, `PESSIMISTIC_WRITE`; PostgreSQL sinh `for no key update`, H2 `for update`). `FOR NO KEY UPDATE` của câu xóa **không** chặn được `INSERT products` (khóa ngoại chỉ cần `FOR KEY SHARE`), nên cần khóa tường minh ở cả hai đường. Xóa trước thì yêu cầu thêm sản phẩm thấy danh mục không còn ("Danh mục không tồn tại."); thêm trước thì câu đếm của yêu cầu xóa thấy sản phẩm mới ("Không thể xóa…").

**Thứ tự khóa, bổ sung bảng của ADR-0009:**

| Thao tác | Khóa lần lượt |
|---|---|
| Ghi giỏ | `users` |
| Đặt hàng | `users` → `products` theo `id` tăng dần |
| Đổi trạng thái đơn (khách hủy; admin xác nhận, giao, hủy) | `orders` → `products` theo `id` tăng dần (chỉ khi hủy) |
| Admin thêm/sửa sản phẩm | `categories` (danh mục đích) → `products` (một dòng, khi sửa) |
| Admin xóa danh mục | `categories` |
| Admin điều chỉnh tồn kho | `products` (một dòng) |

Không đường nào khóa `products` rồi mới tới `users`, `orders` hay `categories`; đặt hàng, giỏ và đơn không chạm `categories`. Vì vậy không có vòng chờ.

SQL thật Hibernate 7 sinh trên PostgreSQL (ghi lại khi viết test GĐ6; danh sách cột được rút gọn thành `…`):

```sql
-- Admin điều chỉnh tồn kho (ProductRepository.adjustStock), rồi đọc lại để phân loại kết quả
update products p1_0 set stock=(p1_0.stock+?) where p1_0.id=? and ?<>0 and (p1_0.stock+?) between 0 and 100000
select … from products p1_0 where p1_0.id=?

-- Admin xóa danh mục rỗng
select c1_0.id, c1_0.description, c1_0.name from categories c1_0 where c1_0.id=? for no key update of c1_0   -- CategoryRepository.lockById
select count(p1_0.id) from products p1_0 where p1_0.category_id=?
delete from categories where id=?

-- Admin hủy đơn (OrderAdminService.changeStatus → OrderCancellation)
select o1_0.id, … from orders o1_0 where o1_0.id=? for no key update of o1_0     -- OrderRepository.lockById (không lọc chủ đơn)
select i1_0.order_id, … from order_items i1_0 where i1_0.order_id=?
update orders set …, status=?, … where id=?                                       -- flush trước câu cộng kho đầu tiên
update products p1_0 set stock=(p1_0.stock+?) where p1_0.id=? and ?>0             -- incrementStock, theo id tăng dần
```

Trên H2 các câu khóa là `FOR UPDATE`. Câu lưu form sửa sản phẩm không bao giờ ghi cột `stock` (`updatable = false`) và, nhờ `@DynamicUpdate` trên `Product`, chỉ ghi các cột thật sự thay đổi, nên không ghi đè `active` hay `image_path` mà một yêu cầu khác vừa đổi.

## Hệ quả

- **Tích cực:**
  - Service không thể nhận ảnh chưa kiểm; file không bao giờ ra ngoài thư mục upload; không còn file mồ côi khi lưu thất bại hay khi thay ảnh.
  - GĐ8 thay `LocalStorageService` bằng bản đám mây mà không sửa `catalog` hay `admin`.
  - Admin không bao giờ ghi đè số khách vừa mua; tồn kho không âm, không vượt 100.000.
  - Xóa danh mục đúng cả khi chạy đồng thời, không trang 500, không sản phẩm mồ côi.
- **Tiêu cực / đánh đổi:**
  - **Request lớn hơn 10 MB** vẫn bị Tomcat từ chối trước controller: trình duyệt nhận lỗi 413 hoặc lỗi kết nối, không có thông báo `Ảnh tối đa 2 MB.`. Người dùng đã chấp nhận ở GĐ6 (kế hoạch phase-6, câu 1); trang "File quá lớn" để GĐ7.
  - Server nhận (ghi tạm ra đĩa) file tới 10 MB trước khi từ chối file lớn hơn 2 MB.
  - Hai admin tạo `Áo` và `áo` **đúng cùng lúc** có thể cùng thành công: trùng tên không phân biệt hoa thường được kiểm trong Java, còn `uq_categories_name` phân biệt hoa thường (index `lower(name)` cho kết quả khác nhau giữa PostgreSQL/Alpine và H2).
  - Ảnh của sản phẩm đã ẩn vẫn mở được nếu ai đó có đúng URL (tên UUID, không đoán được).
  - File tạm do crash giữa lúc ghi file và lúc commit có thể còn lại; chấp nhận được với một cửa hàng nhỏ.
  - Các câu khóa vẫn chưa có `lock_timeout` (như ADR-0008, 0009); đặt trước khi deploy (GĐ8).
  - Khi chỉnh tồn kho thất bại, số "tồn kho hiện tại" trong thông báo là số đọc lại sau câu UPDATE, có thể đã khác lúc câu chạy nếu khách vừa mua.

## Phương án đã cân nhắc

- **Ô tồn kho tuyệt đối trong form sửa:** mất cập nhật như mô tả ở trên; làm an toàn phải so giá trị cũ và báo "tồn kho vừa thay đổi", phức tạp hơn mà không lợi gì.
- **`SELECT … FOR UPDATE` rồi tính tồn kho trong Java:** đúng nhưng thừa; ADR-0005 đã chọn câu UPDATE có điều kiện.
- **`RETURNING stock` của PostgreSQL:** không có trong JPQL và không chạy trên H2.
- **Phục vụ ảnh qua controller tự viết:** viết lại những gì resource handler đã làm đúng (chặn traversal, Range, Last-Modified).
- **Kiểm ảnh bằng `ImageIO.read`:** chậm, không đọc WebP nếu thiếu plugin, và giải mã ảnh không tin cậy là bề mặt tấn công lớn hơn.
- **Để `max-file-size: 2MB` và bắt `MaxUploadSizeExceededException`:** handler trong controller không bao giờ được gọi vì request thất bại ở tầng filter (đo được HTTP 413), và kể cả tới được thì cũng không còn giá trị form để hiện lại.
- **CSRF token trên query string của form upload:** token lộ trong log và lịch sử trình duyệt.
- **Đếm rồi xóa danh mục, bắt lỗi khóa ngoại:** sau exception, transaction PostgreSQL đã hỏng nên không đếm lại được số sản phẩm cho thông báo.
- **Index `unique (lower(name))` (migration V3):** `lower()` cho kết quả khác nhau giữa PostgreSQL/Alpine và H2.

# Kế hoạch GĐ4: Giỏ hàng

- **Spec:** [004 Giỏ hàng](../specs/004-shopping-cart.md) (Đã duyệt 2026-10-24; người dùng chấp nhận cả 9 khuyến nghị: vượt tồn kho thì đặt bằng tồn kho và báo "Chỉ còn {n} sản phẩm trong kho.", tối đa 99 mỗi dòng, thêm xong ở lại trang chi tiết, không có nút thêm ở thẻ danh sách, header hiện tổng số lượng và chỉ "Giỏ hàng" khi trống, không thông báo sau khi gộp, giữ dòng ngừng bán/hết hàng kèm nhãn và không tính vào tổng và GET không sửa giỏ, `ADMIN` có giỏ như `USER`, số lượng 0 là không hợp lệ)
- **Nhánh:** `phase-4/cart` (đã tạo từ `main` sau tag `v0.3.0`)
- **Đã có từ GĐ1–3, không làm lại:**
  - bảng `carts`, `cart_items` (V1) với `uq_carts_user`, `uq_cart_items_cart_product`, `ck_cart_items_quantity_positive`;
  - entity `Cart` (`addItem`/`removeItem`, `getItems()` chỉ đọc, `cascade = ALL`, `orphanRemoval`), `CartItem`, `CartRepository.findByUserId`, tất cả package-private;
  - `catalog.Product` (public, `stock` là `updatable = false`), `ProductRepository` (public, `findByIdAndActiveTrue`, `decrementStock`);
  - `user.CurrentUser` (principal, public), `TestUsers` (fixture test);
  - `SecurityConfig` với CSP, đường dẫn công khai chỉ GET, `/error` và `/access-denied` mở cho mọi method;
  - fragment `layout`, `notice(kind, text)`, `product :: tile/stock`; helper `#vnd`; hướng thiết kế "The specimen catalogue".
- **Không đổi `pom.xml`**, nên không có task cho `devops-engineer`.
- **Không có migration và không đổi mapping entity**, nên không có task `schema-guardian`. Nếu task nào thấy buộc phải đổi mapping (`@Column`, `@OneToMany`…) thì dừng lại và báo phiên chính.

## 1. Mục tiêu

- Khách vãng lai thêm được sản phẩm vào giỏ từ trang chi tiết, xem `/cart`, sửa số lượng, xóa dòng. Giỏ nằm trong HTTP session, không ghi gì vào DB. Duyệt trang danh sách vẫn không tạo session.
- Người đã đăng nhập (`USER` hay `ADMIN`) có đúng một giỏ trong DB. Giỏ khách được gộp vào giỏ DB ngay khi đăng nhập thành công, mà không đổi hành vi chuyển hướng của GĐ3. Hai yêu cầu "thêm" đồng thời của cùng một người không bao giờ gây trang 500, không tạo giỏ hay dòng thứ hai, và không làm mất số lượng.
- Giỏ chỉ lưu id sản phẩm và số lượng: giá luôn là giá hiện tại, tồn kho không bao giờ bị thao tác giỏ thay đổi. Header của mọi trang hiện "Giỏ hàng" hoặc "Giỏ hàng (n)".
- `./mvnw verify` xanh **với 0 test bị bỏ qua** (Docker đang bật). Mọi AC-004-1 đến AC-004-24 có test hoặc mục checklist tương ứng.

## 2. Quyết định thiết kế

**QĐ1. Giỏ của khách là một object nhỏ `SessionCart` lưu thẳng làm attribute của `HttpSession`, đọc bằng `getSession(false)`. Không dùng bean `@SessionScope`.**
- `cart.SessionCart` (package-private, `final class implements Serializable`, có `serialVersionUID`):
  - chỉ có một trường `LinkedHashMap<Long, Integer> lines` (id sản phẩm → số lượng), giữ thứ tự thêm vào. Không chứa entity, giá hay tên (spec §7);
  - mọi method `synchronized`, vì hai tab của cùng khách dùng chung một object trong session; không có đồng bộ thì `LinkedHashMap` có thể hỏng cấu trúc;
  - tên attribute là hằng `SessionCart.SESSION_ATTRIBUTE = "minishop.cart"`.
- Truy cập session đi qua interface `GuestCartStore` (package-private), cài đặt bằng `HttpSessionGuestCartStore`:
  - `find()` dùng `request.getSession(false)`: **không bao giờ tạo session**. Nhờ vậy header và trang giỏ trống của khách chưa có giỏ không sinh cookie `JSESSIONID`;
  - `getOrCreate()` chỉ được gọi khi thật sự ghi; `save(cart)` gọi lại `setAttribute` sau mỗi lần sửa (với session lưu trong bộ nhớ của Tomcat thì không bắt buộc, nhưng là cách đúng nếu sau này dùng session phân tán); `clear()` xóa attribute.
- `changeSessionId` (chống session fixation, GĐ3) giữ nguyên mọi attribute, nên giỏ khách vẫn còn ngay sau khi đăng nhập để gộp. Đăng xuất hủy session, nên khách thấy giỏ trống (AC-004-17).
- **Hai tab của khách cùng sửa một lúc** có thể làm mất một lần cập nhật (đọc, tính, ghi). AC-004-20 chỉ yêu cầu điều này cho người đã đăng nhập; với khách, `synchronized` đủ để dữ liệu không hỏng.

*Phương án bị loại:*
- **Bean `@SessionScope`.** Proxy của nó *tạo session* ngay khi được đọc, nên chỉ cần header đọc số lượng là mọi khách xem trang danh sách đều bị tạo session (trái spec §8). Proxy cũng giấu việc truy cập session sau một lời gọi method bình thường, khó hiểu khi học và khó test.
- **Lưu entity hay giá trong session:** entity bị serialize, lazy loading lỗi, giá cũ bị dùng lại (trái BR-004-3).

**QĐ2. Một API chung: mọi method của `CartService` nhận một `CartOwner`. Tầng web xác định `CartOwner` một lần, bằng một `HandlerMethodArgumentResolver`.**

```java
sealed interface CartOwner {
    record Customer(Long userId) implements CartOwner {}        // đã đăng nhập: giỏ DB
    record Guest(GuestCartStore store) implements CartOwner {}  // khách: giỏ session
}
```

- `CartOwners.resolve(Authentication auth, HttpServletRequest request)` (hàm thuần, package-private): principal là `CurrentUser` thì trả `Customer(user.getId())`, ngược lại (kể cả `anonymousUser`) trả `Guest(new HttpSessionGuestCartStore(request))`.
- `CartOwnerArgumentResolver` gọi hàm trên với `SecurityContextHolder.getContext().getAuthentication()`. `CartWebConfig implements WebMvcConfigurer` (trong `cart`) đăng ký resolver. Controller chỉ khai báo tham số `CartOwner owner` và chuyển nó cho service. **Controller không rẽ nhánh theo loại giỏ** (ADR-0006, spec §8).
- Giỏ chỉ được xác định từ principal hoặc session. Không có URL hay tham số nào chứa id giỏ hay id người dùng (BR-004-8, AC-004-18).
- Service `switch` trên sealed interface (pattern matching của Java 21+), nên trình biên dịch bắt buộc xử lý đủ hai trường hợp.
- `@WithMockUser` tạo principal không phải `CurrentUser`, nên sẽ được coi là khách. Test cần giỏ DB phải dùng `TestUsers` hoặc đăng nhập thật.

*Phương án bị loại:*
- **Service tự đọc `SecurityContextHolder` / `RequestContextHolder`.** Đó là phụ thuộc ẩn qua thread-local: test đơn vị phải dựng context giả, và service gắn với tầng web.
- **Controller nhận `(CurrentUser, HttpSession)` rồi truyền xuống service.** Service phụ thuộc API servlet, và mỗi handler phải tự rẽ nhánh.

**QĐ3. Toàn bộ quy tắc nghiệp vụ nằm trong `CartService` (`@Transactional`). Kết quả trả về là `CartChange`; tầng web chỉ đổi kết quả thành chữ.**

`MAX_LINE_QUANTITY = 99`. Đầu vào của service đã là số (QĐ8); service vẫn chặn `quantity` ngoài 1–99 bằng `IllegalArgumentException`, vì đó là lỗi lập trình.

| Thao tác | Quy tắc, theo thứ tự | Kết quả |
|---|---|---|
| `add(owner, productId, q)` | 1. `findByIdAndActiveTrue`; không có → `NotFoundException` (AC-004-6). 2. `stock == 0` → không đổi gì (AC-004-5). 3. `limit = min(stock, 99)`, `desired = hiện có + q`. 4. `desired ≤ limit` → đặt `desired`. 5. Vượt: đặt `limit`; nếu `limit == stock` là chạm tồn kho, ngược lại là chạm 99 | `OK` / `OUT_OF_STOCK` / `CAPPED_AT_STOCK(stock)` / `CAPPED_AT_MAX(99)` |
| `update(owner, productId, q)` | 1. Dòng không có trong giỏ → không đổi. 2. Sản phẩm đã ẩn → `NotFoundException` (404 "Sản phẩm không tồn tại hoặc đã ngừng bán."). 3. `stock == 0` → không đổi. 4. `q > stock` → đặt `stock` (AC-004-11). 5. Ngược lại đặt `q` | `NOT_IN_CART` / `OUT_OF_STOCK` / `CAPPED_AT_STOCK(stock)` / `OK` |
| `remove(owner, productId)` | Xóa dòng nếu có; không có thì thôi (AC-004-12). Không kiểm sản phẩm, nên xóa được cả dòng đã ngừng bán | (không trả gì) |
| `mergeGuestCart(userId, guest)` | Giỏ khách rỗng → không làm gì. Tải mọi sản phẩm một lần; bỏ qua sản phẩm đã ẩn hoặc `stock == 0` (AC-004-16); còn lại `min(DB + khách, stock, 99)` (AC-004-15); tạo giỏ DB nếu chưa có | (không trả gì) |

- **Chạm tồn kho có thể làm số lượng giảm:** dòng đang 5 mà kho chỉ còn 2, thêm 1 thì dòng thành 2 kèm "Chỉ còn 2 sản phẩm trong kho.". Đây đúng là BR-004-1 ("không vượt tồn kho tại thời điểm thêm").
- **Chạm 99 khi kho còn hơn 99** (ví dụ "Chuột không dây văn phòng" có 100, thêm 60 rồi 50): spec chưa có chữ cho trường hợp này. Plan làm theo **khuyến nghị ở câu hỏi 1**: đặt bằng 99 và báo "Số lượng tối đa cho mỗi sản phẩm là 99.". Đổi sang phương án khác chỉ sửa một nhánh trong service và một chuỗi trong controller.
- **Không lưu giá (BR-004-3):** `CartItem` không có cột giá; trang giỏ luôn đọc `Product.price` hiện tại (AC-004-13).
- **Không bao giờ ghi `products.stock` (AC-004-21, BR-004-4):** `cart` không gọi `decrementStock`, có rule ArchUnit kiểm. `Product` chỉ được *đọc*; `stock` vốn là `updatable = false` nên cascade hay dirty checking cũng không thể ghi cột này.
- **Mọi thao tác ghi giỏ DB gọi `cart.touch()`**, để `carts.updated_at` thật sự là thời điểm sửa cuối (thêm dòng vào collection phía `mappedBy` không làm `Cart` "bẩn", nên `@PreUpdate` không tự chạy).
- `view` là `@Transactional(readOnly = true)` và **không bao giờ sửa giỏ**, kể cả khi thấy dòng ngừng bán hay vượt tồn kho (AC-004-14, câu hỏi mở 7).

*Phương án bị loại:* trả chữ tiếng Việt từ service. Service sẽ dính vào cách hiển thị, và test service phải so chuỗi giao diện.

**QĐ4. Đồng thời (AC-004-20): mọi transaction ghi giỏ DB khóa dòng `users` của chủ giỏ trước tiên (`PESSIMISTIC_WRITE`). Unique constraint là lưới an toàn cuối, không bị bắt.**
- `CartRepository.lockOwner(userId)`: `@Lock(LockModeType.PESSIMISTIC_WRITE)` trên `select u from User u where u.id = :userId`. Hibernate sinh `... for update` (trên PostgreSQL có thể là `for no key update`; B1 ghi lại SQL thật trong báo cáo). Method trả `User`, vì tạo `Cart` mới cũng cần nó.
- `add`, `update`, `remove`, `mergeGuestCart` của `Customer` đều theo đúng thứ tự: **khóa trước**, rồi mới `findWithItemsByUserId`, đọc và ghi, rồi commit (khóa được nhả khi commit).
- **Vì sao đúng:** hai tab gửi "thêm" cùng lúc thì transaction thứ hai đứng chờ ở câu khóa. Khi được chạy tiếp, ở mức `READ COMMITTED` mỗi câu lệnh sau đó nhìn thấy dữ liệu đã commit, nên nó thấy giỏ và dòng mà transaction đầu vừa tạo, rồi cộng tiếp. Kết quả: một giỏ, một dòng, số lượng là tổng (không mất cập nhật), không vượt tồn kho.
- **Khóa dòng `users`** vì đó là dòng duy nhất luôn có sẵn cho mỗi người, kể cả khi chưa có giỏ. Mỗi transaction chỉ khóa một dòng, nên không có deadlock (khác với trường hợp đơn nhiều sản phẩm mà ADR-0005 tránh). Đăng nhập chỉ `SELECT` thường nên không bị chặn.
- **Không bắt `DataIntegrityViolationException`:** với khóa, `uq_carts_user` và `uq_cart_items_cart_product` không thể bị vi phạm qua `CartService`. Nếu vẫn xảy ra thì đó là bug thật (ví dụ code mới quên khóa), và phải lộ ra thành lỗi chứ không được im lặng. Đây là cùng bài học của GĐ3: chỉ đổi lỗi của *đúng* constraint đã dự tính, và ở đây không có constraint nào được dự tính.
- Chạy được trên H2 (Hibernate dùng `H2Dialect`, sinh `for update`), có smoke test (TW4).
- **Đây là quy tắc mới mà GĐ5 phải theo** (`placeOrder` đọc và xóa giỏ, nên cũng khóa dòng `users` trước), nên ghi thành **ADR-0008** (QĐ14).

*Phương án bị loại:*
- **Bắt lỗi unique rồi thử lại (như GĐ3).** Phải chạy lại trong transaction *mới* (transaction cũ đã hỏng trên PostgreSQL), nên cần vòng lặp bên ngoài `@Transactional` và nhận diện hai tên constraint. Trường hợp "đã có dòng, cả hai cùng cộng" vẫn mất cập nhật, trừ khi viết thêm một câu `UPDATE ... SET quantity = least(...)`.
- **`INSERT ... ON CONFLICT DO UPDATE`.** H2 không hỗ trợ, nên hỏng profile `h2`. `MERGE` chuẩn SQL thì trên PostgreSQL vẫn có thể ném lỗi unique khi chạy đồng thời.
- **Optimistic locking (`@Version`)** trên `carts`: cần migration, vẫn phải bắt lỗi và thử lại, và không giải quyết lúc chưa có giỏ.

**QĐ5. Gộp giỏ: một `@EventListener` cho `InteractiveAuthenticationSuccessEvent`, đặt trong package `cart`. Không bọc success handler.**
- Đã kiểm mã nguồn Spring Security (nhánh main, cùng dòng 7.x): `AbstractAuthenticationProcessingFilter` chạy `sessionStrategy.onAuthentication` (đổi session ID), rồi `successfulAuthentication`: lưu `SecurityContext` → phát `InteractiveAuthenticationSuccessEvent` → gọi success handler. Filter nhận event publisher qua `ApplicationEventPublisherAware`, mà `AbstractAuthenticationFilterConfigurer` chạy `postProcess(authFilter)` (tức `initializeBean`), nên callback này được gọi.
  - Kết quả: listener chạy **sau** khi đổi session ID (attribute đã được chuyển sang) và **trước** khi redirect. Success handler mặc định (`SavedRequestAwareAuthenticationSuccessHandler`, `defaultSuccessUrl("/")`) không bị đụng tới, nên AC-003-10 vẫn giữ nguyên.
  - CartMergeIntegrationTest chứng minh điều này bằng đăng nhập thật. Nếu event không được phát, test sẽ đỏ; khi đó đổi sang `AuthenticationSuccessEvent` (do `ProviderManager` phát) và báo phiên chính.
- `CartMergeOnLogin` (`@Component`, package-private):
  1. principal không phải `CurrentUser` → bỏ qua;
  2. `RequestContextHolder.getRequestAttributes()` không phải `ServletRequestAttributes` → bỏ qua (đăng nhập không qua HTTP). Spring Boot đặt `RequestContextFilter` (order −105) **trước** filter của Spring Security (−100), và MockMvc cũng tự đặt request vào `RequestContextHolder`, nên trong một request đăng nhập thật thì luôn có;
  3. `store.find()`: không có giỏ khách thì thôi; giỏ rỗng thì chỉ `clear()`;
  4. `cartService.mergeGuestCart(userId, guest)`, **xong mới** `store.clear()` (tức là sau khi transaction gộp đã commit);
  5. bọc bước 4 trong `try/catch (RuntimeException e)`, log `warn` "Cart merge failed for user {}" (chỉ id, không có email) và **không ném tiếp** (BR-004-7). Nếu ném ra ngoài thì người dùng đã đăng nhập (context đã được lưu) nhưng lại thấy trang 500. Khi lỗi, giỏ session được giữ nguyên (không mất dữ liệu một cách âm thầm; nó mất khi đăng xuất như mọi giỏ khách).
- **Đặt trong `cart`, không trong `config`:** `config` không phụ thuộc `cart`, và `SecurityConfig` không đổi gì cho việc gộp. TW2 thêm rule ArchUnit để giữ điều này.

*Phương án bị loại:*
- **Bọc success handler trong `SecurityConfig`.** Tạo phụ thuộc `config → cart`. Phải tự dựng lại `SavedRequestAwareAuthenticationSuccessHandler` (request cache, `defaultSuccessUrl`), dễ làm hỏng AC-003-10.
- **`SessionAuthenticationStrategy` riêng.** Có sẵn request, nhưng một exception ở đây bị coi là đăng nhập thất bại (trái BR-004-7), và vẫn phải cấu hình trong `SecurityConfig`.
- **`AuthenticationSuccessEvent`.** Dùng được, nhưng nó chạy bên trong `ProviderManager` cho *mọi* lần xác thực, trước khi đổi session ID. Chỉ giữ làm phương án dự phòng.

**QĐ6. Tải dữ liệu không N+1 với `open-in-view=false`; view nhận record.**
- **Trang giỏ, `Customer`:** `@EntityGraph(attributePaths = {"items", "items.product"})` trên `findWithItemsByUserId`: **một** câu SQL lấy giỏ, các dòng và sản phẩm. Dòng sắp theo `CartItem.id` tăng dần trong Java (thứ tự thêm vào). Không thêm `@OrderBy`, để không đổi mapping.
- **Trang giỏ, `Guest`:** `productRepository.findAllById(ids)`: **một** câu `where id in (...)`, rồi ghép theo thứ tự của `SessionCart`. Id không còn tìm thấy (không thể xảy ra, vì sản phẩm chỉ bị xóa mềm) thì bỏ qua khi hiển thị.
- **Ảnh:** `toImageUrl` của `ProductService` chuyển thành `public final class catalog.ProductImages { public static String toUrl(String imagePath) }`, dùng chung cho `catalog` và `cart`. Vẫn chỉ có một chỗ đổi `image_path` thành URL (GĐ6 sẽ chuyển sang `StorageService`).
- **View record** (package-private trong `cart`):
  - `CartView(List<CartLineView> lines, BigDecimal total)`, kèm `isEmpty()`;
  - `CartLineView(Long productId, String name, String imageUrl, BigDecimal unitPrice, int quantity, BigDecimal lineTotal, CartLineStatus status, int availableStock)`;
  - `CartLineStatus { AVAILABLE, LOW_STOCK, OUT_OF_STOCK, DISCONTINUED }`, xét theo thứ tự: `!active` → `DISCONTINUED`; `stock == 0` → `OUT_OF_STOCK`; `quantity > stock` → `LOW_STOCK`; còn lại `AVAILABLE`;
  - `CartLineView` có thêm `isPurchasable()`, `isLowStock()`, `isOutOfStock()`, `isDiscontinued()`, để template viết `${line.lowStock}` thay vì so enum với chuỗi;
  - `availableStock` chỉ có nghĩa khi `LOW_STOCK` (là 0 ở các trạng thái khác), để trang giỏ không lộ số tồn kho ngoài nhãn "Chỉ còn {n} sản phẩm";
  - `lineTotal = unitPrice × quantity` cho dòng mua được (kể cả `LOW_STOCK`, BR-004-5), `null` cho dòng `OUT_OF_STOCK`/`DISCONTINUED`;
  - `total` là tổng các `lineTotal` khác `null`. Tiền là `BigDecimal`, không làm tròn (giá là VND nguyên).
- **Số trên header:**
  - `totalQuantity(owner)` có `@Transactional(propagation = SUPPORTS, readOnly = true)`. Khách: cộng từ `SessionCart`, không mở transaction, không lấy connection, không tạo session.
  - Người đã đăng nhập: **một** câu `select coalesce(sum(i.quantity), 0) from CartItem i where i.cart.user.id = :userId`.
  - Số trên header là tổng số lượng của **mọi** dòng, kể cả dòng không mua được: đó là "những gì đang nằm trong giỏ" (AC-004-15: {A:4, B:1} thì hiện 5).

**QĐ7. Số lượng trên header lấy qua một expression object của Thymeleaf, `${#cartHeader.quantity()}`, do `cart.CartHeaderDialect` cung cấp (cùng kiểu với `#vnd`).**
- `CartHeaderDialect` (`@Component`, package-private, `AbstractDialect implements IExpressionObjectDialect`):
  - `buildObject` lấy request từ context: `IWebContext` → `IServletWebExchange.getNativeRequestObject()`. B4 kiểm lại tên API của Thymeleaf 3.1; nếu khác thì dùng `RequestContextHolder`;
  - nó dựng `CartOwners.resolve(...)` và trả một object `CartHeader` có `int quantity()`. Giá trị được tính **lần đầu được gọi** rồi nhớ lại; `isCacheable` là `true`, nên mỗi lần render trang chỉ có tối đa một truy vấn;
  - context không phải web thì trả 0.
- **Vì sao chọn cách này:**
  - Chỉ chạy khi header thật sự được render. Các POST chỉ redirect thì không tốn truy vấn nào.
  - Chạy được trên **mọi** trang có header, kể cả trang do `@ExceptionHandler` render (404 "Không tìm thấy sản phẩm") và trang lỗi của GĐ7 sau này. Như vậy đạt đúng chữ "mọi trang storefront" của AC-004-22.
- **Hệ quả cho test:** dialect là `IDialect`, nên được nạp vào **mọi** `@WebMvcTest`, và nó cần bean `CartService`. Mà `CartService` là package-private, nên test ở package khác không tự mock được. Vì vậy TW0 tạo fixture `cart.CartHeaderTestConfig` (public `@TestConfiguration`, cung cấp `CartService` mock). Mọi web slice render layout phải `@Import` nó. TW0 chạy **trước** B4, để build luôn xanh.

*Phương án bị loại:*
- **`@ControllerAdvice` + `@ModelAttribute`.** Chạy trước *mọi* handler, kể cả POST chỉ redirect, nên phải bọc thêm một lớp lười. Model của nó cũng không có trong view do `@ExceptionHandler` trả về, nên trang 404 sẽ mất số lượng.
- **Lưu sẵn số lượng trong session.** Thành nguồn dữ liệu thứ hai, bị cũ khi cùng tài khoản sửa giỏ ở tab hay thiết bị khác.
- **Bean dùng qua `${@bean...}`.** Không được nạp vào `@WebMvcTest`, nên mọi slice test lỗi "No bean named".

**QĐ8. Tham số đến dạng chuỗi thô, như GĐ2. Không bao giờ 500.**
- `CartController` nhận `@RequestParam(required = false) String productId`, `String quantity`, và `@PathVariable String productId`. Spring không bao giờ lỗi khi bind chuỗi.
- `CartInput` (package-private, hàm static, thuần):
  - `Optional<Long> productId(String raw)`: `strip()`, khớp `\d{1,18}`, rồi `Long.valueOf`; còn lại (rỗng, `abc`, số âm, tràn) → rỗng;
  - `OptionalInt quantity(String raw)`: `strip()`, khớp `\d{1,2}`, và giá trị trong 1–99. Như vậy `0`, `00`, `-1`, `100`, `abc`, `1e2`, rỗng và `null` đều không hợp lệ.
- **Thứ tự kiểm tra khi thêm:**
  1. `productId` sai → ném `NotFoundException` → 404 (AC-004-6), service không được gọi;
  2. `quantity` sai → flash "Số lượng không hợp lệ.", về `/products/{id}`, service không được gọi (AC-004-7). Nếu id hợp lệ về dạng nhưng sản phẩm không tồn tại, trang được chuyển tới cũng là 404: kết quả vẫn là 404 và giỏ không đổi;
  3. gọi service.
- **Cập nhật và xóa:** id trong đường dẫn sai → 404. Số lượng sai → flash lỗi, về `/cart`.
- **Redirect tự dựng** từ id đã parse: `"redirect:/products/" + id` (một `Long`, nên không thể chèn gì khác). Không đọc `Referer` hay tham số "quay về" (spec §6, chống open redirect).
- **Flash (`RedirectAttributes.addFlashAttribute`), quy ước dùng lại cho GĐ5–6:**
  - `notice` (String, chữ đúng spec §6);
  - `noticeKind` (`success` | `info` | `error`, đúng các loại của fragment `notice`);
  - `showCartLink` (Boolean, chỉ khi thêm `OK` hoặc chạm giới hạn).

  Đều là kiểu serialize được, vì flash nằm trong session.

| Kết quả | Thêm | Cập nhật |
|---|---|---|
| `OK` | `success` "Đã thêm vào giỏ hàng." + `showCartLink` | `success` "Đã cập nhật số lượng." |
| `CAPPED_AT_STOCK(n)` | `info` "Chỉ còn {n} sản phẩm trong kho." + `showCartLink` | `info` "Chỉ còn {n} sản phẩm trong kho." |
| `CAPPED_AT_MAX` | `info` "Số lượng tối đa cho mỗi sản phẩm là 99." + `showCartLink` (câu hỏi 1) | (không xảy ra: đầu vào ≤ 99) |
| `OUT_OF_STOCK` | `error` "Sản phẩm này đã hết hàng." | `error` "Sản phẩm này đã hết hàng." |
| `NOT_IN_CART` | – | không có thông báo |
| Số lượng sai | `error` "Số lượng không hợp lệ." | `error` "Số lượng không hợp lệ." |
| Xóa | – | `success` "Đã xóa sản phẩm khỏi giỏ hàng." (cả khi dòng không có: xóa có tính lũy đẳng) |

**QĐ9. `SecurityConfig`: thêm một dòng `.requestMatchers("/cart", "/cart/**").permitAll()`, cho mọi method, đặt ngay sau dòng `/register`, `/access-denied`, `/error`.**
- Ghi cả `/cart` lẫn `/cart/**`, như cách GĐ3 làm với `/admin`.
- CSRF vẫn áp dụng (mặc định), nên POST không có token → 403 (AC-004-19), qua forward `/access-denied` đã mở sẵn.
- Đường dẫn công khai của GĐ2 vẫn chỉ GET: form thêm giỏ POST tới `/cart/items`, không phải `/products/**`. Test `publicPaths_areGetOnly_postToProductsRequiresLogin` giữ nguyên (AC-004-23).
- Lỗi của một POST giỏ được dispatch thành `POST /error`, mà `/error` đã mở cho mọi method từ GĐ3.
- Cập nhật Javadoc của class: GĐ4 mở `/cart/**` cho mọi người và mọi method, có CSRF.

**QĐ10. 404 của tầng giỏ: `cart.CartNotFoundAdvice`** (`@ControllerAdvice(assignableTypes = CartController.class)`, `@Order(HIGHEST_PRECEDENCE)`), bắt `NotFoundException` và `MethodArgumentTypeMismatchException`, trả view `product/not-found` với status 404, đúng trang của GĐ2 (AC-004-6).
- Không mở rộng `catalog.ProductNotFoundAdvice`: `catalog` không được biết tới `cart`.
- Không làm advice toàn cục: trang lỗi chung là việc của GĐ7. Lặp vài dòng là chấp nhận được và được ghi chú để GĐ7 gom lại.

**QĐ11. Giao diện: dùng lại hướng "The specimen catalogue", không vẽ lại. Template tuân thủ CSP.**
- `frontend-developer` dùng skill `design-taste-frontend` ở chế độ **dùng lại hướng đã vẽ**:
  - đọc token và comment đầu `app.css`; **không** chạy `draw.mjs`; không đổi token, font hay palette;
  - **không** thêm mini-cart, AJAX, nút "Đặt hàng", "one live thing" hay chữ tự nghĩ. Chữ mới được phép chỉ là nhãn ẩn cho khả năng truy cập (`visually-hidden`).
- **Trang chi tiết:** form "Thêm vào giỏ" chỉ có khi `product.inStock`. Sản phẩm hết hàng vẫn chỉ có nhãn "Hết hàng" như GĐ2 (AC-004-1).
  - Ô số lượng có `max="99"`, **không** đặt `max` bằng tồn kho: trang chi tiết không được lộ số tồn kho (GĐ2).
  - Form có `novalidate`, để chữ lỗi luôn đến từ server, như GĐ3.
- **Dòng không mua được** (`DISCONTINUED`, `OUT_OF_STOCK`) hiện số lượng dạng chữ, không có ô sửa và nút "Cập nhật"; vẫn có nút "Xóa" (AC-004-14). Tên sản phẩm `DISCONTINUED` là chữ thường, không link (link sẽ dẫn tới 404). Thành tiền hiện "—".
- **Không có hộp xác nhận khi xóa dòng:** cần JS ngoài, spec không yêu cầu, và việc này dễ làm lại. Quy tắc "hỏi xác nhận khi xóa" của agent frontend được plan này bỏ qua cho giỏ hàng.
- **Ảnh nhỏ trong giỏ** dùng `alt=""`, vì tên sản phẩm đã nằm ngay cạnh trong cùng dòng; nếu không, trình đọc màn hình sẽ đọc tên hai lần. Không có ảnh thì dùng `/images/placeholder-product.svg`.
- **Bảng giỏ ở 375px** không được làm trang tràn ngang (AC-004-24). Khuyến nghị: dưới breakpoint `sm`, mỗi `<tr>` thành một khối dọc. F1 chọn cách làm, miễn đạt checklist mục 23.

**QĐ12. Chiến lược test.**
- **Fixture `cart.CartHeaderTestConfig`** (QĐ7): `ProductControllerTest`, `AuthControllerTest`, `AccessDeniedTest`, `AdminControllerTest` thêm nó vào `@Import`. Mock trả 0, nên header là "Giỏ hàng". `CartControllerTest` tự `@MockitoBean CartService` (cùng package). `SecurityConfigTest` (`useDefaultFilters = false`) không nạp dialect, nên không cần.
- **Trang có đăng nhập** dùng `TestUsers` (id `user()` = 2, `admin()` = 1), không dùng `@WithMockUser` (QĐ2).
- **Test cần PostgreSQL** (khóa đồng thời, gộp khi đăng nhập thật, `products.stock` không đổi) dùng `@Import(TestcontainersConfiguration.class)` + `@Testcontainers(disabledWithoutDocker = true)`, và **không** dùng `@MockitoSpyBean`, để dùng chung context với `LoginFlowTest`.
  - Dữ liệu riêng: người dùng email đuôi `@cart.test` và sản phẩm tên bắt đầu bằng `Cart test ` (danh mục lấy từ seed). `@AfterEach` xóa `cart_items` → `carts` → sản phẩm test → người dùng test.
  - **Không** sửa sản phẩm hay tài khoản seed.
- **Race (AC-004-20)** gọi thẳng `CartService` (test nằm trong package `cart`), 2 luồng, `CountDownLatch` cho cùng xuất phát, lặp 20 vòng. Test class không mang `@Transactional`.
- **Bài học GĐ3 về query string:** form giỏ gửi tham số trong body, nên `.param()` trên POST là đúng như trình duyệt. Riêng kiểm `permitAll` thì dùng URL thật có query (`get("/cart?x")`).

**QĐ13. Phạm vi public.** `cart` vẫn package-private hoàn toàn. Thứ public mới duy nhất là `catalog.ProductImages`.
- **GĐ5 sẽ cần** (không làm bây giờ): một API hẹp, public, của `cart` để `order` đọc các dòng giỏ DB của một người (`productId`, `quantity`), và xóa giỏ trong cùng transaction đặt hàng, sau khi khóa dòng `users` (ADR-0008).

**QĐ14. Một ADR mới: ADR-0008 "Ghi giỏ hàng DB tuần tự theo từng người dùng bằng khóa dòng `users`".** Nội dung:
- QĐ4: bối cảnh hai tab, các phương án bị loại, vì sao khác với ADR-0005;
- hệ quả cho GĐ5.

ADR-0006 vẫn đúng (session cho khách, DB cho user, gộp khi đăng nhập), nên không bị thay thế. Cách lưu giỏ khách (QĐ1) và listener gộp (QĐ5) là chi tiết cài đặt của ADR-0006, ghi vào `CLAUDE.md`, không cần ADR riêng.

## 3. Hợp đồng code và view (chốt trước để backend, frontend, test làm song song)

Nếu cần thêm thuộc tính hay đổi tên, agent dừng lại và báo phiên chính; không tự đặt tên.

```java
// catalog (public mới)
public final class ProductImages { public static String toUrl(String imagePath); }   // null/blank -> null

// cart (tất cả package-private)
final class SessionCart implements Serializable {
    static final String SESSION_ATTRIBUTE = "minishop.cart";
    synchronized int quantityOf(Long productId);           // 0 nếu không có
    synchronized void put(Long productId, int quantity);   // thêm mới hoặc ghi đè, giữ vị trí cũ
    synchronized void remove(Long productId);              // không có thì thôi
    synchronized Map<Long, Integer> lines();               // bản sao không sửa được, đúng thứ tự thêm
    synchronized int totalQuantity();
    synchronized boolean isEmpty();
}
interface GuestCartStore { Optional<SessionCart> find(); SessionCart getOrCreate(); void save(SessionCart cart); void clear(); }
final class HttpSessionGuestCartStore implements GuestCartStore { HttpSessionGuestCartStore(HttpServletRequest request); }
sealed interface CartOwner { record Customer(Long userId) …; record Guest(GuestCartStore store) …; }
final class CartOwners { static CartOwner resolve(Authentication authentication, HttpServletRequest request); }

interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUserId(Long userId);                                      // đã có
    @Lock(PESSIMISTIC_WRITE) @Query("select u from User u where u.id = :userId")
    Optional<User> lockOwner(@Param("userId") Long userId);
    @EntityGraph(attributePaths = {"items", "items.product"})
    @Query("select c from Cart c where c.user.id = :userId")
    Optional<Cart> findWithItemsByUserId(@Param("userId") Long userId);
    @Query("select coalesce(sum(i.quantity), 0) from CartItem i where i.cart.user.id = :userId")
    long sumQuantityByUserId(@Param("userId") Long userId);
}
// Cart: thêm Optional<CartItem> findItem(Long productId)  (so getProduct().getId(), không khởi tạo proxy)

record CartChange(Outcome outcome, int quantity) {    // quantity: số lượng dòng sau thao tác (n của "Chỉ còn n")
    enum Outcome { OK, CAPPED_AT_STOCK, CAPPED_AT_MAX, OUT_OF_STOCK, NOT_IN_CART }
}
enum CartLineStatus { AVAILABLE, LOW_STOCK, OUT_OF_STOCK, DISCONTINUED }
record CartLineView(Long productId, String name, String imageUrl, BigDecimal unitPrice, int quantity,
                    BigDecimal lineTotal, CartLineStatus status, int availableStock) {
    boolean isPurchasable(); boolean isLowStock(); boolean isOutOfStock(); boolean isDiscontinued();
}
record CartView(List<CartLineView> lines, BigDecimal total) { boolean isEmpty(); }

@Service @Transactional
class CartService {
    static final int MAX_LINE_QUANTITY = 99;
    @Transactional(readOnly = true) CartView view(CartOwner owner);
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true) int totalQuantity(CartOwner owner);
    CartChange add(CartOwner owner, long productId, int quantity);     // NotFoundException nếu không có/đã ẩn
    CartChange update(CartOwner owner, long productId, int quantity);  // NotFoundException nếu đã ẩn
    void remove(CartOwner owner, long productId);
    void mergeGuestCart(long userId, SessionCart guestCart);
}

final class CartInput { static Optional<Long> productId(String raw); static OptionalInt quantity(String raw); }
final class CartOwnerArgumentResolver implements HandlerMethodArgumentResolver { … }   // supportsParameter: CartOwner
@Configuration class CartWebConfig implements WebMvcConfigurer { … }                 // addArgumentResolvers
@Controller @RequestMapping("/cart") class CartController { … }                      // xem bảng dưới
@ControllerAdvice(assignableTypes = CartController.class) class CartNotFoundAdvice { … }
@Component class CartMergeOnLogin { @EventListener void onLogin(InteractiveAuthenticationSuccessEvent e); }
@Component class CartHeaderDialect extends AbstractDialect implements IExpressionObjectDialect { … }  // #cartHeader
final class CartHeader { int quantity(); }                                           // tính lười, nhớ kết quả

// test (src/test, package com.minishop.cart)
@TestConfiguration public class CartHeaderTestConfig { @Bean CartService cartService() { return Mockito.mock(CartService.class); } }
```

| View | URL | Status | Model / flash |
|---|---|---|---|
| `cart/view` | `GET /cart` | 200 | `cart` (`CartView`); flash `notice`, `noticeKind` (có thể không có) |
| redirect `/products/{id}` | `POST /cart/items` (`productId`, `quantity`) | 302 | flash `notice`, `noticeKind`, `showCartLink` |
| redirect `/cart` | `POST /cart/items/{productId}` (`quantity`) | 302 | flash `notice`, `noticeKind` (hoặc không có khi `NOT_IN_CART`) |
| redirect `/cart` | `POST /cart/items/{productId}/delete` | 302 | flash `notice`, `noticeKind` |
| `product/not-found` | id sai / không có / đã ẩn | 404 | (không có) |
| `product/detail` (đã có) | `GET /products/{id}` | 200 | thêm flash `notice`, `noticeKind`, `showCartLink` |
| mọi trang có header | – | – | `${#cartHeader.quantity()}` |

Quy ước markup để test và verifier dựa vào (chữ đúng spec §6):

- **Header** (`fragments/layout.html`): ngay sau link `Sản phẩm`, cho **mọi** người:
  `<a class="site-nav-link" th:href="@{/cart}" th:with="cartQty=${#cartHeader.quantity()}" th:text="${cartQty > 0} ? |Giỏ hàng (${cartQty})| : 'Giỏ hàng'">Giỏ hàng</a>`. Gọi `quantity()` đúng một lần.
- **`product/detail`:**
  - khi có `notice`: `fragments/notice :: notice(${noticeKind}, ${notice})`, và nếu `showCartLink` thì có link `th:href="@{/cart}"` chữ `Xem giỏ hàng` ngay sau;
  - khi `product.inStock`: `<form method="post" th:action="@{/cart/items}" novalidate>` chứa:
    - `<input type="hidden" name="productId" th:value="${product.id}">`;
    - `<label for="quantity">Số lượng</label>` và `<input id="quantity" name="quantity" type="number" min="1" max="99" step="1" value="1" inputmode="numeric">`;
    - `<button type="submit">Thêm vào giỏ</button>`;
  - khi hết hàng: không có form, không có chữ `Thêm vào giỏ`.
- **`cart/view`:**
  - tiêu đề tab `Giỏ hàng | Mini Shop`, `h1` `Giỏ hàng`; notice như trên (không có `showCartLink`);
  - **giỏ trống:** `Giỏ hàng của bạn đang trống.` và link `th:href="@{/products}"` chữ `Tiếp tục mua sắm`; **không có `<table>`** và không có form nào (để không tạo session);
  - **có hàng:** `<table>` có `<caption class="visually-hidden">Sản phẩm trong giỏ hàng</caption>` và `<th scope="col">` `Sản phẩm`, `Đơn giá`, `Số lượng`, `Thành tiền`, cùng một cột thao tác có tiêu đề ẩn `Thao tác`;
  - **mỗi dòng:**
    - ảnh nhỏ `alt=""` (placeholder khi `imageUrl` null);
    - tên: `th:text`, là link `th:href="@{/products/{id}(id=${line.productId})}"` trừ khi `line.discontinued`;
    - nhãn (`badge`, luôn có chữ): `Sản phẩm đã ngừng bán` / `Hết hàng` / `|Chỉ còn ${line.availableStock} sản phẩm|`;
    - đơn giá `${#vnd.format(line.unitPrice)}`;
    - nếu `line.purchasable`: `<form method="post" th:action="@{/cart/items/{id}(id=${line.productId})}" novalidate>` có `<label class="visually-hidden" th:for="|qty-${line.productId}|">Số lượng</label>`, ô `th:id="|qty-${line.productId}|" name="quantity" type="number" min="1" max="99" th:value="${line.quantity}"`, nút `Cập nhật`; nếu không mua được thì số lượng là chữ;
    - thành tiền `${#vnd.format(line.lineTotal)}` hoặc `—` khi `null`;
    - `<form method="post" th:action="@{/cart/items/{id}/delete(id=${line.productId})}">`, nút `Xóa` kèm `<span class="visually-hidden" th:text="${line.name}">` để mỗi nút có tên riêng;
  - **cuối bảng:** `Tổng cộng` và `${#vnd.format(cart.total)}`.
- **Cấm** (như GĐ2–3): `<script>` inline, `<style>`, `style=`/`th:style`, `on…=`/`th:on…`, `javascript:`, `th:utext`. Mọi form POST dùng `th:action`.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static tính từ `src/main/resources/`; test tính từ `src/test/java/com/minishop/`.

| # | Task | Agent | File | AC / BR | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| B1 | Lõi giỏ: giỏ session, chủ giỏ, truy vấn repository, ảnh dùng chung | `backend-developer` | `cart/SessionCart`, `cart/GuestCartStore`, `cart/CartOwner` (mới); `cart/Cart`, `cart/CartRepository`, `cart/package-info.java` (sửa); `catalog/ProductImages` (mới), `catalog/ProductService` (sửa) | 004-20 (khóa); BR-004-2, 3, 6, 8 | – | F1 | Xem chi tiết B1 |
| B2 | `CartService` và view record | `backend-developer` | `cart/CartService`, `cart/CartChange`, `cart/CartView`, `cart/CartLineView`, `cart/CartLineStatus` (mới) | 004-3, 4, 5, 8, 11–16, 20, 21; BR-004-1–7 | B1 | F1 | Xem chi tiết B2 |
| TW0 | Fixture header cho web slice | `test-writer` | `cart/CartHeaderTestConfig` (mới); `catalog/ProductControllerTest`, `user/AuthControllerTest`, `common/AccessDeniedTest`, `admin/AdminControllerTest` (chỉ thêm `@Import`) | – (giữ build xanh cho B4) | B2 | B3, F1 | 4 test class trên xanh, không đổi assert nào |
| TW1 | Test đơn vị giỏ session và `CartService` | `test-writer` | `cart/SessionCartTest`, `cart/CartServiceTest`, `cart/InMemoryGuestCartStore` (mới, test double) | 004-3, 4, 5, 8, 11–16, 21; BR-004-1–7 | TW0 | B3, F1 | Xem chi tiết TW1 |
| B3 | Tầng web, chủ giỏ từ request, gộp khi đăng nhập, bảo mật | `backend-developer` | `cart/CartInput`, `cart/HttpSessionGuestCartStore`, `cart/CartOwners`, `cart/CartOwnerArgumentResolver`, `cart/CartWebConfig`, `cart/CartController`, `cart/CartNotFoundAdvice`, `cart/CartMergeOnLogin` (mới); `config/SecurityConfig` (sửa) | 004-2, 4–7, 9–12, 15–19, 23; BR-004-7, 8 | B2 | TW0, TW1, F1 | Xem chi tiết B3 |
| B4 | Số lượng trên header (`#cartHeader`) | `backend-developer` | `cart/CartHeaderDialect`, `cart/CartHeader` (mới) | 004-2, 15, 22 | B3, TW0 | TW1, TW2 | Xem chi tiết B4 |
| F1 | Form thêm giỏ, trang giỏ, link header, CSS; gate của skill | `frontend-developer` | `templates/product/detail.html`, `templates/fragments/layout.html` (sửa), `templates/cart/view.html` (mới); `static/css/app.css` | 004-1, 2, 9, 10, 11, 12, 14, 22, 24 | Dựng `detail` và `cart/view` ngay từ đầu theo mục 3; **sửa header sau B4**; **gate sau B4** | B1–B4, TW0–TW2 | Xem chi tiết F1 |
| TW2 | Ma trận bảo mật và kiến trúc | `test-writer` | `config/SecurityConfigTest`, `ArchitectureTest` (sửa) | 004-19, 21, 23; 003-10 (giữ) | B3 | B4, F1 | Xem chi tiết TW2 |
| TW3 | Test web slice của giỏ và trang chi tiết; quét CSP | `test-writer` | `cart/CartControllerTest`, `cart/CartInputTest` (mới); `catalog/ProductControllerTest`, `TemplateCspComplianceTest` (sửa) | 004-1, 2, 4–7, 9–12, 18, 22, 24 | B4, F1 | TW4, D | Xem chi tiết TW3 |
| TW4 | Test tích hợp PostgreSQL, gộp khi đăng nhập, smoke H2 | `test-writer` | `cart/CartIntegrationTest`, `cart/CartMergeIntegrationTest`, `cart/CartMergeOnLoginTest` (mới); `H2ProfileSmokeTest` (sửa) | 004-2, 8, 13–18, 20, 21, 22; BR-004-7 | B4, F1 | TW3, D | Xem chi tiết TW4 |
| D | Tài liệu | phiên chính | `docs/adr/0008-cart-writes-serialized-per-user.md` (mới), `docs/adr/README.md`, `CLAUDE.md`, `README.md`, `CHANGELOG.md`, `docs/specs/004-shopping-cart.md` (trạng thái, lịch sử, chữ của câu hỏi 1), `docs/specs/README.md` | – | B4, F1 | TW3, TW4 | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | B1–B4, F1, TW0–TW4, D | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc) | 004-1 đến 004-24 | R | – | Checklist mục 5 đạt |

**Thứ tự chạy:**
- **Đợt 1:** B1; F1 dựng `product/detail.html` (form, notice), `cart/view.html` và CSS theo mục 3. F1 chưa sửa header và chưa chạy Maven.
- **Đợt 2:** B2 (sau B1); F1 tiếp tục.
- **Đợt 3:** B3 (sau B2), song song với TW0 rồi TW1 (sau B2; cùng một agent `test-writer`, làm TW0 trước vì B4 chờ nó).
- **Đợt 4:** B4 (sau B3 và TW0), TW2 (sau B3); TW1 làm nốt nếu chưa xong. **Sau B4:** F1 sửa header rồi chạy gate.
- **Đợt 5:** TW3 và TW4 (sau B4 và F1); D.
- **Cuối cùng:** R, rồi V.

**Đường găng:** B1 → B2 → B3 → B4 → F1 (header + gate) → TW4 → R → V. TW4 dài nhất trong nhóm test, vì có test race 20 vòng và đăng nhập thật.

**Không chạy hai lệnh Maven cùng lúc trong cùng thư mục.** Hai lệnh `./mvnw` song song cùng ghi `target/classes` và `target/surefire-reports`, nên có thể ra lỗi biên dịch hay test "ma". Vì vậy:
- **mỗi đợt có tối đa hai agent dùng Maven**, và phiên chính trao "lượt build" cho từng agent một: agent nào xong code trước thì build trước, agent kia chờ;
- F1 chỉ dùng Maven ở bước gate (`spring-boot:run`), nên gate không chạy cùng lúc với một lần `verify` của agent khác;
- `./mvnw verify` đầy đủ chỉ chạy ở cuối mỗi task backend, ở TW4 và ở V.

**Build xanh sau mỗi task:**
- **Sau B1, B2:** chỉ thêm class, chưa ai gọi. `ProductServiceTest` vẫn xanh sau khi chuyển `toImageUrl` sang `ProductImages`.
- **Sau TW0:** 4 slice test có thêm một bean mock không ai dùng.
- **Sau B3:**
  - `CartWebConfig` và `CartNotFoundAdvice` được nạp vào mọi slice, nhưng không cần bean nào;
  - `CartMergeOnLogin` chạy ở mỗi lần đăng nhập trong `LoginFlowTest`; không có giỏ khách nên không làm gì;
  - `SecurityConfigTest` hiện có vẫn đúng.
  - Nếu F1 chưa có `cart/view.html` thì `GET /cart` trả 500 khi chạy app. Đây là trạng thái trung gian có chủ đích như GĐ3; không push giữa các đợt.
- **Sau B4:** dialect được nạp vào mọi slice và cần `CartService`; TW0 đã cung cấp. Layout chưa dùng `#cartHeader` cho tới khi F1 sửa header.
- **F1:** form ở trang chi tiết và `cart/view.html` có thể vào trước B3 (chưa có handler cũng không sao, vì `ProductControllerTest` chỉ thấy thêm một form). **Header chỉ được sửa sau B4**, nếu không mọi slice test lỗi khi render `#cartHeader`.

### Chi tiết B1 (`backend-developer`)

- **`SessionCart`, `GuestCartStore`, `CartOwner`:** theo QĐ1, QĐ2 và mục 3.
  - `SessionCart` có Javadoc giải thích: vì sao chỉ chứa id và số lượng, vì sao `synchronized`, vì sao `Serializable`.
  - `put` với số lượng ≤ 0 ném `IllegalArgumentException`.
- **`CartRepository`:** thêm `lockOwner`, `findWithItemsByUserId`, `sumQuantityByUserId` như mục 3. Javadoc của `lockOwner` ghi rõ: "gọi đầu tiên trong mọi transaction ghi giỏ DB (ADR-0008)".
- **`Cart`:** thêm `Optional<CartItem> findItem(Long productId)`. **Không đổi annotation mapping nào.**
- **`ProductImages`** (public, `final`, constructor private), rồi `ProductService` gọi `ProductImages.toUrl(...)` thay cho method private `toImageUrl`.
- **`cart/package-info.java`:**
  - giỏ session (`SessionCart`) cho khách, giỏ DB cho người đã đăng nhập, gộp khi đăng nhập bằng listener **trong package này**;
  - được phép dùng `catalog`, `user`, `common`;
  - mọi thao tác ghi giỏ DB khóa dòng `users` trước (ADR-0008).

**Xong khi:**
- `./mvnw verify` xanh;
- báo cáo có câu SQL Hibernate sinh ra cho `lockOwner` trên PostgreSQL (bật `logging.level.org.hibernate.SQL=debug` tạm thời khi chạy thử, rồi **gỡ ra**), để ghi vào ADR-0008.

### Chi tiết B2 (`backend-developer`)

- **`CartService`:** theo QĐ3, QĐ4, QĐ6 và mục 3. Constructor injection `CartRepository`, `ProductRepository`.
  - Nhánh `Customer` của `add`/`update`/`remove`/`mergeGuestCart`: `lockOwner(userId)` (không có → `IllegalStateException`), rồi `findWithItemsByUserId`. Với `add` và merge, nếu chưa có giỏ thì `cartRepository.save(new Cart(user))`. Thêm dòng bằng `cart.addItem`, sửa bằng `item.setQuantity`, xóa bằng `cart.removeItem`. Mọi lần ghi gọi `cart.touch()`.
  - Nhánh `Guest`: `add`/`update` chỉ gọi `store.getOrCreate()` **sau khi** đã biết chắc sẽ ghi (không tạo session khi hết hàng hay khi ném `NotFoundException`), rồi `store.save(cart)`. `remove` dùng `store.find()`.
  - `view`: không gọi `save`, `getOrCreate`, `setQuantity` hay `removeItem`.
  - `mergeGuestCart`: một lần `findAllById` cho mọi id của giỏ khách; áp `min(DB + khách, stock, 99)`.
  - Không bao giờ gọi `decrementStock` hay bất kỳ method ghi nào của `ProductRepository`.
  - Không log ở mức `info` trở lên cho các thao tác thường; có thể log `debug` (id sản phẩm, id người dùng; không log email).
- **`CartChange`, `CartView`, `CartLineView`, `CartLineStatus`:** theo QĐ6 và mục 3.

**Xong khi:**
- `./mvnw verify` xanh;
- báo cáo nêu rõ:
  - thứ tự câu lệnh của nhánh `Customer` (khóa → tải → ghi);
  - cách tính `CartLineStatus` và `total`;
  - bảng kết quả `CartChange` cho từng nhánh của `add`/`update`.

### Chi tiết B3 (`backend-developer`)

- **`CartInput`, `HttpSessionGuestCartStore`, `CartOwners`, `CartOwnerArgumentResolver`, `CartWebConfig`:** theo QĐ1, QĐ2, QĐ8 và mục 3.
- **`CartController`** (package-private, constructor injection `CartService`). Các chuỗi chữ là hằng `private static final`, đúng spec §6:
  ```java
  @GetMapping                              String view(CartOwner owner, Model model)          // "cart" -> "cart/view"
  @PostMapping("/items")                   String add(CartOwner owner, @RequestParam(required = false) String productId,
                                                      @RequestParam(required = false) String quantity, RedirectAttributes ra)
  @PostMapping("/items/{productId}")       String update(CartOwner owner, @PathVariable String productId,
                                                         @RequestParam(required = false) String quantity, RedirectAttributes ra)
  @PostMapping("/items/{productId}/delete") String remove(CartOwner owner, @PathVariable String productId, RedirectAttributes ra)
  ```
  Thứ tự kiểm tra và flash đúng QĐ8. Id sai thì `throw new NotFoundException("Product not found: " + raw)` (message tiếng Anh, chỉ cho log).
- **`CartNotFoundAdvice`:** theo QĐ10, có comment "GĐ7 gom các advice 404 lại".
- **`CartMergeOnLogin`:** theo QĐ5. Javadoc giải thích vì sao listener nằm trong `cart` và vì sao nuốt lỗi.
- **`SecurityConfig`:** theo QĐ9, kèm comment một dòng. Cập nhật Javadoc của class.

**Xong khi:**
- `./mvnw verify` xanh;
- app profile `h2` khởi động được, và bằng `curl`:
  - `GET /cart` khi chưa đăng nhập → `200` (hoặc 500 vì thiếu template nếu F1 chưa xong; ghi rõ trong báo cáo);
  - `POST /cart/items` không có CSRF → `403`;
  - `POST /products/1` có cookie và CSRF hợp lệ → vẫn `302 …/login`;
- báo cáo xác nhận view name, model attribute và flash attribute khớp mục 3.

### Chi tiết B4 (`backend-developer`)

- **`CartHeaderDialect` và `CartHeader`:** theo QĐ7. Tên expression object là `cartHeader`.
  - Kiểm tên API `IWebContext#getExchange()` và `IServletWebExchange#getNativeRequestObject()` trong `thymeleaf-3.1.x` (Javadoc chính thức). Nếu khác thì dùng `RequestContextHolder` và ghi lý do.
- `CartHeader.quantity()` gọi `cartService.totalQuantity(owner)` lần đầu, rồi nhớ kết quả.
- Javadoc ghi: vì sao dùng dialect thay cho `@ControllerAdvice` (QĐ7); vì sao khách không tốn truy vấn và không bị tạo session.

**Xong khi:** `./mvnw verify` xanh (cần TW0 đã xong); báo cáo nêu tên expression object và cách lấy request.

### Chi tiết F1 (`frontend-developer`)

- **Đọc trước:**
  - spec 004 §4 và §6 (bảng "Chữ hiển thị chính xác");
  - mục 3 và QĐ8, QĐ11 của plan này;
  - mục "UI design skill" trong `CLAUDE.md`;
  - comment đầu `static/css/app.css`.
- **Skill `design-taste-frontend`, chế độ dùng lại hướng đã vẽ** (QĐ11): không chạy `draw.mjs`; không đổi token, font, palette; không thêm tính năng hay chữ tự nghĩ.
- **Dựng các file theo mục 3:**
  - `product/detail.html`: notice và link `Xem giỏ hàng`, form thêm giỏ (chỉ khi `product.inStock`);
  - `cart/view.html`: giỏ trống, bảng giỏ, nhãn dòng, form cập nhật và xóa, tổng cộng;
  - `fragments/layout.html`: link `Giỏ hàng` ở header — **chỉ sửa sau khi B4 đã vào** (xem "Build xanh");
  - **nhãn dòng** dùng lại `.badge.stock-badge`: `--out` cho `Hết hàng` và `Sản phẩm đã ngừng bán`, thêm modifier `--low` cho `Chỉ còn {n} sản phẩm`, dùng token sẵn có. Luôn có chữ, không chỉ dựa vào màu;
  - **ô số lượng và nút** dùng component có sẵn trong `app.css` (ô nhập có viền nhìn thấy được, nút chính màu nhấn cho `Thêm vào giỏ`; `Cập nhật` và `Xóa` là nút phụ);
  - CSS mới viết ở **cuối** `app.css`, trong khối `/* GĐ4: giỏ hàng (form thêm giỏ, bảng giỏ, nhãn dòng, link header) */`.
- **Gate (chạy sau B4, sau khi đã sửa header):**
  - `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`;
  - `node <skill>/scripts/lint.mjs src/main/resources/templates src/main/resources/static/css`;
  - `node <skill>/scripts/inspect.mjs` cho `/cart` (trống), `/products/{id}` của một sản phẩm còn hàng và của `Loa bluetooth mini` (hết hàng), và `/products`. Lưu kết quả vào `.taste-skill/inspect/`, tự xem mọi ảnh chụp, tối đa 3 vòng sửa;
  - **kiểm tay** (inspector không giữ được giỏ):
    - trong trình duyệt, thêm 3–4 sản phẩm, có một tên dài (`Giáo trình lập trình Java cơ bản`), rồi xem `/cart` ở 375px, 768px, 1440px;
    - dùng psql ẩn một sản phẩm trong giỏ, đặt một sản phẩm `stock = 0`, rồi xem lại; **khôi phục lại dữ liệu sau đó**;
    - DevTools Console: không có lỗi CSP;
  - tắt app khi xong.

**Xong khi:**
- `inspect.mjs` không còn `FAIL`, trừ các mục ghi là "sai lệch có chủ đích" (mỗi mục một câu lý do);
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả nào;
- `grep -rn 'action="' src/main/resources/templates` không có form POST nào dùng `action` thay vì `th:action`;
- trang chi tiết không chứa số tồn kho (không có `max` nào khác `99`);
- báo cáo liệt kê template, model attribute đã dùng, và critique của vòng cuối.

### Chi tiết TW0 (`test-writer`)

- `cart/CartHeaderTestConfig`: `public` `@TestConfiguration` có `@Bean CartService cartService()` trả `Mockito.mock(CartService.class)`. Javadoc: "Mọi `@WebMvcTest` render layout phải `@Import` class này (header gọi `#cartHeader`, QĐ7 plan GĐ4)".
- Thêm `CartHeaderTestConfig.class` vào `@Import` của `ProductControllerTest`, `AuthControllerTest`, `AccessDeniedTest`, `AdminControllerTest`. **Không đổi gì khác.**

**Xong khi:** `./mvnw test -Dtest='ProductControllerTest,AuthControllerTest,AccessDeniedTest,AdminControllerTest'` xanh.

### Chi tiết TW1 (`test-writer`)

**`SessionCartTest`** (JUnit thuần):

| Kiểm | AC / BR |
|---|---|
| `put` rồi `quantityOf`; `put` lại cùng id ghi đè và giữ vị trí; `lines()` đúng thứ tự thêm | BR-004-2 |
| `remove` id không có: không lỗi; `totalQuantity`, `isEmpty` | 004-12 |
| `lines()` là bản sao không sửa được (sửa nó ném `UnsupportedOperationException`, giỏ không đổi) | QĐ1 |
| Serialize rồi deserialize bằng `ObjectOutputStream`: nội dung và thứ tự giữ nguyên | spec §7 |
| `put(id, 0)` ném `IllegalArgumentException` | BR-004-1 |

**`CartServiceTest`** (Mockito, `CartRepository` và `ProductRepository` là mock; khách dùng `InMemoryGuestCartStore`, một test double đếm số lần `getOrCreate`/`save`; `Product` dựng bằng constructor public, id gán bằng `ReflectionTestUtils`). Mỗi case chạy cho **cả hai** loại chủ giỏ, trừ khi ghi rõ:

| Kiểm | AC / BR |
|---|---|
| Khách thêm A×2 vào giỏ trống: dòng A=2, `OK`; không chạm `CartRepository` (`verifyNoInteractions`) | 004-2 |
| Có A=2, thêm A×3 → một dòng A=5 | 004-3, BR-004-2 |
| Kho 4, có A=3, thêm A×5 → A=4, `CAPPED_AT_STOCK`, `quantity() == 4` | 004-4 |
| Kho 200, có 60, thêm 50 → 99, `CAPPED_AT_MAX` | BR-004-1, câu hỏi 1 |
| Có 5, kho 2, thêm 1 → 2, `CAPPED_AT_STOCK` | BR-004-1 |
| Kho 0 → `OUT_OF_STOCK`; giỏ không đổi; khách: `getOrCreate` không được gọi; user: không `save` giỏ mới | 004-5 |
| Sản phẩm không có / đã ẩn → `NotFoundException`; giỏ không đổi | 004-6 |
| `quantity` 0 hoặc 100 → `IllegalArgumentException` | BR-004-1 |
| User chưa có giỏ: `lockOwner` được gọi **trước** `findWithItemsByUserId` (`InOrder`); `save` một `Cart` mới có đúng một dòng | 004-8, 004-20 |
| `update`: `OK`; `q > stock` → đặt bằng kho, `CAPPED_AT_STOCK`; kho 0 → `OUT_OF_STOCK`; không có dòng → `NOT_IN_CART`; đã ẩn → `NotFoundException` | 004-11 |
| `remove` dòng có / dòng không có (không lỗi), kể cả dòng sản phẩm đã ẩn | 004-12, 14 |
| `view` dùng giá hiện tại: đổi `price` của `Product` rồi gọi lại → đơn giá mới | 004-13, BR-004-3 |
| `view` với B đã ẩn, C kho 0, D=5 kho 2, E bình thường: trạng thái `DISCONTINUED`, `OUT_OF_STOCK`, `LOW_STOCK` (`availableStock` 2), `AVAILABLE`; `lineTotal` của B và C là `null`; `total` = D×giá + E×giá; **không** gọi `save`/`getOrCreate`/`setQuantity`/`removeItem` | 004-14, BR-004-5 |
| `mergeGuestCart`: DB {A:3}, khách {A:2, B:1}, kho A=4 → {A:4, B:1}; chưa có giỏ DB thì tạo; sản phẩm đã ẩn hoặc kho 0 bị bỏ qua và không ném lỗi; tổng vượt 99 → 99; giỏ khách rỗng → không gọi `lockOwner` | 004-15, 16, BR-004-7 |
| `totalQuantity`: khách đọc từ session, không chạm repository nào; user gọi `sumQuantityByUserId` đúng một lần | 004-22 |
| Không test nào gọi `productRepository.decrementStock` (`verify(..., never())` trong `@AfterEach`) | 004-21 |

**Xong khi:** `./mvnw test -Dtest='SessionCartTest,CartServiceTest'` xanh.

### Chi tiết TW2 (`test-writer`)

**`SecurityConfigTest`:** giữ mọi test hiện có, thêm:

| Kiểm | AC |
|---|---|
| Chưa đăng nhập: `GET /cart`, `get("/cart?x=1")` không bị redirect sang `/login`, không 403 (slice không có controller nên là 404) | 004-23 |
| Chưa đăng nhập, có CSRF: `POST /cart/items`, `/cart/items/1`, `/cart/items/1/delete` không bị redirect, không 403 | 004-23 |
| Không có CSRF: ba POST trên → 403 và `forwardedUrl("/access-denied")`, cả khi chưa đăng nhập lẫn `@WithMockUser` | 004-19 |
| `POST /products/1` và `POST /products` có CSRF, chưa đăng nhập → vẫn redirect `/login` | 004-23 |

**`ArchitectureTest`:**
- đổi `cart_doesNotDependOnOrderOrAdmin` thành `cart_dependsOnlyOnAllowedPackages`: `cart` không phụ thuộc `order`, `admin`, `config`, `storage`;
- thêm `config_doesNotDependOnCartOrOrder` (giữ quyết định gộp giỏ nằm trong `cart`, QĐ5);
- thêm `cart_neverDecrementsStock`: không class nào trong `com.minishop.cart..` gọi `ProductRepository.decrementStock(Long, int)` (AC-004-21).

**Xong khi:** `./mvnw test -Dtest='SecurityConfigTest,ArchitectureTest'` xanh.

### Chi tiết TW3 (`test-writer`)

**`CartInputTest`:**
- `productId`: `"7"`, `" 7 "` hợp lệ; `""`, `null`, `"abc"`, `"-1"`, `"1.5"`, `"99999999999999999999"` không hợp lệ;
- `quantity`: `"1"`, `"99"`, `" 5 "`, `"05"` hợp lệ; `"0"`, `"00"`, `"-1"`, `"100"`, `"abc"`, `""`, `null`, `"1e2"`, `"+5"` không hợp lệ.

**`CartControllerTest`:**
- `@WebMvcTest(CartController.class)` + `@Import(SecurityConfig.class)` + `@MockitoBean CartService`;
- khách là anonymous; người đăng nhập dùng `.with(user(TestUsers.user()))` hoặc `admin()`;
- POST luôn có `.with(csrf())`, trừ khi ghi rõ.

| Kiểm | AC |
|---|---|
| `GET /cart`, giỏ trống: 200, view `cart/view`, title `Giỏ hàng \| Mini Shop`, `h1` `Giỏ hàng`, `Giỏ hàng của bạn đang trống.`, link `Tiếp tục mua sắm` tới `/products`, không có `<table` và không có `<form` trong `<main>`; `getSession(false)` của request là `null` | 004-10 |
| `GET /cart` có hàng: 4 tiêu đề cột, link tên tới `/products/7`, ảnh placeholder khi `imageUrl` null, `1.250.000 ₫`, ô `name="quantity"` có `label`, nút `Cập nhật`, `Xóa`, `Tổng cộng` và tổng đúng | 004-9 |
| Nhãn: `Sản phẩm đã ngừng bán` (không có link tên, không có ô số lượng), `Hết hàng`, `Chỉ còn 2 sản phẩm`; thành tiền `—` cho dòng không mua được | 004-14 |
| Tên `<script>alert(1)</script>` được escape trong bảng | spec §8 |
| `POST /cart/items` `productId=7&quantity=2`, khách: redirect `/products/7`; flash `notice` `Đã thêm vào giỏ hàng.`, `noticeKind` `success`, `showCartLink` true; service nhận `CartOwner.Guest` | 004-2 |
| Như trên với `TestUsers.user()` và thêm `userId=1&cartId=9`: service nhận `new CartOwner.Customer(2L)` | 004-18, BR-004-8 |
| Service trả `CAPPED_AT_STOCK(4)` → `Chỉ còn 4 sản phẩm trong kho.`; `CAPPED_AT_MAX` → `Số lượng tối đa cho mỗi sản phẩm là 99.`; `OUT_OF_STOCK` → `Sản phẩm này đã hết hàng.`, không có `showCartLink` | 004-4, 5 |
| `productId` là `abc`, `""`, không gửi, `99999999999999999999` → 404, view `product/not-found`, `Không tìm thấy sản phẩm`; service không được gọi. Service ném `NotFoundException` → 404 | 004-6 |
| `quantity` là `0`, `-1`, `100`, `abc`, `""`, không gửi → redirect `/products/7` với `Số lượng không hợp lệ.` (`error`); service không được gọi | 004-7 |
| Có header `Referer: https://evil.example/` và tham số `returnUrl=https://evil.example/` → vẫn redirect `/products/7` | spec §6 |
| `POST /cart/items/7` `quantity=3` → redirect `/cart`, `Đã cập nhật số lượng.`; `CAPPED_AT_STOCK(2)` → `Chỉ còn 2 sản phẩm trong kho.`; `NOT_IN_CART` → không có flash `notice`; `quantity=0` → `Số lượng không hợp lệ.`, service không được gọi; `/cart/items/abc` → 404 | 004-11, 7 |
| `POST /cart/items/7/delete` → redirect `/cart`, `Đã xóa sản phẩm khỏi giỏ hàng.`; `/cart/items/abc/delete` → 404 | 004-12 |
| Header (khách, `TestUsers.user()`, `TestUsers.admin()`): `totalQuantity` trả 5 → `Giỏ hàng (5)` với `href="/cart"`; trả 0 → có `>Giỏ hàng</a>`, không có `Giỏ hàng (` | 004-22 |
| POST không có CSRF → 403 | 004-19 |

**`ProductControllerTest`** (thêm):
- trang chi tiết còn hàng: form `method="post"` `action="/cart/items"`, có ô ẩn `_csrf`, `name="productId" value="7"`, `name="quantity"` `value="1"` `max="99"`, `label for="quantity"` chữ `Số lượng`, nút `Thêm vào giỏ` (AC-004-1);
- hết hàng: không có `Thêm vào giỏ`, không có `action="/cart/items"` (AC-004-1);
- `.flashAttr("notice", "Đã thêm vào giỏ hàng.").flashAttr("noticeKind", "success").flashAttr("showCartLink", true)` → có chữ đó, `role="status"` và link `Xem giỏ hàng` tới `/cart` (AC-004-2);
- `noticeKind` `error` → `role="alert"`;
- trang danh sách vẫn không có `<form method="post"` (câu hỏi mở 4).

**`TemplateCspComplianceTest`:** thêm `view.html` vào danh sách file bắt buộc phải quét được.

**Xong khi:**
- các class trên xanh;
- báo cáo ghi chỗ khác cột "Kiểm bằng" của spec: AC-004-1 nằm ở `ProductControllerTest` (form thuộc trang chi tiết, do `ProductController` render).

### Chi tiết TW4 (`test-writer`)

**`cart/CartIntegrationTest`:**
- `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers theo QĐ12;
- `JdbcTemplate` để dựng và kiểm dữ liệu;
- người dùng test tạo bằng `UserRepository` + `PasswordEncoder`; đăng nhập bằng `.with(user(new CurrentUser(id, email, null, name, Role.USER)))` khi không cần kiểm luồng đăng nhập;
- `@AfterEach` dọn theo QĐ12.

| Kiểm | AC |
|---|---|
| Khách: `GET /products` rồi `GET /cart` (giỏ trống) không tạo session (`getSession(false) == null`) | spec §8 |
| Khách `POST /cart/items` A×2 → `GET /products/{A}` cùng session có `Đã thêm vào giỏ hàng.`, link `Xem giỏ hàng` và `Giỏ hàng (2)`; số dòng `carts`, `cart_items` không đổi | 004-2 |
| User test chưa có giỏ, thêm lần đầu → đúng 1 dòng `carts` cho user đó, 1 dòng `cart_items` | 004-8 |
| Đổi `price` của sản phẩm test bằng JDBC → `GET /cart` hiện giá mới | 004-13 |
| Giỏ DB có B (đặt `active=false`), C (`stock=0`), D=5 (`stock=2`) → `GET /cart` có ba nhãn, tổng cộng chỉ gồm D; **sau khi GET**, `cart_items` không đổi | 004-14 |
| U1, U2 đều có giỏ; U1 thêm, sửa, xóa → `cart_items` của U2 không đổi; HTML của `/cart` không chứa id giỏ nào (`cart_id` lấy từ DB) | 004-18 |
| **Race:** 20 vòng × 2 luồng gọi `CartService.add(Customer(id), p, 3)`, mỗi vòng một user mới chưa có giỏ, kho 50 → không exception, 1 giỏ, 1 dòng, số lượng 6. Thêm biến thể "đã có dòng 2" → 8 (không mất cập nhật), và biến thể "kho 5, mỗi luồng thêm 4" → 5 | 004-20 |
| **Tồn kho không đổi:** chụp `select id, stock from products order by id` trước và sau một chuỗi thêm, sửa, xóa, gộp (`mergeGuestCart`) của cả khách và user → giống hệt nhau | 004-21 |
| Header với user đã đăng nhập: `GET /` có `Giỏ hàng (n)` đúng tổng | 004-22 |

**`cart/CartMergeIntegrationTest`:** cùng cấu hình. Đăng nhập bằng `formLogin("/login").userParameter("email")`, gắn session theo cách `formLoginIn` của `LoginFlowTest` (chép helper, không phụ thuộc chéo).

| Kiểm | AC |
|---|---|
| Kho A=4. Khách thêm A×2, B×1 (cùng session); giỏ DB của user có A=3. Đăng nhập → redirect `/`; DB {A:4, B:1}; `session.getAttribute(SessionCart.SESSION_ATTRIBUTE)` là `null`; `GET /` cùng session có `Giỏ hàng (5)` | 004-15 |
| Khách thêm rồi `GET /orders` (→ `/login`); đăng nhập → redirect có path bắt đầu `/orders`; giỏ vẫn được gộp | 004-15, 003-10 |
| Giỏ khách có sản phẩm test đã bị ẩn và một sản phẩm `stock=0` (đổi bằng JDBC **sau khi** thêm) → đăng nhập thành công, `authenticated()`; hai sản phẩm đó không có trong giỏ DB | 004-16 |
| User có giỏ DB; `POST /logout` → `GET /cart` bằng session mới: `Giỏ hàng của bạn đang trống.`; đăng nhập lại → giỏ còn nguyên | 004-17 |
| Đăng nhập không có giỏ khách → không tạo dòng `carts` nào | BR-004-6 |

**`cart/CartMergeOnLoginTest`** (Mockito; `MockHttpServletRequest` có `MockHttpSession`, đặt vào `RequestContextHolder` và gỡ ra ở `@AfterEach`):

| Kiểm | BR |
|---|---|
| Principal là `CurrentUser`, session có giỏ → `mergeGuestCart(id, cart)` được gọi, attribute bị xóa | BR-004-7 |
| `mergeGuestCart` ném `RuntimeException` → listener **không** ném; attribute còn nguyên | BR-004-7 |
| Principal không phải `CurrentUser`, hoặc không có request → không gọi service | QĐ5 |
| Giỏ khách rỗng → không gọi service; attribute bị xóa | QĐ5 |

**`H2ProfileSmokeTest`** (thêm một test): đăng nhập `user@minishop.local` bằng `formLogin` (mật khẩu `@Value("${app.seed.user-password}")`), `POST /cart/items` một sản phẩm seed còn hàng, rồi `GET /cart` cùng session có tên sản phẩm. Như vậy khóa `for update` chạy được trên H2. Cuối test xóa `cart_items` và `carts` bằng `JdbcTemplate`. Không thêm người dùng (test seed đếm đúng 2).

**Xong khi:**
- `./mvnw verify` xanh;
- `bash .github/scripts/check-no-skipped-tests.sh` exit 0;
- báo cáo liệt kê test theo từng AC, kèm thời gian chạy của test race.

### Chi tiết D (phiên chính)

- **`docs/adr/0008-cart-writes-serialized-per-user.md`** (theo `template.md`): nội dung như QĐ4/QĐ14, kèm SQL thật mà B1 báo cáo. Cập nhật bảng trong `docs/adr/README.md`.
- **`CLAUDE.md`:**
  - **Project status:** GĐ4;
  - **"Visibility today":** thêm `catalog.ProductImages` (public); `cart` vẫn package-private;
  - **mục mới "Cart patterns (GĐ4)":**
    - `CartOwner` được resolve ở tầng web (`CartOwnerArgumentResolver`), và service nhận nó làm tham số;
    - giỏ khách là attribute `minishop.cart`, đọc bằng `getSession(false)`;
    - header dùng `#cartHeader`; mọi `@WebMvcTest` render layout phải `@Import(CartHeaderTestConfig.class)`;
    - flash `notice`/`noticeKind`/`showCartLink`;
    - gộp giỏ bằng listener `InteractiveAuthenticationSuccessEvent` trong `cart`, nuốt lỗi;
    - `CartNotFoundAdvice` chờ GĐ7 gom lại;
  - **"Domain invariants":** thêm "mọi transaction ghi giỏ DB (kể cả `placeOrder` ở GĐ5) gọi `CartRepository.lockOwner` trước tiên (ADR-0008)";
  - **ghi chú cho GĐ5:** API public hẹp của `cart` theo QĐ13;
  - xóa ghi chú "GĐ4 cart merge" trong mục Auth patterns, vì đã làm xong.
- **`README.md`:** trạng thái GĐ4; mô tả giỏ hàng; đánh dấu GĐ4 ở Lộ trình **sau khi V đạt**.
- **`CHANGELOG.md`:** ghi vào `[Unreleased]` (Thêm / Thay đổi / Bảo mật). Việc chuyển thành `[0.4.0]` và gắn tag để sau khi người dùng duyệt giai đoạn.
- **Spec 004 và `docs/specs/README.md`:**
  - chuyển sang `Đang làm` khi bắt đầu triển khai, sang `Hoàn thành` sau khi V đạt;
  - thêm chữ của câu hỏi 1 vào bảng §6 (theo câu trả lời của người dùng);
  - ghi lịch sử thay đổi, kèm các quyết định biên ở QĐ3 và QĐ11 (cập nhật dòng đã ẩn → 404, dòng không mua được không có ô sửa).

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

Docker **đang bật**. Mọi test, kể cả Testcontainers, phải chạy ở local. Trước mục 2, chụp `select id, stock, price, active from products order by id` để so ở mục 21 và khôi phục ở mục 26.

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh` | BUILD SUCCESS; script exit 0; có đủ `SessionCartTest`, `CartServiceTest`, `CartInputTest`, `CartControllerTest`, `CartIntegrationTest`, `CartMergeIntegrationTest`, `CartMergeOnLoginTest` |
| 2 | Không có migration mới | `./mvnw spring-boot:run` trên DB dev cũ (không xóa volume); `select max(version) from flyway_schema_history` | App lên; version vẫn là `2` |
| 3 | Không tạo session thừa | Cửa sổ ẩn danh mới, mở `/products` rồi `/cart` (giỏ trống). DevTools > Application > Cookies | Không có `JSESSIONID`; header có `Giỏ hàng` (không có số) |
| 4 | AC-004-1 | Mở một sản phẩm còn hàng, rồi `Loa bluetooth mini` (hết hàng) | Có ô `Số lượng` = 1 và nút `Thêm vào giỏ`; trang hết hàng chỉ có `Hết hàng`, không có form; mã nguồn trang không có số tồn kho |
| 5 | AC-004-2 | Khách thêm sản phẩm A số lượng 2 | Ở lại trang A, có `Đã thêm vào giỏ hàng.` và link `Xem giỏ hàng`; header `Giỏ hàng (2)`; `select count(*) from carts` và `cart_items` không đổi |
| 6 | AC-004-3 | Thêm A số lượng 3 | `/cart` có **một** dòng A = 5 |
| 7 | AC-004-4 | `Bàn phím cơ cỡ nhỏ` (kho 8): thêm 5, rồi thêm 5 | Dòng = 8; `Chỉ còn 8 sản phẩm trong kho.` |
| 8 | Câu hỏi 1 | `Chuột không dây văn phòng` (kho 100): thêm 60, rồi 50 | Dòng = 99; chữ theo câu trả lời của người dùng |
| 9 | AC-004-5 (âm) | DevTools: ở trang một sản phẩm còn hàng, sửa ô ẩn `productId` thành id của `Loa bluetooth mini`, rồi gửi | Về trang Loa, `Sản phẩm này đã hết hàng.`; giỏ không có Loa |
| 10 | AC-004-6 (âm) | Như trên với `productId` = `abc`, `999999`, và id của sản phẩm seed đã ẩn | Cả ba: HTTP 404, trang `Không tìm thấy sản phẩm`; giỏ không đổi |
| 11 | AC-004-7 (âm) | DevTools sửa ô số lượng (bỏ `type="number"` nếu cần) thành `0`, `-1`, `100`, `abc`, rỗng: thử ở trang chi tiết và ở `Cập nhật` trong giỏ | Luôn `Số lượng không hợp lệ.`; giỏ không đổi; không bao giờ trang 500 |
| 12 | AC-004-9, 13 | Mở `/cart`; rồi `update products set price = price + 1000 where id = <A>` và tải lại | Tiêu đề tab `Giỏ hàng \| Mini Shop`; đủ cột, ảnh nhỏ, tên link tới trang chi tiết, tiền dạng `1.250.000 ₫`, `Tổng cộng`; đơn giá A tăng 1.000 ₫ |
| 13 | AC-004-11 | Sửa A thành 3, `Cập nhật`; rồi sửa `Bàn phím cơ cỡ nhỏ` thành 20 | `Đã cập nhật số lượng.`, thành tiền và tổng tính lại; dòng bàn phím = 8 với `Chỉ còn 8 sản phẩm trong kho.` |
| 14 | AC-004-12 | `Xóa` dòng A; rồi DevTools sửa `action` của một nút `Xóa` sang `/cart/items/999999/delete` và bấm | Dòng A biến mất với `Đã xóa sản phẩm khỏi giỏ hàng.`; lần hai không lỗi, giỏ không đổi |
| 15 | AC-004-14 | Giỏ có B, C, D. psql: B `active=false`, C `stock=0`, D `stock` nhỏ hơn số lượng trong giỏ. Ghi lại `cart_items` (nếu đã đăng nhập) rồi tải `/cart` hai lần | Nhãn `Sản phẩm đã ngừng bán`, `Hết hàng`, `Chỉ còn {n} sản phẩm`; tổng không gồm B, C; xóa được cả ba; `cart_items` không đổi sau khi GET |
| 16 | AC-004-15 | Đăng nhập `user@minishop.local`, thêm A×3, đăng xuất. Khách thêm A×2 và B×1 (A có kho 4: đặt bằng psql trên một sản phẩm, ghi lại giá trị cũ). Mở `/orders` (→ `/login`), đăng nhập | Về `/orders?continue` (trang 404 mặc định, vì `/orders` làm ở GĐ5: đúng như GĐ3); header `Giỏ hàng (5)`; `/cart` có A=4, B=1 |
| 17 | AC-004-16 | Khách thêm một sản phẩm, psql ẩn nó, rồi đăng nhập | Đăng nhập thành công; sản phẩm đó không vào giỏ DB; log không có stack trace |
| 18 | AC-004-17 | `Đăng xuất` | Header `Giỏ hàng`; `/cart` trống. Đăng nhập lại: giỏ còn nguyên |
| 19 | AC-004-18 (âm) | Hai trình duyệt: `user@minishop.local` và `admin@minishop.local` (câu hỏi mở 8: admin có giỏ). Mỗi người thêm, sửa, xóa. Xem mã nguồn mọi form giỏ | Hai giỏ độc lập; không URL hay ô ẩn nào chứa id giỏ hay id người dùng |
| 20 | AC-004-19 (âm) | DevTools xóa ô `_csrf` của form thêm, cập nhật và xóa, rồi gửi | Cả ba: 403 `Bạn không có quyền truy cập trang này.`; giỏ không đổi |
| 21 | AC-004-20, 21 | Đọc kết quả test race ở mục 1. psql: `select user_id, count(*) from carts group by user_id having count(*) > 1` và `select cart_id, product_id, count(*) from cart_items group by 1, 2 having count(*) > 1`; so `id, stock` với ảnh chụp đầu (trừ các dòng đã sửa tay ở mục 15–17) | Hai truy vấn trùng không trả dòng nào; `stock` của mọi sản phẩm không bị thao tác giỏ thay đổi |
| 22 | AC-004-22 | Với khách, `USER`, `ADMIN` (giỏ có hàng): `/`, `/products/{id}`, `/products/abc` (404), `/cart`, `/login` (khách), `/register` (khách), `/admin` (admin), `/admin` khi là `USER` (403) | Mọi trang có `Giỏ hàng (n)` đúng tổng; giỏ trống thì `Giỏ hàng` |
| 23 | AC-004-23, 24 | Khách: các form giỏ không chuyển sang `/login`; `curl -i -X POST http://localhost:8080/products/1` → `/login`. Device mode 375px, 768px, 1440px: `/cart` (có tên dài, có nhãn), `/products/{id}` khi có thông báo | Đúng như mô tả; không có thanh cuộn ngang; bảng giỏ dùng được bằng ngón tay |
| 24 | CSP, truy cập | DevTools Console và Network trên `/cart`, trang chi tiết; xem mã HTML | Không lỗi CSP; mọi tài nguyên từ `localhost`; mọi ô số lượng có `label`; bảng có `caption`/`th scope`; thông báo có `role` |
| 25 | Profile `h2` | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`: khách thêm, đăng nhập (gộp), thêm khi đã đăng nhập, sửa, xóa | Giống PostgreSQL |
| 26 | Dọn dẹp | Khôi phục `products` theo ảnh chụp đầu bằng psql; tắt mọi app | Dữ liệu seed như cũ; không còn tiến trình; container Postgres đã dừng |

## 6. Câu hỏi cho người dùng

> **Người dùng đã trả lời (2026-10-24):** duyệt kế hoạch, bắt đầu triển khai. Câu 1: đặt bằng 99 và báo "Số lượng tối đa cho mỗi sản phẩm là 99.". Cuối giai đoạn được commit, push và mở PR `phase-4/cart` → `main`, **chưa merge**. Các mục "chỉ để biết" được ghi nhận.

Không câu nào chặn việc bắt đầu triển khai: plan làm theo khuyến nghị, và câu 1 chỉ chạm một nhánh trong service và một chuỗi chữ.

1. **Dòng vượt 99 khi kho còn nhiều hơn 99** (spec chưa có chữ cho trường hợp này; xảy ra được ngay với dữ liệu mẫu: chuột có kho 100, sổ tay 150, bút 200). Ví dụ giỏ đã có 60, thêm 50:
   - **Khuyến nghị:** đặt bằng 99 và báo `Số lượng tối đa cho mỗi sản phẩm là 99.`, giống cách xử lý khi chạm tồn kho mà bạn đã duyệt. Cần thêm chữ này vào spec §6.
   - Phương án khác: từ chối, giỏ không đổi, báo `Số lượng không hợp lệ.`. Đúng từng chữ BR-004-1 ("ngoài 1–99 thì từ chối"), nhưng khó hiểu, vì người dùng chỉ gõ 50.
   - Khi gộp giỏ lúc đăng nhập, cả hai phương án đều đặt bằng 99, không có thông báo (câu hỏi mở 6).
2. **Cuối giai đoạn có commit, push và mở PR `phase-4/cart` → `main` không?** Khuyến nghị: **có, nhưng chưa merge**, giống GĐ3. Các agent không tự commit.
3. **Chỉ để bạn biết** (các trường hợp biên spec chưa nói, plan đã chọn):
   - cập nhật số lượng của dòng đã ngừng bán (chỉ xảy ra khi gửi tay) → trang 404 `Không tìm thấy sản phẩm`; dòng ngừng bán hoặc hết hàng không có ô sửa số lượng, chỉ có `Xóa`, và thành tiền hiện `—`;
   - xóa dòng không có hộp xác nhận (cần JavaScript, spec không yêu cầu);
   - xóa một sản phẩm không có trong giỏ vẫn hiện `Đã xóa sản phẩm khỏi giỏ hàng.`;
   - hai tab của **khách** sửa giỏ cùng lúc có thể mất một lần cập nhật. Người đã đăng nhập thì không (AC-004-20, ADR-0008);
   - có **ADR-0008** mới: mọi thao tác ghi giỏ DB khóa dòng `users` của người đó trước. GĐ5 cũng phải theo quy tắc này.

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Nút "Đặt hàng" trên trang giỏ:** GĐ5.
- **Gom các advice 404 (`ProductNotFoundAdvice`, `CartNotFoundAdvice`) thành trang lỗi chung:** GĐ7.
- **Trang riêng khi CSRF token hết hạn** (đã đề xuất ở GĐ3): sau 30 phút không hoạt động, form giỏ sẽ trả 403. Hợp với GĐ7.
- **Dọn giỏ DB lâu không dùng** theo `carts.updated_at` (nay đã được cập nhật đúng): một job định kỳ. Brief không yêu cầu.
- **Hộp xác nhận khi xóa dòng, cập nhật số lượng không tải lại trang, mini-cart trên header, nút thêm giỏ ở thẻ danh sách:** đều cần JavaScript hoặc là tính năng spec đã loại.

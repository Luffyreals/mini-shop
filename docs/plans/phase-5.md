# Kế hoạch GĐ5: Đặt hàng (COD), lịch sử đơn và tự hủy đơn

- **Spec:** [005 Đặt hàng, lịch sử đơn và tự hủy đơn](../specs/005-checkout-and-orders.md) (Đã duyệt 2026-10-24; người dùng chấp nhận cả 11 khuyến nghị: giá đổi thì chặn và cho xem lại, thiếu hàng thì chặn cả đơn, số điện thoại 10 chữ số bắt đầu bằng 0, đơn của người khác là 404 kể cả với `ADMIN`, `ADMIN` đặt hàng được, `POST /checkout` khi giỏ trống thì về `/orders`, hủy có hộp xác nhận bằng file JS, 10 đơn mỗi trang, mã đơn là `#{id}`, không lưu địa chỉ vào hồ sơ, giờ `Asia/Ho_Chi_Minh`)
- **Nhánh:** `phase-5/checkout-orders`, tạo từ `main` (tag `v0.4.0`) trước B1. Spec 005 và dòng mới trong `docs/specs/README.md` (đang chưa commit trên `main`) được commit trên nhánh này.
- **Đã có từ GĐ1–4, không làm lại:**
  - bảng `orders`, `order_items` (V1) với đủ cột và ràng buộc spec §7 dựa vào; index `idx_orders_user_id`, `idx_orders_created_at`;
  - entity `Order` (`addItem` chụp tên và giá, `getItems()` chỉ đọc, `cascade = ALL`), `OrderItem`, `OrderStatus` (chưa có `canTransitionTo`), `PaymentMethod`, `OrderRepository` (rỗng), tất cả package-private;
  - `catalog.ProductRepository.decrementStock` (`@Modifying(clearAutomatically = true, flushAutomatically = true)`), `Product.stock` là `updatable = false`;
  - `cart`: `CartRepository.lockOwner` (ADR-0008), `findWithItemsByUserId`, `CartService`, `CartView`/`CartLineView`/`CartLineStatus`, `#cartHeader`, `CartHeaderTestConfig`, `CartTestData`;
  - `SecurityConfig` đã bắt đăng nhập cho `/orders/**`, `/checkout/**`; `/js/**` đã là đường dẫn công khai (GET); CSP `default-src 'self'` đã cho phép file JS cùng origin;
  - `common.PageNav`, `fragments/pagination`, `fragments/notice`, `#vnd`, `NotFoundException`; `user.CurrentUser`, `TestUsers`; hướng thiết kế "The specimen catalogue".
- **Không đổi `pom.xml`**, nên không có task cho `devops-engineer`.
- **Không có migration và không đổi mapping entity** (chỉ thêm method vào `Order`, `OrderStatus`, `PaymentMethod` và query vào repository), nên không có task `schema-guardian`. Nếu task nào thấy buộc phải đổi `@Column`, `@OneToMany`, thêm `@Version` hay index (`V3`), thì **dừng lại và báo phiên chính**: khi đó cần thêm task `schema-guardian`.

## 1. Mục tiêu

- Người đã đăng nhập (`USER` hay `ADMIN`) đi từ nút "Đặt hàng" ở `/cart` tới `/checkout`, điền thông tin nhận hàng và đặt được một đơn COD. Khách chưa đăng nhập được đưa qua `/login` rồi quay lại `/checkout` với giỏ đã gộp.
- `OrderService.placeOrder` là một transaction: khóa dòng `users` (ADR-0008), đọc giỏ DB, kiểm tra hàng và tổng tiền, chụp tên và giá, trừ kho có điều kiện theo `product_id` tăng dần (ADR-0005), xóa giỏ. Đơn được đặt trọn vẹn hoặc không có gì thay đổi. Không bao giờ bán vượt kho, không bao giờ hai đơn từ một giỏ, không deadlock, không trang 500.
- Khách xem `/orders` (10 đơn/trang, mới nhất trước) và `/orders/{id}` với dữ liệu snapshot, chỉ đơn của chính mình (đơn người khác là 404). Khách tự hủy đơn `PENDING`; kho được cộng lại đúng một lần, kể cả khi hai yêu cầu hủy đến cùng lúc. `OrderStatus.canTransitionTo()` mã hóa toàn bộ luồng trạng thái cho GĐ6 dùng lại.
- `./mvnw verify` xanh **với 0 test bị bỏ qua** (Docker đang bật). Mọi AC-005-1 đến AC-005-34 có test hoặc mục checklist tương ứng (bảng ở mục 4, chi tiết TW).

## 2. Quyết định thiết kế

**QĐ1. `cart` mở một API công khai hẹp cho `order`: class `CartCheckout` và record `CheckoutLine`. Mọi thứ khác của `cart` vẫn package-private.**
- `public record CheckoutLine(long productId, String name, BigDecimal unitPrice, int quantity, boolean active, int stock)`:
  - chỉ là **dữ liệu thuần**, không phải entity: `order` không giữ `Cart`, `CartItem` hay `Product` đang được quản lý của `cart`;
  - `orderable()` trả `CartLineStatus.of(active, stock, quantity) == AVAILABLE`, tức đúng BR-005-2 (đang bán, `stock > 0`, số lượng ≤ `stock`);
  - `lineTotal()` = `unitPrice × quantity`.
- `public class CartCheckout` (`@Service`), ba method:
  - `read(userId)`: `@Transactional(readOnly = true)`, **không khóa**. Dùng cho `GET /checkout`;
  - `lockAndRead(userId)`: `@Transactional(propagation = MANDATORY)`. Gọi `cartRepository.lockOwner(userId)` **trước**, rồi `findWithItemsByUserId` (một câu SQL lấy giỏ, dòng, sản phẩm). Dùng cho `placeOrder`;
  - `clear(userId)`: `@Transactional(propagation = MANDATORY)`. Một câu `@Modifying` xóa mọi `cart_items` của giỏ người đó; **giữ dòng `carts`**, giống khi xóa dòng cuối ở GĐ4.
  - Cả `read` và `lockAndRead` trả dòng theo thứ tự thêm vào giỏ (`CartItem.id` tăng dần), giống trang giỏ; không có giỏ thì trả danh sách rỗng.
- **Vì sao `MANDATORY`:** khóa dòng `users` chỉ có ý nghĩa nếu nó được giữ tới khi đơn commit. Nếu ai đó gọi `lockAndRead` ngoài transaction, Spring sẽ mở và commit một transaction riêng chỉ quanh lời gọi đó, nên khóa được nhả ngay và hai đơn có thể cùng đọc một giỏ. `MANDATORY` biến lỗi lập trình đó thành exception (`IllegalTransactionStateException`) ngay lập tức.
- **Một quy tắc "đặt được", một chỗ:** thêm `static CartLineStatus of(boolean active, int stock, int quantity)` vào enum `CartLineStatus`. `CartService.toLine` (trang giỏ) và `CheckoutLine.orderable()` cùng gọi nó.
- **Cờ cho nút "Đặt hàng" (AC-005-1):** `CartView` có thêm method `isOrderable()` = giỏ không trống và mọi dòng `AVAILABLE`. Đây là method suy ra từ `lines`, **không phải component mới**, nên mọi chỗ đang dựng `CartView(lines, total)` (kể cả test GĐ4) không phải sửa.
- `package-info.java` của `cart` và `order` ghi rõ API này; `ArchitectureTest` có rule: `order` chỉ được dùng `CartCheckout` và `CheckoutLine` của `cart` (TW3).

*Phương án bị loại:*
- **Cho `CartService` thành public.** Method của nó nhận `CartOwner` (khách/session), kiểu mà `order` không cần biết; `order` cũng sẽ thấy cả API sửa giỏ.
- **`order` tự viết JPQL đọc bảng `cart_items`.** Vượt rào module: `order` sẽ phụ thuộc vào cách `cart` lưu dữ liệu, và có thể quên khóa dòng `users`.
- **Trả `List<CartItem>` hay `Product` đang được quản lý.** Phải mở public entity của `cart`; thêm nữa, `decrementStock` xóa persistence context (QĐ2), nên entity giữ trong tay sẽ bị detached giữa chừng.

**QĐ2. Thứ tự thao tác trong `placeOrder` (BR-005-3, BR-005-4), và một bẫy của `clearAutomatically`.**

`placeOrder(userId, form, expectedTotal)` là `@Transactional`, chạy đúng thứ tự:

| Bước | Việc | Ghi chú |
|---|---|---|
| 1 | `cartCheckout.lockAndRead(userId)` | khóa dòng `users` trước khi đọc giỏ (ADR-0008); lấy `List<CheckoutLine>` |
| 2 | Giỏ rỗng → ném `EmptyCart` | AC-005-14, AC-005-15 |
| 3 | Kiểm từng dòng (QĐ4), rồi so tổng tiền (QĐ5) | ném `NotOrderable` / `InsufficientStock` / `PriceChanged` |
| 4 | Tạo `Order` (`userRepository.getReferenceById`, form, tổng), `setNote`, `addItem(productRepository.getReferenceById(id), line.name(), line.unitPrice(), line.quantity())` cho từng dòng theo thứ tự giỏ; `orderRepository.save(order)` | snapshot lấy từ dữ liệu của bước 1. `IDENTITY` nên `save` INSERT ngay, có `order.getId()` |
| 5 | Sắp dòng theo `productId` tăng dần; với mỗi dòng `decrementStock(id, qty)`; trả 0 → ném `InsufficientStock(tên)` | BR-005-4; exception làm rollback cả đơn, kể cả các dòng đã trừ |
| 6 | `cartCheckout.clear(userId)` | xóa dòng giỏ |
| 7 | Trả `orderId` (đã lấy ở bước 4) | log `info` "Order placed: id, user, lines" (không có địa chỉ, số điện thoại) |

- **Bẫy cần nhớ:** `decrementStock` có `clearAutomatically = true`. Sau lần gọi đầu tiên, persistence context bị xóa: `Order`, `Product`, `User` đều thành detached. Vì vậy:
  - `Order` phải được `save` **trước** bước 5 (đúng thứ tự spec), và từ bước 5 trở đi không đọc thêm association lười nào;
  - bước 5 lặp trên **danh sách `CheckoutLine` thuần** (đã có trong bộ nhớ), không lặp trên `order.getItems()`;
  - bước 6 là câu `@Modifying` (xóa hàng loạt), không phải `cart.removeItem()` trên entity đã detached (sẽ im lặng không xóa gì).
- **Vì sao không deadlock (AC-005-17):** trên PostgreSQL, `UPDATE products SET stock = …` lấy khóa `FOR NO KEY UPDATE` trên dòng sản phẩm. Hai đơn cùng trừ A và B theo thứ tự tăng dần nên đơn sau luôn chờ ở A, không bao giờ một bên giữ A chờ B còn bên kia giữ B chờ A. Khóa khóa ngoại lúc INSERT `order_items` chỉ là `FOR KEY SHARE`, không xung đột với `FOR NO KEY UPDATE`.
- **Vì sao không bán vượt kho (AC-005-16):** hai người khác nhau không chờ nhau ở dòng `users`, nên có thể cùng đọc `stock = 1` ở bước 1. Nhưng câu `UPDATE … WHERE stock >= :q` thứ hai phải chờ khóa dòng; khi được chạy tiếp, PostgreSQL (`READ COMMITTED`) **đánh giá lại** điều kiện `WHERE` trên bản ghi mới (`stock = 0`), nên trả 0 dòng → rollback. `ck_products_stock_non_negative` là lớp cuối.
- **Vì sao một giỏ chỉ ra một đơn (AC-005-15):** hai `POST /checkout` của cùng người chờ nhau ở bước 1. Yêu cầu sau đọc giỏ đã bị xóa ở bước 6 của yêu cầu đầu, nên ném `EmptyCart`.

*Phương án bị loại:*
- **Khóa trước mọi dòng sản phẩm (`SELECT … FOR UPDATE ORDER BY id`) rồi trừ.** Đúng, nhưng ADR-0005 đã chọn câu UPDATE có điều kiện; khóa thêm chỉ làm dài thời gian giữ khóa.
- **Đọc `stock`, so, rồi ghi** (kể cả "đã kiểm ở bước 3 nên khỏi điều kiện ở bước 5"). Cấm bởi invariant tồn kho: bước 3 chỉ để báo lỗi sớm và rõ, bước 5 mới là chỗ đảm bảo.
- **Bỏ `clearAutomatically` khỏi `decrementStock`.** Khi đó `Product` trong context giữ `stock` cũ sau khi trừ; GĐ6 (admin) rất dễ đọc nhầm. Thứ tự ở trên đủ để sống chung với nó.

**QĐ3. `placeOrder` hoặc trả id đơn, hoặc ném một `PlaceOrderException` (unchecked). Mọi exception đều làm rollback, nên "đặt trọn vẹn hoặc không có gì thay đổi" trùng khớp với "trả về hoặc ném".**
- `abstract sealed class PlaceOrderException extends RuntimeException`, bốn lớp con lồng trong cùng file:
  - `EmptyCart`;
  - `NotOrderable`;
  - `InsufficientStock(String productName)`;
  - `PriceChanged(CheckoutView current)`: mang luôn trang thanh toán đã tính lại trong transaction, để controller hiện lại mà không phải truy vấn lần nữa.
- Message tiếng Anh, chỉ cho log. Tạo bằng `super(message, null, false, false)` (không chụp stack trace): đây là kết quả nghiệp vụ dự tính trước, không phải lỗi.
- **Vì sao unchecked:** `@Transactional` của Spring mặc định **chỉ rollback với `RuntimeException` và `Error`**. Một checked exception sẽ đi ra ngoài *sau khi transaction đã commit*: các dòng đã trừ kho ở bước 5 sẽ được lưu dù đơn không đặt được. Đây là bài học quan trọng nhất của GĐ5; Javadoc của `PlaceOrderException` ghi lại.
- **Không bắt exception bên trong service.** Bắt rồi trả kết quả bên trong `@Transactional` thì transaction vẫn commit (hoặc, nếu một method con đã đánh dấu rollback-only, thì ném `UnexpectedRollbackException` lúc commit). Controller mới là chỗ bắt, khi transaction đã kết thúc.
- `sealed` cho phép controller viết `switch` theo pattern và trình biên dịch bắt buộc xử lý đủ bốn trường hợp (giống `CartOwner` ở GĐ4).

| Kết quả | Controller làm gì | Flash / model | AC |
|---|---|---|---|
| trả `id` | redirect `/orders/{id}` | flash `success` "Đặt hàng thành công. Mã đơn của bạn là #{id}." | 005-7 |
| `EmptyCart` | redirect `/orders` | flash `info` "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo." | 005-14, 15 |
| `NotOrderable` | redirect `/cart` | flash `error` "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng." | 005-12 |
| `InsufficientStock(tên)` | redirect `/cart` | flash `error` "Sản phẩm {tên} không đủ hàng. Vui lòng cập nhật giỏ hàng." | 005-11, 16 |
| `PriceChanged(view)` | render `order/checkout`, HTTP 200 | model `checkout` = view mới, `form` = giá trị đã nhập, `notice` "Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận.", `noticeKind` `error` | 005-13 |

*Phương án bị loại:*
- **Trả một sealed result và gọi `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` khi trừ kho thất bại.** Gắn service vào nội tình của Spring, và rất dễ quên gọi.
- **Checked exception.** Commit mất các dòng đã trừ (xem trên), trừ khi nhớ ghi `rollbackFor` ở mọi chỗ.

**QĐ4. Phân loại dòng không đặt được lúc xác nhận (bước 3), theo thứ tự ưu tiên.**
1. Có dòng sản phẩm đã ẩn (`!active`) → `NotOrderable` (chữ của AC-005-5; AC-005-12).
2. Có dòng `quantity > stock`, **kể cả `stock = 0`** → `InsufficientStock(tên)` của dòng đầu tiên theo `productId` tăng dần (AC-005-11).
3. Còn lại → so tổng tiền (QĐ5).

- Đây là **khuyến nghị ở câu hỏi 1** (mục 6): spec có một chỗ mâu thuẫn. AC-005-12 nói "hết hàng ngay sau đó" → chữ chung của AC-005-5; AC-005-16 nói người đặt chậm hơn trong cuộc đua → chữ có tên của AC-005-11. Người chậm hơn đọc giỏ *sau khi* người kia commit sẽ thấy `stock = 0`, nên hai AC không thể cùng đúng. Plan chọn: `stock = 0` lúc xác nhận cũng là "Sản phẩm {tên} không đủ hàng." (rõ hơn cho khách, và làm AC-005-16 tất định). Sản phẩm đã ẩn vẫn dùng chữ chung, vì "không đủ hàng" không đúng với hàng ngừng bán. Nếu người dùng chọn đọc spec theo chữ, chỉ đổi điều kiện ở mục 2 thành `0 < stock < quantity` và kỳ vọng của test race AC-005-16 thành "một trong hai exception".
- **`GET /checkout` khác:** mọi dòng không đặt được (ẩn, hết, vượt kho) đều về `/cart` với chữ chung của AC-005-5, đúng spec.

**QĐ5. So tổng tiền (BR-005-5): một ô ẩn `expectedTotal`, chỉ dùng để so, không bao giờ lưu.**
- Tham số đến dạng chuỗi thô (`@RequestParam(required = false) String expectedTotal`), **không nằm trong `CheckoutForm`**, để không bao giờ thành lỗi dưới ô nhập. `OrderInput.amount(raw)`: `strip()`, khớp `[0-9]{1,15}`, rồi `new BigDecimal`; còn lại → rỗng.
- Thiếu hoặc sai dạng → coi như **giá đã đổi** (`PriceChanged`): khách được xem lại trang với giá hiện tại, không bao giờ bị tính một giá họ chưa thấy.
- So bằng `CheckoutView.totalDiffersFrom(expected)` = `expected == null || total.compareTo(expected) != 0`. **Dùng `compareTo`, không dùng `equals`:** `new BigDecimal("450000").equals(new BigDecimal("450000.0"))` là `false` vì khác `scale`.
- Template ghi `th:value="${checkout.total.toPlainString()}"` (không bao giờ ra dạng `4.5E+5`).
- **Lỗi form và giá đổi cùng lúc:** khi form có lỗi, controller tải lại trang thanh toán (giá mới); nếu `totalDiffersFrom(expected)` thì thêm luôn thông báo giá đổi. Như vậy lần bấm sau không đặt theo giá mới mà khách chưa được báo. Đây là BR-005-5 áp dụng nhất quán, không phải tính năng mới.

**QĐ6. Hủy đơn (BR-005-10, AC-005-30): khóa dòng `orders` bằng `PESSIMISTIC_WRITE`, kiểm trạng thái trong Java, rồi cộng kho bằng câu `@Modifying` mới `incrementStock` theo `productId` tăng dần.**
- `OrderRepository.lockByIdAndUserId(id, userId)`: `@Lock(PESSIMISTIC_WRITE)` trên `select o from Order o where o.id = :id and o.user.id = :userId`. Trên PostgreSQL Hibernate sinh `… for no key update` (như ADR-0008), trên H2 là `for update`; cả hai đã chạy được ở GĐ4.
- `cancelByCustomer(userId, orderId)`, một transaction:
  1. `lockByIdAndUserId` → không có → `NotFoundException` (đơn của người khác cũng vậy: không khóa gì của họ);
  2. `!customerMayCancel(order.getStatus())` → trả `NOT_CANCELLABLE`, không ghi gì;
  3. **chép** `(productId, quantity)` của mọi dòng ra một danh sách (khởi tạo `order.getItems()`, một câu SQL) **trước** khi gọi `incrementStock`, vì `clearAutomatically` (QĐ2);
  4. `order.transitionTo(CANCELLED)` (kiểm `canTransitionTo`, QĐ7);
  5. với từng dòng theo `productId` tăng dần: `incrementStock(id, qty)`; trả khác 1 → `IllegalStateException` (không thể xảy ra vì có `fk_order_items_product`, nhưng nếu xảy ra thì phải rollback chứ không im lặng). `flushAutomatically` ghi trạng thái mới xuống DB trước câu cộng kho đầu tiên;
  6. trả `CANCELLED`.
- **Vì sao đúng:** yêu cầu hủy thứ hai chờ ở bước 1. Khi chạy tiếp, nó đọc lại dòng đã commit, thấy `CANCELLED`, trả `NOT_CANCELLABLE`. Kho chỉ được cộng một lần.
- `incrementStock` **không lọc `active`**: sản phẩm đã ẩn vẫn được hoàn kho (AC-005-29).
- **Thứ tự khóa toàn hệ thống** (ghi vào ADR-0009): đặt hàng khóa `users` rồi `products` tăng dần; hủy khóa `orders` rồi `products` tăng dần; giỏ chỉ khóa `users`. Không có đường nào khóa `products` trước rồi mới tới `users` hay `orders`, nên không có vòng chờ.
- **GĐ6 dùng lại:** mọi chuyển trạng thái đơn của admin cũng "khóa dòng `orders`, kiểm `canTransitionTo`, ghi", nên admin xác nhận và khách hủy cùng lúc cũng được xếp hàng.

*Phương án bị loại:*
- **UPDATE có điều kiện** (`update orders set status = 'CANCELLED' where id = ? and user_id = ? and status = 'PENDING'`, 1 dòng thì cộng kho). Nguyên tử, không cần khóa, đối xứng với ADR-0005. Nhưng quy tắc chuyển trạng thái phải viết lại trong JPQL thay vì dùng `canTransitionTo()`, entity trong context bị cũ, và 0 dòng thì phải truy vấn thêm để phân biệt "không có" với "không hủy được".
- **Optimistic locking (`@Version` trên `orders`).** Cần migration (cột `version`), vẫn phải bắt `OptimisticLockException` để không ra trang 500.

**QĐ7. Luồng trạng thái nằm trong enum; quy tắc riêng của khách nằm trong service.**
- `OrderStatus.canTransitionTo(next)`: một `switch` trên `this`. Chỉ `PENDING→CONFIRMED`, `CONFIRMED→SHIPPING`, `SHIPPING→DELIVERED`, `PENDING→CANCELLED`, `CONFIRMED→CANCELLED` là `true` (AC-005-31). `switch` không có `default`, nên thêm một trạng thái mới mà quên xử lý là lỗi biên dịch.
- `Order.transitionTo(next)`: `!status.canTransitionTo(next)` → `IllegalStateException`; ngược lại đổi `status` (`@PreUpdate` cập nhật `updated_at`). Là chốt chặn ở domain cho cả GĐ6.
- `OrderService.customerMayCancel(status)` = `status == PENDING` (BR-005-8: chặt hơn bảng chung). **Dùng cho cả cờ `cancellable` của trang chi tiết lẫn hành động hủy**, để nút và quy tắc không bao giờ lệch nhau.
- **Nhãn tiếng Việt nằm ngay trong enum:** `OrderStatus.label()` (BR-005-9) và `PaymentMethod.label()` ("Thanh toán khi nhận hàng (COD)"). "Đa ngôn ngữ" nằm trong danh sách "Loại ra" của brief, nên không cần `messages.properties`; GĐ6 dùng lại cùng nhãn. View record chỉ nhận `String statusLabel` và `String statusKey` (`pending`, `confirmed`…, cho class CSS), không nhận enum.

**QĐ8. Quyền sở hữu (IDOR, BR-005-11) kiểm ở service bằng cách lọc theo `user_id` ngay trong truy vấn. Không có thì 404, không phân biệt "không có" với "của người khác".**
- Mọi truy vấn đơn của khách đều có `o.user.id = :userId`: `findByUserId`, `findDetailByIdAndUserId`, `lockByIdAndUserId`. `userId` luôn lấy từ `@AuthenticationPrincipal CurrentUser`, không bao giờ từ request.
- **Id đến dạng chuỗi thô** như GĐ4: `@PathVariable String id`, `OrderInput.orderId(raw)` (`strip()`, `[0-9]{1,18}`, `Long.valueOf`); sai → `NotFoundException` trước khi gọi service. `/orders/abc`, `/orders/-1`, `/orders/99999999999999999999` đều 404.
- `order.OrderNotFoundAdvice` (`@ControllerAdvice(assignableTypes = OrderController.class)`, `@Order(HIGHEST_PRECEDENCE)`): bắt `NotFoundException` và `MethodArgumentTypeMismatchException`, trả view `order/not-found` với status 404. Lặp vài dòng như `CartNotFoundAdvice`; GĐ7 gom lại.

*Phương án bị loại:*
- **Tải theo id rồi so chủ đơn trong Java.** Đúng nếu nhớ so ở *mọi* chỗ; lọc trong truy vấn thì không thể quên, và với hủy đơn thì không bao giờ khóa nhầm đơn của người khác.
- **`@PostAuthorize` của Spring Security.** Phải bật method security, và quyền nằm xa nghiệp vụ; spec yêu cầu kiểm ở service.

**QĐ9. Tải dữ liệu, phân trang, ngày giờ.**
- **Danh sách** (`listOrders(userId, rawPage)`):
  - `OrderInput.page(raw)`: `strip()`, số nguyên, kẹp vào `1..10_000` (như `ProductFilter.MAX_PAGE`), sai → 1;
  - `findByUserId(userId, PageRequest.of(page - 1, 10, Sort.by(DESC "createdAt", DESC "id")))`: một câu SELECT có LIMIT/OFFSET và một câu COUNT. `id` là tie-breaker để trang ổn định;
  - chỉ đọc cột của `orders`; không chạm `user` hay `items`, nên **không N+1** (AC-005-26);
  - trang vượt quá (nội dung rỗng nhưng `totalElements > 0`) → truy vấn lại trang 1 ("sai thì về trang 1", AC-005-21). Không bao giờ hiện "Bạn chưa có đơn hàng nào." cho người có đơn.
- **Chi tiết:** `findDetailByIdAndUserId` có `@EntityGraph(attributePaths = "items")`: **một** câu SQL lấy đơn và dòng hàng. Dòng hàng chỉ dùng cột snapshot (`product_name`, `unit_price`, `quantity`), không chạm `product`. Sắp theo `OrderItem.id` tăng dần trong Java (đúng thứ tự trong giỏ lúc đặt).
- **Không thêm index `(user_id, created_at)`:** `idx_orders_user_id` đã thu hẹp về đơn của một người (vài chục dòng), sắp xếp trong bộ nhớ là đủ. Thêm index là migration `V3` + `schema-guardian` cho một lợi ích chưa đo được; ghi ở "Đề xuất ngoài phạm vi".
- **Ngày giờ:** `common.VnDateTimeFormatter.format(Instant)` → `dd/MM/yyyy HH:mm` theo `ZoneId.of("Asia/Ho_Chi_Minh")` cố định, đăng ký trong `ViewHelpersDialect` thành `#vnDateTime` (cùng kiểu với `#vnd`). View record giữ `Instant`. **Không dùng `#temporals`:** với `Instant` nó dùng múi giờ mặc định của JVM, mà container Docker thường là UTC, nên giờ hiển thị sẽ lệch 7 tiếng tùy máy chạy.

**QĐ10. Form thanh toán: `record CheckoutForm` + Jakarta Validation; số điện thoại kiểm bằng một constraint riêng để giữ nguyên chuỗi khách đã gõ.**
- Component: `receiverName`, `phone`, `shippingAddress`, `note`. **Không có** `userId`, `status`, `totalAmount`, `paymentMethod`, `unitPrice`, `id`, nên tham số thừa bị Spring bỏ qua (AC-005-18).
- Compact constructor (chạy trước validation, BR-005-6):
  - `strip()` mọi ô; `null` giữ `null`;
  - bỏ ký tự điều khiển: ở `receiverName`, `phone` bỏ hết; ở `shippingAddress`, `note` giữ `\n`, `\r`, `\t` (ô nhiều dòng). Lý do: PostgreSQL từ chối byte `0x00` trong `varchar`, sẽ thành trang 500 (bài học GĐ2, `ProductFilter`);
  - `note` rỗng → `null` (spec: để trống thì lưu `null`).
- Ràng buộc và chữ lỗi (đúng spec §6):

| Ô | Ràng buộc |
|---|---|
| `receiverName` | `@NotBlank` "Vui lòng nhập họ tên người nhận."; `@Size(max = 100)` "Họ tên người nhận tối đa 100 ký tự." |
| `phone` | `@NotBlank` "Vui lòng nhập số điện thoại."; `@PhoneNumber` "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0." |
| `shippingAddress` | `@NotBlank` "Vui lòng nhập địa chỉ giao hàng."; `@Size(max = 500)` "Địa chỉ giao hàng tối đa 500 ký tự." |
| `note` | `@Size(max = 500)` "Ghi chú tối đa 500 ký tự." |

- `@PhoneNumber` + `PhoneNumberValidator` (như `@ValidPassword` ở GĐ3):
  - `static String normalize(String raw)`: bỏ dấu cách, dấu chấm, dấu gạch ngang (`[ .\-]`);
  - hợp lệ khi `null`/rỗng (để `@NotBlank` báo, tránh hai lỗi một ô) hoặc chuỗi đã chuẩn hóa khớp `0[0-9]{9}`. Viết `[0-9]`, không viết `\d`, cho rõ là chỉ chữ số ASCII.
  - `CheckoutForm.normalizedPhone()` gọi `normalize`; service lưu giá trị này (`0912 345 678` → `0912345678`, AC-005-10).
  - **Vì sao không chuẩn hóa ngay trong compact constructor:** khi có lỗi, trang phải hiện lại *đúng* chuỗi khách đã gõ (AC-005-9), ví dụ `0912.345.67`, không phải `091234567`.
- `toString()` che `phone`, `shippingAddress`, `note` (`***`): spec §8 cấm in địa chỉ và số điện thoại ra log; che sẵn thì một câu `log.debug("{}", form)` vô tình cũng an toàn.
- **Điền sẵn (AC-005-3):** `OrderService.prefilledForm(userId)` đọc `User` (`userRepository.findById`): `fullName`, và `phone` nếu có (chuỗi rỗng nếu `null`). `CurrentUser` không có số điện thoại, và dữ liệu DB luôn mới hơn session. GĐ5 không ghi gì vào `users`.

**QĐ11. Tầng web: hai controller trong `order`, nhận `@AuthenticationPrincipal CurrentUser`, không rẽ nhánh nghiệp vụ.**
- `CheckoutController` (`/checkout`):
  - `GET`: `view = orderService.prepareCheckout(id)`; `view.isEmpty()` → `redirect:/cart` (không flash, trang giỏ tự hiện "Giỏ hàng của bạn đang trống.", AC-005-4); `!view.orderable()` → flash `error` chữ AC-005-5, `redirect:/cart`; còn lại model `checkout`, `form = orderService.prefilledForm(id)` → `order/checkout`.
  - `POST`: `@Valid @ModelAttribute("form") CheckoutForm form, BindingResult result, @RequestParam(required = false) String expectedTotal`.
    - Có lỗi: tải `prepareCheckout`; trống/không đặt được thì xử lý như `GET`; ngược lại render `order/checkout` (200), thêm thông báo giá đổi nếu `totalDiffersFrom` (QĐ5). **Service `placeOrder` không được gọi.**
    - Không lỗi: `placeOrder(id, form, OrderInput.amount(expectedTotal).orElse(null))`, rồi theo bảng QĐ3.
- `OrderController` (`/orders`):
  - `GET /orders?page=`: model `orders` (`OrderListView`), `pageNav` = `PageNav.of(page, totalPages, p -> "/orders?page=" + p)` → `order/list`;
  - `GET /orders/{id}`: model `order` (`OrderDetailView`) → `order/detail`;
  - `POST /orders/{id}/cancel`: `CANCELLED` → flash `success` "Đã hủy đơn hàng #{id}."; `NOT_CANCELLABLE` → flash `error` "Đơn hàng này không thể hủy nữa."; luôn `redirect:/orders/{id}` dựng từ id đã parse.
- Quy ước flash như GĐ4: `notice` (String) và `noticeKind` (`success` | `info` | `error`). Không dùng `showCartLink`.
- **Không đọc `Referer`** hay tham số "quay về"; mọi redirect tự dựng từ id kiểu `long` (spec §6).
- Chữ hiển thị là hằng `private static final` trong controller, đúng từng chữ spec §6.
- **`SecurityConfig` không đổi:** `/checkout/**`, `/orders/**` đã cần đăng nhập; `/js/**` đã công khai; CSRF mặc định bật nên POST thiếu token → 403 (AC-005-19); POST lỗi được dispatch tới `/error`, đã mở mọi method từ GĐ3.

**QĐ12. Hộp xác nhận hủy: file `static/js/confirm.js` dùng chung, kích hoạt bằng thuộc tính `data-confirm` trên `<form>`. CSP không đổi.**
- Lắng nghe sự kiện `submit` ở `document` (event delegation, không có handler nội tuyến). Nếu form có `data-confirm` và `window.confirm(form.dataset.confirm)` trả `false` thì `preventDefault()`. Khoảng 10 dòng, không thư viện, không `eval`, không `innerHTML`.
- Gắn vào **`submit` của form**, không vào `click` của nút: bấm Enter hay dùng bàn phím cũng được hỏi.
- **Chữ của hộp thoại nằm trong template** (`data-confirm="Bạn có chắc muốn hủy đơn hàng này?"`), file JS không chứa chữ tiếng Việt nào, nên GĐ6 dùng lại được cho "ẩn sản phẩm", "xóa danh mục".
- Tắt JavaScript thì form vẫn gửi bình thường (spec §8); service vẫn là chốt chặn thật.
- Thẻ `<script th:src="@{/js/confirm.js}" defer></script>` chỉ nằm cuối `order/detail.html` (trang duy nhất cần), không nằm trong layout.
- CSP `default-src 'self'` đã cho phép script cùng origin; `window.confirm` không bị CSP chặn. `TemplateCspComplianceTest` cho phép `<script src=…>` và không bắt nhầm `data-confirm` (lookbehind của rule `on…=` thấy chữ `c` đứng trước `onfirm`).

*Phương án bị loại:* trang xác nhận riêng `/orders/{id}/cancel` (GET): thêm một URL và một template, mà spec đã chọn hộp `confirm()`.

**QĐ13. Giao diện: dùng lại hướng "The specimen catalogue", không vẽ lại. Template tuân thủ CSP.**
- `frontend-developer` dùng skill `design-taste-frontend` ở **chế độ dùng lại hướng đã vẽ** (như GĐ4): đọc token ở comment đầu `app.css`; không chạy `draw.mjs`; không đổi token, font, palette; không thêm "one live thing", mini-cart, AJAX hay chữ tự nghĩ. Chữ mới được phép duy nhất là nhãn ẩn cho truy cập (`visually-hidden`), ví dụ `caption` của bảng.
- Dùng lại component sẵn có: `.page-title`, `.notice`, `.auth-field` / `.form-label` / `.form-control` / `.field-error`, `.cart-table` (khối dọc dưới `sm`), `.empty-state`, `.price`, nút chính và nút phụ. CSS mới ở **cuối** `app.css`, trong khối `/* GĐ5: thanh toán, danh sách và chi tiết đơn */`.
- Địa chỉ và ghi chú hiển thị bằng `th:text` với `white-space: pre-line` (giữ xuống dòng khách nhập mà vẫn escape), như mô tả sản phẩm ở GĐ2.
- Nhãn trạng thái là chữ trên nền đặc (như `.stock-badge`), không chỉ dựa vào màu; class modifier `order-status--{statusKey}`.
- Phương thức thanh toán là **chữ**, không có radio hay select (BR-005-7).
- Tiêu đề tab động phải nối chuỗi trong biểu thức: `head(${'Đơn hàng #' + order.id + ' | Mini Shop'})`. **Không** dùng literal substitution `|…|`, vì ký tự `|` trong chữ " | Mini Shop" sẽ kết thúc literal.

**QĐ14. Chiến lược test.**
- **Web slice** của `order` (`CheckoutControllerTest`, `OrderControllerTest`) nằm trong package `com.minishop.order`, nên tự `@MockitoBean OrderService` được. `@Import({SecurityConfig.class, CartHeaderTestConfig.class})` (layout gọi `#cartHeader`). Người đăng nhập dùng `TestUsers.user()` (id 2) / `admin()` (id 1); **không** dùng `@WithMockUser` cho trang render, vì `@AuthenticationPrincipal CurrentUser` sẽ là `null`.
- **Test PostgreSQL** theo đúng khuôn `CartIntegrationTest`: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)` + `@Testcontainers(disabledWithoutDocker = true)`, không `@Transactional` (race cần transaction thật), không `@MockitoSpyBean` (dùng chung context).
  - Dữ liệu riêng qua fixture `order.OrderTestData`: người dùng email đuôi `@order.test`, sản phẩm tên bắt đầu `Order test `, danh mục lấy từ seed. Dọn theo thứ tự `order_items` → `orders` → `cart_items` → `carts` → sản phẩm test → người dùng test. **Không** sửa sản phẩm hay tài khoản seed.
  - **Race** gọi thẳng `OrderService` (2 luồng, `CountDownLatch`, 20 vòng, mỗi vòng dữ liệu mới), như `raceAdd` của GĐ4.
  - **Rollback giữa chừng (AC-005-11) làm tất định**, không dựa vào may rủi: một connection JDBC riêng (autocommit tắt) chạy `update products set stock = 0 where id = B` và **chưa commit**, nên giữ khóa dòng B. Luồng đặt hàng đọc được `stock` đã commit của B (vẫn đủ), qua bước 3, trừ A xong rồi đứng chờ ở B. Test chờ tới khi `pg_stat_activity` có một backend `wait_event_type = 'Lock'` (tối đa 10 s), rồi commit connection kia. Câu trừ B đánh giá lại, trả 0 dòng → `InsufficientStock`. Kiểm: kho A **không đổi** (lần trừ A đã bị rollback), không có đơn, giỏ nguyên.
  - **Đếm truy vấn (AC-005-26):** bật `Statistics` của Hibernate lúc chạy (`entityManagerFactory.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(true)`, `clear()`), gọi service, đọc `getPrepareStatementCount()`, rồi tắt lại. Không cần property riêng, nên không sinh context Spring thứ hai.
- **H2:** thêm một test vào `H2ProfileSmokeTest` (đặt rồi hủy một đơn bằng tài khoản seed), khôi phục `stock` và xóa đơn ở `finally`; không thêm người dùng (test seed đếm đúng 2).
- **Bài học GĐ3 về query string:** kiểm bảo mật của URL có query bằng `get("/orders?page=2")`, không bằng `.param()`.

**QĐ15. Một ADR mới, ADR-0009 "Đặt và hủy đơn: thứ tự khóa cố định và chuyển trạng thái đơn qua khóa dòng `orders`".**
- ADR-0005 (trừ kho có điều kiện) và ADR-0008 (khóa dòng `users` trước khi đọc giỏ) vẫn đúng và không bị thay thế. Nhưng GĐ5 thêm hai quy tắc mới mà mọi code sau phải theo, nên theo `CLAUDE.md` cần ADR:
  - trừ/cộng kho của một đơn luôn theo `product_id` tăng dần (ADR-0005 từng loại khóa bi quan vì sợ deadlock nhiều sản phẩm, nhưng câu UPDATE có điều kiện cũng khóa dòng, nên cũng cần thứ tự);
  - mọi chuyển trạng thái đơn (khách hủy ở GĐ5, admin ở GĐ6) khóa dòng `orders` trước, rồi kiểm `canTransitionTo()`; hủy thì cộng kho bằng `incrementStock` trong cùng transaction;
  - kèm bảng thứ tự khóa toàn hệ thống (QĐ6), các phương án bị loại (QĐ6), và hệ quả của `clearAutomatically` (QĐ2).
- Phiên chính viết ở task D, kèm câu SQL thật mà TW5 báo cáo.
- **Phạm vi public mới:** `cart.CartCheckout`, `cart.CheckoutLine`, `common.VnDateTimeFormatter`. Toàn bộ `order` vẫn package-private (GĐ6 sẽ mở một API hẹp cho `admin`).

## 3. Hợp đồng code và view (chốt trước để backend, frontend, test làm song song)

Nếu cần thêm thuộc tính hay đổi tên, agent dừng lại và báo phiên chính; không tự đặt tên.

```java
// catalog (public, thêm vào ProductRepository)
/** Cộng lại kho khi hủy đơn (BR-005-10). Không lọc active: sản phẩm đã ẩn vẫn được hoàn kho. */
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Product p set p.stock = p.stock + :quantity where p.id = :id and :quantity > 0")
int incrementStock(@Param("id") Long id, @Param("quantity") int quantity);

// cart (public mới)
public record CheckoutLine(long productId, String name, BigDecimal unitPrice, int quantity, boolean active, int stock) {
    public boolean orderable();       // CartLineStatus.of(active, stock, quantity) == AVAILABLE
    public BigDecimal lineTotal();    // unitPrice × quantity
}
@Service
public class CartCheckout {
    @Transactional(readOnly = true)                 public List<CheckoutLine> read(long userId);         // không khóa
    @Transactional(propagation = Propagation.MANDATORY) public List<CheckoutLine> lockAndRead(long userId);  // lockOwner trước
    @Transactional(propagation = Propagation.MANDATORY) public void clear(long userId);                     // sau lockAndRead
}
// cart (package-private, thêm)
enum CartLineStatus { …; static CartLineStatus of(boolean active, int stock, int quantity); }  // !active → DISCONTINUED; stock 0 → OUT_OF_STOCK; q > stock → LOW_STOCK; còn lại AVAILABLE
record CartView(…) { boolean isOrderable(); }       // !isEmpty() && mọi dòng AVAILABLE
interface CartRepository {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartItem i where i.cart.id in (select c.id from Cart c where c.user.id = :userId)")
    int deleteItemsByUserId(@Param("userId") Long userId);
}

// common (public mới)
public class VnDateTimeFormatter { public String format(Instant instant); }   // "dd/MM/yyyy HH:mm", Asia/Ho_Chi_Minh; null -> ""
// ViewHelpersDialect: thêm expression object "vnDateTime"  ->  ${#vnDateTime.format(o.createdAt)}

// order (tất cả package-private)
enum OrderStatus { PENDING, CONFIRMED, SHIPPING, DELIVERED, CANCELLED;
    boolean canTransitionTo(OrderStatus next); String label(); }   // "Chờ xử lý", "Đã xác nhận", "Đang giao", "Đã giao", "Đã hủy"
enum PaymentMethod { COD; String label(); }                        // "Thanh toán khi nhận hàng (COD)"
class Order { void transitionTo(OrderStatus next); }               // IllegalStateException nếu !canTransitionTo

interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(Long userId, Pageable pageable);
    @EntityGraph(attributePaths = "items")
    @Query("select o from Order o where o.id = :id and o.user.id = :userId")
    Optional<Order> findDetailByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.user.id = :userId")
    Optional<Order> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}

record CheckoutForm(String receiverName, String phone, String shippingAddress, String note) {
    static CheckoutForm prefill(String fullName, String phone);   // địa chỉ, ghi chú rỗng
    String normalizedPhone();
    // toString() che phone, shippingAddress, note
}
@interface PhoneNumber { … }                                        // @Constraint(validatedBy = PhoneNumberValidator.class)
final class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> { static String normalize(String raw); }

record CheckoutLineView(String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {}
record CheckoutView(List<CheckoutLineView> lines, BigDecimal total, boolean orderable) {
    boolean isEmpty(); boolean totalDiffersFrom(BigDecimal expected); }
abstract sealed class PlaceOrderException extends RuntimeException {
    static final class EmptyCart …; static final class NotOrderable …;
    static final class InsufficientStock … { String productName(); }
    static final class PriceChanged … { CheckoutView current(); } }
enum CancelResult { CANCELLED, NOT_CANCELLABLE }

record OrderSummaryView(long id, Instant createdAt, BigDecimal totalAmount, String statusLabel, String statusKey) {}
record OrderListView(List<OrderSummaryView> orders, int page, int totalPages, long totalElements) { boolean isEmpty(); } // totalElements == 0
record OrderLineView(String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {}
record OrderDetailView(long id, Instant createdAt, String statusLabel, String statusKey, String paymentMethodLabel,
                       String receiverName, String phone, String shippingAddress, String note,
                       List<OrderLineView> lines, BigDecimal totalAmount, boolean cancellable) {}
final class OrderInput { static Optional<Long> orderId(String raw); static Optional<BigDecimal> amount(String raw); static int page(String raw); }

@Service @Transactional
class OrderService {
    static final int PAGE_SIZE = 10;
    @Transactional(readOnly = true) CheckoutView prepareCheckout(long userId);
    @Transactional(readOnly = true) CheckoutForm prefilledForm(long userId);
    long placeOrder(long userId, CheckoutForm form, BigDecimal expectedTotal);    // PlaceOrderException
    @Transactional(readOnly = true) OrderListView listOrders(long userId, String rawPage);
    @Transactional(readOnly = true) OrderDetailView getOrder(long userId, long orderId);   // NotFoundException
    CancelResult cancelByCustomer(long userId, long orderId);                     // NotFoundException
    static boolean customerMayCancel(OrderStatus status);                         // == PENDING
}
@Controller @RequestMapping("/checkout") class CheckoutController { … }
@Controller @RequestMapping("/orders")   class OrderController { … }
@ControllerAdvice(assignableTypes = OrderController.class) @Order(Ordered.HIGHEST_PRECEDENCE) class OrderNotFoundAdvice { … }

// test (src/test, package com.minishop.order)
final class OrderTestData { … }   // như CartTestData, xem chi tiết TW5
```

| View | URL | Status | Model / flash |
|---|---|---|---|
| `order/checkout` | `GET /checkout` | 200 | `form` (`CheckoutForm`), `checkout` (`CheckoutView`) |
| redirect `/cart` | `GET /checkout`, giỏ trống / không đặt được | 302 | không flash / flash `notice`, `noticeKind` `error` |
| `order/checkout` | `POST /checkout`, form lỗi hoặc `PriceChanged` | 200 | `form` + lỗi binding, `checkout`; model `notice`, `noticeKind` khi giá đổi |
| redirect `/orders/{id}`, `/orders`, `/cart` | `POST /checkout` | 302 | flash theo bảng QĐ3 |
| `order/list` | `GET /orders?page=` | 200 | `orders` (`OrderListView`), `pageNav` (`PageNav`); flash `notice`, `noticeKind` (có thể có) |
| `order/detail` | `GET /orders/{id}` | 200 | `order` (`OrderDetailView`); flash `notice`, `noticeKind` (có thể có) |
| redirect `/orders/{id}` | `POST /orders/{id}/cancel` | 302 | flash `notice`, `noticeKind` |
| `order/not-found` | id sai / không có / của người khác | 404 | (không có) |
| `cart/view` (đã có) | `GET /cart` | 200 | thêm `${cart.orderable}` |
| mọi trang có header | – | – | thêm link "Đơn hàng của tôi" khi đã đăng nhập |

Quy ước markup để test và verifier dựa vào (chữ đúng spec §6):

- **Header** (`fragments/layout.html`), ngay sau link `Giỏ hàng`: `<a class="site-nav-link" sec:authorize="isAuthenticated()" th:href="@{/orders}">Đơn hàng của tôi</a>`.
- **`cart/view.html`**, trong khối giỏ có hàng, sau bảng:
  - `th:if="${cart.orderable}"`: `<a class="btn btn-primary" th:href="@{/checkout}">Đặt hàng</a>` (link GET, không phải form);
  - ngược lại: `<p class="cart-blocked">Vui lòng xóa hoặc giảm số lượng các sản phẩm không đủ hàng trước khi đặt hàng.</p>`;
  - giỏ trống: không có cả hai (giữ nguyên "không có `<table>`, không có `<form>`" của GĐ4).
- **`order/checkout.html`:**
  - tiêu đề tab `Thanh toán | Mini Shop`, `h1` `Thanh toán`; notice (`fragments/notice`) khi có `notice`;
  - `<form method="post" th:action="@{/checkout}" th:object="${form}" novalidate>` chứa:
    - bốn ô dùng `th:field`: `receiverName` (`type="text"`, `autocomplete="name"`, `maxlength="100"`), `phone` (`type="tel"`, `autocomplete="tel"`, `inputmode="tel"`), `shippingAddress` (`<textarea>`, `autocomplete="street-address"`, `maxlength="500"`), `note` (`<textarea>`, `maxlength="500"`);
    - nhãn `Họ tên người nhận`, `Số điện thoại`, `Địa chỉ giao hàng`, `Ghi chú (không bắt buộc)`, mỗi `label for` khớp `id` của ô;
    - lỗi: `<p class="field-error" id="{ô}-error" th:errors="*{ô}">`, ô có `aria-invalid="true"` và `aria-describedby="{ô}-error"` khi lỗi (như `register.html`);
    - `<input type="hidden" name="expectedTotal" th:value="${checkout.total.toPlainString()}">`;
    - nút `<button type="submit">Xác nhận đặt hàng</button>` (nằm **trong** form);
  - mục tiêu đề `Phương thức thanh toán`, nội dung chữ `Thanh toán khi nhận hàng (COD)`; không có `<input type="radio">` hay `<select>`;
  - mục tiêu đề `Đơn hàng của bạn`: `<table>` có `<caption class="visually-hidden">`, `<th scope="col">` `Sản phẩm`, `Số lượng`, `Đơn giá`, `Thành tiền`; tên bằng `th:text`; tiền bằng `#vnd.format`; dòng `Tổng cộng`;
  - link `th:href="@{/cart}"` chữ `Quay lại giỏ hàng`.
- **`order/list.html`:**
  - tiêu đề tab `Đơn hàng của tôi | Mini Shop`, `h1` `Đơn hàng của tôi`; notice;
  - `orders.empty`: `Bạn chưa có đơn hàng nào.` + link `th:href="@{/products}"` chữ `Bắt đầu mua sắm`; **không có `<table>`**;
  - ngược lại `<table>` có caption ẩn và `<th scope="col">` `Mã đơn`, `Ngày đặt`, `Tổng tiền`, `Trạng thái`; mỗi dòng: `<a th:href="@{/orders/{id}(id=${o.id})}" th:text="|#${o.id}|">`, `${#vnDateTime.format(o.createdAt)}`, `${#vnd.format(o.totalAmount)}`, `<span class="order-status" th:classappend="|order-status--${o.statusKey}|" th:text="${o.statusLabel}">`;
  - `fragments/pagination :: pagination(${pageNav})`.
- **`order/detail.html`:**
  - tiêu đề tab `head(${'Đơn hàng #' + order.id + ' | Mini Shop'})`, `h1` `|Đơn hàng #${order.id}|`; notice;
  - `Ngày đặt` + `#vnDateTime.format(order.createdAt)`; `Trạng thái` + nhãn như trang danh sách;
  - mục `Thông tin nhận hàng`: `Họ tên người nhận`, `Số điện thoại`, `Địa chỉ giao hàng`, và `Ghi chú` chỉ khi `order.note != null`; mọi giá trị `th:text`;
  - mục `Phương thức thanh toán`: `${order.paymentMethodLabel}`;
  - bảng dòng hàng: caption ẩn, cột `Sản phẩm`, `Đơn giá`, `Số lượng`, `Thành tiền`; dòng `Tổng cộng` (`order.totalAmount`);
  - khi `order.cancellable`: `<form method="post" th:action="@{/orders/{id}/cancel(id=${order.id})}" data-confirm="Bạn có chắc muốn hủy đơn hàng này?">` + `<button type="submit">Hủy đơn hàng</button>`; ngược lại không có form và không có chữ `Hủy đơn hàng`;
  - link `th:href="@{/orders}"` chữ `Quay lại danh sách đơn`;
  - cuối `<body>`: `<script th:src="@{/js/confirm.js}" defer></script>`.
- **`order/not-found.html`:** tiêu đề tab `Không tìm thấy đơn hàng | Mini Shop`, `h1` `Không tìm thấy đơn hàng`, link `th:href="@{/orders}"` chữ `Quay lại danh sách đơn`. Không thêm câu mô tả tự nghĩ.
- **Cấm** (như GĐ2–4): `<script>` nội tuyến, `<style>`, `style=`/`th:style`, `on…=`/`th:on…`, `javascript:`, `th:utext`. Mọi form POST dùng `th:action`.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static từ `src/main/resources/`; test từ `src/test/java/com/minishop/`.

| # | Task | Agent | File | AC / BR | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| B1 | API hẹp của `cart`, cờ "đặt được", câu cộng kho | `backend-developer` | `cart/CartCheckout`, `cart/CheckoutLine` (mới); `cart/CartLineStatus`, `cart/CartView`, `cart/CartService` (chỉ `toLine`), `cart/CartRepository`, `cart/package-info.java`; `catalog/ProductRepository`, `catalog/package-info.java` (sửa) | 005-1, 15, 29; BR-005-1, 2, 3 (bước 1, 2, 6), 10 | – | B2, F1 | Xem chi tiết B1 |
| B2 | Domain đơn hàng, truy vấn, helper ngày giờ | `backend-developer` | `order/OrderStatus`, `order/PaymentMethod`, `order/Order` (chỉ thêm method), `order/OrderRepository`, `order/package-info.java` (sửa); `common/VnDateTimeFormatter` (mới), `common/ViewHelpersDialect` (sửa) | 005-20, 23, 24, 26, 30, 31; BR-005-8, 9, 11 | – | B1, F1 | Xem chi tiết B2 |
| B3 | `OrderService`, form, view record, exception, parse đầu vào | `backend-developer` | `order/OrderService`, `order/CheckoutForm`, `order/PhoneNumber`, `order/PhoneNumberValidator`, `order/CheckoutView`, `order/CheckoutLineView`, `order/PlaceOrderException`, `order/CancelResult`, `order/OrderSummaryView`, `order/OrderListView`, `order/OrderDetailView`, `order/OrderLineView`, `order/OrderInput` (mới) | 005-4, 5, 7–18, 20–24, 26–30; BR-005-1–13 | B1, B2 | F1, F2 | Xem chi tiết B3 |
| B4 | Tầng web của `order` | `backend-developer` | `order/CheckoutController`, `order/OrderController`, `order/OrderNotFoundAdvice` (mới) | 005-2–7, 9, 11–14, 18–24, 27, 28 | B3 | F1, F2, TW1, TW2, TW3 | Xem chi tiết B4 |
| F1 | Trang thanh toán, danh sách, chi tiết, 404 đơn; `confirm.js`; CSS | `frontend-developer` | `templates/order/checkout.html`, `list.html`, `detail.html`, `not-found.html` (mới); `static/js/confirm.js` (mới); `static/css/app.css` | 005-3, 9, 13, 20–24, 27, 28, 33 | – (dựng theo mục 3) | B1–B4, TW1–TW3 | Xem chi tiết F1 |
| F2 | Nút "Đặt hàng" ở giỏ, link "Đơn hàng của tôi" ở header | `frontend-developer` | `templates/cart/view.html`, `templates/fragments/layout.html` | 005-1, 32 | B1 (cần `cart.orderable`) | B2–B4, TW1–TW3 | Xem chi tiết F2 |
| F3 | Gate của skill, kiểm tay giao diện | `frontend-developer` | sửa nhỏ trong file của F1, F2 (chỉ class/CSS, **không đổi markup ở mục 3**) | 005-33 | B4, F1, F2 | TW4, TW5, D | Xem chi tiết F3 |
| TW1 | Test đơn vị thuần: trạng thái, form, parse, ngày giờ, API giỏ | `test-writer` | `order/OrderStatusTest`, `order/CheckoutFormValidationTest`, `order/OrderInputTest`, `common/VnDateTimeFormatterTest`, `cart/CartCheckoutTest` (mới); `cart/CartServiceTest` (sửa) | 005-1, 9, 10, 20, 21, 31; BR-005-2, 6, 8 | B3 | B4, F1–F3, TW2, TW3 | Xem chi tiết TW1 |
| TW2 | `OrderServiceTest` (Mockito) | `test-writer` | `order/OrderServiceTest` (mới) | 005-4, 5, 7, 10–14, 17, 18, 20, 21, 23, 24, 27, 28; BR-005-3–5, 8, 10–13 | B3 | B4, F1–F3, TW1, TW3 | Xem chi tiết TW2 |
| TW3 | Ma trận bảo mật và kiến trúc | `test-writer` | `config/SecurityConfigTest`, `ArchitectureTest` (sửa) | 005-6, 19, 25; spec §8 (kiến trúc) | B3, F1 (cho `/js/confirm.js`) | B4, F2, F3, TW1, TW2 | Xem chi tiết TW3 |
| TW4 | Web slice của `order`, nút đặt hàng, quét CSP | `test-writer` | `order/CheckoutControllerTest`, `order/OrderControllerTest` (mới); `cart/CartControllerTest`, `TemplateCspComplianceTest` (sửa) | 005-1, 3–5, 7, 9, 11–14, 18–24, 27, 28, 32, 33 | B4, F1, F2 | F3, TW5, D | Xem chi tiết TW4 |
| TW5 | Tích hợp PostgreSQL, luồng đăng nhập, smoke H2 | `test-writer` | `order/OrderTestData`, `order/OrderIntegrationTest`, `order/CheckoutFlowTest` (mới); `H2ProfileSmokeTest` (sửa) | 005-2, 7, 8, 11, 15–18, 20, 24, 26, 27, 29, 30, 34 | B4, F1, F2 | F3, TW4, D | Xem chi tiết TW5 |
| D | Tài liệu | phiên chính | `docs/adr/0009-order-placement-and-cancel-locking.md` (mới), `docs/adr/README.md`, `CLAUDE.md`, `README.md`, `CHANGELOG.md`, `docs/specs/005-checkout-and-orders.md`, `docs/specs/README.md` | – | B4, F1, F2, TW5 (lấy SQL thật) | F3, TW4 | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | B1–B4, F1–F3, TW1–TW5, D | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc) | 005-1 đến 005-34 | R | – | Checklist mục 5 đạt |

**Thứ tự chạy:**
- **Đợt 1:** B1 và B2 (file không giao nhau; nếu chạy hai phiên `backend-developer` song song thì phiên chính trao lượt build). F1 dựng các trang `order/*`, `confirm.js` và CSS theo mục 3; F1 chưa chạy Maven.
- **Đợt 2:** B3 (sau B1, B2). F2 (sau B1). F1 tiếp tục.
- **Đợt 3:** B4 (sau B3). TW1, TW2, TW3 (sau B3; TW3 cần thêm F1). Cùng một agent `test-writer`: làm TW2 trước (dài nhất), rồi TW1, TW3.
- **Đợt 4:** TW4 và TW5 (sau B4, F1, F2). F3 (sau B4). D bắt đầu; phần SQL của ADR-0009 chờ báo cáo TW5.
- **Cuối cùng:** R, rồi V.

**Đường găng:** B1 → B3 → B4 → TW5 → D (ADR có SQL thật) → R → V. TW5 dài nhất trong nhóm test (bốn test race 20 vòng, test rollback có khóa, đăng nhập thật).

**Không chạy hai lệnh Maven cùng lúc trong cùng thư mục** (bài học GĐ4: hai `./mvnw` cùng ghi `target/` cho lỗi biên dịch và test "ma"):
- mỗi đợt có tối đa hai agent dùng Maven, phiên chính trao lượt build cho từng agent;
- F1, F2 không chạy Maven; F3 chỉ dùng `spring-boot:run` ở bước gate, không chạy cùng lúc với `verify` của agent khác;
- `./mvnw verify` đầy đủ chỉ chạy ở cuối mỗi task backend, ở TW5 và ở V; TW1–TW4 chạy `-Dtest=…` của class mình.

**Build xanh sau mỗi task:**
- **Sau B1:** `CartCheckout`, `CheckoutLine`, `incrementStock`, `deleteItemsByUserId`, `CartView.isOrderable()` chưa ai gọi. `CartService.toLine` đổi sang `CartLineStatus.of` không đổi hành vi, `CartServiceTest` vẫn xanh. `ArchitectureTest` hiện có vẫn đúng (`cart` không gọi `incrementStock`).
- **Sau B2:** `ViewHelpersDialect` có thêm `#vnDateTime`, mọi slice test vẫn nạp được (không cần bean nào). Repository chỉ thêm query; Spring Data kiểm cú pháp JPQL lúc khởi động, nên `MiniShopApplicationTests` và `H2ProfileSmokeTest` bắt lỗi query sai ngay.
- **Sau B3:** `OrderService` là bean mới, chưa ai gọi. `order` mới phụ thuộc `cart` (đúng chiều).
- **Sau B4:** `OrderNotFoundAdvice` được nạp vào mọi `@WebMvcTest`, không cần bean. Nếu F1 chưa có template thì `/checkout`, `/orders` trả 500 khi chạy app; đây là trạng thái trung gian có chủ đích như GĐ4, không push giữa các đợt.
- **F1** có thể vào bất cứ lúc nào: template `order/*` chưa được test nào render, chỉ bị `TemplateCspComplianceTest` quét, nên phải tuân thủ CSP ngay từ đầu.
- **F2 chỉ sau B1:** `CartControllerTest` render `cart/view` với `CartView` mock; nếu template gọi `cart.orderable` trước khi có `isOrderable()` thì SpEL lỗi và test đỏ. Link header dùng `sec:authorize`, không phụ thuộc gì, nhưng làm cùng F2 cho gọn.

### Chi tiết B1 (`backend-developer`)

- **`CartLineStatus.of(active, stock, quantity)`** theo mục 3; `CartService.toLine` gọi nó thay cho chuỗi `if` hiện có. Không đổi gì khác trong `CartService`.
- **`CartView.isOrderable()`** theo mục 3 (method, không phải component).
- **`CartRepository.deleteItemsByUserId`** theo mục 3. Javadoc: "chỉ gọi sau `lockOwner` trong cùng transaction (ADR-0008)".
- **`CheckoutLine`, `CartCheckout`** theo QĐ1 và mục 3:
  - `CartCheckout` constructor injection `CartRepository`; dòng sắp theo `CartItem.id`; map `Product` → `CheckoutLine` (id, name, price, active, stock) **bên trong** transaction;
  - `lockAndRead`: `lockOwner(userId)` không có → `IllegalStateException`; rồi `findWithItemsByUserId`;
  - Javadoc của class giải thích: vì sao là API hẹp (QĐ1), vì sao `MANDATORY`, thứ tự bắt buộc `lockAndRead` → … → `clear`.
- **`ProductRepository.incrementStock`** theo mục 3, Javadoc đối xứng với `decrementStock` (vì sao không lọc `active`, vì sao `@Modifying` riêng: `stock` là `updatable = false`).
- **`package-info.java`:**
  - `cart`: thêm "API công khai hẹp cho `order`: `CartCheckout`, `CheckoutLine` (đọc giỏ DB sau khi khóa, xóa giỏ). Phần còn lại package-private.";
  - `catalog`: thêm "Chỉ `order` được trừ/cộng kho (`decrementStock`, `incrementStock`)".

**Xong khi:** `./mvnw verify` xanh; báo cáo liệt kê chữ ký public mới và xác nhận không đổi annotation mapping nào.

### Chi tiết B2 (`backend-developer`)

- **`OrderStatus`:** `canTransitionTo` (QĐ7, `switch` không `default`) và `label()` (BR-005-9). Javadoc ghi bảng chuyển trạng thái và câu "khách chỉ hủy được khi `PENDING`: quy tắc riêng trong `OrderService`".
- **`PaymentMethod.label()`:** "Thanh toán khi nhận hàng (COD)".
- **`Order.transitionTo(next)`** theo QĐ7. **Không thêm setter `setStatus`.** Không đổi field hay annotation nào.
- **`OrderRepository`:** ba query theo mục 3, Javadoc từng cái: `findByUserId` (danh sách, không N+1), `findDetailByIdAndUserId` (một câu SQL, IDOR), `lockByIdAndUserId` (QĐ6, gọi đầu tiên trong mọi transaction đổi trạng thái đơn).
- **`common.VnDateTimeFormatter`** (public): `DateTimeFormatter` là thread-safe nên giữ một hằng `static final` (`ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))`); `null` → `""`. Javadoc nêu vì sao không dùng `#temporals` (QĐ9).
- **`ViewHelpersDialect`:** thêm tên `vnDateTime`, trả một instance `VnDateTimeFormatter`; cập nhật Javadoc class.
- **`order/package-info.java`:** "Được phép dùng: `cart` (chỉ `CartCheckout`, `CheckoutLine`), `catalog`, `user`, `common`. Thứ tự khóa: đặt hàng khóa `users` rồi `products` tăng dần; đổi trạng thái khóa `orders` rồi `products` tăng dần (ADR-0009)."

**Xong khi:** `./mvnw verify` xanh (khởi động context là đủ để Spring Data kiểm cú pháp ba query); báo cáo xác nhận không đổi mapping.

### Chi tiết B3 (`backend-developer`)

- **`CheckoutForm`, `PhoneNumber`, `PhoneNumberValidator`:** theo QĐ10 và mục 3. Chữ lỗi đúng từng chữ spec §6.
- **`OrderInput`:** theo QĐ5, QĐ8, QĐ9 (`orderId`: `[0-9]{1,18}`; `amount`: `[0-9]{1,15}`; `page`: 1..10 000, sai → 1). Hàm static, thuần.
- **View record, `CancelResult`, `PlaceOrderException`:** theo QĐ3 và mục 3. Javadoc của `PlaceOrderException` giải thích vì sao unchecked (rollback mặc định) và vì sao không bắt trong service.
- **`OrderService`** (constructor injection `CartCheckout`, `OrderRepository`, `ProductRepository`, `UserRepository`):
  - `prepareCheckout`: `cartCheckout.read` → `CheckoutView` (`total` = tổng `lineTotal` mọi dòng, `orderable` = không rỗng và mọi dòng `orderable()`). Không khóa, không ghi.
  - `prefilledForm`: QĐ10.
  - `placeOrder`: đúng bảng QĐ2 và phân loại QĐ4, QĐ5. `PriceChanged` mang `CheckoutView` dựng từ chính các dòng vừa đọc. `phone` lưu `form.normalizedPhone()`. `paymentMethod` và `status` là mặc định của entity (`COD`, `PENDING`), không bao giờ lấy từ đầu vào. Tổng tiền là `BigDecimal` nguyên, `scale` 0.
  - `listOrders`, `getOrder`: QĐ9; `cancellable = customerMayCancel(status)`.
  - `cancelByCustomer`: đúng thứ tự QĐ6.
  - Log: `info` khi đặt và khi hủy thành công (chỉ id đơn, id người dùng, số dòng); `debug` khi từ chối. Không bao giờ log `form`, địa chỉ, số điện thoại, email.

**Xong khi:**
- `./mvnw verify` xanh;
- báo cáo nêu: thứ tự câu lệnh thật trong `placeOrder` và `cancelByCustomer` (đánh dấu chỗ persistence context bị xóa và vì sao code sau đó vẫn đúng); bảng ánh xạ điều kiện → exception của QĐ4.

### Chi tiết B4 (`backend-developer`)

- **`CheckoutController`, `OrderController`:** theo QĐ11, bảng QĐ3 và bảng view ở mục 3. Package-private, constructor injection `OrderService`. Không đụng repository hay entity (ArchUnit kiểm).
  - `switch` theo pattern trên `PlaceOrderException` (không `default`).
  - Id sai → `throw new NotFoundException("Order not found: " + raw)` (message tiếng Anh, chỉ cho log).
- **`OrderNotFoundAdvice`:** theo QĐ8, comment "GĐ7 gom các advice 404 lại".
- **Không sửa `SecurityConfig`.** Nếu thấy cần sửa thì dừng lại và báo phiên chính.

**Xong khi:**
- `./mvnw verify` xanh;
- app profile `h2` khởi động được, và bằng `curl`:
  - `GET /checkout`, `GET /orders`, `GET /orders/1` khi chưa đăng nhập → `302 …/login`;
  - `POST /orders/1/cancel` không có CSRF → `403`;
- báo cáo xác nhận view name, model attribute, flash attribute khớp mục 3.

### Chi tiết F1 (`frontend-developer`)

- **Đọc trước:** spec 005 §4 và §6 (bảng "Chữ hiển thị chính xác"); mục 3, QĐ12, QĐ13 của plan này; mục "UI design skill" trong `CLAUDE.md`; comment đầu `static/css/app.css`; `cart/view.html` và `user/register.html` (khuôn bảng và form đã có).
- **Skill `design-taste-frontend`, chế độ dùng lại hướng đã vẽ** (QĐ13).
- **Dựng các file theo mục 3:**
  - `order/checkout.html`: desktop hai cột (form trái, tóm tắt phải hoặc ngược lại, F1 chọn), điện thoại một cột. Form theo khuôn `auth-field` của GĐ3;
  - `order/list.html`, `order/detail.html`: bảng theo khuôn `cart-table` (thành khối dọc dưới `sm`);
  - `order/not-found.html`: theo khuôn `product/not-found.html`, chỉ có chữ của mục 3;
  - `static/js/confirm.js` theo QĐ12, có comment đầu file nói cách dùng `data-confirm`;
  - CSS mới ở cuối `app.css`, khối `/* GĐ5: thanh toán, danh sách và chi tiết đơn */`: nhãn trạng thái (chữ trên nền đặc, có chữ, đủ tương phản), `white-space: pre-line` cho địa chỉ/ghi chú, `overflow-wrap: anywhere` cho tên và địa chỉ rất dài, bố cục trang thanh toán.
- **Không** sửa `cart/view.html`, `fragments/layout.html` (thuộc F2).

**Xong khi:**
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả nào;
- `grep -rn 'action="' src/main/resources/templates` không có form POST nào dùng `action` thay vì `th:action`;
- mọi chữ hiển thị so khớp bảng spec §6 (báo cáo liệt kê từng chuỗi và file chứa nó);
- báo cáo liệt kê model attribute đã dùng trong từng template (đối chiếu mục 3).

### Chi tiết F2 (`frontend-developer`)

- `cart/view.html`: nút `Đặt hàng` hoặc dòng chữ chặn, theo mục 3, đặt sau bảng (cạnh hoặc dưới `Tổng cộng`). Nút là link GET tới `/checkout`, nên không có form mới và không ảnh hưởng quy tắc "giỏ trống không có form".
- `fragments/layout.html`: link `Đơn hàng của tôi` theo mục 3. Kiểm header vẫn xuống dòng gọn ở 375px với họ tên dài (GĐ3 đã có CSS cho việc này; chỉ chỉnh nếu cần).

**Xong khi:** hai grep của F1 vẫn sạch; báo cáo chỉ ra vị trí nút và link.

### Chi tiết F3 (`frontend-developer`)

- **Gate (sau B4, F1, F2):**
  - `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`;
  - `node <skill>/scripts/lint.mjs src/main/resources/templates src/main/resources/static/css`;
  - các trang của GĐ5 đều cần đăng nhập, mà `inspect.mjs` không giữ được phiên đăng nhập (như giỏ ở GĐ4), nên **kiểm tay** trong trình duyệt, DevTools device mode 375px, 768px, 1440px:
    - đăng nhập `user@minishop.local`, thêm 2–3 sản phẩm (một tên dài: `Giáo trình lập trình Java cơ bản`), xem `/cart` (có nút `Đặt hàng`), `/checkout` (trống, rồi gửi form rỗng để thấy lỗi dưới từng ô, rồi một địa chỉ 3 dòng dài);
    - đặt hai đơn, xem `/orders` và `/orders/{id}`; bấm `Hủy đơn hàng`, kiểm hộp xác nhận (Hủy bỏ → không gì xảy ra; OK → đã hủy);
    - xem `/orders/abc` (404);
    - Console: không lỗi CSP; Network: `confirm.js` tải từ `localhost`;
  - chụp màn hình lưu `.taste-skill/inspect/phase-5/`, tự xem mọi ảnh, tối đa 3 vòng sửa;
  - tắt app khi xong.
- Chỉ được sửa class và CSS; **không đổi chữ, cấu trúc form, `name`, `id` hay `action`** của mục 3 (TW4 dựa vào đó).

**Xong khi:** `lint.mjs` không còn `FAIL` (trừ mục ghi "sai lệch có chủ đích" kèm một câu lý do); không có thanh cuộn ngang ở 375px trên mọi trang GĐ5; báo cáo có critique của vòng cuối.

### Chi tiết TW1 (`test-writer`)

| Class | Kiểm | AC / BR |
|---|---|---|
| `OrderStatusTest` | `@ParameterizedTest` đủ **25 cặp** `(from, to)`: đúng 5 cặp `true` của AC-005-31, còn lại `false` (kể cả về chính nó, đi lùi, ra khỏi `DELIVERED`/`CANCELLED`); `label()` đúng 5 chữ BR-005-9; `Order.transitionTo` hợp lệ đổi trạng thái, không hợp lệ ném `IllegalStateException` và giữ trạng thái cũ | 005-31; BR-005-8, 9 |
| `CheckoutFormValidationTest` (Validator thuần, như `RegistrationFormValidationTest`) | hợp lệ: `0912345678`, `0912 345 678`, `0912.345.678`, `0912-345-678`, `" 0912345678 "` → `normalizedPhone()` là `0912345678`; sai: `12345`, `0abc456789`, `09123456789`, `1912345678`, `+84912345678` → đúng một lỗi "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0."; rỗng/`null`/`"   "` → đúng một lỗi "Vui lòng nhập số điện thoại."; họ tên, địa chỉ trống → chữ lỗi tương ứng; 100/101 ký tự họ tên, 500/501 địa chỉ và ghi chú → biên đúng; mọi ô bị `strip()`; ghi chú `"  "` → `null`; ký tự `\u0000` bị bỏ, `\n` trong địa chỉ được giữ; `phone()` giữ nguyên chuỗi đã gõ (đã strip); `toString()` không chứa số điện thoại, địa chỉ, ghi chú | 005-9, 10; BR-005-6 |
| `OrderInputTest` | `orderId`: `"7"`, `" 7 "` hợp lệ; `""`, `null`, `"abc"`, `"-1"`, `"1.5"`, `"99999999999999999999"` rỗng. `amount`: `"450000"` hợp lệ; `"450000.5"`, `"-1"`, `"abc"`, `""`, `null`, 16 chữ số → rỗng. `page`: `null`, `"abc"`, `"0"`, `"-3"` → 1; `"2"` → 2; `"999999"` → 10 000 | 005-21, 24; BR-005-5 |
| `VnDateTimeFormatterTest` | `2026-10-24T17:30:00Z` → `25/10/2026 00:30` (qua nửa đêm giờ Việt Nam); kết quả giống nhau khi `TimeZone.setDefault(UTC)` và `America/New_York` (khôi phục ở `@AfterEach`); `null` → `""` | 005-20 |
| `CartCheckoutTest` (Mockito) | `lockAndRead`: `lockOwner` được gọi **trước** `findWithItemsByUserId` (`InOrder`); không có user → `IllegalStateException`; dòng sắp theo `CartItem.id`; không có giỏ → rỗng. `read`: **không** gọi `lockOwner`. `clear`: gọi `deleteItemsByUserId`. `CheckoutLine.orderable()`: bảng (active, stock, quantity) → `true` chỉ khi active, stock > 0, quantity ≤ stock; `lineTotal()` | BR-005-1, 2, 3 |
| `CartServiceTest` (thêm) | `CartView.isOrderable()`: mọi dòng `AVAILABLE` → `true`; có `LOW_STOCK` / `OUT_OF_STOCK` / `DISCONTINUED` → `false`; giỏ trống → `false`. `CartLineStatus.of` khớp đúng thứ tự xét của GĐ4 | 005-1 |

**Xong khi:** `./mvnw test -Dtest='OrderStatusTest,CheckoutFormValidationTest,OrderInputTest,VnDateTimeFormatterTest,CartCheckoutTest,CartServiceTest'` xanh.

### Chi tiết TW2 (`test-writer`)

**`OrderServiceTest`** (Mockito: `CartCheckout`, `OrderRepository`, `ProductRepository`, `UserRepository` là mock; `orderRepository.save` gán id bằng `ReflectionTestUtils`; `Order` dựng bằng constructor package-private; `CheckoutLine` dựng trực tiếp):

| Kiểm | AC / BR |
|---|---|
| `prepareCheckout`: hai dòng → tên, số lượng, đơn giá, thành tiền, tổng; `orderable` `true`; dùng `read`, **không** gọi `lockAndRead` | 005-3 |
| `prepareCheckout`: giỏ rỗng → `isEmpty()`; một dòng ẩn / kho 0 / số lượng > kho → `orderable` `false` | 005-4, 5 |
| `prefilledForm`: họ tên và số điện thoại của user; `phone` `null` → `""` | 005-3 |
| **Đặt thành công** {A: 2 × 100 000, kho 5; B: 1 × 250 000, kho 1}, `expectedTotal` 450 000, phone `0912 345 678`: `InOrder` `lockAndRead` → `save` → `decrementStock(A, 2)` → `decrementStock(B, 1)` → `clear(userId)`; `Order` bắt bằng captor: `PENDING`, `COD`, tổng 450 000, hai dòng với tên/giá/số lượng đúng, `phone` `0912345678`, người nhận, địa chỉ, ghi chú; trả id | 005-7, 10; BR-005-3, 12, 13 |
| Giỏ theo thứ tự thêm {B (id 9), A (id 3)} → `decrementStock(3, …)` trước `decrementStock(9, …)` | 005-17; BR-005-4 |
| Giỏ rỗng → `EmptyCart`; không `save`, không `decrementStock`, không `clear` | 005-14 |
| Dòng đã ẩn → `NotOrderable` (dù dòng khác vượt kho); không ghi gì | 005-12 |
| Số lượng > kho, và kho = 0 (QĐ4) → `InsufficientStock` với tên đúng dòng có `productId` nhỏ nhất; không ghi gì | 005-11, 12 |
| `decrementStock` của dòng thứ hai trả 0 → `InsufficientStock` (tên dòng đó); `clear` **không** được gọi (rollback thật nằm ở TW5) | 005-11 |
| `expectedTotal` 400 000 / `null` → `PriceChanged`, `current().total()` = 450 000, không ghi gì; `new BigDecimal("450000.0")` → đặt được (`compareTo`) | 005-13; BR-005-5 |
| Ghi chú `null` → `note` `null`; `userId` của đơn luôn là tham số (`userRepository.getReferenceById(userId)`) | 005-18 |
| `listOrders(u, "abc")` → `PageRequest` trang 0, cỡ 10, sắp `createdAt` DESC rồi `id` DESC; `"2"` → trang 1; map nhãn `Chờ xử lý`, `statusKey` `pending`; trang vượt quá (rỗng mà `totalElements > 0`) → truy vấn lại trang 0 | 005-20, 21 |
| `getOrder`: có → view với dòng sắp theo id, `cancellable` `true` chỉ khi `PENDING`, nhãn phương thức thanh toán; không có (kể cả của người khác: repository trả rỗng) → `NotFoundException` | 005-23, 24 |
| `cancelByCustomer` `PENDING` {B (id 9): 1, A (id 3): 2} → trạng thái `CANCELLED`, `incrementStock(3, 2)` rồi `incrementStock(9, 1)`, trả `CANCELLED` | 005-27; BR-005-10 |
| `CONFIRMED`, `SHIPPING`, `DELIVERED`, `CANCELLED` → `NOT_CANCELLABLE`, không `incrementStock`, trạng thái không đổi | 005-28; BR-005-8 |
| Không có đơn → `NotFoundException`, không `incrementStock`; `incrementStock` trả 0 → `IllegalStateException` | 005-24 |
| `placeOrder` không bao giờ gọi `incrementStock`; `cancelByCustomer` không bao giờ gọi `decrementStock` hay `cartCheckout` | BR-005-10 |

**Xong khi:** `./mvnw test -Dtest=OrderServiceTest` xanh; báo cáo ghi chỗ khác cột "Kiểm bằng" của spec (nếu có).

### Chi tiết TW3 (`test-writer`)

**`SecurityConfigTest`:** giữ mọi test hiện có, thêm:

| Kiểm | AC |
|---|---|
| Chưa đăng nhập: `GET /orders`, `get("/orders?page=2")`, `/orders/abc`, `get("/checkout?continue")` → redirect `/login` | 005-25, 6 |
| Chưa đăng nhập, có CSRF: `POST /checkout`, `POST /orders/1/cancel` → redirect `/login` | 005-6 |
| `@WithMockUser`, không CSRF: `POST /checkout`, `POST /orders/1/cancel` → 403, `forwardedUrl("/access-denied")` | 005-19 |
| Chưa đăng nhập: `GET /js/confirm.js` → 200 (tài nguyên tĩnh công khai, không bị chuyển sang `/login`) | QĐ12 |

**`ArchitectureTest`:** giữ mọi rule, thêm:
- `order_dependsOnlyOnAllowedPackages`: `order` không phụ thuộc `admin`, `config`, `storage`;
- `order_usesOnlyCartCheckoutApi`: lớp trong `order` chỉ được phụ thuộc lớp `cart` có tên `CartCheckout` hoặc `CheckoutLine` (QĐ1);
- `onlyOrderChangesStock`: không lớp nào ngoài `com.minishop.order..` và `com.minishop.catalog..` gọi `ProductRepository.decrementStock(Long, int)` hay `incrementStock(Long, int)` (spec §8).

**Xong khi:** `./mvnw test -Dtest='SecurityConfigTest,ArchitectureTest'` xanh.

### Chi tiết TW4 (`test-writer`)

**`CheckoutControllerTest`:** `@WebMvcTest(CheckoutController.class)` + `@Import({SecurityConfig.class, CartHeaderTestConfig.class})` + `@MockitoBean OrderService`; người dùng `.with(user(TestUsers.user()))` (và `admin()` ở các case ghi rõ); POST luôn `.with(csrf())` trừ khi ghi rõ.

| Kiểm | AC |
|---|---|
| `GET /checkout` (USER và ADMIN): 200, view `order/checkout`, title `Thanh toán \| Mini Shop`, `h1` `Thanh toán`; bốn nhãn và `label for` khớp `id`; giá trị điền sẵn; `Phương thức thanh toán`, `Thanh toán khi nhận hàng (COD)`, không có `type="radio"` hay `<select`; `Đơn hàng của bạn`, bốn tiêu đề cột, `1.250.000 ₫`, `Tổng cộng`; `name="expectedTotal" value="450000"`; nút `Xác nhận đặt hàng` nằm trong form `action="/checkout"` có `_csrf`; link `Quay lại giỏ hàng` tới `/cart` | 005-3; BR-005-7 |
| Giỏ trống → redirect `/cart`, không có flash `notice` | 005-4 |
| `orderable` `false` → redirect `/cart`, flash `error` "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng." | 005-5 |
| `POST` hợp lệ, service trả 42 → redirect `/orders/42`, flash `success` "Đặt hàng thành công. Mã đơn của bạn là #42."; service nhận `(2L, form, 450000)` | 005-7 |
| Từng ô sai (họ tên trống, phone `12345` / `0abc456789` / `09123456789` / trống, địa chỉ trống, họ tên 101 ký tự, ghi chú 501 ký tự) → 200, view `order/checkout`, đúng chữ lỗi, `aria-invalid="true"`, `aria-describedby` trỏ đúng `id`; giá trị đã gõ còn nguyên (kể cả `0abc456789`); `placeOrder` **không** được gọi | 005-9 |
| Form sai và `expectedTotal` khác tổng mới → có thêm "Giá sản phẩm vừa thay đổi…" | BR-005-5 |
| Service ném `EmptyCart` → redirect `/orders`, flash `info` "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo." | 005-14 |
| `NotOrderable` → redirect `/cart` với chữ AC-005-5; `InsufficientStock("Áo thun")` → redirect `/cart`, "Sản phẩm Áo thun không đủ hàng. Vui lòng cập nhật giỏ hàng." | 005-11, 12 |
| `PriceChanged(view tổng 460 000)` → 200, view `order/checkout`, chữ giá đổi, giá mới, `value="460000"`, giá trị form đã gõ còn nguyên | 005-13 |
| Thêm `userId=1&status=DELIVERED&totalAmount=1&unitPrice=1&paymentMethod=X&id=5` → service vẫn nhận `2L`; form bắt bằng captor chỉ có bốn ô đã gõ | 005-18 |
| `Referer: https://evil.example/` và `returnUrl=…` → vẫn redirect `/orders/42` | spec §6 |
| `POST` không CSRF → 403; service không được gọi | 005-19 |
| Tên sản phẩm `<script>alert(1)</script>` được escape | 005-33 |

**`OrderControllerTest`:** cấu hình như trên.

| Kiểm | AC |
|---|---|
| `GET /orders` với 3 đơn: 200, view `order/list`, title `Đơn hàng của tôi \| Mini Shop`, `h1`, bốn tiêu đề cột, link `#12` tới `/orders/12`, `25/10/2026 00:30` (từ `2026-10-24T17:30:00Z`), tiền VND, `Chờ xử lý`; service nhận `(2L, rawPage)` | 005-20 |
| `totalPages` 3, trang 2 → link `/orders?page=1` và `/orders?page=3`; `page=abc` được chuyển nguyên chuỗi cho service | 005-21 |
| Không có đơn → `Bạn chưa có đơn hàng nào.`, link `Bắt đầu mua sắm` tới `/products`, không có `<table` | 005-22 |
| `GET /orders/12`: title `Đơn hàng #12 \| Mini Shop`, mã, ngày, trạng thái, `Thanh toán khi nhận hàng (COD)`, `Thông tin nhận hàng`, người nhận, số điện thoại, địa chỉ, ghi chú (có/không tùy `note`), bảng dòng, `Tổng cộng`, link `Quay lại danh sách đơn`, `<script src="/js/confirm.js"` | 005-23 |
| `cancellable` `true` → form `action="/orders/12/cancel"` có `_csrf`, `data-confirm="Bạn có chắc muốn hủy đơn hàng này?"`, nút `Hủy đơn hàng`; `false` → không có `Hủy đơn hàng` | 005-27, 28 |
| Service ném `NotFoundException` → 404, view `order/not-found`, `Không tìm thấy đơn hàng` (USER và ADMIN); `/orders/abc`, `/orders/99999999999999999999`, `POST /orders/abc/cancel` → 404, service không được gọi | 005-24 |
| `POST /orders/12/cancel`: `CANCELLED` → redirect `/orders/12`, flash `success` "Đã hủy đơn hàng #12."; `NOT_CANCELLABLE` → "Đơn hàng này không thể hủy nữa."; `NotFoundException` → 404 | 005-27, 28, 24 |
| Header có `href="/orders"` chữ `Đơn hàng của tôi` (USER và ADMIN) | 005-32 |
| Địa chỉ, ghi chú, tên sản phẩm chứa `<script>` được escape | 005-33 |
| `POST /orders/12/cancel` không CSRF → 403 | 005-19 |

**`CartControllerTest`** (thêm): mọi dòng `AVAILABLE` → link `href="/checkout"` chữ `Đặt hàng` (khách và `TestUsers.user()`); có một dòng `LOW_STOCK` / `OUT_OF_STOCK` / `DISCONTINUED` → không có `href="/checkout"`, có chữ chặn; giỏ trống → không có cả hai; khách → header không có `Đơn hàng của tôi` (AC-005-1, 32).

**`TemplateCspComplianceTest`:** thêm `templates/order/checkout.html`, `list.html`, `detail.html`, `not-found.html` vào danh sách bắt buộc quét được.

**Xong khi:** các class trên xanh; báo cáo ghi chỗ khác cột "Kiểm bằng" của spec (AC-005-1 một phần nằm ở `CartServiceTest`).

### Chi tiết TW5 (`test-writer`)

**`order/OrderTestData`** (như `CartTestData`): `createUser()` (email `…@order.test`, mật khẩu đã hash, có `PASSWORD`), `createProduct(suffix, price, stock)` (tên `Order test …`), `setActive`, `setStock`, `setPrice`, `setName`, `createCart(userId, long[]...)`, `cartLines(userId)`, `stock(productId)`, `orderCount(userId)`, `orderRow(orderId)` (map cột), `orderItems(orderId)`, `cleanUp()` theo thứ tự QĐ14.

**`order/OrderIntegrationTest`** (cấu hình QĐ14; `@Autowired OrderService`, `CartCheckout`, `JdbcTemplate`, `DataSource`, `EntityManagerFactory`):

| Kiểm | AC |
|---|---|
| `POST /checkout` (MockMvc, `user(new CurrentUser(...))`, CSRF) với {A: 2 × 100 000, kho 5; B: 1 × 250 000, kho 1}, `expectedTotal=450000` → redirect `/orders/{id}`; đúng **một** dòng `orders` (`PENDING`, `COD`, 450 000, `phone` `0912345678` khi gõ `0912 345 678`), hai `order_items` đúng; kho A 3, B 0; giỏ trống; `GET /orders/{id}` có chữ thành công và `>Giỏ hàng</a>` (không có `Giỏ hàng (`) | 005-7, 10 |
| Sau khi đặt, đổi giá và tên A bằng JDBC → chi tiết đơn vẫn tên cũ, 100 000 ₫, tổng cũ | 005-8 |
| **Rollback giữa chừng tất định** (QĐ14): đơn trả `InsufficientStock` (tên B); kho A vẫn 5, không có đơn, giỏ nguyên | 005-11 |
| **Race cùng người** (20 vòng): hai luồng `placeOrder` cùng giỏ → đúng một id, một `EmptyCart`; một đơn; kho trừ một lần | 005-15 |
| **Race hai người, kho 1** (20 vòng) → đúng một đơn, người kia `InsufficientStock` (QĐ4); `stock` 0, không bao giờ âm | 005-16 |
| **Race thứ tự ngược** (20 vòng): U1 giỏ thêm A rồi B, U2 thêm B rồi A, kho 50 → cả hai đặt được, không exception (không deadlock); kho 48 mỗi sản phẩm | 005-17 |
| Tham số thừa qua MockMvc → dòng `orders` thuộc người đăng nhập, `PENDING`, `COD`, tổng tính từ DB | 005-18 |
| U1 có 3 đơn, U2 có 1 → `/orders` của U1 có đúng 3 mã, mới nhất trước, không có mã của U2 | 005-20 |
| U2 (và một principal `ADMIN` tạo riêng) `GET /orders/{đơn U1}` và `POST …/cancel` → 404; đơn và kho không đổi | 005-24 |
| Đếm truy vấn: `listOrders` với 3 đơn × 2 dòng → đúng 2 câu; `getOrder` → ≤ 2 câu (mong đợi 1) | 005-26 |
| Đơn `PENDING` A: 2 (kho đang 3) → `POST /orders/{id}/cancel` → `CANCELLED`, kho 5, redirect và chữ "Đã hủy đơn hàng #{id}."; chi tiết không còn `Hủy đơn hàng` | 005-27 |
| Ẩn A sau khi đặt rồi hủy → hủy được, kho A được cộng lại | 005-29 |
| **Race hủy** (20 vòng): hai luồng `cancelByCustomer` cùng đơn → một `CANCELLED`, một `NOT_CANCELLABLE`, không exception; kho cộng đúng một lần | 005-30 |
| Gọi `cartCheckout.lockAndRead(id)` ngoài transaction → `IllegalTransactionStateException` | QĐ1 |

Đặt và hủy ở trên chạy trên PostgreSQL thật: phần PostgreSQL của AC-005-34.

**`order/CheckoutFlowTest`** (cùng cấu hình; đăng nhập thật bằng `formLogin("/login").userParameter("email")`, gắn session như helper của `CartMergeIntegrationTest`, chép helper, không phụ thuộc chéo): khách `POST /cart/items` sản phẩm test, bấm `GET /checkout` → `302 /login`; đăng nhập với cùng session → redirect có path bắt đầu `/checkout`; `GET` URL đó bằng session mới → 200, có tên sản phẩm, `Xác nhận đặt hàng`; giỏ DB của user có dòng đó (AC-005-2).

**`H2ProfileSmokeTest`** (thêm một test): đăng nhập `user@minishop.local`; ghi lại `stock` của một sản phẩm seed còn hàng; `POST /cart/items` 1 cái; `GET /checkout` 200; `POST /checkout` (`expectedTotal` = giá đọc bằng JDBC) → redirect `/orders/{id}`; kho giảm 1; `POST /orders/{id}/cancel` → kho về như cũ, `status` `CANCELLED`. `finally`: xóa `order_items`, `orders`, `cart_items`, `carts` của user seed, đặt lại `stock`. Như vậy `for update` trên `orders`, `decrementStock`, `incrementStock` và câu xóa giỏ chạy được trên H2 (phần H2 của AC-005-34).

**Xong khi:**
- `./mvnw verify` xanh; `bash .github/scripts/check-no-skipped-tests.sh` exit 0;
- báo cáo liệt kê test theo từng AC, thời gian chạy của từng test race và test rollback;
- báo cáo có câu SQL thật Hibernate sinh ra trên PostgreSQL cho `lockByIdAndUserId`, `decrementStock`, `incrementStock`, `deleteItemsByUserId` (chạy một lần với `-Dlogging.level.org.hibernate.SQL=debug` trên dòng lệnh, **không** sửa file cấu hình), để ghi vào ADR-0009.

### Chi tiết D (phiên chính)

- **`docs/adr/0009-order-placement-and-cancel-locking.md`** (theo `template.md`): nội dung QĐ2, QĐ6, QĐ15, kèm SQL thật từ TW5. Thêm dòng vào bảng của `docs/adr/README.md`. ADR-0005, 0008 giữ nguyên (không bị thay thế).
- **`CLAUDE.md`:**
  - **Project status:** GĐ5;
  - **"Visibility today":** thêm `cart.CartCheckout`, `cart.CheckoutLine`, `common.VnDateTimeFormatter`; `order` vẫn package-private; bỏ câu "GĐ5 will need a narrow public cart API" (đã làm);
  - **mục mới "Order patterns (GĐ5)":** thứ tự `placeOrder` và bẫy `clearAutomatically`; `PlaceOrderException` unchecked và bắt ở controller; trừ/cộng kho theo `product_id` tăng dần; đổi trạng thái đơn = `lockByIdAndUserId` + `transitionTo`; truy vấn đơn luôn lọc `user_id`; `#vnDateTime`; `confirm.js` + `data-confirm`; `OrderNotFoundAdvice` chờ GĐ7 gom;
  - **"Domain invariants":** thêm hai quy tắc của ADR-0009.
- **`README.md`:** trạng thái GĐ5, mô tả đặt hàng; đánh dấu GĐ5 ở Lộ trình **sau khi V đạt**.
- **`CHANGELOG.md`:** ghi vào `[Unreleased]` (Thêm / Thay đổi / Bảo mật). Chuyển thành `[0.5.0]` và gắn tag sau khi người dùng duyệt giai đoạn.
- **Spec 005 và `docs/specs/README.md`:** `Đang làm` khi bắt đầu triển khai, `Hoàn thành` sau khi V đạt; nếu người dùng chọn khuyến nghị ở câu hỏi 1, sửa câu chữ AC-005-12 ("ẩn → chữ AC-005-5; hết hàng hoặc vượt kho → chữ AC-005-11") và ghi lịch sử thay đổi, kèm các quyết định biên ở mục 6 câu 3.

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

Docker **đang bật**. Trước mục 2, chụp `select id, name, stock, price, active from products order by id` và `select coalesce(max(id), 0) from orders` để so và khôi phục ở mục 30.

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh` | BUILD SUCCESS; script exit 0; có đủ `OrderStatusTest`, `CheckoutFormValidationTest`, `OrderInputTest`, `VnDateTimeFormatterTest`, `CartCheckoutTest`, `OrderServiceTest`, `CheckoutControllerTest`, `OrderControllerTest`, `OrderIntegrationTest`, `CheckoutFlowTest` |
| 2 | Không có migration mới | `./mvnw spring-boot:run` trên DB dev cũ; `select max(version) from flyway_schema_history` | App lên; version vẫn là `2` |
| 3 | AC-005-1 | Khách thêm một sản phẩm còn hàng → `/cart`; rồi psql đặt `stock` của nó nhỏ hơn số lượng trong giỏ, tải lại; xóa hết giỏ | Có nút `Đặt hàng` → `/checkout`; khi vượt kho: không có nút, có chữ `Vui lòng xóa hoặc giảm số lượng…`; giỏ trống: không có cả hai |
| 4 | AC-005-2 | Cửa sổ ẩn danh: khách thêm 2 sản phẩm, bấm `Đặt hàng`, đăng nhập `user@minishop.local` | Về `/checkout?continue`, trang thanh toán có đúng 2 sản phẩm đó |
| 5 | AC-005-3 | Xem `/checkout` | Tiêu đề tab `Thanh toán \| Mini Shop`; họ tên điền sẵn `Khách hàng mẫu` (hoặc tên seed); bốn ô có nhãn; `Thanh toán khi nhận hàng (COD)` là chữ, không có lựa chọn; bảng tóm tắt, tiền `1.250.000 ₫`, `Tổng cộng`; nút `Xác nhận đặt hàng`; link `Quay lại giỏ hàng` |
| 6 | AC-005-4, 5 (âm) | Giỏ trống: mở `/checkout`. Giỏ có một sản phẩm psql đặt `active=false`: mở `/checkout` | Lần 1 về `/cart` "Giỏ hàng của bạn đang trống."; lần 2 về `/cart` với "Giỏ hàng có sản phẩm không đủ hàng…"; `select count(*) from orders` không đổi |
| 7 | AC-005-9 (âm) | Gửi form với: mọi ô trống; phone `12345`, `0abc456789`, `09123456789`; DevTools gỡ `maxlength` rồi nhập họ tên 101 ký tự, ghi chú 501 ký tự | HTTP 200, đúng chữ lỗi dưới từng ô, giá trị đã gõ còn nguyên; không có đơn mới; kho và giỏ không đổi |
| 8 | AC-005-7, 10 | Giỏ {A: 2, B: 1}, phone `0912 345 678`, địa chỉ 2 dòng, có ghi chú → `Xác nhận đặt hàng` | Về `/orders/{id}` với "Đặt hàng thành công. Mã đơn của bạn là #{id}."; header `Giỏ hàng` (không số); psql: một đơn `PENDING`, `COD`, `phone` `0912345678`, tổng đúng, `order_items` đúng tên và giá; kho A, B giảm đúng; `cart_items` của user trống |
| 9 | AC-005-8 | psql đổi `name` và `price` của A; tải lại chi tiết đơn | Vẫn tên cũ, giá cũ, tổng cũ |
| 10 | AC-005-11 (âm) | Giỏ A: 2 (kho ≥ 2), mở `/checkout`; psql `update products set stock = 1 where id = A`; bấm xác nhận | Về `/cart` với "Sản phẩm {tên A} không đủ hàng. Vui lòng cập nhật giỏ hàng."; không có đơn mới; kho mọi sản phẩm không đổi; giỏ nguyên |
| 11 | AC-005-12 (âm) | Mở `/checkout`; psql ẩn một sản phẩm trong giỏ; xác nhận | Về `/cart` với chữ của AC-005-5; không có đơn; kho, giỏ không đổi |
| 12 | AC-005-13 | Mở `/checkout`; psql `update products set price = price + 1000 where id = B`; xác nhận; rồi xác nhận lần nữa | Lần 1: trang thanh toán hiện lại (200) với giá mới, "Giá sản phẩm vừa thay đổi…", giá trị form còn nguyên; lần 2: đặt thành công với giá mới |
| 13 | AC-005-14, 15 | Mở `/checkout` ở hai tab; xác nhận tab 1, rồi tab 2. Bấm `Xác nhận đặt hàng` hai lần thật nhanh ở một đơn mới | Tab 2 về `/orders` với "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo."; bấm đôi: đúng một đơn mới; không trang 500 |
| 14 | AC-005-15, 16, 17, 30 | Đọc kết quả bốn test race ở mục 1 (thời gian, 20 vòng); psql `select id, stock from products where stock < 0` | Bốn test xanh; truy vấn không trả dòng nào |
| 15 | AC-005-18 (âm) | DevTools thêm vào form thanh toán các ô ẩn `userId` (id admin), `status=DELIVERED`, `totalAmount=1`, `unitPrice=1`, `paymentMethod=CARD`, `id=1`; xác nhận | Đơn thuộc `user@minishop.local`, `PENDING`, `COD`, giá và tổng đúng giá DB |
| 16 | AC-005-19 (âm) | DevTools xóa ô `_csrf` của form thanh toán và form hủy, rồi gửi | Cả hai: 403 `Bạn không có quyền truy cập trang này.`; không có đơn mới, đơn không đổi |
| 17 | AC-005-20, 22 | `/orders` của `user@minishop.local`; `/orders` của `admin@minishop.local` (chưa đặt đơn nào) | Tiêu đề tab `Đơn hàng của tôi \| Mini Shop`; cột `Mã đơn` (`#id`, link), `Ngày đặt` khớp `select to_char(created_at at time zone 'Asia/Ho_Chi_Minh', 'DD/MM/YYYY HH24:MI') from orders`, `Tổng tiền`, `Trạng thái` tiếng Việt; mới nhất trên cùng. Admin: "Bạn chưa có đơn hàng nào." và `Bắt đầu mua sắm` |
| 18 | AC-005-21 | psql thêm 11 đơn cho user: `insert into orders (user_id, status, receiver_name, phone, shipping_address, payment_method, total_amount) select <uid>, 'PENDING', 'Kiểm tra', '0912345678', 'Địa chỉ', 'COD', 100000 from generate_series(1, 11)`; mở `/orders`, `/orders?page=2`, `/orders?page=abc`, `/orders?page=999` | 10 đơn/trang, có phân trang; `abc` và `999` hiện trang 1, không lỗi |
| 19 | AC-005-23 | Mở chi tiết đơn ở mục 8 | Tiêu đề tab `Đơn hàng #{id} \| Mini Shop`; mã, ngày, trạng thái, `Thanh toán khi nhận hàng (COD)`, người nhận, số điện thoại, địa chỉ giữ xuống dòng, ghi chú; bảng dòng, `Tổng cộng`; link `Quay lại danh sách đơn` |
| 20 | AC-005-24 (âm) | Admin đặt một đơn. Bằng `user@minishop.local`: mở `/orders/{đơn của admin}`, `/orders/abc`, `/orders/999999`; DevTools đổi `action` của form hủy sang `/orders/{đơn của admin}/cancel` rồi gửi. Bằng admin: mở `/orders/{đơn của user}` | Tất cả: HTTP 404, trang `Không tìm thấy đơn hàng`; đơn của admin không đổi |
| 21 | AC-005-25 | Cửa sổ ẩn danh mở `/orders`, `/orders/1`, `/checkout` | Chuyển sang `/login` |
| 22 | AC-005-26 | Đọc kết quả test đếm truy vấn ở mục 1 | Xanh: danh sách 2 câu, chi tiết ≤ 2 câu |
| 23 | AC-005-27 | Đơn `PENDING` (ghi lại kho của từng sản phẩm): bấm `Hủy đơn hàng`, chọn **Hủy bỏ** ở hộp xác nhận; rồi bấm lại và chọn **OK** | Lần 1 không có gì xảy ra; lần 2: "Đã hủy đơn hàng #{id}.", trạng thái `Đã hủy`, không còn nút hủy; kho cộng lại đúng |
| 24 | Hủy khi tắt JS | DevTools > Settings > Disable JavaScript; hủy một đơn `PENDING` khác | Không có hộp thoại, đơn vẫn hủy được |
| 25 | AC-005-28 (âm) | psql `update orders set status = 'CONFIRMED' where id = …` (rồi `SHIPPING`, `DELIVERED`); mở chi tiết; DevTools chép form hủy từ một đơn `PENDING` khác, đổi `action` sang đơn này, gửi | Không có nút hủy; gửi tay: "Đơn hàng này không thể hủy nữa.", trạng thái và kho không đổi |
| 26 | AC-005-29 | Đặt đơn có A; psql `active=false` cho A; hủy đơn | Hủy được; kho A được cộng lại |
| 27 | AC-005-32 | Header khi là khách, `USER`, `ADMIN` trên `/`, `/products/{id}`, `/cart`, `/orders` | Khách không có `Đơn hàng của tôi`; `USER`, `ADMIN` có, link tới `/orders` |
| 28 | AC-005-33 | Device mode 375px, 768px, 1440px: `/checkout` (có lỗi form, tên dài), `/orders`, `/orders/{id}` (địa chỉ dài); Console và Network; xem mã HTML | Không có thanh cuộn ngang; không lỗi CSP; mọi tài nguyên từ `localhost`, có `/js/confirm.js`; mọi ô có `label`, lỗi gắn `aria-describedby`; bảng có `caption`/`th scope`; nhập `<script>alert(1)</script>` vào địa chỉ và ghi chú → hiện đúng chữ đó, không chạy |
| 29 | AC-005-34 | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`: đăng nhập, thêm giỏ, đặt, xem danh sách và chi tiết, hủy | Giống PostgreSQL; kho trừ rồi cộng lại đúng |
| 30 | Dọn dẹp | psql: xóa `order_items` và `orders` có `id` lớn hơn giá trị chụp đầu; khôi phục `products` (tên, giá, kho, `active`) theo ảnh chụp; xóa giỏ test; tắt mọi app | Dữ liệu seed như cũ; không còn tiến trình; container Postgres đã dừng |

## 6. Câu hỏi cho người dùng

> **Đã trả lời (2026-10-24):** người dùng duyệt kế hoạch. Câu 1: chọn khuyến nghị (ngừng bán → chữ chung; hết hàng hoặc thiếu → chữ có tên sản phẩm); AC-005-12 đã được sửa trong spec. Câu 2: cuối giai đoạn commit, push và mở PR, chưa merge.

Không câu nào chặn việc bắt đầu triển khai: plan làm theo khuyến nghị, và câu 1 chỉ chạm một điều kiện trong `OrderService`, một kỳ vọng của test và một câu trong spec.

1. **Spec có một chỗ mâu thuẫn giữa AC-005-12 và AC-005-16** (QĐ4). AC-005-12: sản phẩm "hết hàng ngay sau đó" thì báo chữ chung "Giỏ hàng có sản phẩm không đủ hàng…". AC-005-16: khi hai người tranh nhau món cuối, người thua nhận chữ có tên "Sản phẩm {tên} không đủ hàng…". Nhưng nếu người thua bấm chậm hơn một chút, lúc đó món hàng đã "hết hàng" (`stock = 0`), nên theo AC-005-12 phải là chữ chung.
   - **Khuyến nghị:** lúc xác nhận, sản phẩm **đã ngừng bán** → chữ chung; **hết hàng hoặc không đủ số lượng** → chữ có tên sản phẩm. Khách biết ngay món nào cần sửa, và AC-005-16 luôn đúng. Cần sửa câu chữ AC-005-12 trong spec (task D).
   - Phương án khác: giữ đúng chữ AC-005-12; khi đó người thua cuộc đua nhận một trong hai chữ tùy thời điểm, và test AC-005-16 chấp nhận cả hai.
   - Trang `GET /checkout` không đổi: mọi trường hợp đều dùng chữ chung (AC-005-5).
2. **Cuối giai đoạn có commit, push và mở PR `phase-5/checkout-orders` → `main` không?** Khuyến nghị: **có, nhưng chưa merge**, giống GĐ3–4. Các agent không tự commit.
3. **Chỉ để bạn biết** (các trường hợp biên spec chưa nói, plan đã chọn):
   - thiếu hoặc sửa tay ô ẩn `expectedTotal` được coi như "giá vừa thay đổi": khách xem lại trang với giá hiện tại rồi xác nhận lại;
   - nếu form có lỗi **và** giá vừa đổi, trang hiện cả lỗi lẫn thông báo giá đổi;
   - `/orders?page=999` (vượt quá số trang) hiện trang 1, như tham số sai;
   - sau khi đặt hàng, các dòng giỏ bị xóa nhưng dòng `carts` được giữ (như khi xóa dòng cuối ở GĐ4);
   - loại thông báo: đặt và hủy thành công là `success`; "giỏ đang trống" sau khi bấm hai lần là `info`; còn lại (`không đủ hàng`, `giá đổi`, `không thể hủy`) là `error` (đọc ngay cho trình đọc màn hình);
   - ký tự điều khiển (ví dụ byte `0x00`) trong form bị bỏ đi để không gây trang 500 trên PostgreSQL; xuống dòng trong địa chỉ và ghi chú được giữ;
   - `confirm.js` chỉ được tải ở trang chi tiết đơn; GĐ6 có thể dùng lại cho các thao tác xóa của admin;
   - có **ADR-0009** mới: thứ tự khóa khi đặt và hủy đơn, và mọi chuyển trạng thái đơn khóa dòng `orders` trước. GĐ6 (admin đổi trạng thái) phải theo quy tắc này.

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Index ghép `orders (user_id, created_at)`** (migration `V3`): chỉ đáng làm khi một người có hàng nghìn đơn; hiện chưa cần.
- **`lock_timeout` cho các câu khóa** (`users`, `orders`, `products`): đã ghi ở ADR-0008 cho GĐ8; nay áp dụng cho cả đặt và hủy đơn.
- **Gom `ProductNotFoundAdvice`, `CartNotFoundAdvice`, `OrderNotFoundAdvice` thành trang lỗi chung**, và trang riêng khi CSRF token hết hạn (form thanh toán để lâu sẽ trả 403): GĐ7.
- **Nhận số điện thoại dạng `+84…`:** spec đã để dành cho GĐ7.
- **Trang quản lý đơn của admin, admin hủy đơn `CONFIRMED`:** GĐ6 (dùng lại `canTransitionTo`, `lockByIdAndUserId`-kiểu khóa, `incrementStock`, `confirm.js`).
- **Email xác nhận đơn, thanh toán online, phí ship, mã giảm giá, mua lại đơn cũ, lọc đơn theo trạng thái, lưu địa chỉ vào hồ sơ:** đều nằm trong "Loại ra" của brief hoặc ngoài phạm vi spec 005.

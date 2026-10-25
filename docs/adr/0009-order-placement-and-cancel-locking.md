# ADR-0009: Đặt và hủy đơn: thứ tự khóa cố định, chuyển trạng thái đơn qua khóa dòng `orders`

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-24

## Bối cảnh

GĐ5 ([spec 005](https://github.com/Luffyreals/mini-shop/blob/v0.8.0/docs/specs/005-checkout-and-orders.md)) thêm hai thao tác ghi vào tồn kho:
- **Đặt hàng** đọc giỏ DB, tạo đơn, trừ kho từng dòng, rồi xóa giỏ.
- **Khách hủy đơn** `PENDING`: đổi trạng thái và cộng lại kho.

Hai ADR trước vẫn đúng và không bị thay thế:
- [ADR-0005](0005-atomic-stock-decrement.md): trừ kho bằng `UPDATE … WHERE stock >= :q`; 0 dòng bị ảnh hưởng thì rollback cả đơn.
- [ADR-0008](0008-cart-writes-serialized-per-user.md): mọi thao tác ghi giỏ DB khóa dòng `users` của người đó trước; `placeOrder` cũng vậy.

Còn thiếu ba quy tắc mà spec đòi hỏi (AC-005-15, 16, 17, 30) và GĐ6 phải theo:
1. Câu `UPDATE` có điều kiện cũng **khóa dòng** sản phẩm tới khi transaction kết thúc. Hai đơn chứa cùng hai sản phẩm A, B nhưng trừ theo thứ tự ngược nhau sẽ khóa chéo nhau: deadlock, một bên nhận lỗi và trang 500. ADR-0005 từng loại khóa bi quan vì lo deadlock nhiều sản phẩm, nhưng chưa đặt thứ tự cho chính câu `UPDATE`.
2. Hủy đơn là chuỗi "đọc trạng thái → kiểm → ghi trạng thái → cộng kho". Hai yêu cầu hủy cùng lúc (bấm hai lần, hai tab) cùng đọc `PENDING` thì kho được cộng **hai lần**.
3. Ở GĐ6 admin cũng đổi trạng thái đơn; admin xác nhận và khách hủy cùng một đơn cùng lúc phải được xếp hàng, không ai ghi đè ai.

## Quyết định

**1. Trừ và cộng kho của một đơn luôn theo `product_id` tăng dần.** `placeOrder` sắp các dòng giỏ theo `productId` trước khi gọi `ProductRepository.decrementStock`; hủy đơn sắp các dòng đơn theo `productId` trước khi gọi `incrementStock` (câu `@Modifying` mới, đối xứng với `decrementStock`, không lọc `active`).

**2. Mọi chuyển trạng thái đơn khóa dòng `orders` trước**, bằng `OrderRepository.lockByIdAndUserId` (`@Lock(PESSIMISTIC_WRITE)`, lọc luôn theo chủ đơn). Sau đó mới kiểm trạng thái hiện tại bằng `OrderStatus.canTransitionTo()` (qua `Order.transitionTo`) và quy tắc riêng của khách (`OrderService.customerMayCancel`: chỉ `PENDING`), rồi ghi. Hủy thì cộng kho trong **cùng transaction**. GĐ6 thêm truy vấn khóa theo id (không lọc chủ đơn) cho admin, cùng quy tắc.

**3. Thứ tự khóa toàn hệ thống:**

| Thao tác | Khóa lần lượt |
|---|---|
| Ghi giỏ (thêm, sửa, xóa, gộp) | `users` (một dòng) |
| Đặt hàng | `users` → `products` theo `id` tăng dần |
| Hủy đơn (khách; admin ở GĐ6) | `orders` (một dòng) → `products` theo `id` tăng dần |

Không có đường nào khóa `products` rồi mới tới `users` hay `orders`, và mỗi bảng được khóa theo một thứ tự cố định, nên không có vòng chờ.

SQL thật Hibernate 7 sinh trên PostgreSQL, theo thứ tự chạy của một lần đặt rồi hủy đơn (ghi lại khi viết test GĐ5; danh sách cột được rút gọn thành `…`):

```sql
-- Đặt hàng
select u1_0.id, … from users u1_0 where u1_0.id=? for no key update of u1_0                 -- CartRepository.lockOwner
select c1_0.id, … from carts c1_0 left join cart_items i1_0 … left join products p1_0 … where c1_0.user_id=?
insert into orders (…) values (…)
insert into order_items (…) values (…)
update products p1_0 set stock=(p1_0.stock-?) where p1_0.id=? and ?>0 and p1_0.stock>=?    -- decrementStock, theo id tăng dần
delete from cart_items ci1_0 where ci1_0.cart_id in (select c2_0.id from carts c2_0 where c2_0.user_id=?)

-- Hủy đơn
select o1_0.id, … from orders o1_0 where o1_0.id=? and o1_0.user_id=? for no key update of o1_0  -- lockByIdAndUserId
select i1_0.order_id, … from order_items i1_0 where i1_0.order_id=?
update orders set …, status=?, updated_at=?, … where id=?                                   -- flush trước khi cộng kho
update products p1_0 set stock=(p1_0.stock+?) where p1_0.id=? and ?>0                        -- incrementStock, theo id tăng dần
```

`FOR NO KEY UPDATE` loại trừ chính nó nhưng không chặn các câu `INSERT` có khóa ngoại trỏ tới dòng đó (chúng chỉ cần `FOR KEY SHARE`). Trên H2 cả hai đều là `FOR UPDATE`.

**4. Sống chung với `clearAutomatically`.** `decrementStock` và `incrementStock` có `@Modifying(clearAutomatically = true, flushAutomatically = true)`: lần gọi đầu tiên ghi mọi thay đổi xuống DB rồi xóa persistence context, mọi entity đang giữ thành detached. Vì vậy:
- `placeOrder` lưu `Order` (kèm `OrderItem`) **trước** vòng trừ kho, lặp trên danh sách `CheckoutLine` thuần trong bộ nhớ, và xóa giỏ bằng câu `@Modifying` hàng loạt (`CartCheckout.clear`), không qua entity;
- hủy đơn chép `(productId, quantity)` ra danh sách và gọi `transitionTo(CANCELLED)` **trước** câu cộng kho đầu tiên (`flushAutomatically` ghi trạng thái mới xuống DB).

**5. Lỗi nghiệp vụ khi đặt hàng là exception unchecked** (`PlaceOrderException`, sealed), không bị bắt trong service. `@Transactional` mặc định chỉ rollback với `RuntimeException`/`Error`; một checked exception sẽ để transaction commit cả các dòng đã trừ kho.

## Hệ quả

- **Tích cực:**
  - Không bán vượt kho, không deadlock giữa các đơn, một giỏ chỉ ra một đơn, một đơn chỉ được hoàn kho một lần; các test đồng thời chạy trên PostgreSQL thật.
  - Quy tắc chuyển trạng thái nằm một chỗ (`canTransitionTo`), dùng chung cho khách và admin.
  - Đơn của người khác không bao giờ bị khóa nhầm: truy vấn khóa lọc theo `user_id`.
  - Chạy được trên cả PostgreSQL và H2.
- **Tiêu cực / đánh đổi:**
  - Hai đơn chứa cùng một sản phẩm chạy tuần tự ở dòng sản phẩm đó (thời gian khóa rất ngắn).
  - Code mới đổi tồn kho hay trạng thái đơn phải nhớ thứ tự này; ghi trong `CLAUDE.md` và Javadoc của `OrderService`, `CartCheckout`.
  - Câu khóa vẫn chưa có `lock_timeout` (như ADR-0008); đặt trước khi deploy (GĐ8).
  - Sau `decrementStock`/`incrementStock`, entity đã đọc trước đó bị detached; code sau lời gọi không được đọc association lười.
  - Đặt hàng không khóa giá sản phẩm: giá được chụp tại lúc đọc giỏ (sau khi khóa `users`). Nếu admin (GĐ6) sửa giá đúng trong khoảng vài mili giây giữa lúc đọc và lúc commit, đơn giữ giá vừa đọc, tức giá mà khách đã thấy và đã xác nhận qua `expectedTotal`. Chấp nhận được: đơn luôn đúng với giá khách đồng ý, và tồn kho vẫn được bảo vệ bởi câu `UPDATE` có điều kiện.

## Phương án đã cân nhắc

- **Hủy đơn bằng `UPDATE orders SET status='CANCELLED' WHERE id=? AND user_id=? AND status='PENDING'`**, 1 dòng thì cộng kho: nguyên tử, không cần khóa, đối xứng với ADR-0005. Nhưng quy tắc chuyển trạng thái phải viết lại trong JPQL thay vì dùng `canTransitionTo()`, và khi 0 dòng thì phải truy vấn thêm để phân biệt "không có" với "không hủy được".
- **Optimistic locking (`@Version` trên `orders`):** cần migration và vẫn phải bắt `OptimisticLockException` để không ra trang 500.
- **Khóa trước mọi dòng sản phẩm (`SELECT … FOR UPDATE ORDER BY id`) rồi trừ:** đúng, nhưng ADR-0005 đã chọn câu `UPDATE` có điều kiện; khóa thêm chỉ làm dài thời gian giữ khóa.
- **Bỏ `clearAutomatically`:** entity `Product` trong context sẽ giữ `stock` cũ sau khi trừ, rất dễ đọc nhầm ở GĐ6.

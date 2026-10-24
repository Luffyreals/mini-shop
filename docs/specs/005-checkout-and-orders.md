# Spec 005: Đặt hàng (COD), lịch sử đơn và tự hủy đơn

- **Giai đoạn:** GĐ5
- **Trạng thái:** Hoàn thành
- **Liên quan:** brief §1 (MVP: checkout COD, lịch sử đơn, tự hủy đơn PENDING), §2 (luồng chính), §3, §5 (chỉ COD), §6.1 (trừ kho nguyên tử, `OrderStatus.canTransitionTo()`), §7 (IDOR), §8 (test `OrderService`); ADR-0002, 0005, 0006, 0007, 0008; spec 001 (bảng `orders`, `order_items`), spec 003 (`CurrentUser`, chuyển về trang đã yêu cầu), spec 004 (giỏ hàng, nhãn dòng)

## 1. Mục tiêu

Người đã đăng nhập biến giỏ hàng thành một đơn hàng thanh toán khi nhận hàng (COD) mà không bao giờ bị bán vượt tồn kho, xem lại các đơn của mình với đúng tên và giá lúc mua, và tự hủy đơn khi đơn còn đang chờ xử lý. Admin (GĐ6) sẽ xử lý các đơn này.

## 2. Phạm vi

**Trong phạm vi**
- Nút "Đặt hàng" trên trang giỏ hàng.
- Trang thanh toán `/checkout`: form thông tin giao hàng, tóm tắt đơn, phương thức thanh toán COD; xác nhận đặt hàng.
- `OrderService.placeOrder`: một transaction tạo `Order` + `OrderItem` (chụp tên và giá), trừ tồn kho nguyên tử (ADR-0005), xóa giỏ DB; khóa dòng `users` của người đặt trước khi đọc giỏ (ADR-0008).
- Trang "Đơn hàng của tôi" `/orders` (danh sách, mới nhất trước) và chi tiết đơn `/orders/{id}`; chỉ đơn của chính mình.
- Khách tự hủy đơn khi đơn đang `PENDING`; hoàn kho trong cùng transaction.
- Quy tắc chuyển trạng thái `OrderStatus.canTransitionTo()` cho toàn bộ luồng (dùng lại ở GĐ6).
- Link "Đơn hàng của tôi" trên header cho người đã đăng nhập.

**Ngoài phạm vi**
- Từ danh sách "Loại ra" của brief: **thanh toán online**, **email** (xác nhận đơn, thông báo đổi trạng thái), **mã giảm giá**, **phí ship**, **biến thể sản phẩm**.
- Trang quản lý đơn của admin, đổi trạng thái `CONFIRMED`/`SHIPPING`/`DELIVERED`, admin hủy đơn (GĐ6). GĐ5 chỉ định nghĩa quy tắc chuyển trạng thái và luồng khách hủy.
- Đặt hàng khi chưa đăng nhập (brief §4: checkout bắt buộc đăng nhập), sổ địa chỉ, lưu địa chỉ/số điện thoại vào hồ sơ người dùng, trang hồ sơ.
- Mua lại đơn cũ ("Đặt lại"), lọc/tìm đơn theo trạng thái, xuất hóa đơn, theo dõi vận chuyển.
- Đặt hàng một phần (bỏ qua dòng không đủ hàng rồi vẫn đặt các dòng còn lại): đơn được đặt trọn vẹn hoặc không được đặt.
- Giữ chỗ tồn kho khi mở trang thanh toán.

## 3. User stories

- **US-005-1:** Là khách có hàng trong giỏ, tôi muốn bấm "Đặt hàng", điền thông tin nhận hàng và xác nhận để mua hàng, trả tiền khi nhận.
- **US-005-2:** Là khách chưa đăng nhập, tôi muốn được đưa qua bước đăng nhập rồi quay lại thanh toán mà không mất giỏ hàng.
- **US-005-3:** Là khách, tôi muốn được báo rõ khi một sản phẩm không còn đủ hàng hoặc vừa đổi giá, thay vì đặt ra một đơn sai.
- **US-005-4:** Là khách, tôi muốn xem danh sách đơn của mình và chi tiết từng đơn (sản phẩm, giá lúc mua, trạng thái, thông tin giao hàng).
- **US-005-5:** Là khách, tôi muốn tự hủy đơn khi đơn còn đang chờ xử lý.
- **US-005-6:** Là chủ cửa hàng, tôi muốn không bao giờ bán vượt tồn kho, kể cả khi nhiều người đặt cùng lúc, và không ai xem hay hủy được đơn của người khác.

## 4. Tiêu chí chấp nhận

**Vào trang thanh toán**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-005-1 | Giỏ (khách hoặc người đã đăng nhập) có hàng và mọi dòng đều đặt được (BR-005-2) | mở `/cart` | có nút "Đặt hàng" dẫn tới `/checkout`. Giỏ trống: không có nút. Giỏ có dòng không đặt được: không có nút, thay bằng dòng "Vui lòng xóa hoặc giảm số lượng các sản phẩm không đủ hàng trước khi đặt hàng." | `CartControllerTest` |
| AC-005-2 | Khách chưa đăng nhập, giỏ session có hàng | bấm "Đặt hàng", đăng nhập thành công | được đưa trở lại `/checkout` (như AC-003-10); giỏ session đã được gộp vào giỏ DB (AC-004-15) và trang thanh toán hiện các sản phẩm đó | `CheckoutFlowTest` (tích hợp) |
| AC-005-3 | Người đã đăng nhập (`USER` hoặc `ADMIN`), giỏ DB đặt được | mở `/checkout` | HTTP 200, tiêu đề tab `Thanh toán \| Mini Shop`, `h1` `Thanh toán`; form có các ô "Họ tên người nhận" (điền sẵn họ tên tài khoản), "Số điện thoại" (điền sẵn số của tài khoản nếu có), "Địa chỉ giao hàng", "Ghi chú" (không bắt buộc); phần "Phương thức thanh toán" ghi "Thanh toán khi nhận hàng (COD)"; bảng tóm tắt có tên, số lượng, đơn giá, thành tiền từng dòng và "Tổng cộng", tiền định dạng `1.250.000 ₫`; nút "Xác nhận đặt hàng" | `CheckoutControllerTest` |
| AC-005-4 | Người đã đăng nhập, giỏ trống | mở `/checkout` | chuyển về `/cart` (trang hiện "Giỏ hàng của bạn đang trống."); không tạo đơn | `CheckoutControllerTest` |
| AC-005-5 | Giỏ có dòng không đặt được (ngừng bán, hết hàng, hoặc số lượng lớn hơn tồn kho) | mở `/checkout` | chuyển về `/cart` với thông báo "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng."; không tạo đơn | `CheckoutControllerTest`, `OrderServiceTest` |
| AC-005-6 | Khách chưa đăng nhập | mở `/checkout` hoặc gửi `POST /checkout` (có CSRF) | bị chuyển sang `/login`; không tạo đơn | `SecurityConfigTest` |

**Đặt hàng**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-005-7 | Giỏ DB {A: 2 (giá 100.000 ₫, kho 5), B: 1 (giá 250.000 ₫, kho 1)}, form hợp lệ | bấm "Xác nhận đặt hàng" | tạo đúng **một** đơn `PENDING`, `payment_method = COD`, `total_amount = 450.000`, hai `order_items` với tên, đơn giá và số lượng đúng; kho A còn 3, B còn 0; giỏ DB trống; chuyển tới `/orders/{id}` với thông báo "Đặt hàng thành công. Mã đơn của bạn là #{id}."; header hiện "Giỏ hàng" (không số) | `OrderServiceTest`, `OrderIntegrationTest`, `CheckoutControllerTest` |
| AC-005-8 | Đơn đã đặt với A giá 100.000 ₫ | admin (hoặc SQL) đổi giá A thành 120.000 ₫ và đổi tên A | chi tiết đơn vẫn hiện tên cũ, đơn giá 100.000 ₫ và tổng cũ (snapshot) | `OrderIntegrationTest` |
| AC-005-9 | Họ tên trống, số điện thoại trống hoặc sai định dạng (`12345`, `0abc456789`, `09123456789`), địa chỉ trống, hoặc một ô vượt độ dài tối đa | bấm "Xác nhận đặt hàng" | không tạo đơn, giỏ và kho không đổi; trang thanh toán hiện lại (HTTP 200) với lỗi tiếng Việt dưới từng ô sai (BR-005-6) và giữ nguyên các giá trị đã nhập | `CheckoutFormValidationTest`, `CheckoutControllerTest` |
| AC-005-10 | Số điện thoại nhập `0912 345 678` hoặc `0912.345.678` | đặt hàng | đơn lưu `phone = 0912345678` | `CheckoutFormValidationTest`, `OrderServiceTest` |
| AC-005-11 | Lúc mở trang thanh toán A còn 2; trước khi người dùng xác nhận, A chỉ còn 1 (người khác vừa mua) | xác nhận đơn có A: 2 | **không** tạo đơn; không sản phẩm nào bị trừ kho (rollback cả đơn, kể cả các dòng đã trừ trước đó); giỏ không đổi; chuyển về `/cart` với "Sản phẩm {tên A} không đủ hàng. Vui lòng cập nhật giỏ hàng." | `OrderServiceTest`, `OrderIntegrationTest` |
| AC-005-12 | Sau khi mở trang thanh toán, A bị ẩn (`active = false`) | xác nhận | không tạo đơn, giỏ và kho không đổi; chuyển về `/cart` với thông báo của AC-005-5. Nếu A hết hàng (`stock = 0`) hoặc không đủ số lượng thì dùng thông báo có tên sản phẩm của AC-005-11 | `OrderServiceTest` |
| AC-005-13 | Trang thanh toán hiện tổng 450.000 ₫; trước khi xác nhận, giá B đổi | xác nhận | **không** tạo đơn; trang thanh toán hiện lại với giá mới và thông báo "Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận."; người dùng xác nhận lần nữa thì đặt được với giá mới | `OrderServiceTest`, `CheckoutControllerTest` |
| AC-005-14 | Giỏ trống (ví dụ đơn vừa được đặt ở tab khác, hoặc bấm "Xác nhận" hai lần) | gửi `POST /checkout` | không tạo đơn, không trang 500; chuyển tới `/orders` với thông báo "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo." | `CheckoutControllerTest` |
| AC-005-15 | Cùng một người, hai yêu cầu `POST /checkout` gửi gần như đồng thời với cùng một giỏ | cả hai được xử lý | tạo đúng **một** đơn; tồn kho chỉ bị trừ một lần; không trang 500 | `OrderIntegrationTest` (PostgreSQL) |
| AC-005-16 | Sản phẩm A còn 1; hai người khác nhau cùng đặt A: 1 gần như đồng thời | cả hai được xử lý | đúng một đơn được tạo, người còn lại nhận thông báo của AC-005-11; `stock` của A là 0, không bao giờ âm | `OrderIntegrationTest` (PostgreSQL) |
| AC-005-17 | Hai người đặt cùng lúc hai đơn chứa cùng hai sản phẩm A, B nhưng thêm vào giỏ theo thứ tự ngược nhau | cả hai được xử lý | không deadlock, không trang 500 (BR-005-4) | `OrderIntegrationTest` (PostgreSQL) |
| AC-005-18 | Request đặt hàng có thêm tham số `userId`, `status`, `totalAmount`, `unitPrice`, `paymentMethod` hoặc `id` | đặt hàng | các tham số đó bị bỏ qua: đơn thuộc người đang đăng nhập, `PENDING`, `COD`, giá và tổng tính từ DB | `CheckoutControllerTest` |
| AC-005-19 | Bất kỳ form POST nào của GĐ5 (đặt hàng, hủy đơn) | gửi **không có** CSRF token | 403; không tạo hay đổi đơn | `SecurityConfigTest` |

**Lịch sử và chi tiết đơn**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-005-20 | Người dùng có 3 đơn | mở `/orders` | HTTP 200, tiêu đề tab `Đơn hàng của tôi \| Mini Shop`, `h1` `Đơn hàng của tôi`; bảng có cột "Mã đơn" (`#{id}`, link tới chi tiết), "Ngày đặt" (`dd/MM/yyyy HH:mm`, giờ Việt Nam), "Tổng tiền", "Trạng thái" (nhãn tiếng Việt, BR-005-9); đơn mới nhất ở trên; chỉ có đơn của chính người đó | `OrderControllerTest`, `OrderIntegrationTest` |
| AC-005-21 | Người dùng có nhiều hơn 10 đơn | mở `/orders`, `/orders?page=2`, `/orders?page=abc` | mỗi trang 10 đơn, có phân trang như GĐ2 (`fragments/pagination`); `page` sai thì về trang 1, không lỗi | `OrderControllerTest` |
| AC-005-22 | Người dùng chưa có đơn | mở `/orders` | dòng "Bạn chưa có đơn hàng nào." và link "Bắt đầu mua sắm" về `/products`; không có bảng | `OrderControllerTest` |
| AC-005-23 | Đơn của chính người dùng | mở `/orders/{id}` | HTTP 200, tiêu đề tab `Đơn hàng #{id} \| Mini Shop`; hiện mã đơn, ngày đặt, trạng thái, phương thức thanh toán "Thanh toán khi nhận hàng (COD)", người nhận, số điện thoại, địa chỉ, ghi chú (nếu có); bảng dòng hàng với tên, đơn giá, số lượng, thành tiền (từ snapshot) và "Tổng cộng"; link "Quay lại danh sách đơn" | `OrderControllerTest` |
| AC-005-24 | Đơn của người khác, `id` không tồn tại, hoặc `id` không phải số | mở `/orders/{id}` hoặc gửi `POST /orders/{id}/cancel` | HTTP 404 với trang "Không tìm thấy đơn hàng" (không phân biệt "không có" với "của người khác"); đơn không đổi. Áp dụng cả với `ADMIN` (admin xem đơn của người khác ở GĐ6) | `OrderServiceTest`, `OrderControllerTest`, `OrderIntegrationTest` |
| AC-005-25 | Khách chưa đăng nhập | mở `/orders` hoặc `/orders/{id}` | bị chuyển sang `/login` | `SecurityConfigTest` (đã có) |
| AC-005-26 | Danh sách và chi tiết đơn | mở trang | dữ liệu được tải đủ trong service (`open-in-view=false`): danh sách không N+1 theo đơn; chi tiết tải đơn và dòng hàng trong tối đa hai truy vấn | `OrderIntegrationTest` (đếm truy vấn) hoặc review |

**Hủy đơn**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-005-27 | Đơn `PENDING` của chính mình, A: 2 (kho đang 3) | mở chi tiết, bấm "Hủy đơn hàng" rồi xác nhận | đơn chuyển `CANCELLED`, kho A thành 5 (cùng transaction); về `/orders/{id}` với "Đã hủy đơn hàng #{id}."; nút hủy biến mất | `OrderServiceTest`, `OrderIntegrationTest`, `OrderControllerTest` |
| AC-005-28 | Đơn ở trạng thái `CONFIRMED`, `SHIPPING`, `DELIVERED` hoặc `CANCELLED` | mở chi tiết | không có nút "Hủy đơn hàng". Gửi tay `POST /orders/{id}/cancel`: đơn và kho không đổi; về `/orders/{id}` với "Đơn hàng này không thể hủy nữa." | `OrderServiceTest`, `OrderControllerTest` |
| AC-005-29 | Đơn `PENDING` có sản phẩm A đã bị ẩn sau khi đặt | hủy đơn | vẫn hủy được; kho của A vẫn được cộng lại | `OrderIntegrationTest` |
| AC-005-30 | Đơn `PENDING`; hai yêu cầu hủy gửi gần như đồng thời (bấm hai lần, hai tab) | cả hai được xử lý | kho chỉ được cộng lại **một** lần; đơn `CANCELLED`; không trang 500; yêu cầu thứ hai nhận "Đơn hàng này không thể hủy nữa." | `OrderIntegrationTest` (PostgreSQL) |
| AC-005-31 | `OrderStatus` | gọi `canTransitionTo` cho mọi cặp trạng thái | đúng bảng ở BR-005-8: chỉ `PENDING→CONFIRMED`, `CONFIRMED→SHIPPING`, `SHIPPING→DELIVERED`, `PENDING→CANCELLED`, `CONFIRMED→CANCELLED` là `true`; mọi cặp khác (kể cả về chính nó, đi lùi, ra khỏi `DELIVERED`/`CANCELLED`) là `false` | `OrderStatusTest` |

**Chung**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-005-32 | Người đã đăng nhập (`USER`, `ADMIN`) | xem header bất kỳ trang storefront nào | có link "Đơn hàng của tôi" tới `/orders`; khách chưa đăng nhập không thấy link này | `OrderControllerTest` + checklist |
| AC-005-33 | Trang thanh toán, danh sách đơn, chi tiết đơn | xem trên desktop và điện thoại 375px | không tràn ngang; không lỗi CSP trong console; template qua `TemplateCspComplianceTest`; tên sản phẩm, địa chỉ, ghi chú được escape (`th:text`) | `TemplateCspComplianceTest` + checklist |
| AC-005-34 | Toàn bộ luồng trên PostgreSQL thật và trên profile `h2` | đặt rồi hủy một đơn | chạy được trên cả hai (khóa dòng, câu trừ/cộng kho) | `OrderIntegrationTest`, `H2ProfileSmokeTest` |

## 5. Quy tắc nghiệp vụ

- **BR-005-1:** Chỉ người đã đăng nhập mới đặt hàng được (`USER` và `ADMIN` như nhau). Đơn luôn được đặt từ **giỏ DB** của người đang đăng nhập (`CurrentUser`), không bao giờ từ tham số request. Không có URL nào chứa id giỏ.
- **BR-005-2:** Một dòng giỏ **đặt được** khi sản phẩm đang bán (`active`), `stock > 0` và số lượng trong giỏ ≤ `stock`. Giỏ đặt được khi không trống và mọi dòng đều đặt được. Đơn được đặt trọn vẹn hoặc không được đặt (không đặt một phần).
- **BR-005-3:** `placeOrder` là **một transaction**, theo thứ tự:
  1. khóa dòng `users` của người đặt (ADR-0008);
  2. đọc giỏ DB kèm sản phẩm; giỏ trống thì dừng (AC-005-14);
  3. kiểm tra BR-005-2 và tổng tiền mà người dùng đã thấy (BR-005-5);
  4. tạo `Order` và các `OrderItem` với tên và giá hiện tại (snapshot);
  5. trừ kho từng dòng bằng câu UPDATE có điều kiện (ADR-0005); một dòng trả về 0 hàng bị ảnh hưởng thì rollback cả đơn (AC-005-11);
  6. xóa giỏ DB.
- **BR-005-4:** Các câu trừ kho của một đơn chạy theo thứ tự `product_id` tăng dần, để hai đơn chứa cùng các sản phẩm không khóa chéo nhau (AC-005-17).
- **BR-005-5:** Trang thanh toán gửi kèm tổng tiền mà nó đã hiện. Nếu tổng tính lại trong transaction khác giá trị đó (giá vừa đổi), không đặt và hiện lại trang thanh toán với giá mới (AC-005-13). Giá trị gửi lên chỉ dùng để **so sánh**, không bao giờ được lưu.
- **BR-005-6 (form giao hàng):** mọi ô được cắt khoảng trắng hai đầu trước khi kiểm tra.
  - Họ tên người nhận: bắt buộc, tối đa 100 ký tự.
  - Số điện thoại: bắt buộc; bỏ dấu cách, dấu chấm, dấu gạch ngang rồi phải đúng dạng `0` + 9 chữ số (10 chữ số); lưu dạng đã chuẩn hóa.
  - Địa chỉ giao hàng: bắt buộc, tối đa 500 ký tự.
  - Ghi chú: không bắt buộc, tối đa 500 ký tự; để trống thì lưu `null`.
- **BR-005-7:** Phương thức thanh toán luôn là `COD`; không có lựa chọn nào khác trên form.
- **BR-005-8 (luồng trạng thái, `OrderStatus.canTransitionTo()`):** `PENDING → CONFIRMED → SHIPPING → DELIVERED`; hủy (`→ CANCELLED`) được từ `PENDING` và `CONFIRMED`. `DELIVERED` và `CANCELLED` là trạng thái cuối. **Khách** chỉ hủy được khi đơn đang `PENDING` (quy tắc riêng trong service, chặt hơn bảng chung); admin hủy từ `CONFIRMED` là việc của GĐ6.
- **BR-005-9 (nhãn trạng thái):** `PENDING` "Chờ xử lý", `CONFIRMED` "Đã xác nhận", `SHIPPING` "Đang giao", `DELIVERED` "Đã giao", `CANCELLED` "Đã hủy".
- **BR-005-10:** Hủy đơn đổi trạng thái và cộng lại kho của mọi dòng (theo `order_items.product_id`, kể cả sản phẩm đã bị ẩn) trong **cùng một transaction**. Việc kiểm tra trạng thái và đổi trạng thái phải an toàn khi chạy đồng thời, để kho không bao giờ được cộng hai lần (AC-005-30). Cộng kho dùng một câu `@Modifying` riêng, không qua `Product.stock` (cột này `updatable = false`).
- **BR-005-11:** Quyền sở hữu đơn được kiểm ở **service** (IDOR): mọi truy vấn đọc/hủy đơn của khách đều lọc theo `user_id` của `CurrentUser`. Đơn của người khác được đối xử như không tồn tại (404).
- **BR-005-12:** Giá, tên, số lượng trong `order_items` và `orders.total_amount` không bao giờ thay đổi sau khi đặt; `total_amount` = tổng `unit_price × quantity` của các dòng, là số nguyên VND.
- **BR-005-13:** Tiền tính bằng `BigDecimal` số nguyên; không có phí ship hay giảm giá, nên tổng đơn = tổng thành tiền các dòng.

## 6. Giao diện và luồng

| URL | Phương thức | Quyền | Mô tả |
|---|---|---|---|
| `/cart` | GET | Mọi người | Như GĐ4, thêm nút "Đặt hàng" (AC-005-1) |
| `/checkout` | GET | Đã đăng nhập | Trang thanh toán |
| `/checkout` | POST | Đã đăng nhập (CSRF) | Đặt hàng: `receiverName`, `phone`, `shippingAddress`, `note`, `expectedTotal`; thành công thì về `/orders/{id}` |
| `/orders` | GET | Đã đăng nhập | Đơn hàng của tôi; tham số `page` (chuỗi, chuẩn hóa như GĐ2) |
| `/orders/{id}` | GET | Đã đăng nhập, chủ đơn | Chi tiết đơn |
| `/orders/{id}/cancel` | POST | Đã đăng nhập, chủ đơn (CSRF) | Hủy đơn; xong thì về `/orders/{id}` |

`/checkout/**` và `/orders/**` đã được `SecurityConfig` yêu cầu đăng nhập từ GĐ3; GĐ5 không mở thêm đường dẫn công khai nào. Mọi chuyển hướng tự dựng từ id đã parse, không dùng `Referer`.

**Chữ hiển thị chính xác:**

| Vị trí | Nội dung |
|---|---|
| Trang giỏ | nút `Đặt hàng`; khi giỏ có dòng không đặt được: `Vui lòng xóa hoặc giảm số lượng các sản phẩm không đủ hàng trước khi đặt hàng.` |
| Tiêu đề tab, `h1` | `Thanh toán \| Mini Shop`, `Thanh toán` |
| Nhãn form | `Họ tên người nhận`, `Số điện thoại`, `Địa chỉ giao hàng`, `Ghi chú (không bắt buộc)` |
| Thanh toán | tiêu đề `Phương thức thanh toán`; nội dung `Thanh toán khi nhận hàng (COD)` |
| Tóm tắt đơn | tiêu đề `Đơn hàng của bạn`; cột `Sản phẩm`, `Số lượng`, `Đơn giá`, `Thành tiền`; dòng `Tổng cộng` |
| Nút | `Xác nhận đặt hàng`; link `Quay lại giỏ hàng` |
| Lỗi họ tên | `Vui lòng nhập họ tên người nhận.` / `Họ tên người nhận tối đa 100 ký tự.` |
| Lỗi số điện thoại | `Vui lòng nhập số điện thoại.` / `Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.` |
| Lỗi địa chỉ | `Vui lòng nhập địa chỉ giao hàng.` / `Địa chỉ giao hàng tối đa 500 ký tự.` |
| Lỗi ghi chú | `Ghi chú tối đa 500 ký tự.` |
| Đặt thành công | `Đặt hàng thành công. Mã đơn của bạn là #{id}.` |
| Giỏ có dòng không đặt được | `Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng.` |
| Hết hàng lúc xác nhận | `Sản phẩm {tên} không đủ hàng. Vui lòng cập nhật giỏ hàng.` |
| Giá đổi lúc xác nhận | `Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận.` |
| POST khi giỏ trống | `Giỏ hàng đang trống nên chưa có đơn mới nào được tạo.` |
| Header | `Đơn hàng của tôi` (chỉ khi đã đăng nhập) |
| Danh sách đơn | tiêu đề tab `Đơn hàng của tôi \| Mini Shop`; cột `Mã đơn`, `Ngày đặt`, `Tổng tiền`, `Trạng thái` |
| Chưa có đơn | `Bạn chưa có đơn hàng nào.` + link `Bắt đầu mua sắm` |
| Chi tiết đơn | tiêu đề tab `Đơn hàng #{id} \| Mini Shop`; mục `Thông tin nhận hàng`, `Phương thức thanh toán`; link `Quay lại danh sách đơn` |
| Nhãn trạng thái | `Chờ xử lý`, `Đã xác nhận`, `Đang giao`, `Đã giao`, `Đã hủy` |
| Hủy đơn | nút `Hủy đơn hàng`; hộp xác nhận `Bạn có chắc muốn hủy đơn hàng này?` |
| Hủy thành công | `Đã hủy đơn hàng #{id}.` |
| Không hủy được | `Đơn hàng này không thể hủy nữa.` |
| 404 đơn | `Không tìm thấy đơn hàng` |

## 7. Dữ liệu

- Dùng bảng `orders` và `order_items` từ V1, **không cần migration**. Các ràng buộc đã có và được dựa vào:
  - `ck_orders_status`, `ck_orders_payment_method` (`COD`), `ck_orders_total_non_negative`;
  - `ck_order_items_unit_price_non_negative`, `ck_order_items_quantity_positive`;
  - `ck_products_stock_non_negative`: lớp bảo vệ cuối cùng chống bán vượt kho (ADR-0005);
  - `fk_order_items_product`: giữ liên kết tới sản phẩm để hoàn kho khi hủy, nên sản phẩm chỉ được ẩn, không xóa cứng.
- Độ dài các ô form khớp cột: `receiver_name` 100, `phone` 20 (lưu 10 chữ số), `shipping_address` 500, `note` 500.
- `users.phone` chỉ được **đọc** để điền sẵn; GĐ5 không ghi vào `users`.
- Index sẵn có `idx_orders_user_id`, `idx_orders_created_at` đủ cho danh sách đơn theo người dùng; nếu kế hoạch thấy cần index ghép `(user_id, created_at)` thì đó là migration `V3` và phải qua `schema-guardian`.
- Câu cộng kho khi hủy là truy vấn `@Modifying` mới trong `catalog` (đối xứng với `decrementStock`), không cần đổi schema.

## 8. Yêu cầu phi chức năng

- **Đồng thời và toàn vẹn:**
  - không bán vượt kho trong mọi tình huống đồng thời (AC-005-15, 16, 17); `stock` không bao giờ âm;
  - một giỏ chỉ sinh ra tối đa một đơn; một đơn chỉ được hoàn kho một lần;
  - các test đồng thời chạy trên PostgreSQL thật (Testcontainers), như GĐ4.
- **Bảo mật:**
  - IDOR: mọi truy cập đơn lọc theo chủ đơn ở service (BR-005-11), có test với hai tài khoản;
  - không bind entity từ request: form là `record` + Jakarta Validation; `toString()` của form không cần che (không có mật khẩu) nhưng log không được in địa chỉ/số điện thoại;
  - mọi form POST có CSRF; trang lỗi không lộ stack trace.
- **Kiến trúc:**
  - logic đặt hàng và hủy nằm trong `OrderService` `@Transactional`; controller chỉ bind form và chọn view;
  - `order` dùng giỏ qua một **API công khai hẹp** của `cart` (đọc dòng giỏ DB của một người sau khi khóa, xóa giỏ); phần còn lại của `cart` giữ package-private; chiều phụ thuộc `order → cart → catalog`, không ngược lại (`ArchitectureTest`);
  - chỉ `order` được gọi `decrementStock` và câu cộng kho; `cart` vẫn bị cấm (`ArchitectureTest`).
- **Hiệu năng:** trang thanh toán và chi tiết đơn không N+1; danh sách đơn phân trang ở DB.
- **Giao diện:** dùng lại hướng thiết kế "The specimen catalogue"; không script/style nội tuyến. Hộp xác nhận hủy đơn (nếu dùng JavaScript) nằm trong file `static/js/`, và nút hủy vẫn hoạt động khi tắt JavaScript. Mọi ô form có `label`, lỗi gắn với ô bằng `aria-describedby`.

## 9. Câu hỏi mở

> **Đã trả lời (2026-10-24):** người dùng duyệt cả 11 câu theo khuyến nghị.

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Giá đổi giữa lúc mở trang thanh toán và lúc xác nhận thì sao? | **Chặn và cho xem lại** (AC-005-13, BR-005-5): trang gửi kèm tổng đã hiện, service so sánh trong transaction. Rẻ (một ô ẩn, một phép so sánh) và tránh việc khách bị tính giá họ chưa thấy. Phương án khác là lặng lẽ đặt theo giá mới. |
| 2 | Sản phẩm không đủ hàng lúc xác nhận: chặn cả đơn hay đặt phần còn lại? | **Chặn cả đơn**, rollback mọi câu trừ kho, giỏ giữ nguyên để khách tự sửa (đúng ADR-0005). |
| 3 | Định dạng số điện thoại? | **10 chữ số bắt đầu bằng 0**, cho phép gõ kèm dấu cách/chấm/gạch rồi chuẩn hóa. Không nhận `+84` cho đơn giản; có thể nới ở GĐ7. |
| 4 | Xem/hủy đơn của người khác trả 403 hay 404? | **404** "Không tìm thấy đơn hàng" để không lộ việc đơn đó tồn tại. Áp dụng cả với `ADMIN` ở trang `/orders` (admin có trang riêng ở GĐ6). |
| 5 | `ADMIN` có đặt hàng được không? | **Có**, như `USER` (thống nhất với spec 004: `ADMIN` có giỏ). Trang `/orders` của admin chỉ chứa đơn của chính admin. |
| 6 | Bấm "Xác nhận" hai lần hoặc giỏ đã trống khi `POST /checkout`: chuyển về đâu? | **Về `/orders`** với "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo.", để khách thấy ngay đơn vừa đặt ở yêu cầu đầu. Còn `GET /checkout` với giỏ trống thì về `/cart`. |
| 7 | Hủy đơn có cần bước xác nhận không? | **Có**: hộp `confirm()` từ một file JS nhỏ trong `static/js/` (hợp CSP), gắn qua thuộc tính `data-confirm`. Tắt JS thì nút vẫn gửi form bình thường. Phương án không JS là thêm trang xác nhận riêng, tốn thêm một URL và một template. |
| 8 | Danh sách đơn có phân trang không, bao nhiêu đơn mỗi trang? | **Có, 10 đơn/trang**, dùng lại `PageNav` và `fragments/pagination` của GĐ2. |
| 9 | Mã đơn hiển thị thế nào? | **`#{id}`** (id trong DB). Mã đơn ngẫu nhiên/khó đoán không cần thiết vì quyền đã được kiểm ở service. |
| 10 | Có lưu số điện thoại/địa chỉ vừa nhập vào hồ sơ người dùng để lần sau điền sẵn không? | **Không** ở GĐ5 (không có trang hồ sơ trong brief). Chỉ điền sẵn họ tên và `users.phone` nếu đã có. |
| 11 | Ngày đặt hiển thị theo múi giờ nào? | **`Asia/Ho_Chi_Minh`**, dạng `dd/MM/yyyy HH:mm`, cố định (không theo trình duyệt). |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-24 | Tạo bản nháp |
| 2026-10-24 | Người dùng duyệt spec và 11 câu hỏi mở theo khuyến nghị; trạng thái `Đã duyệt` |
| 2026-10-24 | Người dùng duyệt kế hoạch [phase-5](../plans/phase-5.md); sửa AC-005-12 theo câu hỏi 1 của kế hoạch (ngừng bán → thông báo chung, hết hàng hoặc thiếu → thông báo có tên sản phẩm), để không mâu thuẫn với AC-005-16; trạng thái `Đang làm` |
| 2026-10-24 | `phase-verifier` đạt 29/30 mục; mục 28 (tên người nhận dài không dấu cách tràn ngang ở 375px trên chi tiết đơn) đã sửa bằng CSS và kiểm lại (không còn cuộn ngang). Trạng thái `Hoàn thành` |

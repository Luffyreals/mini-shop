# Spec 004: Giỏ hàng

- **Giai đoạn:** GĐ4
- **Trạng thái:** Hoàn thành
- **Liên quan:** brief §4 (Giỏ hàng), §5 (luồng chính), §9 (test `CartService`); ADR-0002, 0006, 0007; spec 001 (bảng `carts`, `cart_items`), spec 002 (trang chi tiết, nhãn kho), spec 003 (`CurrentUser`, đăng nhập)

## 1. Mục tiêu

Khách (kể cả chưa đăng nhập) chọn được sản phẩm vào giỏ, xem giỏ, sửa số lượng và xóa dòng. Người đã đăng nhập giữ được giỏ qua các lần đăng nhập, và những gì đã chọn trước khi đăng nhập được gộp vào giỏ của họ. GĐ5 (đặt hàng) dùng giỏ này làm đầu vào.

## 2. Phạm vi

**Trong phạm vi**
- Nút "Thêm vào giỏ" kèm ô số lượng ở trang chi tiết sản phẩm.
- Trang giỏ hàng `/cart`: xem, sửa số lượng, xóa dòng, tổng tiền.
- Giỏ trong HTTP session cho khách vãng lai; giỏ trong DB (`carts`, `cart_items`) cho người đã đăng nhập (`USER` và `ADMIN` như nhau).
- Gộp giỏ session vào giỏ DB khi đăng nhập thành công (ADR-0006).
- Link "Giỏ hàng" kèm số lượng trên header.

**Ngoài phạm vi**
- Từ danh sách "Loại ra" của brief: **mã giảm giá**, **phí ship**, **wishlist** ("để dành mua sau"), **biến thể sản phẩm** (size/màu).
- Nút "Đặt hàng" và trang checkout (GĐ5). Giỏ hàng GĐ4 chưa có nút này.
- Giữ chỗ hay trừ tồn kho khi thêm vào giỏ: tồn kho chỉ bị trừ lúc đặt hàng (GĐ5, ADR-0005).
- Thêm vào giỏ từ thẻ sản phẩm ở trang danh sách (xem câu hỏi mở 4), cập nhật giỏ bằng JavaScript/AJAX, giỏ hàng thu nhỏ (mini-cart) trên header.
- Lưu giỏ của khách vãng lai vào DB hoặc cookie (ADR-0006 đã loại).

## 3. User stories

- **US-004-1:** Là khách, tôi muốn thêm sản phẩm vào giỏ với số lượng mong muốn mà không cần đăng nhập trước.
- **US-004-2:** Là khách, tôi muốn xem giỏ với đơn giá, thành tiền từng dòng và tổng cộng, sửa số lượng và bỏ sản phẩm không muốn mua.
- **US-004-3:** Là người đã đăng nhập, tôi muốn giỏ của mình vẫn còn khi đăng xuất rồi quay lại, và những gì tôi chọn trước khi đăng nhập không bị mất.
- **US-004-4:** Là khách, tôi muốn biết khi sản phẩm trong giỏ đã hết hàng, ngừng bán hoặc không còn đủ số lượng.

## 4. Tiêu chí chấp nhận

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-004-1 | Sản phẩm đang bán, còn hàng | mở trang chi tiết | có ô "Số lượng" (mặc định 1) và nút "Thêm vào giỏ" trong một form POST có CSRF. Sản phẩm hết hàng: không có form, chỉ có nhãn "Hết hàng" như GĐ2 | `ProductControllerTest`, `CartControllerTest` |
| AC-004-2 | Khách chưa đăng nhập, giỏ trống | thêm sản phẩm A số lượng 2 | quay lại trang chi tiết của A với thông báo "Đã thêm vào giỏ hàng." và link "Xem giỏ hàng"; header hiện "Giỏ hàng (2)"; **không có dòng nào được ghi vào** `carts`/`cart_items` | `CartControllerTest`, `CartIntegrationTest` |
| AC-004-3 | Giỏ đã có A số lượng 2 | thêm A số lượng 3 | giỏ có **một** dòng A số lượng 5 (cộng dồn, không tạo dòng thứ hai) | `CartServiceTest`, `SessionCartTest` |
| AC-004-4 | A còn 4 trong kho, giỏ đã có A số lượng 3 | thêm A số lượng 5 | dòng A thành 4 (bằng tồn kho); thông báo "Chỉ còn 4 sản phẩm trong kho." thay cho thông báo thành công | `CartServiceTest` |
| AC-004-5 | A hết hàng (`stock = 0`) | gửi yêu cầu thêm A (ví dụ form cũ hoặc gửi tay) | không thêm; quay lại trang chi tiết với thông báo "Sản phẩm này đã hết hàng." | `CartServiceTest`, `CartControllerTest` |
| AC-004-6 | `productId` không tồn tại, đang ẩn (`active = false`), hoặc không phải số | gửi yêu cầu thêm | HTTP 404 với trang "Không tìm thấy sản phẩm" như GĐ2; giỏ không đổi | `CartControllerTest` |
| AC-004-7 | Số lượng là `0`, `-1`, `100`, `abc`, hoặc trống | thêm vào giỏ hoặc cập nhật | không thay đổi giỏ; thông báo "Số lượng không hợp lệ."; không bao giờ trang 500 | `CartControllerTest` |
| AC-004-8 | Người dùng `USER` chưa có giỏ trong DB | thêm sản phẩm lần đầu | tạo đúng một dòng `carts` cho người đó và một dòng `cart_items` | `CartIntegrationTest` |
| AC-004-9 | Giỏ có sản phẩm | mở `/cart` | HTTP 200, tiêu đề tab `Giỏ hàng \| Mini Shop`; mỗi dòng có tên (link tới trang chi tiết), ảnh nhỏ (hoặc ảnh thay thế), đơn giá, ô số lượng, thành tiền; cuối bảng có "Tổng cộng"; mọi số tiền định dạng `1.250.000 ₫` | `CartControllerTest` |
| AC-004-10 | Giỏ trống (khách hoặc người đã đăng nhập) | mở `/cart` | dòng "Giỏ hàng của bạn đang trống." và link "Tiếp tục mua sắm" về `/products`; không có bảng | `CartControllerTest` |
| AC-004-11 | Giỏ có A số lượng 2 | sửa thành 3 và bấm "Cập nhật" | về `/cart` với "Đã cập nhật số lượng."; thành tiền và tổng cộng tính lại. Nếu vượt tồn kho: đặt bằng tồn kho và hiện "Chỉ còn {n} sản phẩm trong kho." | `CartServiceTest`, `CartControllerTest` |
| AC-004-12 | Giỏ có A | bấm "Xóa" ở dòng A | về `/cart` với "Đã xóa sản phẩm khỏi giỏ hàng."; dòng A biến mất. Xóa một sản phẩm không có trong giỏ: không lỗi, giỏ không đổi | `CartServiceTest`, `CartControllerTest` |
| AC-004-13 | Đã thêm A giá 100.000 ₫, sau đó admin đổi giá thành 120.000 ₫ | mở `/cart` | đơn giá hiện 120.000 ₫ (giỏ luôn dùng giá hiện tại, không lưu giá) | `CartServiceTest` |
| AC-004-14 | Trong giỏ: B đã bị ẩn, C hết hàng, D có số lượng 5 nhưng kho chỉ còn 2 | mở `/cart` | B có nhãn "Sản phẩm đã ngừng bán", C có nhãn "Hết hàng", D có nhãn "Chỉ còn 2 sản phẩm"; B và C **không** được tính vào tổng cộng; vẫn xóa được cả ba dòng; mở trang **không** tự sửa giỏ | `CartServiceTest`, `CartIntegrationTest` |
| AC-004-15 | Khách có giỏ session {A: 2, B: 1}; tài khoản đã có giỏ DB {A: 3}; kho A còn 4 | đăng nhập | giỏ DB thành {A: 4, B: 1} (cộng dồn, không vượt tồn kho); giỏ session bị xóa; header hiện "Giỏ hàng (5)"; vẫn được đưa về trang đã yêu cầu trước đó như AC-003-10 | `CartServiceTest`, `CartMergeIntegrationTest` |
| AC-004-16 | Giỏ session có sản phẩm đã bị ẩn hoặc đã hết hàng | đăng nhập | các sản phẩm đó bị bỏ qua khi gộp, không gây lỗi; đăng nhập vẫn thành công | `CartServiceTest`, `CartMergeIntegrationTest` |
| AC-004-17 | Người dùng có giỏ DB | đăng xuất, rồi đăng nhập lại | giỏ vẫn còn nguyên. Ngay sau khi đăng xuất, khách thấy giỏ trống | `CartMergeIntegrationTest` |
| AC-004-18 | Hai tài khoản U1, U2 đều có giỏ | U1 thêm, sửa, xóa | chỉ giỏ của U1 thay đổi. Không URL hay tham số nào chứa id giỏ; giỏ luôn được xác định từ người đang đăng nhập hoặc session | `CartIntegrationTest` |
| AC-004-19 | Bất kỳ form POST nào của giỏ (thêm, cập nhật, xóa) | gửi **không có** CSRF token | 403, giỏ không đổi | `SecurityConfigTest` |
| AC-004-20 | Người dùng đã đăng nhập, cùng sản phẩm, hai yêu cầu "thêm" gửi gần như đồng thời (hai tab), kể cả khi chưa có giỏ DB | cả hai được xử lý | không bao giờ trang 500; giỏ có đúng một dòng cho sản phẩm đó, số lượng không vượt tồn kho; người đó có đúng một giỏ | `CartIntegrationTest` |
| AC-004-21 | Bất kỳ thao tác giỏ nào (thêm, sửa, xóa, gộp) | thực hiện | `products.stock` **không đổi** | `CartIntegrationTest` |
| AC-004-22 | Mọi trang storefront, với khách, `USER` và `ADMIN` | xem header | có link "Giỏ hàng" tới `/cart`; khi giỏ có hàng thì kèm tổng số lượng, ví dụ "Giỏ hàng (5)"; giỏ trống thì chỉ "Giỏ hàng" | `CartControllerTest` + checklist |
| AC-004-23 | Khách chưa đăng nhập | mở `/cart` và gửi các form giỏ (có CSRF) | được phép, không bị chuyển sang `/login`. Các đường dẫn công khai khác của GĐ2 vẫn chỉ cho GET | `SecurityConfigTest` |
| AC-004-24 | Trang giỏ và trang chi tiết có form | xem trên desktop và điện thoại 375px | không tràn ngang (bảng giỏ dùng được trên điện thoại); console không có lỗi CSP; template qua được `TemplateCspComplianceTest` | `TemplateCspComplianceTest` + checklist |

## 5. Quy tắc nghiệp vụ

- **BR-004-1:** Số lượng mỗi dòng là số nguyên từ 1 đến 99, và không vượt tồn kho **tại thời điểm** thêm, cập nhật hoặc gộp. Vượt tồn kho thì đặt bằng tồn kho và báo cho người dùng; ngoài khoảng 1–99 thì từ chối.
- **BR-004-2:** Mỗi sản phẩm chỉ có một dòng trong một giỏ; thêm lại thì cộng dồn số lượng.
- **BR-004-3:** Giỏ chỉ lưu sản phẩm và số lượng, **không lưu giá**. Đơn giá, thành tiền và tổng cộng luôn tính từ giá hiện tại. Bản chụp giá chỉ có ở `OrderItem` (GĐ5).
- **BR-004-4:** Giỏ không giữ chỗ và không thay đổi tồn kho. Tồn kho chỉ bị trừ khi đặt hàng (GĐ5, ADR-0005), nên tình trạng "đủ hàng" trong giỏ có thể đổi theo thời gian (AC-004-14).
- **BR-004-5:** Tổng cộng chỉ tính các dòng mua được: sản phẩm đang bán (`active`) và còn hàng (`stock > 0`). Dòng có số lượng lớn hơn tồn kho vẫn được tính theo số lượng trong giỏ, kèm cảnh báo; GĐ5 sẽ chặn khi đặt hàng.
- **BR-004-6:** Khách vãng lai: giỏ nằm trong HTTP session, mất khi session hết hạn hoặc đăng xuất. Người đã đăng nhập (`USER` hay `ADMIN`): mỗi người đúng một giỏ trong DB, tạo khi thêm sản phẩm đầu tiên hoặc khi gộp.
- **BR-004-7:** Gộp khi đăng nhập thành công: cộng dồn theo sản phẩm, áp BR-004-1, bỏ qua sản phẩm đã ẩn hoặc hết hàng, rồi xóa giỏ session. Lỗi khi gộp không được làm đăng nhập thất bại.
- **BR-004-8:** Giỏ luôn được xác định từ người đang đăng nhập (`CurrentUser`) hoặc từ session hiện tại, không bao giờ từ tham số của request (chống IDOR).

## 6. Giao diện và luồng

| URL | Phương thức | Quyền | Mô tả |
|---|---|---|---|
| `/products/{id}` | GET | Mọi người | Như GĐ2, thêm form "Thêm vào giỏ" khi sản phẩm còn hàng |
| `/cart` | GET | Mọi người | Xem giỏ |
| `/cart/items` | POST | Mọi người (CSRF) | Thêm: tham số `productId`, `quantity`; xong thì về `/products/{productId}` |
| `/cart/items/{productId}` | POST | Mọi người (CSRF) | Cập nhật số lượng: tham số `quantity`; xong thì về `/cart` |
| `/cart/items/{productId}/delete` | POST | Mọi người (CSRF) | Xóa dòng; xong thì về `/cart` |

Sau khi thêm, ứng dụng chuyển về đường dẫn tự dựng từ `productId` (`/products/{productId}`), không dùng header `Referer` hay tham số "quay về" do người dùng gửi (tránh open redirect).

**Chữ hiển thị chính xác:**

| Vị trí | Nội dung |
|---|---|
| Trang chi tiết | nhãn `Số lượng`; nút `Thêm vào giỏ` |
| Thêm thành công | `Đã thêm vào giỏ hàng.` kèm link `Xem giỏ hàng` |
| Vượt tồn kho (thêm, cập nhật) | `Chỉ còn {n} sản phẩm trong kho.` |
| Vượt 99 khi kho còn nhiều hơn 99 (thêm) | `Số lượng tối đa cho mỗi sản phẩm là 99.` (dòng được đặt bằng 99) |
| Sản phẩm hết hàng (thêm) | `Sản phẩm này đã hết hàng.` |
| Số lượng sai | `Số lượng không hợp lệ.` |
| Tiêu đề tab, `h1` | `Giỏ hàng \| Mini Shop`, `Giỏ hàng` |
| Cột bảng giỏ | `Sản phẩm`, `Đơn giá`, `Số lượng`, `Thành tiền` |
| Nút trong giỏ | `Cập nhật`, `Xóa` |
| Tổng | `Tổng cộng` |
| Cập nhật thành công | `Đã cập nhật số lượng.` |
| Xóa thành công | `Đã xóa sản phẩm khỏi giỏ hàng.` |
| Giỏ trống | `Giỏ hàng của bạn đang trống.` + link `Tiếp tục mua sắm` |
| Nhãn dòng trong giỏ | `Sản phẩm đã ngừng bán`, `Hết hàng`, `Chỉ còn {n} sản phẩm` |
| Header | `Giỏ hàng`, hoặc `Giỏ hàng ({tổng số lượng})` khi giỏ có hàng |

## 7. Dữ liệu

- Dùng bảng `carts` và `cart_items` từ V1, **không cần migration**. Các ràng buộc đã có và được dựa vào:
  - `uq_carts_user`: mỗi người một giỏ (AC-004-20);
  - `uq_cart_items_cart_product`: mỗi sản phẩm một dòng (BR-004-2, AC-004-20);
  - `ck_cart_items_quantity_positive`: số lượng > 0.
- Giới hạn 1–99 (BR-004-1) kiểm ở tầng ứng dụng, không thêm CHECK vào DB.
- Giỏ session chỉ chứa id sản phẩm và số lượng (dữ liệu nhỏ, serialize được), không chứa entity hay giá.

## 8. Yêu cầu phi chức năng

- **Bảo mật:**
  - mọi form giỏ là POST có CSRF; không có URL nào chứa id giỏ (BR-004-8);
  - header và trang giỏ escape tên sản phẩm (`th:text`).
- **Session:** trang chi tiết sản phẩm giờ có form có CSRF token, nên khách vãng lai xem trang chi tiết sẽ được tạo session. Trang danh sách vẫn không tạo session.
- **Hiệu năng:**
  - trang giỏ tải sản phẩm của mọi dòng trong một truy vấn, không N+1;
  - số lượng trên header của người đã đăng nhập tốn tối đa một truy vấn nhẹ mỗi trang.
- **Kiến trúc:**
  - logic giỏ (cả hai loại) nằm trong `CartService` `@Transactional` với một API chung (ADR-0006); controller không biết giỏ đang ở session hay DB;
  - `cart` chỉ phụ thuộc `catalog` và `user`;
  - việc gộp được gắn vào sự kiện đăng nhập thành công và không thay đổi hành vi chuyển hướng của GĐ3.
- **CSP và giao diện:** dùng lại hướng thiết kế "The specimen catalogue" của storefront; không script hay style nội tuyến; bảng giỏ có `caption` hoặc tiêu đề cột đúng chuẩn, ô số lượng có `label`.

## 9. Câu hỏi mở

> **Đã trả lời (2026-10-24):** người dùng duyệt cả 9 câu theo khuyến nghị.

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Thêm hoặc sửa vượt tồn kho thì sao? | **Đặt bằng tồn kho** và báo "Chỉ còn {n} sản phẩm trong kho." (AC-004-4). Thông báo này cho biết số tồn kho cụ thể, khác với GĐ2 (trang chi tiết chỉ hiện "Còn hàng"), nhưng chỉ hiện khi người dùng đã chạm giới hạn, và là cách làm phổ biến. |
| 2 | Có giới hạn số lượng mỗi dòng không? | **Có, tối đa 99**, để chặn số lượng vô lý và giữ giỏ session nhỏ. |
| 3 | Thêm xong thì ở lại trang chi tiết hay chuyển sang giỏ? | **Ở lại trang chi tiết** với thông báo và link "Xem giỏ hàng", để khách tiếp tục mua. |
| 4 | Có nút "Thêm vào giỏ" ở thẻ sản phẩm trong trang danh sách không? | **Không** ở GĐ4: thêm form vào mọi thẻ làm mọi khách xem danh sách đều bị tạo session, và brief không yêu cầu. |
| 5 | Số trên header là tổng số lượng hay số dòng? Giỏ trống có hiện "(0)"? | **Tổng số lượng**; giỏ trống chỉ hiện "Giỏ hàng". |
| 6 | Sau khi gộp giỏ lúc đăng nhập có hiện thông báo không? | **Không**: số trên header đã phản ánh giỏ mới. Giữ nguyên luồng đăng nhập của GĐ3. |
| 7 | Dòng có sản phẩm đã ẩn hoặc hết hàng xử lý thế nào? | **Giữ lại, gắn nhãn, không tính vào tổng**; người dùng tự xóa. Mở trang giỏ không tự sửa giỏ (GET không được thay đổi dữ liệu). GĐ5 sẽ chặn đặt hàng khi giỏ có dòng không mua được. |
| 8 | Tài khoản `ADMIN` có giỏ không? | **Có**, như `USER`: vai trò không ảnh hưởng tới giỏ. |
| 9 | Sửa số lượng thành 0 có nghĩa là xóa không? | **Không**: 0 là số lượng không hợp lệ; xóa dùng nút "Xóa" riêng, để tránh xóa nhầm. |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-24 | Tạo bản nháp |
| 2026-10-24 | Người dùng duyệt spec và 9 câu hỏi mở theo khuyến nghị; trạng thái `Đã duyệt` |
| 2026-10-24 | Người dùng duyệt kế hoạch [phase-4](../plans/phase-4.md); thêm chữ cho trường hợp vượt 99 (đặt bằng 99); trạng thái `Đang làm` |
| 2026-10-24 | `phase-verifier` đạt cả 26 mục checklist, 485 test xanh; trạng thái `Hoàn thành` |

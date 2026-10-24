# ADR-0008: Ghi giỏ hàng DB tuần tự theo từng người dùng bằng khóa dòng `users`

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-24

## Bối cảnh

ADR-0006 đã chọn: khách vãng lai giữ giỏ trong session, người đã đăng nhập giữ giỏ trong DB (`carts`, `cart_items`), và giỏ được gộp khi đăng nhập. Spec 004 (AC-004-20) yêu cầu thêm:
- hai yêu cầu "thêm vào giỏ" của **cùng một người** đến gần như đồng thời (hai tab, bấm hai lần) không bao giờ gây trang 500;
- người đó chỉ có một giỏ, mỗi sản phẩm chỉ có một dòng;
- số lượng không bị mất và không vượt tồn kho.

Mỗi thao tác ghi là một chuỗi "đọc giỏ → tính → ghi". Ở mức `READ COMMITTED`, hai transaction cùng đọc "chưa có giỏ" hoặc "dòng đang là 2" rồi cùng ghi. Kết quả là lỗi `uq_carts_user` / `uq_cart_items_cart_product` (trang 500), hoặc mất một lần cộng.

## Quyết định

**Mọi transaction ghi giỏ DB của một người (thêm, sửa, xóa, gộp) khóa dòng `users` của người đó trước tiên**, bằng `CartRepository.lockOwner(userId)` (`@Lock(PESSIMISTIC_WRITE)`). Sau đó transaction mới đọc giỏ, tính và ghi.

- Trên PostgreSQL, Hibernate 7 sinh `SELECT ... FOR NO KEY UPDATE`.
  - Hai khóa loại này trên cùng một dòng loại trừ nhau, nên transaction thứ hai đứng chờ tới khi transaction đầu commit. Khi chạy tiếp, nó đọc thấy dữ liệu mới nhất và cộng tiếp.
  - Khóa này không chặn các câu ghi bảng khác có khóa ngoại trỏ tới `users` (chúng chỉ cần `FOR KEY SHARE`), cũng không chặn việc đăng nhập (chỉ `SELECT` thường).
- Mỗi transaction chỉ khóa **một** dòng, nên không thể có deadlock giữa các thao tác giỏ.
- Unique constraint vẫn là lưới an toàn cuối cùng và **không bị bắt**. Nếu nó bị vi phạm, đó là bug (ví dụ code mới quên khóa), và phải lộ ra.
- **GĐ5 phải theo cùng quy tắc:** `placeOrder` đọc rồi xóa giỏ, nên cũng khóa dòng `users` của người đặt trước khi đọc giỏ. Thứ tự khóa trong đặt hàng là: dòng `users`, rồi các câu trừ tồn kho có điều kiện của ADR-0005.

## Hệ quả

- **Tích cực:**
  - Đúng trong mọi trường hợp, kể cả khi chưa có giỏ.
  - Không mất cập nhật.
  - Không cần vòng thử lại.
  - Chạy được trên cả PostgreSQL và H2.
  - Code dễ đọc: "khóa, rồi làm như một luồng".
- **Tiêu cực / đánh đổi:**
  - Các thao tác giỏ của **cùng một người** chạy tuần tự. Không đáng kể, vì một người không gửi nhiều yêu cầu cùng lúc.
  - Mọi method ghi giỏ phải nhớ gọi `lockOwner` trước. Quy tắc này được ghi trong Javadoc của `CartRepository` và `CLAUDE.md`, và được test đồng thời trên PostgreSQL kiểm.
  - Câu khóa chưa có timeout: nếu một transaction giữ dòng `users` quá lâu, các thao tác giỏ của đúng người đó phải chờ theo. Nên đặt `lock_timeout` trước khi deploy (GĐ8).
  - Giỏ của khách (trong session) không được bảo vệ như vậy: hai tab của khách sửa đúng cùng lúc có thể mất một lần cập nhật. Spec chỉ yêu cầu điều này cho người đã đăng nhập.

## Phương án đã cân nhắc

- **Bắt lỗi unique rồi thử lại** (như việc đăng ký trùng email ở GĐ3):
  - phải chạy lại trong transaction mới, vì transaction cũ đã hỏng trên PostgreSQL;
  - phải nhận diện hai tên constraint;
  - trường hợp "dòng đã có, hai bên cùng cộng" vẫn mất cập nhật.
- **`INSERT ... ON CONFLICT DO UPDATE`:** H2 không hỗ trợ, nên hỏng profile `h2`. `MERGE` chuẩn SQL trên PostgreSQL vẫn có thể ném lỗi unique khi chạy đồng thời.
- **Optimistic locking (`@Version` trên `carts`):** cần migration, vẫn phải bắt lỗi và thử lại, và không xử lý được lúc chưa có giỏ.
- **Khóa dòng `carts`:** dòng này chưa tồn tại ở lần thêm đầu tiên, đúng là lúc dễ xảy ra race nhất.

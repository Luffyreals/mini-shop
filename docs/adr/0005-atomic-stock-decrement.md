# ADR-0005: Trừ tồn kho bằng câu UPDATE có điều kiện

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-23

## Bối cảnh

Khi đặt hàng phải trừ tồn kho và không được bán vượt số lượng còn lại. Cách làm ngây thơ là đọc `stock`, kiểm tra `stock >= q`, rồi ghi `stock - q`. Nếu hai người đặt cùng lúc, cả hai đều đọc thấy còn hàng và cả hai đều trừ, dẫn tới **bán vượt kho** (race condition).

## Quyết định

Trừ kho bằng một câu lệnh nguyên tử:

```sql
UPDATE product SET stock = stock - :q WHERE id = :id AND stock >= :q
```

Nếu số dòng bị ảnh hưởng là 0, ném `BusinessException` ("Sản phẩm X không đủ hàng") và rollback cả đơn. Toàn bộ `OrderService.placeOrder()` nằm trong một transaction. Hủy đơn thì cộng lại kho trong cùng transaction với việc đổi trạng thái. Thêm ràng buộc `CHECK (stock >= 0)` ở DB làm lớp bảo vệ cuối cùng.

## Hệ quả

- Tích cực: đúng khi có nhiều người đặt cùng lúc mà không cần khóa thủ công; code ngắn; test được bằng Testcontainers.
- Đánh đổi: thay đổi `stock` đi qua một truy vấn riêng, không qua dirty checking của JPA, nên entity `Product` đang nằm trong persistence context có thể giữ giá trị cũ. Phải lưu ý điều này khi viết code.

## Phương án đã cân nhắc

- **Optimistic locking (`@Version`)**: đúng, nhưng phải xử lý `OptimisticLockException` và retry, phức tạp hơn.
- **Pessimistic locking (`SELECT ... FOR UPDATE`)**: đúng, nhưng giữ khóa lâu hơn và dễ deadlock khi đơn có nhiều sản phẩm.

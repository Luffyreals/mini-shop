# ADR-0006: Giỏ hàng: session cho khách, DB cho user, gộp khi đăng nhập

- **Trạng thái:** Chấp nhận
- **Ngày:** 2026-10-23

## Bối cảnh

Khách chưa đăng nhập vẫn phải thêm được hàng vào giỏ. Người đã đăng nhập mong giỏ hàng còn nguyên khi đăng xuất rồi quay lại.

## Quyết định

- **Khách vãng lai:** giỏ lưu trong HTTP session (`SessionCart`), không ghi vào DB.
- **User đã đăng nhập:** giỏ lưu trong bảng `cart` / `cart_item`, mỗi user một giỏ.
- **Lúc đăng nhập** (bắt sự kiện đăng nhập thành công): gộp giỏ session vào giỏ DB. Sản phẩm trùng thì cộng số lượng nhưng không vượt tồn kho. Sau đó xóa giỏ session.
- `CartService` cung cấp một API chung cho cả hai loại giỏ, nên controller không cần biết giỏ đang nằm ở đâu.

## Hệ quả

- Tích cực: trải nghiệm giống các trang bán hàng thật; DB không bị đầy giỏ rác của khách vãng lai.
- Đánh đổi: có hai nơi lưu giỏ và thêm logic gộp, nên phải test kỹ (`CartServiceTest`).

## Phương án đã cân nhắc

- **Session cho tất cả**: đơn giản nhất, nhưng giỏ mất khi hết phiên hoặc đăng xuất.
- **DB cho tất cả, kể cả khách** (định danh bằng cookie): phải dọn dẹp giỏ rác định kỳ; quá mức cần thiết.

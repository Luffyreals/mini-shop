# Spec 006: Trang quản trị (dashboard, danh mục, sản phẩm, tồn kho, đơn hàng)

- **Giai đoạn:** GĐ6
- **Trạng thái:** Hoàn thành
- **Liên quan:** brief §1 (MVP admin: dashboard, CRUD danh mục và sản phẩm có upload ảnh, quản lý đơn và đổi trạng thái, hoàn kho khi hủy), §2 (luồng admin, layout riêng có sidebar, ưu tiên desktop), §3 (ẩn sản phẩm bằng `active=false`, ảnh ≤ 2 MB), §6.1 (`OrderStatus.canTransitionTo()`, `StorageService`), §7 (kiểm tra file upload); ADR-0002, 0005, 0009; spec 001 (bảng `categories`, `products`), spec 002 (khách chỉ thấy sản phẩm đang bán), spec 003 (`/admin/**` chỉ `ADMIN`, trang `/admin` tạm), spec 004 (dòng giỏ "Ngừng bán"), spec 005 (luồng trạng thái, khách hủy đơn, snapshot)

## 1. Mục tiêu

Quản trị viên vận hành được cửa hàng trên trình duyệt: thêm và sửa danh mục, sản phẩm (kèm ảnh), nhập thêm hoặc điều chỉnh tồn kho, xem mọi đơn hàng và đưa đơn đi hết luồng `Chờ xử lý → Đã xác nhận → Đang giao → Đã giao` hoặc hủy đơn và hoàn kho. Mọi thao tác này không bao giờ làm sai tồn kho, kể cả khi khách đang đặt hàng cùng lúc.

## 2. Phạm vi

**Trong phạm vi**
- Layout quản trị riêng có sidebar cho mọi trang `/admin/**`.
- Dashboard `/admin` thay trang tạm của GĐ3: số đơn theo trạng thái, doanh thu đơn đã giao, sản phẩm sắp hết hàng, đơn mới nhất.
- Danh mục: xem danh sách, thêm, sửa, xóa (chỉ khi danh mục không còn sản phẩm nào).
- Sản phẩm: danh sách gồm cả sản phẩm đã ẩn (tìm theo tên, phân trang), thêm, sửa, ẩn và hiện lại. Không xóa cứng.
- Ảnh sản phẩm: upload khi thêm hoặc sửa, qua interface `StorageService` và bản cài đặt lưu file cục bộ; ảnh đã upload được phục vụ công khai.
- Tồn kho: nhập ban đầu khi tạo sản phẩm; sau đó chỉ điều chỉnh bằng một lượng tăng hoặc giảm, chạy nguyên tử.
- Đơn hàng: danh sách mọi đơn (lọc theo trạng thái, phân trang), chi tiết đơn, chuyển trạng thái theo `OrderStatus.canTransitionTo()`, admin hủy đơn `PENDING`/`CONFIRMED` và hoàn kho trong cùng transaction.

**Ngoài phạm vi**
- Từ danh sách "Loại ra" của brief: **email** (báo khách khi đổi trạng thái), **biến thể sản phẩm**, **mã giảm giá**, **phí ship**, **thanh toán online**, **REST API**.
- Quản lý người dùng (xem, khóa, đổi vai trò, tạo admin): đã loại ở spec 003.
- Xóa cứng sản phẩm; ẩn danh mục; nhiều ảnh cho một sản phẩm; cắt hoặc nén ảnh; xóa ảnh mà không thay ảnh mới.
- Sửa nội dung đơn đã đặt (dòng hàng, địa chỉ, giá); hoàn tiền; lý do hủy; in hóa đơn; xuất CSV.
- Đi lùi trạng thái hoặc hủy đơn đang giao/đã giao (bảng chuyển trạng thái của GĐ5 không cho phép).
- Biểu đồ, thống kê theo ngày/tháng, báo cáo doanh thu chi tiết.
- Nhật ký thao tác admin lưu trong DB (audit log); khóa lạc quan khi hai admin cùng sửa một sản phẩm.
- Lưu ảnh lên dịch vụ đám mây (GĐ8 thay bản cài đặt của `StorageService` nếu deploy).

## 3. User stories

- **US-006-1:** Là admin, tôi muốn mở `/admin` là thấy ngay có bao nhiêu đơn đang chờ xử lý và sản phẩm nào sắp hết hàng, để biết việc cần làm trước.
- **US-006-2:** Là admin, tôi muốn thêm, sửa và xóa danh mục để sắp xếp hàng hóa.
- **US-006-3:** Là admin, tôi muốn thêm sản phẩm mới với giá, mô tả, tồn kho ban đầu và ảnh, rồi sửa thông tin khi cần.
- **US-006-4:** Là admin, tôi muốn ẩn một sản phẩm không bán nữa mà không làm hỏng các đơn cũ, và hiện lại khi cần.
- **US-006-5:** Là admin, tôi muốn nhập thêm hàng hoặc giảm tồn kho (hàng hỏng, kiểm kê) mà không ghi đè số lượng khách vừa mua.
- **US-006-6:** Là admin, tôi muốn xem mọi đơn hàng, lọc theo trạng thái, và chuyển đơn sang bước tiếp theo hoặc hủy đơn (hoàn kho).
- **US-006-7:** Là chủ cửa hàng, tôi muốn chỉ `ADMIN` làm được những việc trên, và file upload không thể dùng để tấn công trang web.

## 4. Tiêu chí chấp nhận

**Truy cập và layout**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-1 | Khách chưa đăng nhập | mở bất kỳ URL `/admin/**` nào ở mục 6 | chuyển sang `/login`; `POST` không thay đổi dữ liệu | `SecurityConfigTest` |
| AC-006-2 | Tài khoản `USER` | mở hoặc gửi `POST` (có CSRF) tới bất kỳ URL `/admin/**` nào ở mục 6 | HTTP 403 với trang 403 của GĐ3; không thay đổi dữ liệu | `SecurityConfigTest` |
| AC-006-3 | Tài khoản `ADMIN` | gửi bất kỳ form `POST` nào của GĐ6 **không có** CSRF token (kể cả form upload `multipart/form-data`) | HTTP 403; không thay đổi dữ liệu | `SecurityConfigTest` |
| AC-006-4 | Tài khoản `ADMIN` | mở bất kỳ trang `/admin/**` nào | trang dùng layout quản trị: sidebar có các link `Tổng quan`, `Đơn hàng`, `Sản phẩm`, `Danh mục`, `Về cửa hàng`; link của mục đang mở được đánh dấu (`aria-current="page"`); có `Xin chào, {họ tên}` và nút `Đăng xuất`; tiêu đề tab dạng `{Tên trang} \| Quản trị Mini Shop` | `Admin*ControllerTest` + checklist |
| AC-006-5 | Admin mở URL có id không tồn tại hoặc không phải số (`/admin/products/abc/edit`, `/admin/orders/999999`, `/admin/categories/x/edit`) | mở trang hoặc gửi `POST` tới URL đó | HTTP 404 trong layout quản trị, tiêu đề `Không tìm thấy sản phẩm` / `Không tìm thấy đơn hàng` / `Không tìm thấy danh mục`, link quay lại danh sách tương ứng; không trang 500 | `Admin*ControllerTest` |

**Dashboard**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-6 | Có 3 đơn `PENDING`, 1 `CONFIRMED`, 0 `SHIPPING`, 2 `DELIVERED` (tổng 300.000 ₫ và 150.000 ₫), 1 `CANCELLED` | admin mở `/admin` | HTTP 200, tiêu đề tab `Tổng quan \| Quản trị Mini Shop`, `h1` `Tổng quan`; năm ô số đơn theo trạng thái (nhãn BR-005-9) lần lượt 3, 1, 0, 2, 1, mỗi ô link tới `/admin/orders?status={TRẠNG_THÁI}`; ô `Doanh thu (đơn đã giao)` hiện `450.000 ₫` | `AdminDashboardControllerTest`, `AdminDashboardIntegrationTest` |
| AC-006-7 | Sản phẩm đang bán có tồn kho 0, 3, 5, 6; một sản phẩm đã ẩn có tồn kho 1 | mở `/admin` | mục `Sắp hết hàng` liệt kê đúng ba sản phẩm đang bán có tồn kho ≤ 5 (BR-006-14), tồn kho tăng dần (0, 3, 5), mỗi dòng link tới trang sửa sản phẩm; tối đa 10 dòng. Không có sản phẩm nào: dòng `Không có sản phẩm nào sắp hết hàng.` | `AdminDashboardIntegrationTest`, `AdminDashboardControllerTest` |
| AC-006-8 | Có 7 đơn | mở `/admin` | mục `Đơn hàng mới nhất` hiện 5 đơn mới nhất (mã đơn link tới `/admin/orders/{id}`, ngày đặt, khách hàng, tổng tiền, trạng thái). Chưa có đơn: `Chưa có đơn hàng nào.` | `AdminDashboardControllerTest` |
| AC-006-9 | Bất kỳ dữ liệu nào | mở `/admin` | dữ liệu được tải bằng số truy vấn cố định, không phụ thuộc số đơn hay số sản phẩm (không N+1) | `AdminDashboardIntegrationTest` (đếm truy vấn) hoặc review |

**Danh mục**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-10 | Có 3 danh mục, một trong đó có 2 sản phẩm (1 đang bán, 1 đã ẩn) | mở `/admin/categories` | HTTP 200, `h1` `Danh mục`; bảng cột `Tên`, `Mô tả`, `Số sản phẩm` (đếm cả sản phẩm đã ẩn, ở đây là 2), thao tác `Sửa`, `Xóa`; sắp theo tên tiếng Việt (như GĐ2); nút `Thêm danh mục`. Chưa có danh mục: `Chưa có danh mục nào.` | `AdminCategoryControllerTest` |
| AC-006-11 | Form thêm danh mục, tên `  Đồ chơi  `, mô tả trống | bấm `Lưu` | tạo danh mục tên `Đồ chơi`, mô tả `null`; về `/admin/categories` với `Đã thêm danh mục Đồ chơi.`; danh mục xuất hiện ở bộ lọc của storefront | `CategoryAdminServiceTest`, `AdminCategoryControllerTest` |
| AC-006-12 | Tên trống, tên dài hơn 100 ký tự, hoặc mô tả dài hơn 1000 ký tự | bấm `Lưu` (thêm hoặc sửa) | không lưu; form hiện lại (HTTP 200) với lỗi tiếng Việt dưới ô sai (mục 6) và giữ giá trị đã nhập | `CategoryFormValidationTest`, `AdminCategoryControllerTest` |
| AC-006-13 | Đã có danh mục `Đồ chơi` | thêm danh mục `đồ chơi` hoặc ` ĐỒ CHƠI `, hoặc đổi tên danh mục khác thành `Đồ Chơi` | không lưu; lỗi dưới ô tên `Tên danh mục đã tồn tại.` (BR-006-2). Hai admin cùng thêm một tên gần như đồng thời: chỉ một danh mục được tạo, yêu cầu còn lại nhận cùng lỗi, không trang 500 | `CategoryAdminServiceTest`, `CategoryAdminIntegrationTest` |
| AC-006-14 | Danh mục `Đồ chơi` | mở `/admin/categories/{id}/edit`, đổi tên thành `Đồ chơi trẻ em` (giữ nguyên tên của chính nó cũng hợp lệ) | lưu; về danh sách với `Đã cập nhật danh mục Đồ chơi trẻ em.`; sản phẩm thuộc danh mục vẫn giữ danh mục đó | `CategoryAdminServiceTest`, `AdminCategoryControllerTest` |
| AC-006-15 | Danh mục không có sản phẩm nào | bấm `Xóa` rồi xác nhận | danh mục bị xóa; về danh sách với `Đã xóa danh mục {tên}.` | `CategoryAdminServiceTest`, `AdminCategoryControllerTest` |
| AC-006-16 | Danh mục còn ít nhất một sản phẩm, kể cả sản phẩm đã ẩn | gửi `POST /admin/categories/{id}/delete` | không xóa; về danh sách với `Không thể xóa danh mục {tên} vì còn {n} sản phẩm (kể cả sản phẩm đã ẩn).` Nếu một sản phẩm được thêm vào danh mục đúng lúc đang xóa: hoặc xóa thất bại với thông báo này, hoặc thêm sản phẩm thất bại, không bao giờ trang 500 hay sản phẩm mồ côi | `CategoryAdminServiceTest`, `CategoryAdminIntegrationTest` |

**Sản phẩm**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-17 | Có 25 sản phẩm, 2 trong đó đã ẩn | mở `/admin/products`, `?page=2`, `?page=abc`, `?q=áo` | HTTP 200, `h1` `Sản phẩm`; 20 sản phẩm mỗi trang, mới thêm trước; **gồm cả sản phẩm đã ẩn**; cột `Ảnh`, `Tên`, `Danh mục`, `Giá`, `Tồn kho`, `Trạng thái` (`Đang bán` / `Đã ẩn`), thao tác; `q` tìm theo tên như GĐ2 (không phân biệt hoa thường, ký tự `%` `_` được escape); `page` sai về trang 1; nút `Thêm sản phẩm`. Không có kết quả: `Không tìm thấy sản phẩm phù hợp.`; chưa có sản phẩm nào: `Chưa có sản phẩm nào.` | `AdminProductControllerTest`, `ProductAdminIntegrationTest` |
| AC-006-18 | Có ít nhất một danh mục | mở `/admin/products/new` | form có các ô `Tên sản phẩm`, `Danh mục` (danh sách chọn, sắp theo tên), `Giá (₫)`, `Tồn kho ban đầu`, `Mô tả`, `Ảnh sản phẩm` (không bắt buộc, gợi ý `JPG, PNG hoặc WebP, tối đa 2 MB.`), ô chọn `Đang bán` (mặc định bật); nút `Lưu`, link `Hủy`. Chưa có danh mục nào: không có form, chỉ có `Cần tạo danh mục trước khi thêm sản phẩm.` và link tới `/admin/categories/new` | `AdminProductControllerTest` |
| AC-006-19 | Form hợp lệ: tên `Bình nước 1L`, danh mục `Gia dụng`, giá `1.250.000`, tồn kho `30`, có ảnh PNG 500 KB | bấm `Lưu` | tạo sản phẩm với giá `1250000`, tồn kho 30, `active = true`, `image_path` trỏ tới file mới; về `/admin/products/{id}/edit` với `Đã thêm sản phẩm Bình nước 1L.`; sản phẩm hiện trên storefront với ảnh vừa upload | `ProductAdminServiceTest`, `AdminProductControllerTest`, `ProductAdminIntegrationTest` |
| AC-006-20 | Giá nhập `1250000`, `1.250.000` hoặc `1 250 000` | lưu | đều lưu giá `1250000`. Giá trống, `0`, `-5`, `12,5`, `1.5`, `abc`, hoặc lớn hơn `999.999.999`: không lưu, lỗi dưới ô giá (BR-006-5) | `ProductFormValidationTest` |
| AC-006-21 | Tên trống hoặc dài hơn 200 ký tự, mô tả dài hơn 4000 ký tự, chưa chọn danh mục, danh mục không tồn tại, tồn kho ban đầu trống, âm, không phải số nguyên hoặc lớn hơn 100.000 | bấm `Lưu` | không lưu và không giữ file ảnh nào; form hiện lại (HTTP 200) với lỗi tiếng Việt dưới từng ô sai và giữ các giá trị chữ đã nhập (ô file phải chọn lại) | `ProductFormValidationTest`, `AdminProductControllerTest` |
| AC-006-22 | Sản phẩm `A` đang có trong giỏ và trong đơn cũ | admin mở `/admin/products/{id}/edit`, đổi tên, giá, mô tả, danh mục, không chọn ảnh mới | lưu; về trang sửa với `Đã cập nhật sản phẩm {tên mới}.`; ảnh cũ giữ nguyên; storefront và giỏ hiện tên, giá mới; đơn cũ vẫn hiện tên và giá lúc mua (AC-005-8). Form sửa **không có** ô tồn kho | `ProductAdminServiceTest`, `AdminProductControllerTest` |
| AC-006-23 | Tồn kho A là 10; admin mở trang sửa A; trong lúc đó khách đặt đơn có A: 2 (tồn kho còn 8) | admin bấm `Lưu` form sửa | tồn kho A vẫn là 8, không bị ghi đè về 10 (BR-006-8) | `ProductAdminIntegrationTest` |
| AC-006-24 | Request thêm hoặc sửa sản phẩm có thêm tham số `stock` (với form sửa), `id`, `active` (với form sửa), `imagePath`, `createdAt` | lưu | các tham số đó bị bỏ qua: id lấy từ URL, tồn kho chỉ đổi qua AC-006-32, `image_path` chỉ đổi qua upload, trạng thái chỉ đổi qua ẩn/hiện | `AdminProductControllerTest` |
| AC-006-25 | Sản phẩm A đang bán | bấm `Ẩn` trên danh sách hoặc trang sửa | `active = false`; về trang vừa đứng với `Đã ẩn sản phẩm {tên}. Khách sẽ không thấy sản phẩm này nữa.`; storefront: A biến mất khỏi danh sách, `/products/{id}` trả 404 (BR-002-1); giỏ có A hiện "Ngừng bán" và không đặt hàng được (spec 004, AC-005-12); đơn cũ có A vẫn xem được, hủy vẫn hoàn kho (AC-005-29) | `ProductAdminServiceTest`, `AdminProductControllerTest`, checklist |
| AC-006-26 | Sản phẩm A đã ẩn | bấm `Hiện lại` | `active = true`; `Đã hiện lại sản phẩm {tên}.`; A xuất hiện lại trên storefront | `ProductAdminServiceTest`, `AdminProductControllerTest` |

**Ảnh sản phẩm**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-27 | Upload file JPG, PNG hoặc WebP hợp lệ ≤ 2 MB, tên gốc bất kỳ (`ảnh sản phẩm (1).PNG`, `../../x.png`) | lưu sản phẩm | file được lưu vào thư mục upload cấu hình được (mặc định `uploads/`) với **tên ngẫu nhiên UUID** và đuôi chuẩn hóa (`.jpg`, `.png`, `.webp`); tên gốc không được dùng hay lưu; ảnh xem được tại `/uploads/{tên file}` mà không cần đăng nhập | `LocalStorageServiceTest`, `ProductAdminIntegrationTest`, `SecurityConfigTest` |
| AC-006-28 | File có đuôi khác (`.gif`, `.svg`, `.html`, `.exe`, không đuôi), hoặc đuôi `.jpg` nhưng nội dung không phải ảnh JPG/PNG/WebP (ví dụ file HTML đổi tên), hoặc content type khai báo không khớp | lưu | không lưu sản phẩm, không ghi file nào; lỗi dưới ô ảnh `Ảnh chỉ được là JPG, PNG hoặc WebP.` | `LocalStorageServiceTest`, `AdminProductControllerTest` |
| AC-006-29 | File ảnh lớn hơn 2 MB (thử 2,5 MB và 9 MB) | lưu | không lưu sản phẩm, không ghi file; trang hiện thông báo `Ảnh tối đa 2 MB.`; không trang 500, không ngắt kết nối | `AdminProductControllerTest`, checklist |
| AC-006-30 | Sản phẩm đang có ảnh đã upload trước đó | sửa sản phẩm và chọn ảnh mới hợp lệ | `image_path` trỏ tới file mới; file ảnh cũ trong thư mục upload bị xóa sau khi lưu thành công (BR-006-11). Ảnh mẫu của seed (`/images/products/…`) không bao giờ bị xóa | `ProductAdminServiceTest`, `LocalStorageServiceTest` |
| AC-006-31 | Yêu cầu tới `/uploads/../application.yml`, `/uploads/%2e%2e/…`, hoặc tên file không tồn tại | mở URL | HTTP 404 (hoặc 400); không bao giờ trả file nằm ngoài thư mục upload | `UploadsServingTest` |

**Tồn kho**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-32 | Trang sửa sản phẩm A, tồn kho 10 | mục `Điều chỉnh tồn kho` hiện `Tồn kho hiện tại: 10`; admin nhập `+5` (hoặc `5`) rồi bấm `Cập nhật tồn kho` | tồn kho thành 15 bằng một câu UPDATE nguyên tử (BR-006-7); về trang sửa với `Đã cập nhật tồn kho sản phẩm {tên}. Tồn kho hiện tại: 15.` | `ProductAdminServiceTest`, `ProductAdminIntegrationTest`, `AdminProductControllerTest` |
| AC-006-33 | Tồn kho A là 3 | nhập `-2` | tồn kho thành 1. Nhập `-5`: không đổi, thông báo `Không thể giảm 5 vì tồn kho hiện tại chỉ còn 3.` | `ProductAdminServiceTest`, `ProductAdminIntegrationTest` |
| AC-006-34 | Bất kỳ | nhập `0`, trống, `abc`, `1.5`, lớn hơn `100000` hoặc nhỏ hơn `-100000`; hoặc tồn kho sau điều chỉnh vượt 100.000 | không đổi tồn kho; thông báo `Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.` hoặc `Tồn kho sau điều chỉnh không được vượt quá 100.000.` | `ProductAdminServiceTest`, `AdminProductControllerTest` |
| AC-006-35 | A có tồn kho 5 | admin `+10` và hai khách mỗi người đặt A: 2, gửi gần như đồng thời | tồn kho cuối cùng là 11; không mất cập nhật nào; không trang 500 | `ProductAdminIntegrationTest` (PostgreSQL) |
| AC-006-36 | A có tồn kho 2 | admin `-2` và một khách đặt A: 2 gần như đồng thời | đúng một trong hai thành công; tồn kho là 0, không bao giờ âm; bên thua nhận thông báo của mình (AC-006-33 hoặc AC-005-11) | `ProductAdminIntegrationTest` (PostgreSQL) |
| AC-006-37 | Sản phẩm đã ẩn | điều chỉnh tồn kho | vẫn điều chỉnh được (để chuẩn bị trước khi hiện lại) | `ProductAdminServiceTest` |

**Đơn hàng**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-38 | Có 25 đơn của nhiều khách | mở `/admin/orders`, `?page=2`, `?page=abc` | HTTP 200, `h1` `Đơn hàng`; 20 đơn mỗi trang, mới nhất trước (ngày đặt giảm dần, rồi id giảm dần); cột `Mã đơn` (`#{id}`, link `/admin/orders/{id}`), `Ngày đặt` (giờ Việt Nam), `Khách hàng` (họ tên và email tài khoản), `Người nhận`, `Tổng tiền`, `Trạng thái`; `page` sai về trang 1; không N+1 theo đơn | `AdminOrderControllerTest`, `OrderAdminIntegrationTest` |
| AC-006-39 | Như trên | chọn bộ lọc `Chờ xử lý` (`?status=PENDING`), hoặc `?status=abc` | chỉ đơn `PENDING`, link phân trang giữ `status`; bộ lọc có `Tất cả` và năm nhãn trạng thái, mục đang chọn được đánh dấu; `status` sai thì như `Tất cả`. Không có đơn: `Chưa có đơn hàng nào.` hoặc `Không có đơn hàng nào ở trạng thái {nhãn}.` | `AdminOrderControllerTest` |
| AC-006-40 | Đơn bất kỳ (của bất kỳ khách nào) | mở `/admin/orders/{id}` | HTTP 200, tiêu đề tab `Đơn hàng #{id} \| Quản trị Mini Shop`; hiện mọi thông tin của AC-005-23 (trạng thái, phương thức thanh toán, người nhận, số điện thoại, địa chỉ, ghi chú, dòng hàng từ snapshot, tổng) cộng `Tài khoản đặt hàng: {họ tên} ({email})` và `Cập nhật lần cuối` | `AdminOrderControllerTest` |
| AC-006-41 | Đơn ở từng trạng thái | mở chi tiết | chỉ hiện nút của bước được phép (BR-006-12): `PENDING`: `Xác nhận đơn`, `Hủy đơn`; `CONFIRMED`: `Bắt đầu giao hàng`, `Hủy đơn`; `SHIPPING`: `Xác nhận đã giao`; `DELIVERED`, `CANCELLED`: không có nút, thay bằng `Đơn hàng đã ở trạng thái cuối.` | `AdminOrderControllerTest` |
| AC-006-42 | Đơn `PENDING` | bấm `Xác nhận đơn` | trạng thái `CONFIRMED`, kho không đổi; về chi tiết với `Đã chuyển đơn hàng #{id} sang trạng thái Đã xác nhận.`; khách xem `/orders/{id}` thấy `Đã xác nhận` và không còn nút hủy (AC-005-28) | `OrderAdminServiceTest`, `AdminOrderControllerTest` |
| AC-006-43 | Đơn `CONFIRMED` rồi `SHIPPING` | bấm `Bắt đầu giao hàng`, rồi `Xác nhận đã giao` và xác nhận hộp thoại | lần lượt `SHIPPING`, `DELIVERED`; kho không đổi; thông báo như AC-006-42 với nhãn tương ứng | `OrderAdminServiceTest`, `AdminOrderControllerTest` |
| AC-006-44 | Đơn `PENDING` hoặc `CONFIRMED` có A: 2 (kho A đang 3), kể cả khi A đã bị ẩn | bấm `Hủy đơn` rồi xác nhận | đơn `CANCELLED`, kho A thành 5 trong cùng transaction (BR-005-10); về chi tiết với `Đã hủy đơn hàng #{id} và hoàn lại tồn kho.` | `OrderAdminServiceTest`, `OrderAdminIntegrationTest` |
| AC-006-45 | Đơn ở trạng thái không cho phép bước đó (ví dụ `SHIPPING` → hủy, `DELIVERED` → bất kỳ, `PENDING` → `SHIPPING`, `PENDING` → `PENDING`), hoặc `status` gửi lên không phải trạng thái hợp lệ | gửi tay `POST /admin/orders/{id}/status` | đơn và kho không đổi; về chi tiết với `Không thể chuyển đơn hàng #{id} từ {nhãn hiện tại} sang {nhãn đích}.` hoặc `Trạng thái không hợp lệ.` | `OrderAdminServiceTest`, `AdminOrderControllerTest` |
| AC-006-46 | Đơn `PENDING`; admin bấm `Xác nhận đơn` và khách bấm hủy gần như đồng thời | cả hai được xử lý | đúng một bên thắng; nếu khách hủy trước thì kho được hoàn đúng một lần và admin nhận thông báo "Không thể chuyển…" của AC-006-45; nếu admin xác nhận trước thì khách nhận `Đơn hàng này không thể hủy nữa.`; không trang 500 | `OrderAdminIntegrationTest` (PostgreSQL) |
| AC-006-47 | Đơn `CONFIRMED`; hai yêu cầu admin hủy gửi gần như đồng thời (hai tab) | cả hai được xử lý | kho chỉ được hoàn **một** lần; yêu cầu thứ hai nhận thông báo của AC-006-45; không trang 500 | `OrderAdminIntegrationTest` (PostgreSQL) |
| AC-006-48 | Trang `/orders` và `/orders/{id}` của khách | admin mở đơn của người khác qua đường dẫn của khách | vẫn 404 như AC-005-24; admin chỉ xem đơn người khác qua `/admin/orders/{id}` | `OrderControllerTest` (đã có) |

**Chung**

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-006-49 | Mọi form của GĐ6 | xem trang | mọi ô có `label`; lỗi gắn với ô bằng `aria-describedby`; nút có chữ rõ nghĩa; hành động không hoàn tác được (`Hủy đơn`, `Xác nhận đã giao`, `Xóa` danh mục) có hộp xác nhận qua `data-confirm` (`static/js/confirm.js`) và vẫn gửi được khi tắt JavaScript | `Admin*ControllerTest` + checklist |
| AC-006-50 | Mọi trang `/admin/**` | xem trên desktop 1280px và điện thoại 375px | desktop dùng được thoải mái; 375px không cuộn ngang cả trang (bảng được cuộn trong khung riêng); không lỗi CSP trong console; template qua `TemplateCspComplianceTest`; mọi chữ do người dùng nhập (tên, mô tả, địa chỉ, ghi chú) được escape (`th:text`) | `TemplateCspComplianceTest` + checklist |
| AC-006-51 | Toàn bộ luồng admin | chạy trên PostgreSQL thật và trên profile `h2` | thêm danh mục, thêm sản phẩm có ảnh, điều chỉnh kho, đổi trạng thái và hủy đơn đều chạy được trên cả hai | `*AdminIntegrationTest`, `H2ProfileSmokeTest` |
| AC-006-52 | Luồng chính của brief | khách đặt hàng → admin mở dashboard → bấm ô `Chờ xử lý` → mở đơn → `Xác nhận đơn` → `Bắt đầu giao hàng` → `Xác nhận đã giao` | khách thấy đơn `Đã giao` ở `/orders`; doanh thu trên dashboard tăng đúng tổng đơn | `AdminFlowTest` (tích hợp) + checklist |

## 5. Quy tắc nghiệp vụ

- **BR-006-1 (quyền):** mọi trang và thao tác GĐ6 chỉ dành cho `ADMIN` (đã có trong `SecurityConfig` từ GĐ3). Đường dẫn công khai **duy nhất** được thêm là `GET /uploads/**` (ảnh sản phẩm).
- **BR-006-2 (danh mục):** tên bắt buộc, cắt khoảng trắng hai đầu, tối đa 100 ký tự, **không trùng** khi so sánh không phân biệt hoa thường (`Locale.ROOT`); mô tả không bắt buộc, tối đa 1000 ký tự, trống thì lưu `null`. Ràng buộc `uq_categories_name` vẫn là lưới an toàn cuối khi hai yêu cầu chạy đồng thời.
- **BR-006-3 (xóa danh mục):** chỉ xóa cứng được khi không còn sản phẩm nào thuộc danh mục, **kể cả sản phẩm đã ẩn** (khóa ngoại `fk_products_category`). Không có ẩn danh mục.
- **BR-006-4 (sản phẩm):** tên bắt buộc, tối đa 200 ký tự; mô tả không bắt buộc, tối đa 4000 ký tự; danh mục bắt buộc và phải tồn tại; mọi ô chữ được cắt khoảng trắng hai đầu và bỏ ký tự điều khiển (giữ xuống dòng trong mô tả), như `CheckoutForm` của GĐ5.
- **BR-006-5 (giá):** số nguyên VND từ 1 đến 999.999.999. Ô nhập chấp nhận chữ số, có thể kèm dấu chấm hoặc dấu cách phân cách hàng nghìn đúng nhóm ba chữ số (`1.250.000`, `1 250 000`); không nhận số thập phân, dấu phẩy, số âm. Lỗi: `Vui lòng nhập giá.` / `Giá phải là số nguyên từ 1 đến 999.999.999 ₫.`
- **BR-006-6 (tồn kho ban đầu):** chỉ nhập khi **tạo** sản phẩm; số nguyên từ 0 đến 100.000.
- **BR-006-7 (điều chỉnh tồn kho):** sau khi tạo, tồn kho chỉ đổi bằng một **lượng điều chỉnh** khác 0 trong khoảng −100.000 đến 100.000, qua **một câu UPDATE có điều kiện** dạng `stock = stock + :delta WHERE id = :id AND stock + :delta BETWEEN 0 AND 100000`. 0 dòng bị ảnh hưởng thì không đổi gì và báo lỗi (AC-006-33, 34). Không bao giờ đọc tồn kho rồi ghi lại giá trị tính trong Java (cùng tinh thần ADR-0005). Đây là cách duy nhất ngoài `order` được đổi tồn kho.
- **BR-006-8:** lưu form sửa sản phẩm không bao giờ ghi cột `stock` (`Product.stock` đã `updatable = false`).
- **BR-006-9 (ẩn/hiện):** ẩn = `active = false`, hiện lại = `active = true`; không xóa cứng sản phẩm. Ẩn không đụng tới giỏ hay đơn: giỏ hiện "Ngừng bán" (spec 004), đặt hàng bị chặn (AC-005-12), đơn cũ giữ snapshot và hủy vẫn hoàn kho (AC-005-29).
- **BR-006-10 (ảnh):** không bắt buộc. Chỉ nhận JPG, PNG, WebP; kiểm cả **đuôi file**, **content type** và **chữ ký đầu file** (magic bytes) phải cùng khớp một loại; tối đa 2 MB. File được lưu với tên UUID + đuôi chuẩn hóa trong thư mục cấu hình được, không bao giờ dùng tên gốc. Lỗi ở bất kỳ ô nào của form thì không ghi file. Sản phẩm không có ảnh hiện ảnh giữ chỗ như hiện nay.
- **BR-006-11 (thay ảnh):** khi lưu ảnh mới thành công, file ảnh cũ **nằm trong thư mục upload** bị xóa sau khi transaction commit; xóa thất bại chỉ ghi log, không báo lỗi cho admin. Nếu transaction lưu sản phẩm thất bại sau khi đã ghi file mới, file mới bị xóa. Ảnh mẫu của seed không bao giờ bị xóa.
- **BR-006-12 (chuyển trạng thái):** admin dùng đúng bảng `OrderStatus.canTransitionTo()` của GĐ5 (BR-005-8), không có quy tắc riêng: `PENDING → CONFIRMED | CANCELLED`, `CONFIRMED → SHIPPING | CANCELLED`, `SHIPPING → DELIVERED`. Nút nào hiện ra cũng được suy từ bảng này, không viết lại trong template.
- **BR-006-13 (khóa khi đổi trạng thái):** theo ADR-0009: khóa dòng `orders` trước (bản không lọc chủ đơn, chỉ dùng cho admin), kiểm `canTransitionTo` sau khi khóa, rồi ghi; hủy thì hoàn kho theo `product_id` tăng dần trong cùng transaction. Admin và khách đổi cùng một đơn được xếp hàng, không ai ghi đè ai (AC-006-46).
- **BR-006-14 (dashboard):** "sắp hết hàng" = sản phẩm **đang bán** có tồn kho ≤ 5 (kể cả 0). Doanh thu = tổng `total_amount` của các đơn `DELIVERED`. Dữ liệu đọc bằng transaction chỉ đọc: số đơn theo trạng thái, doanh thu và đơn mới nhất trong cùng một transaction của `order`; danh sách sắp hết hàng trong một transaction riêng của `catalog`, đọc ngay sau. Không có cache.
- **BR-006-15:** controller của `admin` chỉ bind form và chọn view; mọi nghiệp vụ (kiểm trùng tên, xóa danh mục, lưu ảnh, điều chỉnh kho, chuyển trạng thái) nằm trong service `@Transactional` của `catalog`, `order`, `storage`. `admin` không dùng repository và không thấy entity.

## 6. Giao diện và luồng

| URL | Phương thức | Quyền | Mô tả |
|---|---|---|---|
| `/admin` | GET | `ADMIN` | Dashboard (thay trang tạm GĐ3) |
| `/admin/categories` | GET | `ADMIN` | Danh sách danh mục |
| `/admin/categories/new` | GET | `ADMIN` | Form thêm |
| `/admin/categories` | POST | `ADMIN` (CSRF) | Tạo danh mục: `name`, `description` |
| `/admin/categories/{id}/edit` | GET | `ADMIN` | Form sửa |
| `/admin/categories/{id}` | POST | `ADMIN` (CSRF) | Lưu sửa |
| `/admin/categories/{id}/delete` | POST | `ADMIN` (CSRF) | Xóa |
| `/admin/products` | GET | `ADMIN` | Danh sách sản phẩm; `q`, `page` (chuỗi, chuẩn hóa như GĐ2) |
| `/admin/products/new` | GET | `ADMIN` | Form thêm |
| `/admin/products` | POST | `ADMIN` (CSRF, multipart) | Tạo: `name`, `categoryId`, `price`, `initialStock`, `description`, `active`, `image` |
| `/admin/products/{id}/edit` | GET | `ADMIN` | Form sửa + mục điều chỉnh tồn kho |
| `/admin/products/{id}` | POST | `ADMIN` (CSRF, multipart) | Lưu sửa: `name`, `categoryId`, `price`, `description`, `image` |
| `/admin/products/{id}/hide`, `/admin/products/{id}/show` | POST | `ADMIN` (CSRF) | Ẩn / hiện lại; tham số `from` (`list` hoặc `edit`) chỉ chọn trang quay về |
| `/admin/products/{id}/stock` | POST | `ADMIN` (CSRF) | Điều chỉnh tồn kho: `delta` |
| `/admin/orders` | GET | `ADMIN` | Danh sách đơn; `status`, `page` |
| `/admin/orders/{id}` | GET | `ADMIN` | Chi tiết đơn |
| `/admin/orders/{id}/status` | POST | `ADMIN` (CSRF) | Chuyển trạng thái: `status` (trạng thái đích) |
| `/uploads/{file}` | GET | Mọi người | Ảnh sản phẩm đã upload |

Sau mỗi `POST` thành công là chuyển hướng (PRG) kèm flash message; lỗi validate thì hiện lại form với HTTP 200. Mọi chuyển hướng dựng từ id đã parse, không dùng `Referer`. Link `Quản trị` trên header storefront (GĐ3) dẫn tới dashboard.

**Chữ hiển thị chính xác:**

| Vị trí | Nội dung |
|---|---|
| Tiêu đề tab | `{Tên trang} \| Quản trị Mini Shop`: `Tổng quan`, `Danh mục`, `Thêm danh mục`, `Sửa danh mục`, `Sản phẩm`, `Thêm sản phẩm`, `Sửa sản phẩm`, `Đơn hàng`, `Đơn hàng #{id}` |
| Sidebar | `Tổng quan`, `Đơn hàng`, `Sản phẩm`, `Danh mục`, `Về cửa hàng` |
| Dashboard | `h1` `Tổng quan`; ô `Chờ xử lý`, `Đã xác nhận`, `Đang giao`, `Đã giao`, `Đã hủy`; `Doanh thu (đơn đã giao)`; mục `Sắp hết hàng`, `Đơn hàng mới nhất`, link `Xem tất cả đơn hàng` |
| Dashboard trống | `Không có sản phẩm nào sắp hết hàng.` / `Chưa có đơn hàng nào.` |
| Danh mục | nút `Thêm danh mục`; cột `Tên`, `Mô tả`, `Số sản phẩm`; thao tác `Sửa`, `Xóa`; trống `Chưa có danh mục nào.` |
| Form danh mục | nhãn `Tên danh mục`, `Mô tả (không bắt buộc)`; nút `Lưu`, link `Hủy` |
| Lỗi danh mục | `Vui lòng nhập tên danh mục.` / `Tên danh mục tối đa 100 ký tự.` / `Tên danh mục đã tồn tại.` / `Mô tả tối đa 1000 ký tự.` |
| Thông báo danh mục | `Đã thêm danh mục {tên}.` / `Đã cập nhật danh mục {tên}.` / `Đã xóa danh mục {tên}.` / `Không thể xóa danh mục {tên} vì còn {n} sản phẩm (kể cả sản phẩm đã ẩn).` |
| Hộp xác nhận xóa danh mục | `Xóa danh mục {tên}? Không thể hoàn tác.` |
| Sản phẩm | nút `Thêm sản phẩm`; ô tìm `Tìm theo tên sản phẩm…`, nút `Tìm`; cột `Ảnh`, `Tên`, `Danh mục`, `Giá`, `Tồn kho`, `Trạng thái`; nhãn `Đang bán` / `Đã ẩn`; thao tác `Sửa`, `Ẩn`, `Hiện lại`; trống `Chưa có sản phẩm nào.` / `Không tìm thấy sản phẩm phù hợp.` |
| Form sản phẩm | nhãn `Tên sản phẩm`, `Danh mục`, `Giá (₫)`, `Tồn kho ban đầu` (chỉ form thêm), `Mô tả (không bắt buộc)`, `Ảnh sản phẩm` (form sửa: `Ảnh mới (không bắt buộc)` kèm ảnh hiện tại), `Đang bán`; gợi ý `JPG, PNG hoặc WebP, tối đa 2 MB.`; nút `Lưu`, link `Hủy`; trang sửa có link `Xem trên cửa hàng` khi đang bán |
| Chưa có danh mục | `Cần tạo danh mục trước khi thêm sản phẩm.` + link `Thêm danh mục` |
| Lỗi sản phẩm | `Vui lòng nhập tên sản phẩm.` / `Tên sản phẩm tối đa 200 ký tự.` / `Vui lòng chọn danh mục.` / `Danh mục không tồn tại.` / `Vui lòng nhập giá.` / `Giá phải là số nguyên từ 1 đến 999.999.999 ₫.` / `Vui lòng nhập tồn kho ban đầu.` / `Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.` / `Mô tả tối đa 4000 ký tự.` |
| Lỗi ảnh | `Ảnh chỉ được là JPG, PNG hoặc WebP.` / `Ảnh tối đa 2 MB.` |
| Thông báo sản phẩm | `Đã thêm sản phẩm {tên}.` / `Đã cập nhật sản phẩm {tên}.` / `Đã ẩn sản phẩm {tên}. Khách sẽ không thấy sản phẩm này nữa.` / `Đã hiện lại sản phẩm {tên}.` |
| Điều chỉnh tồn kho | tiêu đề `Điều chỉnh tồn kho`; `Tồn kho hiện tại: {n}`; nhãn `Số lượng thay đổi`; gợi ý `Số dương để nhập thêm, số âm để giảm (ví dụ: 20 hoặc -3).`; nút `Cập nhật tồn kho` |
| Thông báo tồn kho | `Đã cập nhật tồn kho sản phẩm {tên}. Tồn kho hiện tại: {n}.` / `Không thể giảm {x} vì tồn kho hiện tại chỉ còn {n}.` / `Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000.` / `Tồn kho sau điều chỉnh không được vượt quá 100.000.` |
| Đơn hàng | bộ lọc `Tất cả` + năm nhãn trạng thái; cột `Mã đơn`, `Ngày đặt`, `Khách hàng`, `Người nhận`, `Tổng tiền`, `Trạng thái`; trống `Chưa có đơn hàng nào.` / `Không có đơn hàng nào ở trạng thái {nhãn}.` |
| Chi tiết đơn | các mục của AC-005-23; thêm `Tài khoản đặt hàng: {họ tên} ({email})`, `Cập nhật lần cuối`; link `Quay lại danh sách đơn` |
| Nút trạng thái | `Xác nhận đơn`, `Bắt đầu giao hàng`, `Xác nhận đã giao`, `Hủy đơn`; trạng thái cuối: `Đơn hàng đã ở trạng thái cuối.` |
| Hộp xác nhận | hủy: `Hủy đơn hàng #{id} và hoàn lại tồn kho?`; đã giao: `Đánh dấu đơn hàng #{id} đã giao? Không thể hoàn tác.` |
| Thông báo đơn | `Đã chuyển đơn hàng #{id} sang trạng thái {nhãn}.` / `Đã hủy đơn hàng #{id} và hoàn lại tồn kho.` / `Không thể chuyển đơn hàng #{id} từ {nhãn hiện tại} sang {nhãn đích}.` / `Trạng thái không hợp lệ.` |
| 404 quản trị | `Không tìm thấy sản phẩm` / `Không tìm thấy đơn hàng` / `Không tìm thấy danh mục` + link quay lại danh sách |

## 7. Dữ liệu

- **Không cần migration.** Dùng các bảng và ràng buộc sẵn có từ V1:
  - `categories`: `name varchar(100)` + `uq_categories_name` (lưới an toàn; kiểm trùng không phân biệt hoa thường làm ở service), `description varchar(1000)`;
  - `products`: `name` 200, `description` 4000, `price numeric(12,0)` + `ck_products_price_non_negative`, `stock integer` + `ck_products_stock_non_negative`, `image_path varchar(255)`, `active`, `fk_products_category` (chặn xóa danh mục còn sản phẩm);
  - `orders.status` + `ck_orders_status`; `idx_orders_status`, `idx_orders_created_at` cho danh sách đơn của admin.
- `products.image_path` của ảnh upload lưu đường dẫn công khai dạng `/uploads/{uuid}.{jpg|png|webp}` (độ dài < 60 ký tự); ảnh seed giữ dạng `/images/products/{slug}.jpg`. Việc đổi đường dẫn thành URL hiển thị chuyển từ `catalog.ProductImages` sang `StorageService` (như ghi chú trong code GĐ2).
- **Cấu hình mới:** thư mục upload (ví dụ `app.storage.local.upload-dir`, mặc định `uploads`, đã có trong `.gitignore`); giới hạn multipart chỉnh sao cho file quá 2 MB nhận thông báo AC-006-29 thay vì lỗi.
- Câu điều chỉnh tồn kho là truy vấn `@Modifying` mới trong `catalog`, cùng họ với `decrementStock`/`incrementStock`; không đổi schema.
- Nếu kế hoạch thấy cần index mới (ví dụ `(status, created_at)` cho danh sách đơn) thì đó là migration `V3` và phải qua `schema-guardian`.

## 8. Yêu cầu phi chức năng

- **Bảo mật:**
  - không bind entity từ request: mọi form là `record` + Jakarta Validation; tham số lạ bị bỏ qua (AC-006-24);
  - upload: whitelist ba loại ảnh, kiểm đuôi + content type + magic bytes, tên UUID, chặn path traversal ở cả lúc ghi và lúc phục vụ file (AC-006-27 đến 31); không nhận SVG (có thể chứa script); ảnh được phục vụ với `X-Content-Type-Options: nosniff` (mặc định của Spring Security);
  - admin xem được dữ liệu cá nhân trong đơn (brief §7), nhưng log không in số điện thoại, địa chỉ, ghi chú; mỗi thao tác đổi dữ liệu của admin được ghi một dòng log INFO (id admin, đối tượng, hành động), không có PII.
- **Đồng thời và toàn vẹn:** không mất cập nhật tồn kho giữa admin và khách (AC-006-23, 35, 36); hoàn kho đúng một lần (AC-006-46, 47); thứ tự khóa của ADR-0009 được giữ (câu điều chỉnh kho chỉ khóa một dòng `products` và không giữ khóa nào khác). Các test đồng thời chạy trên PostgreSQL thật.
- **Kiến trúc:**
  - `admin` chỉ có controller, form và view model của tầng web; gọi API công khai **hẹp** của `catalog`, `order`, `storage`; không ai phụ thuộc vào `admin` (`ArchitectureTest` đã có);
  - `order` mở một API quản trị hẹp (đọc danh sách/chi tiết mọi đơn, chuyển trạng thái), phần còn lại giữ package-private;
  - `ArchitectureTest` và `package-info.java` được cập nhật cho mọi chiều phụ thuộc mới (ví dụ `catalog → storage`) và cho quy tắc "ai được đổi tồn kho" (`order` trừ/cộng kho; chỉ service điều chỉnh kho của `catalog` gọi câu điều chỉnh; `cart` và `admin` không bao giờ);
  - quyết định mới về lưu và phục vụ ảnh, và về cách admin điều chỉnh tồn kho, được ghi trong một ADR mới (ADR-0010) trước khi code.
- **Hiệu năng:** danh sách sản phẩm và đơn phân trang ở DB, không N+1 (fetch danh mục và tài khoản kèm theo); dashboard dùng truy vấn gộp (`count … group by status`, `sum`), không tải toàn bộ đơn vào bộ nhớ.
- **Giao diện:** layout quản trị riêng, đi theo nhánh "app screen" của skill `design-taste-frontend` (CLAUDE.md), vẽ hướng thiết kế một lần cho khu `/admin` vào `.taste-skill/` rồi dùng lại; Bootstrap 5.3 qua WebJars; CSP giữ nguyên (không script/style nội tuyến); tiền luôn qua `#vnd.format`, ngày qua `#vnDateTime.format`.

## 9. Câu hỏi mở

> **Đã trả lời (2026-10-24):** người dùng duyệt cả 14 câu theo khuyến nghị.

| # | Câu hỏi | Khuyến nghị |
|---|---|---|
| 1 | Admin đổi tồn kho thế nào? | **Lượng điều chỉnh ± chạy nguyên tử** (BR-006-7): `+20` nhập thêm, `-3` giảm, một câu UPDATE có điều kiện nên không bao giờ ghi đè số khách vừa mua. Phương án "ô tồn kho tuyệt đối" trong form sửa dễ ghi đè (đọc 10, khách mua 2, admin lưu 10); muốn làm an toàn phải so sánh giá trị cũ và báo "tồn kho vừa thay đổi", phức tạp hơn mà không lợi gì cho cửa hàng nhỏ. |
| 2 | Giới hạn giá và tồn kho? | **Giá 1–999.999.999 ₫, tồn kho 0–100.000**, lượng điều chỉnh mỗi lần −100.000 đến 100.000. Đủ rộng cho cửa hàng mini, chặn lỗi gõ thừa số 0 và tràn `integer`. Không cho giá 0 (không có hàng tặng trong brief). |
| 3 | Ảnh: định dạng, giới hạn, có xóa ảnh không? | **JPG, PNG, WebP, ≤ 2 MB** (brief §3), kiểm đuôi + content type + magic bytes; **không nhận SVG, GIF**. Chỉ **thay** ảnh, không có nút xóa ảnh (ngoài phạm vi; sản phẩm mới không có ảnh thì dùng ảnh giữ chỗ). |
| 4 | Thay ảnh thì file cũ xử lý sao? | **Xóa file cũ sau khi commit** (BR-006-11), chỉ với file trong thư mục upload; lỗi xóa chỉ ghi log. Giữ lại thì thư mục upload phình dần với file không ai dùng. |
| 5 | Ảnh upload được phục vụ thế nào? | **`GET /uploads/**` công khai**, map thẳng tới thư mục upload (resource handler của Spring MVC), thêm vào danh sách đường dẫn công khai của `SecurityConfig`. Đơn giản, không đi qua controller; GĐ8 thay bằng URL của dịch vụ đám mây qua `StorageService`. |
| 6 | Tên danh mục trùng khi khác hoa thường (`Áo` và `áo`)? | **Coi là trùng**, kiểm ở service (không cần migration). Index `lower(name)` trên DB không chạy giống nhau trên H2, nên `uq_categories_name` (phân biệt hoa thường) chỉ là lưới an toàn cho trường hợp đồng thời. |
| 7 | Xóa danh mục còn sản phẩm đã ẩn? | **Không cho xóa** (BR-006-3): khóa ngoại bắt buộc vậy, và sản phẩm ẩn vẫn được đơn cũ tham chiếu. Thông báo nói rõ số sản phẩm để admin chuyển chúng sang danh mục khác trước. |
| 8 | Ngưỡng "sắp hết hàng" trên dashboard? | **≤ 5, chỉ sản phẩm đang bán**, tối đa 10 dòng, tồn kho tăng dần. Cố định trong code (không cần trang cài đặt). |
| 9 | Dashboard có doanh thu không? | **Có, một con số**: tổng tiền đơn `Đã giao`. Rẻ (một câu `sum`) và cho thấy luồng đơn đi tới cuối. Không làm biểu đồ hay doanh thu theo kỳ (ngoài phạm vi). |
| 10 | Danh sách đơn của admin lọc/tìm thế nào? | **Lọc theo trạng thái + phân trang 20 đơn**, mới nhất trước. Không tìm theo email/số điện thoại/mã đơn ở GĐ6 (mở chi tiết bằng link; có thể thêm ở GĐ7 nếu cần). Danh sách sản phẩm: **tìm theo tên + phân trang 20**, không lọc theo danh mục. |
| 11 | Thao tác nào cần hộp xác nhận? | **Hủy đơn, Xác nhận đã giao, Xóa danh mục** (không hoàn tác được). Ẩn sản phẩm và các bước trạng thái khác không cần (hiện lại được, hoặc là bước bình thường hằng ngày). |
| 12 | Admin hủy đơn có cần nhập lý do không? | **Không**: cần cột mới và brief không yêu cầu. Khách thấy trạng thái `Đã hủy` như khi tự hủy. |
| 13 | Hai admin sửa cùng một sản phẩm cùng lúc? | **Chấp nhận "lưu sau thắng"** cho tên, giá, mô tả (chỉ có một tài khoản admin seed). Tồn kho đã an toàn nhờ BR-006-7/8. Khóa lạc quan (`@Version`) cần migration, để dành nếu sau này có nhiều admin. |
| 14 | Trang 404 trong khu quản trị? | **Dùng layout quản trị** với tiêu đề theo loại đối tượng (AC-006-5), để admin không bị đẩy ra giao diện cửa hàng. Trang lỗi chung (500…) vẫn để GĐ7. |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-24 | Tạo bản nháp |
| 2026-10-24 | Người dùng duyệt spec và 14 câu hỏi mở theo khuyến nghị; trạng thái `Đã duyệt` |
| 2026-10-24 | Người dùng duyệt kế hoạch [phase-6](../plans/phase-6.md) (câu 1: chấp nhận file > 10 MB không có thông báo đẹp, ghi ở ADR-0010); sửa BR-006-14 cho khớp QĐ10 (dashboard đọc bằng hai transaction chỉ đọc, một của `order`, một của `catalog`); trạng thái `Đang làm` |
| 2026-10-24 | Sau review: `PriceInput` từ chối số 0 ở đầu (`007`, `0.500`) đúng BR-006-5 "nhóm ba chữ số"; sửa danh mục khóa dòng `categories` như khi xóa; `Product` dùng `@DynamicUpdate` để lưu form sửa không ghi đè việc ẩn/hiện vừa xảy ra. Đo trên Tomcat thật: với trần 2 MB, ảnh 2,5 MB nhận HTTP 413 (ADR-0010 đã sửa theo số đo) |
| 2026-10-24 | `phase-verifier` đạt 44/44 mục checklist; `./mvnw verify` 1597 test, 0 bỏ qua. Chi tiết đơn của admin hiện "Tài khoản đặt hàng" và "{họ tên} ({email})" thành nhãn và giá trị riêng (cùng nội dung với AC-006-40). Trạng thái `Hoàn thành` |

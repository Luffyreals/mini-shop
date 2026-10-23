# Spec 002: Danh sách và chi tiết sản phẩm

- **Giai đoạn:** GĐ2
- **Trạng thái:** Đang làm (duyệt 2026-10-23)
- **Liên quan:** brief §1 (MVP), §2 (luồng chính), §11; ADR-0002, 0003; spec 001 (schema, dữ liệu mẫu, CSP)

## 1. Mục tiêu

Khách (kể cả chưa đăng nhập) tìm được sản phẩm mình cần: xem danh sách có phân trang, tìm theo tên, lọc theo danh mục, sắp xếp theo giá, rồi mở trang chi tiết. Đây cũng là lần đầu dự án có giao diện thật, nên GĐ2 dựng luôn layout dùng chung cho các trang khách hàng.

## 2. Phạm vi

**Trong phạm vi**
- Trang danh sách sản phẩm: tìm theo tên, lọc theo một danh mục, sắp xếp theo giá hoặc mới nhất, phân trang. Mọi điều kiện kết hợp được với nhau và được giữ lại khi chuyển trang.
- Trang chi tiết sản phẩm.
- Layout dùng chung cho khu khách hàng: header (tên shop, link "Sản phẩm"), footer ("Dự án học tập, không bán hàng thật"), fragment phân trang, ảnh thay thế khi sản phẩm không có ảnh.
- Trang 404 tối thiểu khi không tìm thấy sản phẩm. Các trang lỗi hoàn chỉnh (403/500, giao diện chung) làm ở GĐ7.
- Giao diện theo skill `design-taste-frontend` (quy trình page), trong khuôn khổ Thymeleaf + Bootstrap và CSP đã bật (spec 001, AC-001-16).

**Ngoài phạm vi**
- Nút "Thêm vào giỏ" và mọi thứ về giỏ hàng (GĐ4).
- Đăng nhập, đăng ký, menu tài khoản (GĐ3).
- Tìm kiếm **không phân biệt dấu** (ví dụ "ao" khớp "Áo"); xem câu hỏi mở 3.
- Lọc theo khoảng giá, lọc nhiều danh mục cùng lúc, URL dạng slug, cuộn vô hạn hoặc tải bằng AJAX.
- Từ danh sách "Loại ra" của brief: đánh giá sản phẩm, wishlist, biến thể (size/màu), REST API công khai, đa ngôn ngữ.

## 3. User stories

- **US-002-1:** Là khách, tôi muốn xem danh sách sản phẩm đang bán, chia trang, để duyệt hàng mà không bị quá tải.
- **US-002-2:** Là khách, tôi muốn tìm sản phẩm theo tên để nhanh chóng thấy món mình cần.
- **US-002-3:** Là khách, tôi muốn lọc theo danh mục và sắp xếp theo giá để so sánh các lựa chọn.
- **US-002-4:** Là khách, tôi muốn xem chi tiết một sản phẩm (giá, mô tả, còn hàng hay không) trước khi quyết định mua.

## 4. Tiêu chí chấp nhận

Quy ước tham số URL của trang danh sách: `q` (từ khóa), `category` (id danh mục), `sort` (`newest` | `price_asc` | `price_desc`), `page` (bắt đầu từ 1).

| Mã | Cho | Khi | Thì | Kiểm bằng |
|---|---|---|---|---|
| AC-002-1 | Khách chưa đăng nhập, có dữ liệu mẫu | mở `/products` | HTTP 200; hiện tối đa **12** sản phẩm đang bán, mới nhất trước; mỗi thẻ có ảnh, tên, danh mục, giá và link sang trang chi tiết | `ProductControllerTest`, `ProductQueryTest` |
| AC-002-2 | Khách chưa đăng nhập | mở `/` | hiện cùng nội dung như `/products` (trang chủ là danh sách sản phẩm) | `ProductControllerTest` |
| AC-002-3 | Có sản phẩm `active = false` | xem danh sách với bất kỳ điều kiện lọc nào | sản phẩm đó **không bao giờ** xuất hiện và không được tính vào tổng số | `ProductQueryTest` |
| AC-002-4 | Có sản phẩm tên "Áo thun cổ tròn" | tìm `q=áo THUN` (có khoảng trắng hai đầu) | tìm thấy: so khớp kiểu "chứa", không phân biệt hoa thường, bỏ khoảng trắng hai đầu. `q` rỗng hoặc chỉ có khoảng trắng nghĩa là không lọc | `ProductQueryTest` |
| AC-002-5 | `q` dài hơn 100 ký tự | gửi tìm kiếm | chỉ dùng 100 ký tự đầu, không lỗi | `ProductServiceTest` |
| AC-002-6 | Chọn một danh mục | mở `/products?category={id}` | chỉ hiện sản phẩm thuộc danh mục đó. `category` không phải số thì bị bỏ qua; id không tồn tại thì danh sách rỗng (AC-002-11) | `ProductQueryTest`, `ProductControllerTest` |
| AC-002-7 | Có nhiều sản phẩm | `sort=price_asc` / `sort=price_desc` | sắp xếp theo giá tăng / giảm; khi giá bằng nhau thì theo `id` để thứ tự ổn định giữa các trang. `sort` lạ thì dùng `newest` | `ProductQueryTest`, `ProductServiceTest` |
| AC-002-8 | Có hơn 12 sản phẩm khớp điều kiện | mở `page=2` | hiện sản phẩm thứ 13–24. Có "Trang trước", "Trang sau" và số trang; link của trang hiện tại không bấm được. `page` < 1 hoặc không phải số thì về trang 1 | `ProductServiceTest`, `ProductControllerTest` |
| AC-002-9 | Đang lọc `q` + `category` + `sort` | bấm sang trang khác | URL giữ đủ cả ba tham số; ô tìm kiếm, danh mục và kiểu sắp xếp đang chọn vẫn hiển thị đúng | `ProductControllerTest` |
| AC-002-10 | Điều kiện lọc bất kỳ | xem danh sách | dòng "Tìm thấy N sản phẩm" với N là tổng số sản phẩm khớp, không phải số trên trang hiện tại | `ProductControllerTest` |
| AC-002-11 | Không có sản phẩm nào khớp (hoặc `page` vượt quá trang cuối) | xem danh sách | hiện "Không tìm thấy sản phẩm phù hợp." và link "Xóa bộ lọc" về `/products` | `ProductControllerTest` |
| AC-002-12 | Sản phẩm giá 1250000 | xem ở danh sách và chi tiết | giá hiển thị đúng dạng `1.250.000 ₫` | `ProductControllerTest` |
| AC-002-13 | Sản phẩm có `stock = 0` | xem ở danh sách và chi tiết | có nhãn "Hết hàng"; sản phẩm còn hàng có nhãn "Còn hàng" ở trang chi tiết | `ProductControllerTest` |
| AC-002-14 | Sản phẩm đang bán | mở `/products/{id}` | HTTP 200 với tên, danh mục, giá, mô tả, trạng thái kho, ảnh; tiêu đề tab là "{tên sản phẩm} \| Mini Shop" | `ProductControllerTest` |
| AC-002-15 | Id không tồn tại, sản phẩm `active = false`, hoặc id không phải số | mở `/products/{id}` | HTTP **404**, trang có "Không tìm thấy sản phẩm", "Sản phẩm không tồn tại hoặc đã ngừng bán." và link "Về danh sách sản phẩm". Không bao giờ trả 400/500 | `ProductControllerTest` |
| AC-002-16 | Sản phẩm không có ảnh (`image_path` rỗng) | xem ở danh sách và chi tiết | hiện ảnh thay thế tự host trong `static/`, có `alt` là tên sản phẩm | `ProductControllerTest` + checklist |
| AC-002-17 | Từ khóa chứa HTML, ví dụ `q=<script>alert(1)</script>` | xem danh sách | từ khóa được hiển thị lại trong ô tìm kiếm dưới dạng văn bản đã escape, không chạy script | `ProductControllerTest` |
| AC-002-18 | Mở danh sách 12 sản phẩm | đếm số câu SQL của một request | số câu SQL không tăng theo số sản phẩm trên trang (không có N+1 khi lấy tên danh mục) | `ProductQueryTest` (Hibernate statistics) |
| AC-002-19 | Mọi template trong `templates/` | build chạy | không có `<script>` inline, thuộc tính `style=`/`th:style`, hay `on…=` (onclick…), để tuân thủ CSP | `TemplateCspComplianceTest` |
| AC-002-20 | Trình duyệt desktop và điện thoại (375px) | mở danh sách và chi tiết | bố cục không tràn ngang, ảnh và chữ đọc được; console trình duyệt không có lỗi CSP | checklist |

## 5. Quy tắc nghiệp vụ

- **BR-002-1:** Khách chỉ thấy sản phẩm `active = true`, cả ở danh sách lẫn trang chi tiết.
- **BR-002-2:** Sản phẩm hết hàng (`stock = 0`) vẫn hiển thị, kèm nhãn "Hết hàng".
- **BR-002-3:** Tham số URL sai không bao giờ gây trang lỗi: giá trị không hợp lệ thì quay về mặc định, như các AC ở trên.
- **BR-002-4:** Các trang này chỉ đọc dữ liệu. Không có thay đổi dữ liệu nào, và service dùng `@Transactional(readOnly = true)`.

## 6. Giao diện và luồng

| URL | Phương thức | Quyền | Mô tả |
|---|---|---|---|
| `/` | GET | Mọi người | Trang chủ, cùng nội dung với `/products` |
| `/products` | GET | Mọi người | Danh sách: `q`, `category`, `sort`, `page` |
| `/products/{id}` | GET | Mọi người | Chi tiết sản phẩm |

Form tìm/lọc dùng phương thức **GET**, nên URL chia sẻ hoặc bookmark được và không cần CSRF token.

**Chữ hiển thị chính xác** (test và verifier sẽ tìm đúng các chuỗi này):

| Vị trí | Nội dung |
|---|---|
| Tiêu đề tab danh sách | `Sản phẩm \| Mini Shop` |
| Tiêu đề tab chi tiết | `{tên sản phẩm} \| Mini Shop` |
| Ô tìm kiếm (placeholder) / nút | `Tìm sản phẩm theo tên…` / `Tìm` |
| Lọc danh mục | nhãn `Danh mục`, lựa chọn mặc định `Tất cả danh mục` |
| Sắp xếp | nhãn `Sắp xếp`: `Mới nhất`, `Giá: thấp đến cao`, `Giá: cao đến thấp` |
| Số kết quả | `Tìm thấy N sản phẩm` |
| Trạng thái rỗng | `Không tìm thấy sản phẩm phù hợp.` + link `Xóa bộ lọc` |
| Nhãn kho | `Còn hàng` / `Hết hàng` |
| Phân trang | `Trang trước` / `Trang sau` |
| Trang 404 sản phẩm | `Không tìm thấy sản phẩm` / `Sản phẩm không tồn tại hoặc đã ngừng bán.` / link `Về danh sách sản phẩm` |
| Footer | `Dự án học tập, không bán hàng thật.` |

## 7. Dữ liệu

- **Không đổi schema.** Dùng các bảng `products` và `categories` từ V1.
- Nếu câu hỏi mở 5 chọn ảnh thật: cập nhật `DataInitializer` để gán `image_path` cho sản phẩm mẫu, và thêm file ảnh vào `static/images/products/`. Đây chỉ là thay đổi dữ liệu mẫu, không cần migration.
  - Lưu ý: DB PostgreSQL dev đang lưu trong volume `mini-shop_postgres-data` và đã có dữ liệu cũ. Seed chỉ chạy khi bảng trống, nên phải xóa volume (`docker compose down -v`) mới thấy ảnh mới. README sẽ ghi lệnh này.

## 8. Yêu cầu phi chức năng

- **Hiệu năng:** trang danh sách dùng phân trang ở DB (không tải hết rồi cắt trong Java), và lấy tên danh mục cùng lúc (fetch join hoặc DTO projection), đúng AC-002-18.
- **Bảo mật:** `/products/**` công khai; thêm vào danh sách đường dẫn công khai trong `SecurityConfig`. CSP giữ nguyên, template tuân theo AC-002-19.
- **Truy cập:** mọi ô nhập có `label`; ảnh có `alt`; nhãn kho có chữ, không chỉ dựa vào màu.
- **Kiến trúc:** controller chỉ nhận tham số và chọn view; việc chuẩn hóa tham số và truy vấn nằm trong `ProductService` (`@Transactional(readOnly = true)`); view nhận DTO, không nhận entity. `ArchitectureTest` vẫn phải xanh.

## 9. Câu hỏi mở

Đã chốt ngày 2026-10-23: người dùng duyệt **tất cả** khuyến nghị dưới đây.

| # | Câu hỏi | Trả lời |
|---|---|---|
| 1 | Trang chủ `/` hiển thị gì? | Hiển thị luôn danh sách sản phẩm (AC-002-2); `/products` là URL chuẩn khi có bộ lọc. |
| 2 | Mỗi trang bao nhiêu sản phẩm? | 12: chia đều cho lưới 2, 3 hoặc 4 cột. |
| 3 | Tìm kiếm có phân biệt dấu tiếng Việt không? | GĐ2 **không phân biệt hoa thường nhưng phân biệt dấu** ("áo" khớp "Áo", "ao" thì không). Bỏ dấu cần extension `unaccent` của PostgreSQL, không chạy được trên H2; để sau nếu thật sự cần. |
| 4 | Trang chi tiết hiện số lượng tồn kho cụ thể không? | Không: chỉ "Còn hàng" / "Hết hàng". |
| 5 | Ảnh sản phẩm mẫu lấy ở đâu? | Theo spec 001: dùng **ảnh thật có giấy phép miễn phí**, chọn qua skill thiết kế, tự host trong `static/images/products/`, ghi nguồn trong README. Sản phẩm không có ảnh dùng ảnh thay thế (AC-002-16). |
| 6 | Trang chi tiết có nút "Thêm vào giỏ" (bị vô hiệu) không? | Không có ở GĐ2; GĐ4 sẽ thêm khi có giỏ hàng. |
| 7 | Bộ lọc có hiện các danh mục không có sản phẩm nào không? | Có, hiện tất cả danh mục, sắp theo tên. |

## 10. Lịch sử thay đổi

| Ngày | Thay đổi |
|---|---|
| 2026-10-23 | Tạo bản nháp |
| 2026-10-23 | Người dùng duyệt theo tất cả khuyến nghị. |

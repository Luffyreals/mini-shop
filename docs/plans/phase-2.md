# Kế hoạch GĐ2: Danh sách và chi tiết sản phẩm

- **Spec:** [002 Danh sách và chi tiết sản phẩm](../specs/002-product-catalog.md) (Đã duyệt 2026-10-23, người dùng chấp nhận cả 7 khuyến nghị)
- **Nhánh:** `phase-2/product-catalog`, tạo từ `main` tại tag `v0.1.0`
- **Đã có từ GĐ1, không làm lại:** entity `Product`/`Category`, `ProductRepository.decrementStock`, `CategoryRepository`, V1 (có index `category_id`, `name`), `DataInitializer` (5 danh mục, 22 sản phẩm: 1 hết hàng, 1 bị ẩn), `SecurityConfig` với CSP bật thật, `ArchitectureTest`, JaCoCo, CI chặn test bị bỏ qua.
- **Không đổi schema** (spec §7), nên giai đoạn này không có migration và không cần `schema-guardian`.

## 1. Mục tiêu

- Khách chưa đăng nhập mở `/` hoặc `/products` thì thấy danh sách sản phẩm đang bán: tìm theo tên, lọc theo danh mục, sắp xếp, phân trang 12 sản phẩm một trang. Tham số sai không bao giờ gây trang lỗi. `/products/{id}` hiện chi tiết; id sai, sản phẩm bị ẩn hoặc id không phải số đều trả 404 với trang riêng.
- Có layout dùng chung cho khu khách hàng (head, header, footer, phân trang, thẻ sản phẩm, ảnh thay thế), theo hướng thiết kế rút từ skill `design-taste-frontend`. Ảnh sản phẩm là ảnh thật có giấy phép miễn phí, tự host và được ghi nguồn trong README. Giá luôn hiện dạng `1.250.000 ₫` qua một cách định dạng duy nhất.
- Mọi template tuân thủ CSP, có test tự động giữ quy tắc này.
- `./mvnw verify` xanh **với 0 test bị bỏ qua** (máy đã bật Docker). Test truy vấn chạy trên PostgreSQL thật và chứng minh không có N+1.

## 2. Quyết định thiết kế

**QĐ1. Truy vấn danh sách: Spring Data `Specification` + `@EntityGraph(attributePaths = "category")`, rồi map sang DTO trong service.**
- `ProductRepository` kế thừa thêm `JpaSpecificationExecutor<Product>` và khai báo lại `Page<Product> findAll(Specification<Product> spec, Pageable pageable)` kèm `@EntityGraph`. Nhờ vậy câu SELECT lấy luôn danh mục bằng một JOIN (AC-002-18).
- Điều kiện nằm trong class package-private `ProductSpecifications`: `isActive()`, `nameContains(String)`, `inCategory(Long)`. Service ghép chúng bằng `Specification.allOf(list)`.
- **Phân trang với fetch join an toàn ở đây** vì `category` là quan hệ `@ManyToOne`, nên LIMIT/OFFSET vẫn chạy trong SQL. Cái bẫy "phân trang trong bộ nhớ" (Hibernate cảnh báo HHH90003004) chỉ xảy ra khi fetch join một **collection**.
- **Không gọi `root.fetch(...)` trong Specification.** Spring Data dùng chính Specification đó để tạo câu `count`, và fetch trong câu count sẽ làm Hibernate báo lỗi. `@EntityGraph` chỉ áp vào câu SELECT, không áp vào câu count.
- Đã kiểm mã nguồn Spring Data JPA 4: `Specification.where(null)` không còn nhận `null`. Muốn có Specification "không lọc gì" thì dùng `Specification.unrestricted()`, hoặc `allOf(...)` với danh sách điều kiện.

*Phương án bị loại:*
- **JPQL với tham số tùy chọn** (`:q is null or lower(p.name) like :q`). Trên PostgreSQL, tham số `null` đi vào hàm (`lower(:q)`) hay gây lỗi "could not determine data type of parameter". Ngoài ra phải viết tay câu count.
- **DTO projection bằng `select new ...`.** Cách này lấy ít cột hơn, nhưng phải viết `countQuery` riêng, ghi tên class đầy đủ trong chuỗi JPQL, và sort động theo `Pageable` dễ vỡ. Với 12 dòng một trang, lợi ích này không đáng kể. Specification thì GĐ6 (admin, cần cả sản phẩm ẩn) dùng lại được.

**QĐ2. Tham số URL: controller nhận chuỗi thô, service chuẩn hóa. Không bind `Pageable`, không dùng `one-indexed-parameters`, không dùng `@PageableDefault`.**
- Controller nhận `q`, `category`, `sort`, `page` đều là `String` (`@RequestParam(required = false)`), gói vào record `ProductSearchParams` rồi gọi service. Vì kiểu là `String` nên việc bind không bao giờ thất bại, tức không bao giờ có 400 (BR-002-3).
- `ProductService` gọi `ProductFilter.from(params)` để chuẩn hóa:
  - `q`: `strip()`, cắt còn 100 ký tự; rỗng thì `null`;
  - `category`: parse `Long`, lỗi thì `null`;
  - `sort`: `ProductSort.fromKey(...)`, giá trị lạ thì `NEWEST`;
  - `page`: parse `int`, lỗi hoặc < 1 thì 1.
- Chỉ có **một chỗ** đổi từ trang bắt đầu từ 1 sang trang bắt đầu từ 0: `PageRequest.of(filter.page() - 1, 12, filter.sort().toSort())`.

*Lý do không bind `Pageable`:*
- Resolver của Spring hiểu `sort=price_asc` là tên thuộc tính `price_asc`, nên sẽ ném `PropertyReferenceException` và trả 500.
- Spec cần những quy tắc riêng: giá trị sai thì về mặc định một cách im lặng, và cắt `q` còn 100 ký tự.
- `spring.data.web.pageable.one-indexed-parameters` là cấu hình **toàn cục**, nên sẽ âm thầm ảnh hưởng mọi `Pageable` của admin ở GĐ6.

**QĐ3. Ngữ nghĩa tìm kiếm.**
- Dùng `cb.like(cb.lower(root.get("name")), pattern, '\\')`, với `pattern = "%" + escape(q.toLowerCase(Locale.ROOT)) + "%"`.
- `escape` thêm `\` trước `\`, `%` và `_`, để khách gõ "50%" thì tìm đúng chuỗi "50%", chứ không phải "mọi thứ". Hàm này chạy trên cả H2 và PostgreSQL.
- `Locale.ROOT` tránh lỗi kiểu "chữ i tiếng Thổ Nhĩ Kỳ" khi máy chủ có locale lạ.
- Tìm kiếm phân biệt dấu, theo câu hỏi mở số 3 của spec.

**Rủi ro cần biết:** `lower()` của PostgreSQL phụ thuộc `lc_ctype` của database. Với locale `C`, `lower('Á')` vẫn là `'Á'`, nên tìm "áo" sẽ không ra "Áo thun".
- Image `postgres:17-alpine` khởi tạo với `LANG=en_US.utf8`, nên dự kiến `lower()` chạy đúng. `ProductQueryTest` có một case dành riêng để chứng minh điều này.
- Nếu case đó fail, xử lý theo phương án dự phòng ở câu hỏi số 2 (mục 5). Không sửa bằng cách lách trong Java.

**QĐ4. Sắp xếp: enum `ProductSort` với khóa URL và `Sort` tương ứng. Luôn có `id` làm tiêu chí phụ.**

| Khóa URL | Sắp xếp |
|---|---|
| `newest` (mặc định) | `createdAt DESC, id DESC` |
| `price_asc` | `price ASC, id ASC` |
| `price_desc` | `price DESC, id ASC` |

Nếu không có tiêu chí phụ, các sản phẩm cùng giá có thể đổi chỗ giữa hai lần truy vấn. Khi đó một sản phẩm có thể xuất hiện ở cả trang 1 lẫn trang 2, hoặc biến mất (AC-002-7). Seed tạo sản phẩm trong cùng một transaction nên `createdAt` có thể trùng nhau, và `id DESC` giữ cho thứ tự "mới nhất" ổn định.

**QĐ5. Danh sách danh mục cho bộ lọc được sắp xếp trong Java, bằng `Collator` tiếng Việt.**
- Service lấy `categoryRepository.findAll()`, sắp bằng `Collator.getInstance(Locale.forLanguageTag("vi"))`, rồi map sang `CategoryOption`.
- `Collator` không an toàn khi dùng chung giữa nhiều luồng, nên tạo mới trong mỗi lần gọi. Chỉ có vài danh mục nên chi phí không đáng kể.

*Phương án bị loại:* `ORDER BY name` trong DB.
- Image Alpine dùng musl, không có collation tiếng Việt, nên thực tế so sánh theo byte. Khi đó "Điện tử" (chữ Đ có mã U+0110) đứng **sau** "Thời trang".
- H2 lại sắp theo cách khác, nên hai DB cho hai kết quả khác nhau.

**QĐ6. Bố cục package và quyền truy cập.** Tất cả nằm phẳng trong `catalog`, mặc định package-private:

| File | Vai trò |
|---|---|
| `ProductController` (thay `HomeController`, file cũ bị xóa) | `GET /`, `GET /products`, `GET /products/{id}` |
| `ProductService` | `@Service @Transactional(readOnly = true)`: `search(ProductSearchParams)`, `getDetail(Long)` |
| `ProductSpecifications`, `ProductSort`, `ProductSearchParams`, `ProductFilter` | Truy vấn và chuẩn hóa tham số |
| `ProductCardView`, `ProductDetailView`, `CategoryOption`, `ProductListView` | DTO dạng `record` cho view |
| `ProductNotFoundAdvice` | Trả 404 cho riêng `ProductController` (xem QĐ7) |

- **`readOnly = true`:** Spring đặt flush mode `MANUAL`, nên Hibernate bỏ qua bước dirty-check. Cách này vừa nhanh hơn vừa thể hiện BR-002-4: các trang này chỉ đọc dữ liệu.
- **Record package-private vẫn dùng được trong Thymeleaf.** Đã kiểm mã nguồn `ReflectivePropertyAccessor` của Spring Framework: SpEL đọc được accessor kiểu record (`name()`) và tự gọi `makeAccessible`.
- **`ProductService` để package-private.** Khi nào GĐ4 (`cart`) thật sự cần gọi thì mới chuyển sang public.
- **Package `common`:** thêm `NotFoundException` (public), `PageNav` (QĐ9), `VndFormatter` và `ViewHelpersDialect` (QĐ8). `common` không phụ thuộc vào package tính năng nào, đúng như `ArchitectureTest` yêu cầu.

**QĐ7. 404: `common.NotFoundException`, cộng `catalog.ProductNotFoundAdvice` chỉ áp cho `ProductController`.**
- Advice dùng `@ControllerAdvice(assignableTypes = ProductController.class)` và `@Order(Ordered.HIGHEST_PRECEDENCE)`. Nó bắt `NotFoundException` và `MethodArgumentTypeMismatchException`, trả status 404 cùng view `product/not-found`.
- Vì `@PathVariable Long id` nên các trường hợp sau đều vào advice này và nhận 404, đúng như AC-002-15 ("không bao giờ 400/500"):
  - `/products/abc`;
  - `/products/99999999999999999999` (tràn kiểu `Long`).
- `getDetail` dùng `findByIdAndActiveTrue(id)`, có `@EntityGraph("category")`. Sản phẩm bị ẩn được xử lý y như id không tồn tại: không lộ ra rằng sản phẩm đó từng có.
- **Ghi chú cho GĐ7:** khi thêm advice toàn cục trong `common`, advice của catalog vẫn phải giữ thứ tự ưu tiên cao hơn. Spring chọn advice theo `@Order`, không theo độ cụ thể.

*Phương án bị loại:*
- **Advice toàn cục trong `common` hiện chữ "Không tìm thấy sản phẩm".** Chữ này sai cho đơn hàng ở GĐ5.
- **Regex trên đường dẫn (`{id:\d+}`).** Id quá dài vẫn khớp regex rồi tràn `Long`, dẫn tới 400. Còn id không khớp thì rơi vào trang lỗi chung của Boot, với chữ sai.

**QĐ8. Định dạng VND: expression object `#vnd` cho Thymeleaf, đăng ký qua một bean `IDialect`.**
- `common.VndFormatter` là class thuần, có `String format(BigDecimal amount)`:
  - `null` cho ra `""`;
  - `1250000` cho ra `"1.250.000 ₫"`, với **dấu cách thường** (U+0020);
  - mỗi lần gọi tạo một `DecimalFormat("#,##0")` mới, với `DecimalFormatSymbols` (grouping là `.`, decimal là `,`) và `RoundingMode.HALF_UP`. Tạo mới mỗi lần vì `DecimalFormat` không an toàn khi dùng chung giữa nhiều luồng.
- `common.ViewHelpersDialect` (`@Component`, kế thừa `AbstractDialect` và cài `IExpressionObjectDialect`) đưa formatter vào template dưới tên `vnd`. Template chỉ viết `th:text="${#vnd.format(p.price)}"`.
- CSS đặt `white-space: nowrap` cho giá để `₫` không bị rớt xuống dòng.
- Đã kiểm `WebMvcTypeExcludeFilter` của Boot 4: `org.thymeleaf.dialect.IDialect` nằm trong `OPTIONAL_INCLUDES`, nên dialect **tự có mặt trong `@WebMvcTest`** mà không cần `@Import`.

*Phương án bị loại:*
- **`#numbers.formatDecimal(...)` + `' ₫'` viết trong từng template.** Đó chính là kiểu "format inline" mà ta muốn tránh.
- **`Formatter<BigDecimal>` toàn cục với `${{...}}`.** Nó cũng sẽ áp vào ô nhập giá của admin ở GĐ6, làm ô hiện "1.250.000 ₫" rồi không parse lại được.
- **`NumberFormat.getCurrencyInstance(vi-VN)`.** Kết quả có dấu cách không ngắt (NBSP), và vị trí ký hiệu phụ thuộc phiên bản dữ liệu CLDR của JDK. Test so chuỗi chính xác sẽ vỡ khi nâng JDK.
- **Định dạng sẵn thành `String` trong DTO.** Cách này để service gánh việc trình bày, và mất kiểu `BigDecimal`.

**QĐ9. Phân trang: `common.PageNav`, URL do controller dựng từ bộ lọc đã chuẩn hóa.**
- `PageNav.of(int current, int totalPages, IntFunction<String> urlForPage)` tạo:
  - tối đa 5 số trang quanh trang hiện tại, kẹp trong `[1, totalPages]`;
  - `previousUrl`, là `null` ở trang 1;
  - `nextUrl`, là `null` ở trang cuối.
- Logic cửa sổ trang nằm trong Java nên test đơn vị được, và GĐ5/GĐ6 dùng lại được.
- `ProductController` có một hàm private dựng URL:
  ```java
  UriComponentsBuilder.fromPath("/products")
      .queryParam("q", "{q}") ... .encode().buildAndExpand(values).toUriString()
  ```
  - Chỉ thêm `q` và `category` khi có giá trị; `sort` và `page` luôn có mặt (AC-002-9).
  - Phải gọi `encode()` **trước** `buildAndExpand`, để giá trị biến được mã hóa chặt, kể cả dấu `+`. Nếu không, tìm "C++" sẽ thành `q=C++`, và server đọc ra "C  ".
- Template chỉ viết `th:href="@{${link.url}}"`.

*Phương án bị loại:* dựng link bằng cú pháp `@{/products(q=${...},...)}` của Thymeleaf. Tham số `null` vẫn bị in ra (`?q&category`), và logic giữ tham số nằm trong HTML, không test đơn vị được.

**QĐ10. Template: fragment thuần (không dùng Layout Dialect), tuân thủ CSP.**
- `fragments/layout.html` gồm:
  - `head(title)`: meta charset/viewport, `<title th:text="${title}">`, CSS Bootstrap qua WebJars và `app.css`;
  - `header`: tên shop và link "Sản phẩm";
  - `footer`: "Dự án học tập, không bán hàng thật.".
- `fragments/product.html` gồm:
  - `card(p)`;
  - `image(src, alt)`: ảnh thay thế khi `src` là `null`;
  - `stock(inStock, showInStock)`: nhãn kho có chữ.
- `fragments/pagination.html` gồm `pagination(nav)`.
- Trang:
  - `product/list.html`, `product/detail.html`, `product/not-found.html`;
  - xóa `index.html`.
- Không cần JS của Bootstrap, vì header không có menu thu gọn. Nếu có JS (chỉ để làm hiệu ứng), nó nằm trong `static/js/` và được nạp bằng `<script src defer>`. Nội dung không bao giờ bị ẩn khi JS lỗi.
- Không được có:
  - `<script>` inline hay thẻ `<style>`;
  - `style=` hay `th:style`;
  - thuộc tính `on…=` hay `th:on…`;
  - link dạng `javascript:`.

*Phương án bị loại:* Thymeleaf Layout Dialect. `CLAUDE.md` đã loại nó; fragment thuần là đủ cho ba trang.

**QĐ11. Ảnh sản phẩm và dữ liệu mẫu.**
- **Định dạng `image_path`:** đường dẫn web tuyệt đối, ví dụ `/images/products/tai-nghe-khong-day.jpg`.
  - `ProductService` chuyển giá trị rỗng thành `imageUrl = null`; template thấy `null` thì dùng ảnh thay thế.
  - Việc chuyển `image_path` sang URL nằm **ở một chỗ duy nhất**, để GĐ6 chuyển nó vào `StorageService` khi có ảnh upload.
- **Tên file chốt trước ở bảng dưới.** Nhờ vậy frontend tải ảnh và backend sửa seed làm song song được, không phải đoán tên.
- **Giấy phép:**
  - ưu tiên CC0 hoặc public domain (`assets.mjs --license pd`);
  - nếu không có ảnh trung thực thì được dùng CC BY / CC BY-SA (`--license safe`), ghi nguồn đầy đủ trong README theo quyết định đã duyệt;
  - cấm NC và ND;
  - chỉ thu nhỏ ảnh, không chỉnh sửa khác.
- **Kích thước:** JPEG rộng khoảng 800px, mỗi file ≤ 250 KB.
- **Nơi tải ảnh:** tải vào `.taste-skill/assets/` (đã có trong gitignore), rồi chép sang `static/images/products/`. Lý do: `--get` ghi `credits.json` ngay trong thư mục đích, và mọi thứ trong `static/` đều công khai.
- **Sản phẩm cố ý không có ảnh** (để thể hiện AC-002-16):
  - "Truyện ngắn chọn lọc" và "Giáo trình lập trình Java cơ bản": đây là sách có tựa cụ thể, nên ảnh bìa tìm được sẽ là bìa của cuốn khác, gây hiểu nhầm;
  - "Ly thủy tinh kiểu cũ": sản phẩm bị ẩn, không bao giờ hiển thị.
- **DB dev cũ không tự có ảnh**, vì seed chỉ chạy khi DB trống. README phải ghi lệnh `docker compose down -v`, kèm cảnh báo rằng lệnh này xóa dữ liệu dev. Profile `h2` thì luôn seed mới.

| Sản phẩm (seed) | File trong `static/images/products/` |
|---|---|
| Áo thun cotton cổ tròn | `ao-thun-cotton-co-tron.jpg` |
| Quần jean nam ống đứng | `quan-jean-nam-ong-dung.jpg` |
| Váy liền thân dáng chữ A | `vay-lien-than-dang-chu-a.jpg` |
| Áo khoác gió hai lớp | `ao-khoac-gio-hai-lop.jpg` |
| Tai nghe không dây | `tai-nghe-khong-day.jpg` |
| Sạc dự phòng 10000 mAh | `sac-du-phong-10000mah.jpg` |
| Chuột không dây văn phòng | `chuot-khong-day-van-phong.jpg` |
| Bàn phím cơ cỡ nhỏ | `ban-phim-co-co-nho.jpg` |
| Loa bluetooth mini | `loa-bluetooth-mini.jpg` |
| Bộ chén đĩa sứ 12 món | `bo-chen-dia-su-12-mon.jpg` |
| Đèn bàn LED chống cận | `den-ban-led-chong-can.jpg` |
| Gối ôm lông cừu giả | `goi-om-long-cuu-gia.jpg` |
| Bình giữ nhiệt 500 ml | `binh-giu-nhiet-500ml.jpg` |
| Sổ tay bìa cứng A5 | `so-tay-bia-cung-a5.jpg` |
| Bộ bút bi mực gel 10 cây | `bo-but-bi-muc-gel-10-cay.jpg` |
| Thảm tập yoga chống trượt | `tham-tap-yoga-chong-truot.jpg` |
| Bóng đá số 5 | `bong-da-so-5.jpg` |
| Dây nhảy thể lực | `day-nhay-the-luc.jpg` |
| Tạ tay cao su 5 kg | `ta-tay-cao-su-5kg.jpg` |
| Truyện ngắn chọn lọc, Giáo trình lập trình Java cơ bản, Ly thủy tinh kiểu cũ | *(không có ảnh)* |

Nếu không tìm được ảnh trung thực cho một dòng nào đó, F1 ghi rõ dòng đó trong báo cáo. B3 bỏ qua dòng đó, và sản phẩm dùng ảnh thay thế. **Không dùng ảnh sai chủ đề.**

**QĐ12. Chạy skill `design-taste-frontend` (page run) trong khuôn khổ dự án.**
- **Vẽ hướng thiết kế một lần cho khu storefront** và lưu vào `.taste-skill/`. Thư mục này bị gitignore, nên F1 phải ghi tên hướng, seed, palette và kiểu chữ vào **comment đầu `app.css`**, để hướng thiết kế còn lưu lại trong repo và các giai đoạn sau dựng lại được.
- **Có làm:**
  - bước 1–3: đọc brief, viết "argument", vẽ hướng;
  - bước 4: chỉ câu "ý tưởng";
  - bước 5: chữ chỉ lấy từ bảng "Chữ hiển thị chính xác" của spec, cộng nhãn ẩn cho ô nhập;
  - bước 6: token, ảnh;
  - bước 7–8: `lint.mjs`, `inspect.mjs` ở 1440/768/390, tự xem ảnh chụp, tối đa 3 vòng;
  - bước 10: báo cáo.
- **Không làm** (`CLAUDE.md` coi những thứ này là tính năng, cần người dùng duyệt):
  - "one live thing";
  - scroll scene;
  - section hoặc hero có chữ tự nghĩ ra;
  - bước 9 bắt buộc ≥ 8 phần tử chuyển động.

  Chỉ giữ phản hồi hover/focus/press và có thể thêm hiệu ứng xuất hiện nhẹ, luôn kèm `prefers-reduced-motion`.
- **Giữ Bootstrap 5.3.** Hướng thiết kế được thể hiện qua biến `--bs-*` và các class trong `app.css`. Ô nhập vẫn có viền nhìn thấy được: khả năng truy cập quan trọng hơn quy tắc "không có viền mảnh" của skill.
- **Mỗi `FAIL`/`WARN` của inspector mâu thuẫn với quy tắc dự án** được ghi lại là "sai lệch có chủ đích" trong báo cáo, kèm lý do. Không thêm tính năng để chiều máy đo.
- **Font:**
  - tối đa một họ chữ, ≤ 2 file `woff2`, tự host trong `static/fonts/`, kèm file giấy phép (OFL);
  - **bắt buộc có đủ glyph tiếng Việt** (ví dụ ệ, ữ, ở, Đ). Nếu font được chia không hỗ trợ tiếng Việt, chọn font gần nhất có hỗ trợ, hoặc dùng font hệ thống, và ghi lại lý do;
  - CSP `default-src 'self'` sẽ chặn Google Fonts từ CDN.
- Skill nói ảnh tìm được "không bao giờ được trình bày là sản phẩm của khách hàng". Ở đây người dùng đã quyết định dùng ảnh thật làm ảnh minh họa cho cửa hàng demo, và footer ghi rõ "không bán hàng thật". Đây là sai lệch có chủ đích, phải ghi trong báo cáo.

**QĐ13. Chiến lược test.**
- **`ProductQueryTest`** (`@DataJpaTest` + Testcontainers + `@Import({TestcontainersConfiguration.class, ProductService.class})`): test **qua service thật**, nên kiểm cả chuẩn hóa, Specification, sort và phân trang trên PostgreSQL.
  - **Đếm N+1:** bật thuộc tính `spring.jpa.properties.hibernate.generate_statistics=true`, rồi lấy `Statistics` qua `entityManagerFactory.unwrap(SessionFactory.class)`. Phải `flush()` + `clear()` EntityManager **trước khi đo**: nếu không, danh mục đã nằm sẵn trong bộ nhớ đệm cấp 1 và N+1 bị che mất.
  - **Cách assert:** số câu SQL với 12 sản phẩm thuộc 12 danh mục khác nhau **bằng** số câu với 1 sản phẩm, và ≤ 3 (trang, count, danh mục).
- **`ProductServiceTest`:** Mockito thuần, `ArgumentCaptor<Pageable>`.
- **`ProductControllerTest`:** `@WebMvcTest(ProductController.class)` + `@Import(SecurityConfig.class)` + `@MockitoBean ProductService` (`org.springframework.test.context.bean.override.mockito`; `@MockBean` đã bị gỡ ở Boot 4).
- **`SecurityConfigTest`** chuyển sang `@WebMvcTest(useDefaultFilters = false)`.
  - Lý do: `@WebMvcTest` không chỉ định controller sẽ nạp **mọi** controller. Khi đó `ProductController` đòi bean `ProductService`, mà test ở package `config` không mock được class package-private.
  - Test bảo mật chỉ cần chuỗi filter, nên không cần controller nào. Header CSP vẫn được ghi cả trên response 404.

## 3. Hợp đồng view (chốt trước để backend và frontend làm song song)

Các kiểu dưới đây do backend tạo ở B1/B2; frontend dựng template theo đúng tên này. Nếu cần thêm thuộc tính, frontend dừng lại và báo phiên chính; không tự đặt tên.

```java
// catalog
record ProductCardView(Long id, String name, String categoryName, BigDecimal price, boolean inStock, String imageUrl) {}
record ProductDetailView(Long id, String name, String categoryName, BigDecimal price, String description,
                         boolean inStock, String imageUrl) {}
record CategoryOption(Long id, String name) {}
enum ProductSort { NEWEST("newest"), PRICE_ASC("price_asc"), PRICE_DESC("price_desc"); String key(); }
record ProductFilter(String q, Long categoryId, ProductSort sort, int page) {}   // đã chuẩn hóa; q null nếu không lọc; page >= 1
record ProductListView(List<ProductCardView> products, long totalElements, int totalPages,
                       ProductFilter filter, List<CategoryOption> categories) {}
// common
record PageNav(int current, int totalPages, List<PageLink> links, String previousUrl, String nextUrl) {
    record PageLink(int number, String url, boolean current) {}
}
```

| View | URL | Status | Model |
|---|---|---|---|
| `product/list` | `GET /`, `GET /products` | 200 | `view` (`ProductListView`), `pageNav` (`PageNav`) |
| `product/detail` | `GET /products/{id}` | 200 | `product` (`ProductDetailView`) |
| `product/not-found` | `GET /products/{id}` khi không tìm thấy | 404 | (không có) |

Quy ước markup để test và verifier dựa vào:

- **Form lọc:**
  - `<form method="get" th:action="@{/products}">`;
  - các ô nhập `name="q"` (`maxlength="100"`, có `<label>`), `name="category"` (lựa chọn đầu là `value=""` "Tất cả danh mục"), `name="sort"` (3 giá trị `newest`/`price_asc`/`price_desc`);
  - nút `Tìm`;
  - giá trị đang chọn hiện bằng `th:value` / `th:selected`;
  - form **không** gửi `page`, nên đổi bộ lọc thì quay về trang 1.
- **Số kết quả:** dòng `Tìm thấy N sản phẩm` luôn hiển thị, với `N = view.totalElements`.
- **Danh sách rỗng:** khi `view.products` rỗng, hiện `Không tìm thấy sản phẩm phù hợp.` và `<a href="/products">Xóa bộ lọc</a>`. Không hiện phân trang.
- **Phân trang:** chỉ hiện khi `pageNav.totalPages > 1` và danh sách không rỗng. "Trang trước" và "Trang sau" luôn có mặt:
  - URL tương ứng là `null` thì hiện dạng `<span>`, không có `href`;
  - trang hiện tại là phần tử **không có `href`** và mang `aria-current="page"`.
- **Ảnh:**
  - `alt` luôn là tên sản phẩm;
  - ảnh thay thế ở `/images/placeholder-product.svg`;
  - giá luôn hiện bằng `${#vnd.format(...)}`.
- **Nhãn kho:**
  - ở danh sách, chỉ hiện `Hết hàng` khi `!inStock`;
  - ở chi tiết, hiện `Còn hàng` hoặc `Hết hàng`;
  - không bao giờ hiện số lượng tồn.
- **Tiêu đề tab:**
  - danh sách: `Sản phẩm | Mini Shop`;
  - chi tiết: `{tên} | Mini Shop`;
  - 404: `Không tìm thấy sản phẩm | Mini Shop`.
- **Mô tả sản phẩm:** dùng `th:text`, kèm CSS `white-space: pre-line`. Tuyệt đối không dùng `th:utext` ở bất kỳ đâu.

## 4. Danh sách task

"Song song với" chỉ áp dụng khi hai task không sửa chung file nào. Đường dẫn Java tính từ `src/main/java/com/minishop/`; template và static tính từ `src/main/resources/`.

| # | Task | Agent | File | AC | Phụ thuộc | Song song với | Xong khi |
|---|---|---|---|---|---|---|---|
| B1 | Truy vấn và `ProductService` | `backend-developer` | `catalog/`: `ProductRepository` (sửa), `ProductSpecifications`, `ProductSort`, `ProductSearchParams`, `ProductFilter`, `ProductCardView`, `ProductDetailView`, `CategoryOption`, `ProductListView`, `ProductService` (mới); `common/NotFoundException` (mới) | 002-1, 3, 4, 5, 6, 7, 8, 10, 14, 15, 18; BR-002-1, 4 | – | F1, TW1 | Xem chi tiết B1 |
| TW1 | Chuẩn bị test cho việc thay `HomeController` | `test-writer` | Xóa `src/test/.../catalog/HomeControllerTest.java`; sửa `src/test/.../config/SecurityConfigTest.java` | 001-6, 001-16 (giữ xanh) | – | B1, F1 | Xem chi tiết TW1 |
| F1 | Hướng thiết kế storefront, ảnh sản phẩm, font, ảnh thay thế, design token | `frontend-developer` | `.taste-skill/**` (không commit), `static/css/app.css` (phần token và comment hướng thiết kế), `static/images/products/*.jpg`, `static/images/placeholder-product.svg`, `static/fonts/**` | 002-16, 002-20 (một phần) | – | B1, TW1, B2 | Xem chi tiết F1 |
| B2 | Tầng web: controller, 404, `PageNav`, `#vnd`, đường dẫn công khai | `backend-developer` | `catalog/ProductController`, `catalog/ProductNotFoundAdvice` (mới); xóa `catalog/HomeController`; `common/PageNav`, `common/VndFormatter`, `common/ViewHelpersDialect` (mới); `config/SecurityConfig` | 002-1, 2, 8, 9, 12, 15; spec §8 (bảo mật) | B1, TW1 | F1 | Xem chi tiết B2 |
| B3 | Gán `image_path` cho dữ liệu mẫu | `backend-developer` | `config/DataInitializer` | 002-16 | F1 (danh sách file thực tế) | F2, TW2 | Xem chi tiết B3 |
| F2 | Layout, fragment, 3 trang, CSS; gate của skill | `frontend-developer` | `templates/fragments/{layout,product,pagination}.html`, `templates/product/{list,detail,not-found}.html` (mới); xóa `templates/index.html`; `static/css/app.css`; `static/js/app.js` (nếu cần) | 002-1, 2, 8–17, 19, 20 | F1, B2; cần B3 xong để kiểm ảnh | B3, TW2 | Xem chi tiết F2 |
| TW2 | Test đơn vị và test truy vấn trên PostgreSQL | `test-writer` | `src/test/.../catalog/ProductServiceTest`, `catalog/ProductQueryTest`, `common/PageNavTest`, `common/VndFormatterTest` (mới) | 002-3, 4, 5, 6, 7, 8, 12, 15, 18 | B1, B2 | F2, B3 | Xem chi tiết TW2 |
| TW3 | Test web, CSP của template, smoke H2, kiến trúc | `test-writer` | `src/test/.../catalog/ProductControllerTest`, `TemplateCspComplianceTest` (mới); `H2ProfileSmokeTest`, `ArchitectureTest` (sửa) | 002-1, 2, 6, 8–17, 19; 001-5, 001-16 | F2, B3, TW2 | D | Xem chi tiết TW3 |
| D | Cập nhật tài liệu | phiên chính | `README.md`, `CHANGELOG.md`, `CLAUDE.md`, `docs/specs/002-product-catalog.md`, `docs/specs/README.md`, `docs/specs/001-project-foundation.md` (chỉ mục Lịch sử) | – | F1 (danh sách nguồn ảnh), B3, F2 | TW3 | Xem chi tiết D |
| R | Review diff của giai đoạn | `java-code-reviewer` | (chỉ đọc) | – | B1–B3, F1–F2, TW1–TW3, D | – | Không còn lỗi nghiêm trọng; lỗi nhỏ đã sửa hoặc được người dùng chấp nhận |
| V | Kiểm tra cuối giai đoạn | `phase-verifier` | (chỉ đọc) | 002-1 đến 002-20 | R | – | Checklist mục 5 đạt |

- **Đợt 1** (song song): B1, TW1, F1.
- **Đợt 2:** B2 (sau B1 và TW1). B3 chạy ngay khi F1 xong. TW2 chạy sau B2.
- **Đợt 3:** F2 (sau F1 và B2), song song với B3 và TW2.
- **Đợt 4:** TW3 song song với D, rồi đến R và V.

**Đường găng:** F1 → F2 → TW3 → R → V. F1 dài nhất, vì gồm vẽ hướng và tìm khoảng 19 ảnh, nên giao F1 ngay từ đầu.

**Build xanh sau mỗi task:**
- TW1 làm trước B2, để việc xóa `HomeController` không làm vỡ phần biên dịch test.
- Sau B2, `./mvnw verify` vẫn xanh. Tuy nhiên `/` và `/products` trả 500 khi chạy app (chưa có template), cho tới khi F2 xong. Đây là trạng thái trung gian có chủ đích; không push ở giữa các đợt.

### Chi tiết B1 (`backend-developer`)

**`ProductRepository`:**
- kế thừa thêm `JpaSpecificationExecutor<Product>`;
- khai báo lại `@EntityGraph(attributePaths = "category") Page<Product> findAll(Specification<Product> spec, Pageable pageable)`;
- thêm `@EntityGraph(attributePaths = "category") Optional<Product> findByIdAndActiveTrue(Long id)`;
- giữ nguyên `decrementStock`.

**Các class mới:**
- `ProductSpecifications`: theo QĐ1 và QĐ3, có escape `\ % _` và `like` kèm ký tự escape `'\\'`.
- `ProductSort`: theo QĐ4, có `fromKey(String)` và `toSort()`.
- `ProductSearchParams(String q, String category, String sort, String page)`: tham số thô từ URL.
- `ProductFilter`: có `static from(ProductSearchParams)` theo QĐ2. Cắt chuỗi **sau** `strip()`.
- Các view record: theo mục 3.

**`ProductService`** (`@Service @Transactional(readOnly = true)`, package-private):
- **`ProductListView search(ProductSearchParams)`:**
  1. chuẩn hóa tham số;
  2. ghép Specification từ `isActive()`, cộng `nameContains` và `inCategory` nếu có;
  3. gọi `findAll(spec, PageRequest.of(page - 1, 12, sort))`;
  4. map sang `ProductCardView`;
  5. lấy danh mục, sắp theo QĐ5;
  6. trả `ProductListView`.

  Hằng số `PAGE_SIZE = 12`.
- **`ProductDetailView getDetail(Long id)`:** gọi `findByIdAndActiveTrue`. Nếu rỗng thì ném `NotFoundException("Product not found: " + id)`.
- **Hàm private `toImageUrl(String imagePath)`:** chuỗi rỗng hoặc toàn khoảng trắng trả `null` (QĐ11).

**`common/NotFoundException`:** public, kế thừa `RuntimeException`, message tiếng Anh dành cho log.

**Xong khi:**
- `./mvnw verify` xanh (không đổi hành vi hiện có), và `ArchitectureTest` vẫn đạt;
- báo cáo liệt kê chữ ký public/package của các kiểu trong mục 3, và nêu rõ chỗ nào lệch (nếu có).

### Chi tiết TW1 (`test-writer`)

- **Xóa `HomeControllerTest`.** Phần kiểm `/` chuyển sang `ProductControllerTest` ở TW3. AC-001-5 được AC-002-2 thay thế, xem D.
- **`SecurityConfigTest`:**
  - đổi sang `@WebMvcTest(useDefaultFilters = false)` + `@Import(SecurityConfig.class)`;
  - thêm Javadoc giải thích: test chỉ kiểm chuỗi filter bảo mật, không cần controller nào (QĐ13);
  - `homePage_carriesContentSecurityPolicy` chỉ assert header, không assert status (lúc này `/` không có handler nên trả 404, nhưng header vẫn có);
  - các test còn lại giữ nguyên.

**Xong khi:** `./mvnw verify` xanh **trên code hiện tại**, khi B2 chưa chạy.

### Chi tiết F1 (`frontend-developer`)

Chạy page run của skill theo QĐ12. Thư mục làm việc là gốc dự án; `<skill>` là `~/.claude/skills/design-taste-frontend`.

1. **Đọc trước:** spec 002 (§6, bảng "Chữ hiển thị chính xác"), `CLAUDE.md` (mục UI design skill) và mục 3 của plan này. Ghi inventory của chủ đề, chỉ dùng sự thật có trong brief và seed: 5 danh mục, giá VNĐ, cửa hàng demo không bán thật.
2. **Vẽ hướng:**
   ```
   node <skill>/scripts/draw.mjs --list sectors
   node <skill>/scripts/draw.mjs --sector <gần nhất> --brief "Mini Shop: cửa hàng demo đồ dùng hằng ngày (thời trang, điện tử, nhà cửa, sách, thể thao), giá VND" --sections list,footer --have product-photo --options 3
   ```
   Chọn một hướng, ghi một dòng lý do, rồi chạy lại với `--direction <id>`. Mỗi lần redraw phải có lý do (ví dụ: font không có glyph tiếng Việt).
3. **Token:** đưa palette và kiểu chữ vào `app.css` dưới dạng biến CSS, và ánh xạ sang `--bs-*`. Comment đầu file ghi tên hướng, seed, palette và font.
4. **Font:** theo QĐ12. File đặt trong `static/fonts/`, kèm `OFL.txt`.
5. **Ảnh:**
   - mỗi dòng của bảng QĐ11:
     ```
     node <skill>/scripts/assets.mjs "<từ khóa tiếng Anh>" --license pd --min-width 800
     ```
     nếu không có ảnh trung thực thì thử lại với `--license safe`;
   - tải về:
     ```
     node <skill>/scripts/assets.mjs --get <ref> --to .taste-skill/assets --name <slug> --width 800
     ```
     rồi chép sang `static/images/products/<slug>.jpg`;
   - tự xem từng ảnh trước khi chọn;
   - mỗi file ≤ 250 KB;
   - cuối cùng chạy `assets.mjs --credits --to .taste-skill/assets`.
6. **Ảnh thay thế:** `static/images/placeholder-product.svg`, tự vẽ, trung tính, không có chữ, dùng màu trong palette. Không dùng `style=` bên trong file SVG; dùng thuộc tính `fill` thay thế.

**Xong khi:** báo cáo có đủ:
- hướng thiết kế (tên, seed), các lần redraw và lý do;
- câu "ý tưởng";
- font và giấy phép của nó;
- **bảng ảnh**: file, tiêu đề, tác giả, giấy phép, link nguồn, link giấy phép;
- danh sách slug **không** có ảnh;
- những phần của skill bị bỏ theo QĐ12.

Không có file nào ngoài các thư mục kể trên.

### Chi tiết B2 (`backend-developer`)

**`ProductController`:**
```java
@GetMapping({"/", "/products"})
String list(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String page, Model model)
// view = productService.search(new ProductSearchParams(q, category, sort, page))
// model: "view" và "pageNav" = PageNav.of(view.filter().page(), view.totalPages(), p -> listUrl(view.filter(), p))
// return "product/list"

@GetMapping("/products/{id}")
String detail(@PathVariable Long id, Model model)   // model "product"; return "product/detail"
```
- `listUrl` theo QĐ9.
- Xóa `HomeController`.

**Các class khác:**
- `ProductNotFoundAdvice`: theo QĐ7. Handler có `@ResponseStatus(HttpStatus.NOT_FOUND)`, trả `"product/not-found"`, ghi log ở mức `debug`, không in stack trace.
- `common/PageNav`: theo QĐ9.
- `common/VndFormatter` + `common/ViewHelpersDialect`: theo QĐ8. Đặt tên expression object là `vnd`.
- `SecurityConfig`: thêm `"/products", "/products/**"` vào `PUBLIC_PATHS`, và sửa Javadoc cho khớp.

**Xong khi:**
- `./mvnw verify` xanh;
- app chạy profile `h2` khởi động được, và `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/products` **không** phải 302 sang `/login`. Lúc này 500 vì thiếu template là chấp nhận được;
- báo cáo xác nhận view name và model attribute khớp mục 3.

### Chi tiết B3 (`backend-developer`)

- Trong `DataInitializer`, gán `setImagePath("/images/products/<slug>.jpg")` theo bảng QĐ11, **chỉ cho những file F1 thực sự giao**.
- Có thể thêm tham số `String imageSlug` (nullable) vào helper `product(...)`.
- Không đổi số lượng, giá hay tồn kho của dữ liệu mẫu, vì `DataInitializerTest` và README đang dựa vào các con số đó.

**Xong khi:**
- app chạy profile `h2`;
- `select name, image_path from products` trong `/h2-console` cho thấy đúng các đường dẫn;
- mỗi đường dẫn mở được qua `http://localhost:8080/images/products/<slug>.jpg`.

### Chi tiết F2 (`frontend-developer`)

- **Đọc trước:** `ProductController` thật và mục 3 của plan.
- **Dựng:** các fragment và trang theo QĐ10, mục 3 và hướng thiết kế đã vẽ ở F1. Không vẽ hướng mới.
- **Chữ:** dùng **đúng** các chuỗi trong spec §6. Chữ mới chỉ được thêm cho nhãn ẩn của ô tìm kiếm, ví dụ `<label class="visually-hidden">Tìm sản phẩm theo tên</label>`.
- **Nội dung trang:**
  - mỗi trang có một `h1`: "Sản phẩm" ở danh sách, tên sản phẩm ở chi tiết, "Không tìm thấy sản phẩm" ở 404;
  - không có nút "Thêm vào giỏ" (câu hỏi mở 6);
  - không hiện số lượng tồn.
- **Ảnh:** có `width`/`height` hoặc `aspect-ratio` để trang không bị nhảy khi ảnh tải xong, và dùng `object-fit: cover`.
- **Gate:**
  - chạy app với profile `h2` (`./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`), vì profile này luôn seed mới và có ảnh nếu B3 đã xong;
  - chạy `node <skill>/scripts/lint.mjs src/main/resources/templates src/main/resources/static/css`;
  - chạy `node <skill>/scripts/inspect.mjs http://localhost:8080/products` và `.../products/<id>`, lưu kết quả vào `.taste-skill/inspect/`;
  - mở và xem mọi ảnh chụp, tối đa 3 vòng sửa;
  - kiểm tay ở bề rộng 375px: không tràn ngang;
  - DevTools Console: không có lỗi CSP;
  - tắt app khi xong.

**Xong khi:**
- `inspect.mjs` không còn `FAIL`, trừ những mục ghi là "sai lệch có chủ đích" theo QĐ12, mỗi mục kèm một câu lý do;
- `grep -rnE '<script>|<style|style=|th:style|\son[a-z]+=|th:on|javascript:|th:utext' src/main/resources/templates` không trả kết quả nào;
- báo cáo liệt kê template và model attribute đã dùng, kèm bản critique của vòng cuối.

### Chi tiết TW2 (`test-writer`)

**`ProductServiceTest`** (Mockito, `ArgumentCaptor<Pageable>`). Repository trả `PageImpl` chứa entity dựng bằng constructor; `id` là `null` cũng không sao.

| Đầu vào | Mong đợi | AC |
|---|---|---|
| `page` = `null`, `"abc"`, `"0"`, `"-3"`, `"99999999999"` | `pageNumber = 0`, `filter.page = 1` | 002-8 |
| `page = "2"` | `pageNumber = 1`; `pageSize` luôn là 12 | 002-8 |
| `sort` là `price_asc` / `price_desc` / `"xyz"` / `null` | Đúng `Sort` theo bảng QĐ4; giá trị lạ cho ra `NEWEST` | 002-7 |
| `q` dài 150 ký tự (có khoảng trắng hai đầu) | `filter.q` dài đúng 100 ký tự; `"   "` cho ra `null` | 002-5 |
| `category` = `"abc"` / `"5"` | `null` / `5L` | 002-6 |
| Danh mục "Thời trang", "Điện tử", "Sách", "Nhà cửa" | Thứ tự: Điện tử, Nhà cửa, Sách, Thời trang | QĐ5 |
| `getDetail`, repository trả `Optional.empty()` | Ném `NotFoundException` | 002-15 |
| `imagePath` là `""` / `"  "` | `imageUrl = null` | 002-16 |

**`ProductQueryTest`** (PostgreSQL thật, QĐ13). Không giả định DB trống: xóa sạch `products` và `categories` ở `@BeforeEach`, trong transaction của test. Kiểm:
- **25 sản phẩm active và 1 sản phẩm ẩn:**
  - trang 1 có 12 sản phẩm, mới nhất trước; tổng là 25;
  - trang 2 là sản phẩm thứ 13–24;
  - trang 4 rỗng nhưng tổng vẫn là 25 (002-1, 002-8, 002-11).
- **Sản phẩm ẩn** không xuất hiện và không được đếm, với mọi tổ hợp `q`, `category` và `sort` (002-3).
- **"Áo thun cổ tròn":**
  - tìm `"  áo THUN "` thì ra;
  - tìm `"ao thun"` thì **không** ra (phân biệt dấu);
  - tìm `"50%"` không khớp "Áo thun"; một sản phẩm tên "Giảm 50%" thì khớp (002-4).

  Case đầu là bằng chứng cho rủi ro `lower()` ở QĐ3. Nếu case này fail, **không sửa test**: báo phiên chính kèm kết quả `show lc_ctype`.
- **Lọc danh mục:** chỉ trả sản phẩm của danh mục đó; id không tồn tại cho ra danh sách rỗng với tổng 0 (002-6).
- **Sắp theo giá:** đúng thứ tự tăng/giảm; hai sản phẩm cùng giá thì xếp theo `id` tăng dần, ổn định giữa các trang (002-7).
- **N+1:** theo QĐ13 (002-18).
- **`getDetail`:** sản phẩm ẩn thì ném `NotFoundException`; sản phẩm active thì có `categoryName` (002-15, 002-14).

**`PageNavTest`:**
- 1 trang;
- 3 trang, đang ở trang 1/2/3;
- 20 trang, đang ở trang 10, nên cửa sổ là 8–12;
- `previousUrl`/`nextUrl` là `null` ở trang đầu/cuối.

**`VndFormatterTest`:**
- `1250000` → `"1.250.000 ₫"`;
- `0` → `"0 ₫"`;
- `999` → `"999 ₫"`;
- `1000` → `"1.000 ₫"`;
- `null` → `""`;
- kết quả không chứa NBSP.

**Xong khi:** `./mvnw test -Dtest='ProductServiceTest,ProductQueryTest,PageNavTest,VndFormatterTest'` xanh khi Docker đang bật, không test nào bị skipped.

### Chi tiết TW3 (`test-writer`)

**`ProductControllerTest`** (QĐ13). Service được mock trả view dựng sẵn; không đăng nhập.

| Kiểm | AC |
|---|---|
| `GET /` và `GET /products`: 200, view `product/list`, `<title>Sản phẩm \| Mini Shop</title>`, thẻ có tên, danh mục, `1.250.000 ₫` và `href="/products/{id}"` | 002-1, 002-2, 002-12, 001-5 |
| `GET /` có header CSP (chuyển từ `SecurityConfigTest`) | 001-16 |
| `GET /products?category=abc&page=abc&sort=xyz`: 200; service nhận đúng chuỗi thô (`ArgumentCaptor`) | 002-6, 002-8, BR-002-3 |
| Tổng 25, trang có 12: hiện `Tìm thấy 25 sản phẩm` | 002-10 |
| Danh sách rỗng: hiện `Không tìm thấy sản phẩm phù hợp.`, link `Xóa bộ lọc` tới `/products`, không có phân trang | 002-11 |
| 3 trang, đang ở trang 2, lọc `q=áo`, `category=3`, `sort=price_asc`: link giữ đủ 3 tham số (lưu ý `&` được escape thành `&amp;` trong HTML, và `áo` được mã hóa thành `%C3%A1o`); có `Trang trước` / `Trang sau`; trang 2 có `aria-current="page"` và không có `href`; ô tìm kiếm, danh mục 3 và `price_asc` đang được chọn | 002-8, 002-9 |
| `q=<script>alert(1)</script>`: HTML chứa `&lt;script&gt;alert(1)&lt;/script&gt;` và không chứa chuỗi thô | 002-17 |
| Sản phẩm hết hàng có `Hết hàng` ở danh sách; trang chi tiết có `Còn hàng` / `Hết hàng`; không lộ số tồn | 002-13 |
| `GET /products/7`: 200; có tên, danh mục, giá, mô tả, nhãn kho, `<title>{tên} \| Mini Shop</title>` | 002-14 |
| `imageUrl = null`: `src` chứa `placeholder-product.svg` và `alt` là tên sản phẩm (danh sách và chi tiết) | 002-16 |
| Service ném `NotFoundException`; `GET /products/abc`; `GET /products/99999999999999999999`: cả ba trả **404** với 3 chuỗi của trang 404 | 002-15 |

**`TemplateCspComplianceTest`** (JUnit thuần, không có Spring context):
- Đọc mọi `classpath*:templates/**/*.html` bằng `PathMatchingResourcePatternResolver`.
- Fail khi gặp, và in ra tên file cùng số dòng:
  - `<script` không có `src`;
  - `<style`;
  - `style=` hoặc `th:style`;
  - `on[a-z]+=` hoặc `th:on`;
  - `javascript:`;
  - `th:utext`.
- Thêm một test tham số hóa chứng minh bộ phát hiện **có bắt được** các đoạn HTML vi phạm mẫu, để tránh "xanh giả" (002-19).

**`H2ProfileSmokeTest`** (chạy ở mọi nơi):
- `GET /products` trả 200 và có `Tìm thấy 21 sản phẩm`. Đây là truy vấn Specification thật trên H2.
- Mọi `image_path` khác `null` trong bảng `products` đều trỏ tới một resource có thật: `new ClassPathResource("static" + path).exists()`.

**`ArchitectureTest`:** thêm rule "class có `@Controller` không phụ thuộc class có `@Entity`". Rule này biến quy tắc "view chỉ nhận DTO" (spec §8) thành kiểm tra tự động.

**Xong khi:**
- `./mvnw verify` xanh;
- `bash .github/scripts/check-no-skipped-tests.sh` exit 0 (0 test bị skipped);
- báo cáo liệt kê test theo từng AC.

### Chi tiết D (phiên chính)

- **`README.md`:**
  - cập nhật trạng thái GĐ2;
  - thêm mục **"Nguồn ảnh"** dạng bảng: file, tiêu đề, tác giả, giấy phép kèm link, link nguồn. Lấy từ báo cáo F1;
  - ghi chú: ảnh chỉ để minh họa cho cửa hàng demo;
  - thêm mục **"Làm mới dữ liệu mẫu"**: `docker compose down -v` (cảnh báo: lệnh này xóa toàn bộ dữ liệu dev trong volume `postgres-data`), và nói rõ profile `h2` luôn seed mới;
  - đánh dấu GĐ2 ở mục Lộ trình **sau khi V đạt**.
- **`CHANGELOG.md`:** ghi các mục GĐ2 vào `[Unreleased]`. Việc chuyển thành `[0.2.0]` và gắn tag để sau khi người dùng duyệt giai đoạn.
- **`CLAUDE.md`:**
  - Project status: GĐ2;
  - các quy ước mới:
    - giá dùng `${#vnd.format(...)}`;
    - fragment nằm trong `templates/fragments/`;
    - 404 theo tính năng dùng `NotFoundException` + advice có phạm vi;
    - `PageNav` cho phân trang;
    - template phải qua `TemplateCspComplianceTest`.
- **Spec 002 và `docs/specs/README.md`:**
  - đổi trạng thái sang `Đang làm` khi bắt đầu triển khai;
  - đổi sang `Hoàn thành` sau khi V đạt.
- **Spec 001, mục Lịch sử:** ghi "từ GĐ2, `/` là trang danh sách sản phẩm (AC-002-2); tiêu đề tab của `/` là `Sản phẩm | Mini Shop`, thay cho điều kiện tiêu đề ở AC-001-5". Không sửa bảng AC của spec đã hoàn thành.

## 5. Checklist kiểm tra cuối giai đoạn (`phase-verifier`)

Docker **đang bật**: mọi test, kể cả Testcontainers, phải chạy ở local.

| # | Kiểm tra | Cách kiểm | Mong đợi |
|---|---|---|---|
| 1 | Build, AC-002-3–19 có test | `./mvnw verify`, rồi `bash .github/scripts/check-no-skipped-tests.sh` | BUILD SUCCESS; script exit 0 (0 skipped); có đủ `ProductQueryTest`, `ProductServiceTest`, `ProductControllerTest`, `TemplateCspComplianceTest`, `PageNavTest`, `VndFormatterTest` |
| 2 | Chuẩn bị DB | `docker compose down -v`, rồi `./mvnw spring-boot:run` | App lên với PostgreSQL; log có dòng seed |
| 3 | AC-002-1, 2 | Mở `/` và `/products` khi chưa đăng nhập | Hai trang giống nhau; 12 thẻ có ảnh, tên, danh mục, giá; `Tìm thấy 21 sản phẩm`; có 2 trang |
| 4 | AC-002-3 | Tìm "Ly thủy tinh" | Không có kết quả; tổng không tính sản phẩm bị ẩn |
| 5 | AC-002-4 | Tìm `  áo THUN ` | Ra "Áo thun cotton cổ tròn" |
| 6 | Phân biệt dấu (âm) | Tìm `ao thun` | `Không tìm thấy sản phẩm phù hợp.`; bấm `Xóa bộ lọc` thì về `/products` |
| 7 | AC-002-6, 7 | Chọn danh mục "Điện tử" + `Giá: thấp đến cao` | Chỉ có sản phẩm điện tử, giá tăng dần; danh sách danh mục xếp theo tên ("Điện tử" đứng đầu) và có "Tất cả danh mục" |
| 8 | AC-002-8, 9 | Mở `/products?q=n&sort=price_desc`, rồi bấm `Trang sau` | URL giữ `q` và `sort`; ô tìm kiếm và kiểu sắp xếp vẫn đúng; trang hiện tại không bấm được |
| 9 | Tham số sai (âm) | `?page=0`, `?page=abc`, `?page=999`, `?category=abc`, `?category=999`, `?sort=xyz`, `?q=` + 150 ký tự | Không trang nào là 400/500. `page=999` và `category=999` hiện trạng thái rỗng |
| 10 | AC-002-17 (âm) | `?q=<script>alert(1)</script>` | Ô tìm kiếm hiện đúng chuỗi dạng văn bản; không có hộp alert; không có lỗi trong Console |
| 11 | AC-002-12, 13 | Xem "Bàn phím cơ cỡ nhỏ" và "Loa bluetooth mini" ở danh sách và chi tiết | `1.250.000 ₫`; Loa có `Hết hàng` ở cả hai nơi; sản phẩm còn hàng có `Còn hàng` ở chi tiết; không lộ số tồn |
| 12 | AC-002-14 | Mở chi tiết một sản phẩm | Có tên, danh mục, giá, mô tả, nhãn kho, ảnh; tiêu đề tab `{tên} \| Mini Shop` |
| 13 | AC-002-16 | Mở "Giáo trình lập trình Java cơ bản" ở danh sách và chi tiết | Hiện ảnh thay thế tự host; `alt` là tên sản phẩm |
| 14 | AC-002-15 (âm) | `/products/999999`, `/products/abc`, `/products/99999999999999999999`, và id của sản phẩm bị ẩn (lấy bằng `docker compose exec postgres psql -U minishop -d minishop -c "select id from products where active = false"`) | Cả 4 trả **404** (xem trong tab Network), có `Không tìm thấy sản phẩm`, `Sản phẩm không tồn tại hoặc đã ngừng bán.` và link `Về danh sách sản phẩm` |
| 15 | AC-002-19, 20; AC-001-16 | DevTools trên danh sách, chi tiết, 404: tab Console và Network | Không có lỗi CSP; mọi tài nguyên đều từ `localhost`; có header `Content-Security-Policy` |
| 16 | AC-002-20 | Device mode ở 375px, rồi 768 và 1440, trên danh sách và chi tiết | Không có thanh cuộn ngang; ảnh và chữ đọc được; form lọc dùng được bằng ngón tay |
| 17 | Truy cập | Xem cây accessibility hoặc mã HTML | Mọi ô nhập có `label`; mọi ảnh có `alt`; nhãn kho là chữ |
| 18 | Ảnh và giấy phép | So `static/images/products/` với mục "Nguồn ảnh" trong README | File nào cũng có dòng ghi nguồn với giấy phép CC0/PD/CC BY/CC BY-SA; mỗi file ≤ 250 KB |
| 19 | Profile `h2` | `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`, mở `/products` và một trang chi tiết | Chạy giống như PostgreSQL, ảnh hiển thị |
| 20 | Trang bảo vệ (âm, hồi quy) | Mở `/orders` khi chưa đăng nhập | Chuyển sang `/login` |
| 21 | Dọn dẹp | Tắt mọi app đã chạy | Không còn tiến trình nào; container Postgres đã dừng |

## 6. Câu hỏi cho người dùng

> **Đã trả lời 2026-10-23:** người dùng duyệt kế hoạch. (1) Được commit, push và mở PR cuối giai đoạn, chưa merge. (2) Duyệt trước phương án dự phòng đổi locale DB. (3) Đã biết.

Không câu nào chặn việc bắt đầu triển khai.

1. **Cuối giai đoạn có commit, push và mở PR `phase-2/product-catalog` → `main` không?** Các agent không tự commit. Nếu đồng ý, phiên chính commit theo Conventional Commits sau khi V đạt, và chưa merge.
2. **Duyệt trước phương án dự phòng cho rủi ro `lower()` ở QĐ3?** Phương án này chỉ dùng khi case "áo THUN" của `ProductQueryTest` fail trên PostgreSQL. Khi đó `devops-engineer` sẽ:
   - thêm `POSTGRES_INITDB_ARGS=--locale-provider=builtin --builtin-locale=C.UTF-8` (có từ PostgreSQL 17, chuyển hoa/thường theo Unicode, không phụ thuộc musl) vào `compose.yaml`;
   - `test-writer` thêm biến đó vào `TestcontainersConfiguration`;
   - viết một ADR ngắn.

   Cách này buộc phải xóa volume dev, nhưng README đã yêu cầu bước đó rồi. Nếu bạn không duyệt trước, tôi sẽ dừng lại và hỏi khi gặp lỗi.
3. **Chỉ để bạn biết:** từ GĐ2, tiêu đề tab của `/` đổi thành `Sản phẩm | Mini Shop`. AC-001-5 cũ của spec 001 yêu cầu tiêu đề là "Mini Shop". Thay đổi này là hệ quả của câu hỏi mở 1 mà bạn đã duyệt, và được ghi vào mục Lịch sử của spec 001 ở task D.

## 7. Đề xuất ngoài phạm vi (chỉ để bạn cân nhắc, không nằm trong task)

- **Tìm kiếm không phân biệt dấu** ("ao" khớp "Áo"): thêm cột `name_search` đã bỏ dấu (tính trong Java, chạy được trên cả H2), hoặc dùng extension `unaccent` (chỉ có trên PostgreSQL).
- **Index cho tìm "chứa":** `like '%q%'` không dùng được `idx_products_name`. Khi dữ liệu lớn có thể dùng `pg_trgm` + GIN, nhưng chưa cần với vài chục sản phẩm.
- **Tự gửi form khi đổi danh mục hoặc kiểu sắp xếp:** khoảng 10 dòng JS trong `static/js/`, CSP vẫn ổn. Hiện tại người dùng phải bấm `Tìm`.
- **Dòng "Nguồn ảnh" ở footer**, link tới danh sách ghi nguồn. Đây là cách ghi công phổ biến nhất cho ảnh CC BY trên website, ngoài README.
- **Ảnh responsive (`srcset`, WebP) và header cache cho `/images/**`:** hợp với GĐ7/GĐ8 hơn.
- **Trang 404/403/500 chung và xử lý `/products/`** (có dấu `/` ở cuối, hiện rơi vào trang lỗi mặc định của Boot): để GĐ7, đúng như phạm vi của spec.

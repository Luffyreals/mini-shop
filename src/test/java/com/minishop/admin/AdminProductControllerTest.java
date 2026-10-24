package com.minishop.admin;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.catalog.AdminProductPage;
import com.minishop.catalog.AdminProductRow;
import com.minishop.catalog.CategoryAdminService;
import com.minishop.catalog.CategoryNotFoundException;
import com.minishop.catalog.CategoryOption;
import com.minishop.catalog.ProductAdminService;
import com.minishop.catalog.ProductDraft;
import com.minishop.catalog.ProductEditView;
import com.minishop.catalog.SavedProduct;
import com.minishop.catalog.StockAdjustResult;
import com.minishop.common.NotFoundException;
import com.minishop.config.SecurityConfig;
import com.minishop.storage.ImageFormat;
import com.minishop.storage.ImageUpload;
import com.minishop.support.TestImages;
import com.minishop.user.Role;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static com.minishop.admin.AdminPageAssertions.assertLabelled;
import static com.minishop.admin.AdminPageAssertions.assertLayout;
import static com.minishop.admin.AdminPageAssertions.formWithAction;
import static com.minishop.admin.AdminPageAssertions.formsWithAction;
import static com.minishop.admin.AdminPageAssertions.main;
import static com.minishop.admin.AdminPageAssertions.matches;
import static com.minishop.admin.AdminPageAssertions.tagWithId;
import static com.minishop.admin.AdminPageAssertions.unescape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Product pages with mocked services (AC-006-4, 5, 17, 18, 19, 21, 22, 24, 25, 26, 28, 29, 32, 33, 34, 49): list and
 * pagination, both forms with every error under its field, the upload paths (valid, wrong type, too large), the
 * mass-assignment guard, hide/show redirects, the stock adjustment, and the admin 404. Whenever an error is
 * expected the service must not be called, so no file can be written.
 */
@WebMvcTest(AdminProductController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AdminProductControllerTest {

    private static final String EVIL = "<script>alert(1)</script>";
    private static final String EVIL_ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;";
    private static final String NOT_FOUND_TITLE = "Không tìm thấy sản phẩm";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProductAdminService productService;

    @MockitoBean
    CategoryAdminService categoryService;

    @BeforeEach
    void stubCategories() {
        when(categoryService.options()).thenReturn(List.of(new CategoryOption(2L, "Điện tử"),
                new CategoryOption(3L, "Gia dụng")));
        when(productService.list(any(), any())).thenReturn(page(List.of(), null, 1, 0, 0));
    }

    // ---------------------------------------------------------------- helpers

    private static RequestPostProcessor admin() {
        return user(TestUsers.admin());
    }

    private static AdminProductRow row(long id, String name, boolean active, String imageUrl) {
        return new AdminProductRow(id, name, "Gia dụng", new BigDecimal("1250000"), 10, active, imageUrl);
    }

    private static AdminProductPage page(List<AdminProductRow> rows, String q, int page, int totalPages, long total) {
        return new AdminProductPage(rows, q, page, totalPages, total);
    }

    private static ProductEditView edit(long id, boolean active, String imageUrl) {
        return new ProductEditView(id, "Bình nước 1L", 3L, new BigDecimal("1250000"), "Giữ nhiệt", 10, active, imageUrl);
    }

    private String html(String url) throws Exception {
        return mockMvc.perform(get(url).with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** A multipart POST with the given text fields and an optional image part; admin, CSRF. */
    private MockMultipartHttpServletRequestBuilder multipartPost(String url, MockMultipartFile image,
                                                                  String... nameValuePairs) {
        MockMultipartHttpServletRequestBuilder request = multipart(url);
        if (image != null) {
            request.file(image);
        }
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        request.with(admin()).with(csrf());
        return request;
    }

    private static String[] validNew(String... overrides) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("name", "Bình nước 1L");
        fields.put("categoryId", "3");
        fields.put("price", "1.250.000");
        fields.put("initialStock", "30");
        fields.put("description", "Giữ nhiệt 12 giờ");
        fields.put("active", "true");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        return fields.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new);
    }

    private static String[] validEdit(String... overrides) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("name", "Bình nước 1L");
        fields.put("categoryId", "3");
        fields.put("price", "1.250.000");
        fields.put("description", "Giữ nhiệt 12 giờ");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        return fields.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new);
    }

    private static MockMultipartFile png(int bytes) {
        return new MockMultipartFile("image", "ảnh (1).PNG", "image/png", TestImages.png(bytes));
    }

    private static void assertFieldError(String html, String field, String message) {
        assertThat(unescape(html)).contains(message);
        assertThat(html).contains("id=\"" + field + "-error\"");
        assertThat(tagWithId(html, field)).contains("aria-invalid=\"true\"")
                .contains("aria-describedby=\"" + field + "-error\"");
    }

    // ============================================================= LIST (AC-006-17)

    @Test
    void list_rendersLayoutColumnsRowsAndStatusWithText() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(
                row(5, "Bình nước 1L", true, "/uploads/aaaa.png"), row(4, "Ly cũ", false, null)), null, 1, 1, 2));

        String html = mockMvc.perform(get("/admin/products").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/product-list"))
                .andExpect(model().attributeExists("products", "pageNav"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Sản phẩm | Quản trị Mini Shop", "Sản phẩm", "products");
        for (String column : List.of("Ảnh", "Tên", "Danh mục", "Giá", "Tồn kho", "Trạng thái")) {
            assertThat(matches("<th scope=\"col\"[^>]*>\\s*" + column + "\\s*</th>", html)).as(column).isTrue();
        }
        assertThat(matches("<a[^>]*href=\"/admin/products/new\"[^>]*>\\s*Thêm sản phẩm\\s*</a>", html)).isTrue();
        assertThat(html).contains("Bình nước 1L").contains("Gia dụng").contains("1.250.000 ₫");
        assertThat(matches("<img[^>]*src=\"/uploads/aaaa.png\"[^>]*alt=\"Bình nước 1L\"", html)
                || matches("<img[^>]*alt=\"Bình nước 1L\"[^>]*src=\"/uploads/aaaa.png\"", html)).isTrue();
        assertThat(matches("<span[^>]*class=\"admin-flag admin-flag--on\"[^>]*>\\s*Đang bán\\s*</span>", html)).isTrue();
        assertThat(matches("<span[^>]*class=\"admin-flag admin-flag--off\"[^>]*>\\s*Đã ẩn\\s*</span>", html)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/products/5/edit\"[^>]*>\\s*Sửa\\s*</a>", html)).isTrue();
        // A product without an image shows an empty thumb with a text alternative, not a broken <img>.
        assertThat(html).containsOnlyOnce("<img").contains("Chưa có ảnh");
    }

    @Test
    void list_hideAndShowAreSeparatePostFormsCarryingFromQAndPage() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(
                row(5, "Đang bán", true, null), row(4, "Đã ẩn", false, null)), "áo", 2, 3, 45));

        String html = html("/admin/products?q=%C3%A1o&page=2");

        String hide = formWithAction(html, "/admin/products/5/hide");
        assertThat(hide).contains("method=\"post\"").contains("name=\"_csrf\"")
                .contains("name=\"from\" value=\"list\"").contains("name=\"q\" value=\"áo\"")
                .contains("name=\"page\" value=\"2\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Ẩn\\s*</button>", hide)).isTrue();
        String show = formWithAction(html, "/admin/products/4/show");
        assertThat(show).contains("method=\"post\"").contains("name=\"_csrf\"").contains("name=\"from\" value=\"list\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Hiện lại\\s*</button>", show)).isTrue();
        assertThat(html).doesNotContain("/admin/products/5/show").doesNotContain("/admin/products/4/hide");
    }

    @Test
    void list_passesQAndPageAsRawStringsToTheService() throws Exception {
        mockMvc.perform(get("/admin/products").with(admin()).param("q", "áo").param("page", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/products?page=abc").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/products").with(admin())).andExpect(status().isOk());

        verify(productService).list("áo", "2");
        verify(productService).list(null, "abc");
        verify(productService).list(null, null);
    }

    @Test
    void list_hasSearchFormWithLabelPlaceholderAndButton() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(row(1, "Áo", true, null)), "áo", 1, 1, 1));

        String html = html("/admin/products?q=%C3%A1o");

        String form = formWithAction(html, "/admin/products");
        assertThat(form).contains("method=\"get\"");
        assertThat(tagWithId(form, "q")).contains("type=\"search\"").contains("name=\"q\"")
                .contains("placeholder=\"Tìm theo tên sản phẩm…\"").contains("value=\"áo\"");
        assertLabelled(form, "q");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Tìm\\s*</button>", form)).isTrue();
    }

    @Test
    void list_withoutProductsAndWithoutQuery_saysThereAreNone() throws Exception {
        String html = html("/admin/products");

        assertThat(html).contains("Chưa có sản phẩm nào.").doesNotContain("Không tìm thấy sản phẩm phù hợp.");
        assertThat(main(html)).doesNotContain("<table");
    }

    @Test
    void list_withQueryAndNoMatch_saysNothingMatched() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(), "zzz", 1, 0, 0));

        String html = html("/admin/products?q=zzz");

        assertThat(html).contains("Không tìm thấy sản phẩm phù hợp.").doesNotContain("Chưa có sản phẩm nào.");
        assertThat(main(html)).doesNotContain("<table");
    }

    @Test
    void list_paginationLinksKeepTheEncodedQuery() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(row(1, "Áo thun", true, null)),
                "áo thun&x=1", 2, 3, 45));

        String html = html("/admin/products?q=x&page=2");

        assertThat(html).contains("href=\"/admin/products?q=%C3%A1o%20thun%26x%3D1&amp;page=1\"")
                .contains("href=\"/admin/products?q=%C3%A1o%20thun%26x%3D1&amp;page=3\"");
    }

    @Test
    void list_withoutQuery_paginationLinksHaveOnlyThePage() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(row(1, "Áo", true, null)), null, 1, 2, 25));

        assertThat(html("/admin/products")).contains("href=\"/admin/products?page=2\"").doesNotContain("q=");
    }

    @Test
    void list_nameAndCategoryWithScript_areEscaped() throws Exception {
        when(productService.list(any(), any())).thenReturn(page(List.of(new AdminProductRow(1, EVIL, EVIL,
                new BigDecimal("1000"), 1, true, null)), null, 1, 1, 1));

        String html = html("/admin/products");

        assertThat(html).doesNotContain(EVIL);
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).isGreaterThanOrEqualTo(2);
    }

    @Test
    void list_withFlashNotice_showsIt() throws Exception {
        String html = mockMvc.perform(get("/admin/products").with(admin())
                        .flashAttr("notice", "Đã ẩn sản phẩm X. Khách sẽ không thấy sản phẩm này nữa.")
                        .flashAttr("noticeKind", "success"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Đã ẩn sản phẩm X. Khách sẽ không thấy sản phẩm này nữa.").contains("role=\"status\"");
    }

    @Test
    void list_adminNameWithScript_isEscaped() throws Exception {
        String html = mockMvc.perform(get("/admin/products").with(user(TestUsers.withName(EVIL, Role.ADMIN))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Xin chào, " + EVIL_ESCAPED).doesNotContain(EVIL);
    }

    // ============================================================= NEW FORM (AC-006-18)

    @Test
    void newForm_rendersEveryLabelHintDefaultAndMultipartEncoding() throws Exception {
        String html = mockMvc.perform(get("/admin/products/new").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/product-new"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Thêm sản phẩm | Quản trị Mini Shop", "Thêm sản phẩm", "products");
        String form = formWithAction(html, "/admin/products");
        assertThat(form).contains("method=\"post\"").contains("enctype=\"multipart/form-data\"")
                .contains("novalidate").contains("name=\"_csrf\"");
        for (String label : List.of("Tên sản phẩm", "Danh mục", "Giá \\(₫\\)", "Tồn kho ban đầu",
                "Mô tả \\(không bắt buộc\\)", "Ảnh sản phẩm", "Đang bán")) {
            assertThat(matches("<label[^>]*>\\s*" + label + "\\s*</label>", form)).as(label).isTrue();
        }
        for (String id : List.of("name", "categoryId", "price", "initialStock", "description", "image", "active")) {
            assertLabelled(form, id);
        }
        assertThat(tagWithId(form, "image")).contains("type=\"file\"").contains("name=\"image\"")
                .contains("aria-describedby=\"image-hint\"");
        assertThat(form).contains("id=\"image-hint\"").contains("JPG, PNG hoặc WebP, tối đa 2 MB.");
        assertThat(tagWithId(form, "active")).contains("type=\"checkbox\"").contains("value=\"true\"").contains("checked");
        assertThat(tagWithId(form, "price")).contains("inputmode=\"numeric\"");
        assertThat(tagWithId(form, "initialStock")).contains("inputmode=\"numeric\"");
        assertThat(tagWithId(form, "name")).contains("maxlength=\"200\"");
        assertThat(tagWithId(form, "description")).contains("maxlength=\"4000\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Lưu\\s*</button>", form)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/products\"[^>]*>\\s*Hủy\\s*</a>", form)).isTrue();
        // The select starts with an empty option (selected on a blank form), then the categories in order.
        String options = unescape(form);
        java.util.regex.Matcher empty = Pattern.compile("<option value=\"\"[^>]*>Chọn danh mục</option>").matcher(options);
        assertThat(empty.find()).isTrue();
        assertThat(empty.start()).isLessThan(options.indexOf(">Điện tử</option>"));
        assertThat(options.indexOf(">Điện tử</option>")).isLessThan(options.indexOf(">Gia dụng</option>"));
        assertThat(options).contains("<option value=\"2\">Điện tử</option>").contains("<option value=\"3\">Gia dụng</option>");
    }

    @Test
    void newForm_hasNoStockOrIdFieldsBeyondTheInitialStock() throws Exception {
        String form = formWithAction(html("/admin/products/new"), "/admin/products");

        assertThat(form).doesNotContain("name=\"id\"").doesNotContain("name=\"stock\"")
                .doesNotContain("name=\"imagePath\"");
    }

    @Test
    void newForm_withoutCategories_showsOnlyTheHintAndLinkNoProductForm() throws Exception {
        when(categoryService.options()).thenReturn(List.of());

        String html = html("/admin/products/new");

        assertThat(html).contains("Cần tạo danh mục trước khi thêm sản phẩm.");
        assertThat(matches("<a[^>]*href=\"/admin/categories/new\"[^>]*>\\s*Thêm danh mục\\s*</a>", main(html))).isTrue();
        assertThat(html).doesNotContain("action=\"/admin/products\"").doesNotContain("type=\"file\"");
    }

    // ============================================================= CREATE (AC-006-19)

    @Test
    void create_withValidFormAndPng_passesParsedValuesAndRedirectsToEditPage() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(42, "Bình nước 1L"));

        mockMvc.perform(multipartPost("/admin/products", png(500 * 1024), validNew()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products/42/edit"))
                .andExpect(flash().attribute("notice", "Đã thêm sản phẩm Bình nước 1L."))
                .andExpect(flash().attribute("noticeKind", "success"));

        ArgumentCaptor<ProductDraft> draft = ArgumentCaptor.forClass(ProductDraft.class);
        ArgumentCaptor<ImageUpload> image = ArgumentCaptor.forClass(ImageUpload.class);
        verify(productService).create(eq(1L), draft.capture(), eq(30), eq(true), image.capture());
        assertThat(draft.getValue()).isEqualTo(new ProductDraft("Bình nước 1L", 3L, new BigDecimal("1250000"),
                "Giữ nhiệt 12 giờ"));
        assertThat(draft.getValue().price().scale()).isZero();
        assertThat(image.getValue().format()).isEqualTo(ImageFormat.PNG);
        assertThat(image.getValue().size()).isEqualTo(500 * 1024);
    }

    @ParameterizedTest(name = "price \"{0}\" is stored as 1250000")
    @ValueSource(strings = {"1250000", "1.250.000", "1 250 000", "  1250000  "})
    void create_acceptsAllThreePriceNotations(String price) throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(1, "X"));

        mockMvc.perform(multipartPost("/admin/products", null, validNew("price", price)))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ProductDraft> draft = ArgumentCaptor.forClass(ProductDraft.class);
        verify(productService).create(eq(1L), draft.capture(), eq(30), eq(true), isNull());
        assertThat(draft.getValue().price()).isEqualByComparingTo("1250000");
    }

    @Test
    void create_withoutImagePartOrWithEmptyFilePart_passesNullImage() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(1, "X"));

        mockMvc.perform(multipartPost("/admin/products", null, validNew())).andExpect(status().is3xxRedirection());
        mockMvc.perform(multipartPost("/admin/products", new MockMultipartFile("image", "", "application/octet-stream",
                new byte[0]), validNew())).andExpect(status().is3xxRedirection());

        verify(productService, org.mockito.Mockito.times(2)).create(eq(1L), any(), eq(30), eq(true), isNull());
    }

    @Test
    void create_withActiveUnticked_isInactive() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(1, "X"));
        String[] fields = java.util.stream.Stream.of(validNew()).toArray(String[]::new);
        // drop the "active" pair: an unticked checkbox sends nothing
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (int i = 0; i < fields.length; i += 2) {
            if (!fields[i].equals("active")) {
                kept.add(fields[i]);
                kept.add(fields[i + 1]);
            }
        }

        mockMvc.perform(multipartPost("/admin/products", null, kept.toArray(String[]::new)))
                .andExpect(status().is3xxRedirection());

        verify(productService).create(eq(1L), any(), eq(30), eq(false), isNull());
    }

    @Test
    void create_withBlankDescription_passesNull() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(1, "X"));

        mockMvc.perform(multipartPost("/admin/products", null, validNew("description", "   ")))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ProductDraft> draft = ArgumentCaptor.forClass(ProductDraft.class);
        verify(productService).create(eq(1L), draft.capture(), eq(30), eq(true), isNull());
        assertThat(draft.getValue().description()).isNull();
    }

    // ------------------------------------------------------------ AC-006-21: each field

    @ParameterizedTest(name = "[{index}] {0}={1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "name|''|Vui lòng nhập tên sản phẩm.|name",
            "name|'   '|Vui lòng nhập tên sản phẩm.|name",
            "categoryId|''|Vui lòng chọn danh mục.|categoryId",
            "categoryId|abc|Danh mục không tồn tại.|categoryId",
            "categoryId|-1|Danh mục không tồn tại.|categoryId",
            "price|''|Vui lòng nhập giá.|price",
            "price|0|Giá phải là số nguyên từ 1 đến 999.999.999 ₫.|price",
            "price|-5|Giá phải là số nguyên từ 1 đến 999.999.999 ₫.|price",
            "price|12,5|Giá phải là số nguyên từ 1 đến 999.999.999 ₫.|price",
            "price|1.5|Giá phải là số nguyên từ 1 đến 999.999.999 ₫.|price",
            "price|abc|Giá phải là số nguyên từ 1 đến 999.999.999 ₫.|price",
            "price|1.000.000.000|Giá phải là số nguyên từ 1 đến 999.999.999 ₫.|price",
            "initialStock|''|Vui lòng nhập tồn kho ban đầu.|initialStock",
            "initialStock|-1|Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.|initialStock",
            "initialStock|1.5|Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.|initialStock",
            "initialStock|100001|Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.|initialStock",
            "initialStock|abc|Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.|initialStock"})
    void create_withInvalidField_rendersFormWithErrorUnderThatFieldKeepsValuesAndDoesNotCallService(
            String field, String value, String message, String errorField) throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products", png(1024), validNew(field, value)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/product-new"))
                .andReturn().getResponse().getContentAsString();

        assertFieldError(html, errorField, message);
        assertThat(unescape(html)).containsOnlyOnce(message);
        // The other typed values come back (text fields only, never the file).
        if (!field.equals("name")) {
            assertThat(tagWithId(html, "name")).contains("value=\"Bình nước 1L\"");
        }
        assertThat(unescape(html)).contains(">Giữ nhiệt 12 giờ<");
        if (!field.equals("categoryId")) {
            assertThat(html).contains("<option value=\"3\" selected=\"selected\">Gia dụng</option>");
        }
        verifyNoInteractions(productService);
    }

    @Test
    void create_withNameOf201Characters_showsLengthErrorAndKeepsTheValue() throws Exception {
        String longName = "n".repeat(201);

        String html = mockMvc.perform(multipartPost("/admin/products", null, validNew("name", longName)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFieldError(html, "name", "Tên sản phẩm tối đa 200 ký tự.");
        assertThat(tagWithId(html, "name")).contains("value=\"" + longName + "\"");
        verifyNoInteractions(productService);
    }

    @Test
    void create_withDescriptionOf4001Characters_showsErrorUnderDescription() throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products", null, validNew("description", "d".repeat(4001))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFieldError(html, "description", "Mô tả tối đa 4000 ký tự.");
        verifyNoInteractions(productService);
    }

    @Test
    void create_withSeveralInvalidFields_showsEveryErrorAtOnce() throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products", null,
                        validNew("name", "", "price", "0", "initialStock", "-1", "categoryId", "")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFieldError(html, "name", "Vui lòng nhập tên sản phẩm.");
        assertFieldError(html, "categoryId", "Vui lòng chọn danh mục.");
        assertFieldError(html, "price", "Giá phải là số nguyên từ 1 đến 999.999.999 ₫.");
        assertFieldError(html, "initialStock", "Tồn kho ban đầu phải là số nguyên từ 0 đến 100.000.");
        verifyNoInteractions(productService);
    }

    @Test
    void create_withActiveTickedAndAnError_keepsTheCheckboxTicked() throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products", null, validNew("name", "")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(tagWithId(html, "active")).contains("checked");
    }

    // ------------------------------------------------------------ AC-006-28, 29: images

    @Test
    void create_withGifImage_showsTypeErrorUnderImageAndNeverCallsService() throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products",
                        new MockMultipartFile("image", "a.gif", "image/gif", TestImages.gif()), validNew()))
                .andExpect(status().isOk()).andExpect(view().name("admin/product-new"))
                .andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Ảnh chỉ được là JPG, PNG hoặc WebP.");
        assertThat(html).contains("id=\"image-error\"");
        assertThat(tagWithId(html, "image")).contains("aria-invalid=\"true\"")
                .contains("aria-describedby=\"image-hint image-error\"");
        assertThat(html).contains("JPG, PNG hoặc WebP, tối đa 2 MB.");
        // The other fields keep what was typed.
        assertThat(tagWithId(html, "name")).contains("value=\"Bình nước 1L\"");
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "[{index}] {0} as {1} / {2}")
    @CsvSource(delimiter = '|', value = {
            "evil.jpg|image/jpeg|html",
            "a.svg|image/svg+xml|svg",
            "a.exe|application/octet-stream|png",
            "noextension|image/png|png",
            "a.png|text/html|png",
            "a.jpg|image/png|png",
            "a.png|image/jpeg|jpeg"})
    void create_withWrongTypeNameOrContent_isRejectedWithTheTypeError(String filename, String contentType, String kind)
            throws Exception {
        byte[] bytes = switch (kind) {
            case "html" -> TestImages.htmlNamedJpg();
            case "svg" -> TestImages.svg();
            case "jpeg" -> TestImages.jpeg(100);
            default -> TestImages.png(100);
        };

        String html = mockMvc.perform(multipartPost("/admin/products",
                        new MockMultipartFile("image", filename, contentType, bytes), validNew()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Ảnh chỉ được là JPG, PNG hoặc WebP.");
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "{0} bytes -> too large")
    @ValueSource(ints = {2 * 1024 * 1024 + 1, 2_621_440, 9 * 1024 * 1024})
    void create_withImageOverTwoMegabytes_showsSizeErrorAndPageIsRenderedNot500(int bytes) throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products", png(bytes), validNew()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Ảnh tối đa 2 MB.");
        assertThat(tagWithId(html, "name")).contains("value=\"Bình nước 1L\"");
        verifyNoInteractions(productService);
    }

    @Test
    void create_withExactlyTwoMegabytes_isAccepted() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(1, "X"));

        mockMvc.perform(multipartPost("/admin/products", png(2 * 1024 * 1024), validNew()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void create_withBadImageAndAnotherBadField_showsBothErrors() throws Exception {
        String html = mockMvc.perform(multipartPost("/admin/products",
                        new MockMultipartFile("image", "a.gif", "image/gif", TestImages.gif()), validNew("price", "0")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFieldError(html, "price", "Giá phải là số nguyên từ 1 đến 999.999.999 ₫.");
        assertThat(unescape(html)).contains("Ảnh chỉ được là JPG, PNG hoặc WebP.");
        verifyNoInteractions(productService);
    }

    @Test
    void create_whenCategoryVanished_showsErrorUnderCategoryAfterTheServiceTransaction() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenThrow(new CategoryNotFoundException("gone"));

        String html = mockMvc.perform(multipartPost("/admin/products", png(1024), validNew()))
                .andExpect(status().isOk()).andExpect(view().name("admin/product-new"))
                .andReturn().getResponse().getContentAsString();

        assertFieldError(html, "categoryId", "Danh mục không tồn tại.");
        assertThat(tagWithId(html, "name")).contains("value=\"Bình nước 1L\"");
    }

    // ------------------------------------------------------------ AC-006-24: mass assignment

    @Test
    void create_ignoresForgedIdStockImagePathAndTimestamps() throws Exception {
        when(productService.create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new SavedProduct(1, "X"));

        mockMvc.perform(multipartPost("/admin/products", null, validNew("id", "5", "stock", "999",
                        "imagePath", "/x", "createdAt", "2020-01-01", "version", "9")))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ProductDraft> draft = ArgumentCaptor.forClass(ProductDraft.class);
        verify(productService).create(eq(1L), draft.capture(), eq(30), eq(true), isNull());
        assertThat(draft.getValue()).isEqualTo(new ProductDraft("Bình nước 1L", 3L, new BigDecimal("1250000"),
                "Giữ nhiệt 12 giờ"));
        verify(productService, never()).adjustStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    void update_ignoresForgedIdStockActiveImagePathCreatedAtAndInitialStock() throws Exception {
        when(productService.update(anyLong(), anyLong(), any(), any())).thenReturn("Bình nước 1L");

        mockMvc.perform(multipartPost("/admin/products/5", null, validEdit("stock", "999", "id", "7",
                        "active", "false", "imagePath", "/x", "createdAt", "2020-01-01", "initialStock", "7")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products/5/edit"));

        ArgumentCaptor<ProductDraft> draft = ArgumentCaptor.forClass(ProductDraft.class);
        verify(productService).update(eq(1L), eq(5L), draft.capture(), isNull());
        assertThat(draft.getValue()).isEqualTo(new ProductDraft("Bình nước 1L", 3L, new BigDecimal("1250000"),
                "Giữ nhiệt 12 giờ"));
        verify(productService, never()).adjustStock(anyLong(), anyLong(), anyInt());
        verify(productService, never()).setActive(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(productService, never()).create(anyLong(), any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    // ============================================================= EDIT FORM (AC-006-22, 32)

    @Test
    void editForm_prefillsAndHasNoInitialStockOrActiveField_andShowsTheStockSection() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, "/uploads/aaaa.png"));

        String html = mockMvc.perform(get("/admin/products/5/edit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/product-edit"))
                .andExpect(model().attributeExists("product", "form", "categories"))
                .andReturn().getResponse().getContentAsString();

        assertLayout(html, "Sửa sản phẩm | Quản trị Mini Shop", "Sửa sản phẩm", "products");
        String form = formWithAction(html, "/admin/products/5");
        assertThat(form).contains("enctype=\"multipart/form-data\"").contains("name=\"_csrf\"");
        assertThat(tagWithId(form, "name")).contains("value=\"Bình nước 1L\"");
        assertThat(tagWithId(form, "price")).contains("value=\"1250000\"");
        assertThat(form).contains("<option value=\"3\" selected=\"selected\">Gia dụng</option>");
        assertThat(unescape(form)).contains(">Giữ nhiệt<");
        assertThat(form).doesNotContain("initialStock").doesNotContain("name=\"active\"").doesNotContain("name=\"stock\"");
        assertThat(matches("<label[^>]*for=\"image\"[^>]*>\\s*Ảnh mới \\(không bắt buộc\\)\\s*</label>", form)).isTrue();
        assertThat(form).contains("src=\"/uploads/aaaa.png\"").contains("JPG, PNG hoặc WebP, tối đa 2 MB.");
        for (String id : List.of("name", "categoryId", "price", "description", "image")) {
            assertLabelled(form, id);
        }
        assertThat(tagWithId(form, "name")).contains("maxlength=\"200\"");
        assertThat(tagWithId(form, "description")).contains("maxlength=\"4000\"");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Lưu\\s*</button>", form)).isTrue();
        assertThat(matches("<a[^>]*href=\"/admin/products\"[^>]*>\\s*Hủy\\s*</a>", form)).isTrue();
    }

    @Test
    void editForm_stockSection_showsCurrentStockAndTheAdjustForm() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, null));

        String html = html("/admin/products/5/edit");

        assertThat(matches("<h2[^>]*>\\s*Điều chỉnh tồn kho\\s*</h2>", html)).isTrue();
        assertThat(html).contains("Tồn kho hiện tại: 10");
        String form = formWithAction(html, "/admin/products/5/stock");
        assertThat(form).contains("method=\"post\"").contains("name=\"_csrf\"");
        assertThat(tagWithId(form, "delta")).contains("type=\"text\"").contains("name=\"delta\"")
                .contains("inputmode=\"numeric\"").contains("aria-describedby=\"delta-hint\"");
        assertThat(matches("<label[^>]*for=\"delta\"[^>]*>\\s*Số lượng thay đổi\\s*</label>", form)).isTrue();
        assertThat(form).contains("id=\"delta-hint\"")
                .contains("Số dương để nhập thêm, số âm để giảm (ví dụ: 20 hoặc -3).");
        assertThat(matches("<button[^>]*type=\"submit\"[^>]*>\\s*Cập nhật tồn kho\\s*</button>", form)).isTrue();
    }

    @Test
    void editForm_activeProduct_hasStorefrontLinkAndHideFormWithFromEdit() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, null));

        String html = html("/admin/products/5/edit");

        assertThat(matches("<a[^>]*href=\"/products/5\"[^>]*>\\s*Xem trên cửa hàng\\s*</a>", html)).isTrue();
        String hide = formWithAction(html, "/admin/products/5/hide");
        assertThat(hide).contains("name=\"from\" value=\"edit\"").contains("name=\"_csrf\"");
        assertThat(matches("<button[^>]*>\\s*Ẩn\\s*</button>", hide)).isTrue();
        assertThat(html).contains("Đang bán").doesNotContain("/admin/products/5/show");
    }

    @Test
    void editForm_hiddenProduct_hasNoStorefrontLinkAndShowFormWithFromEdit() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, false, null));

        String html = html("/admin/products/5/edit");

        assertThat(html).doesNotContain("Xem trên cửa hàng").doesNotContain("href=\"/products/5\"");
        String show = formWithAction(html, "/admin/products/5/show");
        assertThat(show).contains("name=\"from\" value=\"edit\"");
        assertThat(matches("<button[^>]*>\\s*Hiện lại\\s*</button>", show)).isTrue();
        assertThat(html).contains("Đã ẩn");
    }

    @Test
    void editForm_nameAndDescriptionWithScript_areEscaped() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(new ProductEditView(5, EVIL, 3L, new BigDecimal("1000"), EVIL,
                1, true, null));

        String html = html("/admin/products/5/edit");

        assertThat(html).doesNotContain(EVIL);
        assertThat(html.split(Pattern.quote(EVIL_ESCAPED), -1).length - 1).as("name and description").isEqualTo(2);
    }

    @Test
    void editForm_withFlashNotice_showsIt() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, null));

        String html = mockMvc.perform(get("/admin/products/5/edit").with(admin())
                        .flashAttr("notice", "Không thể giảm 5 vì tồn kho hiện tại chỉ còn 3.")
                        .flashAttr("noticeKind", "error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Không thể giảm 5 vì tồn kho hiện tại chỉ còn 3.").contains("role=\"alert\"");
    }

    // ============================================================= UPDATE

    @Test
    void update_withValidForm_redirectsBackToEditPageWithFlash() throws Exception {
        when(productService.update(anyLong(), anyLong(), any(), any())).thenReturn("Bình nước 1,5L");

        mockMvc.perform(multipartPost("/admin/products/5", null, validEdit("name", "Bình nước 1,5L")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice", "Đã cập nhật sản phẩm Bình nước 1,5L."))
                .andExpect(flash().attribute("noticeKind", "success"));

        verify(productService).update(eq(1L), eq(5L), any(ProductDraft.class), isNull());
    }

    @Test
    void update_withNewImage_passesTheCheckedUpload() throws Exception {
        when(productService.update(anyLong(), anyLong(), any(), any())).thenReturn("X");

        mockMvc.perform(multipartPost("/admin/products/5", png(2048), validEdit())).andExpect(status().is3xxRedirection());

        ArgumentCaptor<ImageUpload> image = ArgumentCaptor.forClass(ImageUpload.class);
        verify(productService).update(eq(1L), eq(5L), any(), image.capture());
        assertThat(image.getValue().format()).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void update_withInvalidFields_rendersEditPageWithErrorsAndNeverUpdates() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, null));

        String html = mockMvc.perform(multipartPost("/admin/products/5", null,
                        validEdit("name", "", "price", "12,5", "categoryId", "abc")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/product-edit"))
                .andReturn().getResponse().getContentAsString();

        assertFieldError(html, "name", "Vui lòng nhập tên sản phẩm.");
        assertFieldError(html, "price", "Giá phải là số nguyên từ 1 đến 999.999.999 ₫.");
        assertFieldError(html, "categoryId", "Danh mục không tồn tại.");
        assertThat(formWithAction(html, "/admin/products/5")).contains("name=\"_csrf\"");
        assertThat(html).contains("Tồn kho hiện tại: 10"); // the page around the form is complete
        verify(productService, never()).update(anyLong(), anyLong(), any(), any());
    }

    @Test
    void update_withInvalidImage_rendersEditPageWithImageError() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, null));

        String html = mockMvc.perform(multipartPost("/admin/products/5",
                        new MockMultipartFile("image", "evil.jpg", "image/jpeg", TestImages.htmlNamedJpg()), validEdit()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String big = mockMvc.perform(multipartPost("/admin/products/5", png(9 * 1024 * 1024), validEdit()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(unescape(html)).contains("Ảnh chỉ được là JPG, PNG hoặc WebP.");
        assertThat(unescape(big)).contains("Ảnh tối đa 2 MB.");
        verify(productService, never()).update(anyLong(), anyLong(), any(), any());
    }

    @Test
    void update_whenCategoryVanished_showsErrorUnderCategory() throws Exception {
        when(productService.getForEdit(5L)).thenReturn(edit(5, true, null));
        when(productService.update(anyLong(), anyLong(), any(), any())).thenThrow(new CategoryNotFoundException("gone"));

        String html = mockMvc.perform(multipartPost("/admin/products/5", null, validEdit()))
                .andExpect(status().isOk()).andExpect(view().name("admin/product-edit"))
                .andReturn().getResponse().getContentAsString();

        assertFieldError(html, "categoryId", "Danh mục không tồn tại.");
    }

    // ============================================================= HIDE / SHOW (AC-006-25, 26)

    @Test
    void hide_fromEdit_redirectsToEditPageWithFlash() throws Exception {
        when(productService.setActive(1L, 5L, false)).thenReturn("Bình nước 1L");

        mockMvc.perform(post("/admin/products/5/hide").with(admin()).with(csrf()).param("from", "edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice", "Đã ẩn sản phẩm Bình nước 1L. Khách sẽ không thấy sản phẩm này nữa."))
                .andExpect(flash().attribute("noticeKind", "success"));
    }

    @Test
    void show_fromEdit_redirectsToEditPageWithFlash() throws Exception {
        when(productService.setActive(1L, 5L, true)).thenReturn("Bình nước 1L");

        mockMvc.perform(post("/admin/products/5/show").with(admin()).with(csrf()).param("from", "edit"))
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice", "Đã hiện lại sản phẩm Bình nước 1L."))
                .andExpect(flash().attribute("noticeKind", "success"));
    }

    @Test
    void hide_fromList_goesBackToTheSamePageKeepingEncodedQuery() throws Exception {
        when(productService.setActive(1L, 5L, false)).thenReturn("Áo");

        mockMvc.perform(post("/admin/products/5/hide").with(admin()).with(csrf())
                        .param("from", "list").param("q", "áo thun").param("page", "2"))
                .andExpect(redirectedUrl("/admin/products?q=%C3%A1o%20thun&page=2"));
    }

    @Test
    void show_fromList_withoutQuery_goesBackToTheListPage() throws Exception {
        when(productService.setActive(1L, 5L, true)).thenReturn("Áo");

        mockMvc.perform(post("/admin/products/5/show").with(admin()).with(csrf())
                        .param("from", "list").param("q", "").param("page", "3"))
                .andExpect(redirectedUrl("/admin/products?page=3"));
    }

    @Test
    void hide_withNoFromBadPageAndOddQuery_defaultsToListPageOneWithStrictlyEncodedQuery() throws Exception {
        when(productService.setActive(1L, 5L, false)).thenReturn("Áo");

        mockMvc.perform(post("/admin/products/5/hide").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/products?page=1"));
        mockMvc.perform(post("/admin/products/5/hide").with(admin()).with(csrf())
                        .param("from", "list").param("page", "abc").param("q", "a&b=c#d+e"))
                .andExpect(redirectedUrl("/admin/products?q=a%26b%3Dc%23d%2Be&page=1"));
        mockMvc.perform(post("/admin/products/5/hide").with(admin()).with(csrf())
                        .param("from", "https://evil.example/"))
                .andExpect(redirectedUrl("/admin/products?page=1"));
    }

    @Test
    void hide_ignoresRefererAndCapsAVeryLongQuery() throws Exception {
        when(productService.setActive(1L, 5L, false)).thenReturn("Áo");

        String url = mockMvc.perform(post("/admin/products/5/hide").with(admin()).with(csrf())
                        .header("Referer", "https://evil.example/").param("from", "list").param("q", "x".repeat(300)))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(url).isEqualTo("/admin/products?q=" + "x".repeat(100) + "&page=1");
    }

    // ============================================================= STOCK (AC-006-32, 33, 34)

    @ParameterizedTest(name = "delta \"{0}\" -> {1}")
    @CsvSource({"+5,5", "5,5", "-2,-2", "  7  ,7", "100000,100000", "-100000,-100000"})
    void stock_withValidDelta_callsServiceOnceAndFlashesTheNewStock(String raw, int expected) throws Exception {
        when(productService.adjustStock(1L, 5L, expected)).thenReturn(new StockAdjustResult.Adjusted("Bình nước 1L", 15));

        mockMvc.perform(post("/admin/products/5/stock").with(admin()).with(csrf()).param("delta", raw))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice", "Đã cập nhật tồn kho sản phẩm Bình nước 1L. Tồn kho hiện tại: 15."))
                .andExpect(flash().attribute("noticeKind", "success"));

        verify(productService).adjustStock(1L, 5L, expected);
    }

    @Test
    void stock_whenNotEnough_flashesTheErrorWithBothNumbers() throws Exception {
        when(productService.adjustStock(1L, 5L, -5)).thenReturn(new StockAdjustResult.NotEnough("Bình nước 1L", 5, 3));

        mockMvc.perform(post("/admin/products/5/stock").with(admin()).with(csrf()).param("delta", "-5"))
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice", "Không thể giảm 5 vì tồn kho hiện tại chỉ còn 3."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @Test
    void stock_whenWouldExceed_flashesTheLimitError() throws Exception {
        when(productService.adjustStock(1L, 5L, 10)).thenReturn(new StockAdjustResult.WouldExceed("Bình nước 1L"));

        mockMvc.perform(post("/admin/products/5/stock").with(admin()).with(csrf()).param("delta", "10"))
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice", "Tồn kho sau điều chỉnh không được vượt quá 100.000."))
                .andExpect(flash().attribute("noticeKind", "error"));
    }

    @ParameterizedTest(name = "delta \"{0}\" is refused without calling the service")
    @ValueSource(strings = {"0", "+0", "-0", "", "   ", "abc", "1.5", "1,5", "100001", "-100001", "+-1", "1e3",
            "9999999999999999999"})
    void stock_withInvalidDelta_flashesErrorAndNeverCallsService(String raw) throws Exception {
        mockMvc.perform(post("/admin/products/5/stock").with(admin()).with(csrf()).param("delta", raw))
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice",
                        "Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000."))
                .andExpect(flash().attribute("noticeKind", "error"));

        verify(productService, never()).adjustStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    void stock_withoutDeltaParameter_isTheSameInvalidMessage() throws Exception {
        mockMvc.perform(post("/admin/products/5/stock").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/products/5/edit"))
                .andExpect(flash().attribute("notice",
                        "Số lượng điều chỉnh phải là số nguyên khác 0, từ -100.000 đến 100.000."));

        verify(productService, never()).adjustStock(anyLong(), anyLong(), anyInt());
    }

    // ============================================================= 404 (AC-006-5)

    @ParameterizedTest(name = "GET {0} -> admin 404 without calling the service")
    @ValueSource(strings = {"/admin/products/abc/edit", "/admin/products/-1/edit", "/admin/products/1.5/edit",
            "/admin/products/99999999999999999999/edit"})
    void editForm_withBadId_is404InAdminLayout(String url) throws Exception {
        String html = mockMvc.perform(get(url).with(admin()))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString();

        assertNotFoundPage(html);
        verifyNoInteractions(productService);
    }

    @Test
    void editForm_whenServiceThrowsNotFound_is404InAdminLayout() throws Exception {
        when(productService.getForEdit(999L)).thenThrow(new NotFoundException("Product not found: 999"));

        assertNotFoundPage(mockMvc.perform(get("/admin/products/999/edit").with(admin()))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest(name = "POST {0} with a bad id -> admin 404")
    @ValueSource(strings = {"/admin/products/abc", "/admin/products/abc/stock", "/admin/products/abc/hide",
            "/admin/products/abc/show"})
    void posts_withBadId_are404AndChangeNothing(String url) throws Exception {
        assertNotFoundPage(mockMvc.perform(post(url).with(admin()).with(csrf()).param("delta", "5")
                        .param("name", "X").param("categoryId", "3").param("price", "1000"))
                .andExpect(status().isNotFound()).andExpect(view().name("admin/not-found"))
                .andReturn().getResponse().getContentAsString());

        verifyNoInteractions(productService);
    }

    @Test
    void posts_whenServiceThrowsNotFound_are404() throws Exception {
        when(productService.adjustStock(1L, 999L, 5)).thenThrow(new NotFoundException("Product not found: 999"));
        when(productService.setActive(anyLong(), eq(999L), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new NotFoundException("Product not found: 999"));
        when(productService.update(anyLong(), eq(999L), any(), any())).thenThrow(new NotFoundException("Product not found: 999"));

        for (var request : List.of(
                post("/admin/products/999/stock").param("delta", "5"),
                post("/admin/products/999/hide"),
                post("/admin/products/999/show"))) {
            assertNotFoundPage(mockMvc.perform(request.with(admin()).with(csrf()))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        }
        assertNotFoundPage(mockMvc.perform(multipartPost("/admin/products/999", null, validEdit()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
    }

    private static void assertNotFoundPage(String html) {
        assertLayout(html, NOT_FOUND_TITLE + " | Quản trị Mini Shop", NOT_FOUND_TITLE, "products");
        assertThat(matches("<a[^>]*href=\"/admin/products\"[^>]*>\\s*Quay lại danh sách sản phẩm\\s*</a>", main(html)))
                .isTrue();
    }
}

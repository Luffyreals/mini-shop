package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import com.minishop.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tầng web của danh sách và chi tiết sản phẩm, service được mock, không đăng nhập
 * (AC-002-1, 2, 6, 8..17; AC-001-5 được AC-002-2 thay thế; AC-001-16 trên /products).
 */
@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    private static final String PLACEHOLDER = "/images/placeholder-product.svg";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProductService productService;

    // ---------- helpers ----------

    private static ProductCardView card(long id, String name, String category, String price, boolean inStock,
                                        String imageUrl) {
        return new ProductCardView(id, name, category, new BigDecimal(price), inStock, imageUrl);
    }

    private static ProductFilter defaultFilter() {
        return new ProductFilter(null, null, ProductSort.NEWEST, 1);
    }

    private static List<CategoryOption> categories() {
        return List.of(new CategoryOption(1L, "Điện tử"), new CategoryOption(3L, "Sách"));
    }

    private static ProductListView listOf(List<ProductCardView> products, long total, int totalPages,
                                          ProductFilter filter) {
        return new ProductListView(products, total, totalPages, filter, categories());
    }

    private static ProductListView singleProductList() {
        return listOf(List.of(card(7, "Tai nghe không dây", "Điện tử", "1250000", true,
                "/images/products/tai-nghe-khong-day.jpg")), 1, 1, defaultFilter());
    }

    private static ProductDetailView detail(boolean inStock, String imageUrl) {
        return new ProductDetailView(7L, "Tai nghe không dây", "Điện tử", new BigDecimal("1250000"),
                "Mô tả dòng một\nMô tả dòng hai", inStock, imageUrl);
    }

    private String body(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static boolean matches(String regex, String html) {
        return Pattern.compile(regex).matcher(html).find();
    }

    // ---------- danh sách: AC-002-1, 2, 12, AC-001-5 ----------

    @ParameterizedTest
    @ValueSource(strings = {"/", "/products"})
    void list_isPublicAndRendersCardWithNameCategoryPriceAndLink(String url) throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(content().string(containsString("<title>Sản phẩm | Mini Shop</title>")))
                .andExpect(content().string(containsString("Tai nghe không dây")))
                .andExpect(content().string(containsString("Điện tử")))
                .andExpect(content().string(containsString("1.250.000 ₫")))
                .andExpect(content().string(containsString("href=\"/products/7\"")));
    }

    @Test
    void list_atRoot_hasSameContentAsProducts() throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        assertThat(body("/")).isEqualTo(body("/products"));
    }

    /** AC-001-16 trên trang sản phẩm. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/products"})
    void list_carriesContentSecurityPolicy(String url) throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        mockMvc.perform(get(url))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    // ---------- tham số sai: AC-002-6, 8, BR-002-3 ----------

    @Test
    void list_withGarbageParams_returns200AndPassesRawStringsToService() throws Exception {
        when(productService.search(any())).thenReturn(listOf(List.of(), 0, 0, defaultFilter()));

        mockMvc.perform(get("/products").param("category", "abc").param("page", "abc").param("sort", "xyz"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"));

        ArgumentCaptor<ProductSearchParams> captor = ArgumentCaptor.forClass(ProductSearchParams.class);
        verify(productService).search(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ProductSearchParams(null, "abc", "xyz", "abc"));
    }

    // ---------- AC-002-10 ----------

    @Test
    void list_showsTotalMatchingCountNotPageSize() throws Exception {
        List<ProductCardView> twelve = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> card(i, "Sản phẩm " + i, "Sách", "10000", true, null)).toList();
        when(productService.search(any())).thenReturn(listOf(twelve, 25, 3, defaultFilter()));

        assertThat(body("/products")).contains("Tìm thấy 25 sản phẩm");
    }

    // ---------- AC-002-11 ----------

    @Test
    void list_whenEmpty_showsEmptyStateWithClearFilterLinkAndNoPagination() throws Exception {
        when(productService.search(any())).thenReturn(listOf(List.of(), 0, 0, defaultFilter()));

        String html = body("/products");

        assertThat(html).contains("Tìm thấy 0 sản phẩm")
                .contains("Không tìm thấy sản phẩm phù hợp.")
                .contains("Xóa bộ lọc")
                .doesNotContain("aria-label=\"Phân trang\"")
                .doesNotContain("Trang trước")
                .doesNotContain("Trang sau");
        assertThat(matches("<a[^>]*href=\"/products\"[^>]*>\\s*Xóa bộ lọc", html)).isTrue();
    }

    @Test
    void list_whenSinglePage_hidesPagination() throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        assertThat(body("/products")).doesNotContain("Trang trước").doesNotContain("Trang sau");
    }

    // ---------- AC-002-8, 9 ----------

    @Test
    void list_onMiddlePage_keepsAllParamsInLinksAndShowsSelectedControls() throws Exception {
        ProductFilter filter = new ProductFilter("áo", 3L, ProductSort.PRICE_ASC, 2);
        when(productService.search(any())).thenReturn(
                listOf(List.of(card(1, "Áo thun", "Sách", "10000", true, null)), 30, 3, filter));

        String html = body("/products?q=áo&category=3&sort=price_asc&page=2");

        String base = "/products?q=%C3%A1o&amp;category=3&amp;sort=price_asc&amp;page=";
        assertThat(html)
                .contains("href=\"" + base + "1\"")
                .contains("href=\"" + base + "3\"")
                .contains("Trang trước")
                .contains("Trang sau")
                .contains("<span class=\"page-link\" aria-current=\"page\">2</span>")
                // trang hiện tại không có link tới chính nó
                .doesNotContain("href=\"" + base + "2\"");
        // ô tìm kiếm, danh mục, sắp xếp đang chọn
        assertThat(matches("<input(?=[^>]*name=\"q\")(?=[^>]*value=\"áo\")[^>]*>", html)).isTrue();
        assertThat(matches("<option(?=[^>]*value=\"3\")(?=[^>]*selected)[^>]*>", html)).isTrue();
        assertThat(matches("<option(?=[^>]*value=\"price_asc\")(?=[^>]*selected)[^>]*>", html)).isTrue();
        assertThat(matches("<option(?=[^>]*value=\"newest\")(?=[^>]*selected)[^>]*>", html)).isFalse();
    }

    @Test
    void list_onFirstPage_rendersPreviousAsDisabledSpanWithoutHref() throws Exception {
        when(productService.search(any())).thenReturn(
                listOf(List.of(card(1, "Áo thun", "Sách", "10000", true, null)), 30, 3, defaultFilter()));

        String html = body("/products");

        assertThat(html).contains("<span class=\"page-link\">Trang trước</span>")
                .contains("<span class=\"page-link\" aria-current=\"page\">1</span>");
        assertThat(matches("<a[^>]*>\\s*Trang trước", html)).isFalse();
        assertThat(matches("<a[^>]*>\\s*Trang sau", html)).isTrue();
    }

    @Test
    void list_onLastPage_rendersNextAsDisabledSpanWithoutHref() throws Exception {
        ProductFilter filter = new ProductFilter(null, null, ProductSort.NEWEST, 3);
        when(productService.search(any())).thenReturn(
                listOf(List.of(card(1, "Áo thun", "Sách", "10000", true, null)), 30, 3, filter));

        String html = body("/products?page=3");

        assertThat(html).contains("<span class=\"page-link\">Trang sau</span>");
        assertThat(matches("<a[^>]*>\\s*Trang sau", html)).isFalse();
        assertThat(matches("<a[^>]*>\\s*Trang trước", html)).isTrue();
    }

    @Test
    void list_pageLinks_encodeSpecialCharactersInQuery() throws Exception {
        ProductFilter filter = new ProductFilter("C++", null, ProductSort.NEWEST, 1);
        when(productService.search(any())).thenReturn(
                listOf(List.of(card(1, "Sách C++", "Sách", "10000", true, null)), 30, 3, filter));

        assertThat(body("/products?q=C%2B%2B"))
                .contains("href=\"/products?q=C%2B%2B&amp;sort=newest&amp;page=2\"");
    }

    @Test
    void list_formHasGetMethodAndNoPageField() throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        String html = body("/products");

        assertThat(matches("<form(?=[^>]*method=\"get\")(?=[^>]*action=\"/products\")[^>]*>", html)).isTrue();
        assertThat(html).doesNotContain("name=\"page\"")
                .contains("Tất cả danh mục").contains("Mới nhất")
                .contains("Giá: thấp đến cao").contains("Giá: cao đến thấp")
                .contains("placeholder=\"Tìm sản phẩm theo tên…\"").contains("Tìm</button>");
    }

    // ---------- AC-002-17 ----------

    @Test
    void list_escapesHtmlInSearchKeyword() throws Exception {
        String raw = "<script>alert(1)</script>";
        ProductFilter filter = new ProductFilter(raw, null, ProductSort.NEWEST, 1);
        when(productService.search(any())).thenReturn(listOf(List.of(), 0, 0, filter));

        String html = mockMvc.perform(get("/products").param("q", raw)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain(raw);
    }

    // ---------- AC-002-13 ----------

    @Test
    void list_outOfStockProduct_showsBadgeAndInStockOneDoesNot() throws Exception {
        when(productService.search(any())).thenReturn(listOf(List.of(
                card(1, "Hàng còn", "Sách", "10000", true, null),
                card(2, "Hàng hết", "Sách", "20000", false, null)), 2, 1, defaultFilter()));

        assertThat(body("/products")).containsOnlyOnce("Hết hàng").doesNotContain("Còn hàng");
    }

    @Test
    void detail_showsStockLabelAndNeverStockQuantity() throws Exception {
        when(productService.getDetail(7L)).thenReturn(detail(true, null));
        assertThat(body("/products/7")).contains("Còn hàng").doesNotContain("Hết hàng");

        when(productService.getDetail(8L)).thenReturn(detail(false, null));
        assertThat(body("/products/8")).contains("Hết hàng").doesNotContain("Còn hàng");
    }

    // ---------- AC-002-14 ----------

    @Test
    void detail_rendersNameCategoryPriceDescriptionAndTitle() throws Exception {
        when(productService.getDetail(7L)).thenReturn(detail(true, "/images/products/tai-nghe-khong-day.jpg"));

        mockMvc.perform(get("/products/7"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/detail"))
                .andExpect(content().string(containsString("<title>Tai nghe không dây | Mini Shop</title>")))
                .andExpect(content().string(containsString("Điện tử")))
                .andExpect(content().string(containsString("1.250.000 ₫")))
                .andExpect(content().string(containsString("Mô tả dòng một")))
                .andExpect(content().string(containsString("/images/products/tai-nghe-khong-day.jpg")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    @Test
    void detail_escapesHtmlInDescription() throws Exception {
        when(productService.getDetail(7L)).thenReturn(new ProductDetailView(7L, "Tai nghe", "Điện tử",
                new BigDecimal("1000"), "<b>đậm</b>", true, null));

        assertThat(body("/products/7")).contains("&lt;b&gt;đậm&lt;/b&gt;").doesNotContain("<b>đậm</b>");
    }

    // ---------- AC-002-16 ----------

    @Test
    void list_withoutImage_usesPlaceholderWithProductNameAsAlt() throws Exception {
        when(productService.search(any())).thenReturn(listOf(
                List.of(card(5, "Truyện ngắn chọn lọc", "Sách", "50000", true, null)), 1, 1, defaultFilter()));

        String html = body("/products");

        assertThat(matches("<img(?=[^>]*src=\"" + PLACEHOLDER + "\")(?=[^>]*alt=\"Truyện ngắn chọn lọc\")[^>]*>",
                html)).isTrue();
    }

    @Test
    void detail_withoutImage_usesPlaceholderWithProductNameAsAlt() throws Exception {
        when(productService.getDetail(7L)).thenReturn(detail(true, null));

        String html = body("/products/7");

        assertThat(matches("<img(?=[^>]*src=\"" + PLACEHOLDER + "\")(?=[^>]*alt=\"Tai nghe không dây\")[^>]*>",
                html)).isTrue();
    }

    @Test
    void list_withImage_usesProductImageNotPlaceholder() throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        assertThat(body("/products")).contains("src=\"/images/products/tai-nghe-khong-day.jpg\"")
                .doesNotContain("placeholder-product.svg");
    }

    // ---------- AC-002-15 ----------

    @Test
    void detail_whenServiceThrowsNotFound_returns404WithNotFoundPage() throws Exception {
        when(productService.getDetail(99L)).thenThrow(new NotFoundException("Product not found: 99"));

        assertNotFoundPage("/products/99");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "99999999999999999999"})
    void detail_withNonNumericOrOverflowingId_returns404WithoutCallingService(String id) throws Exception {
        assertNotFoundPage("/products/" + id);

        verify(productService, never()).getDetail(any());
    }

    private void assertNotFoundPage(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isNotFound())
                .andExpect(view().name("product/not-found"))
                .andExpect(content().string(containsString("<title>Không tìm thấy sản phẩm | Mini Shop</title>")))
                .andExpect(content().string(containsString("Sản phẩm không tồn tại hoặc đã ngừng bán.")))
                .andExpect(content().string(containsString("Về danh sách sản phẩm")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    // ---------- layout chung ----------

    @Test
    void pages_includeSharedHeaderAndFooter() throws Exception {
        when(productService.search(any())).thenReturn(singleProductList());

        assertThat(body("/products")).contains("Mini Shop").contains("Dự án học tập, không bán hàng thật.");
    }
}

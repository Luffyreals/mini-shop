package com.minishop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-002-19 (và AC-001-16): template và trang tĩnh không được có thứ CSP sẽ chặn hoặc thứ mở lỗ XSS:
 * script nội tuyến, thẻ style, style=, on...=, javascript:, th:utext. JUnit thuần, không có Spring context.
 */
class TemplateCspComplianceTest {

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc\\s*=)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TAG = Pattern.compile("<style\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTR = Pattern.compile("(?<![\\w-])(th:)?style\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_ATTR = Pattern.compile("(?<![\\w-])(th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_URL = Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE);
    private static final Pattern UTEXT = Pattern.compile("th:utext", Pattern.CASE_INSENSITIVE);

    private static final List<Rule> RULES = List.of(
            new Rule("inline <script> (no src)", INLINE_SCRIPT),
            new Rule("<style> tag", STYLE_TAG),
            new Rule("style= / th:style attribute", STYLE_ATTR),
            new Rule("on...= / th:on... event attribute", EVENT_ATTR),
            new Rule("javascript: URL", JS_URL),
            new Rule("th:utext (unescaped output)", UTEXT));

    private record Rule(String name, Pattern pattern) {
    }

    @Test
    void templatesAndStaticHtml_haveNoCspViolations() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> files = new ArrayList<>();
        files.addAll(List.of(resolver.getResources("classpath*:templates/**/*.html")));
        files.addAll(List.of(resolver.getResources("classpath*:static/**/*.html")));

        // chống "xanh giả": phải thực sự quét được các file đã biết
        assertThat(files).extracting(Resource::getFilename)
                .contains("list.html", "detail.html", "not-found.html", "layout.html", "pagination.html",
                        "product.html", "credits.html", "login.html", "register.html", "403.html", "index.html",
                        "notice.html");

        // trang giỏ hàng (GĐ4) phải nằm trong phạm vi quét
        assertThat(files).extracting(f -> f.getDescription().replace("\\", "/"))
                .anyMatch(d -> d.contains("templates/cart/view.html"));

        // trang thanh toán và đơn hàng (GĐ5) phải nằm trong phạm vi quét
        assertThat(files).extracting(f -> f.getDescription().replace("\\", "/"))
                .anyMatch(d -> d.contains("templates/order/checkout.html"))
                .anyMatch(d -> d.contains("templates/order/list.html"))
                .anyMatch(d -> d.contains("templates/order/detail.html"))
                .anyMatch(d -> d.contains("templates/order/not-found.html"));

        // the admin area (GĐ6, AC-006-50): the layout fragment and every admin page must be in the scan
        List<String> scanned = files.stream().map(f -> f.getDescription().replace("\\", "/")).toList();
        for (String template : List.of("fragments/admin.html", "admin/index.html", "admin/not-found.html",
                "admin/category-list.html", "admin/category-form.html", "admin/product-list.html",
                "admin/product-new.html", "admin/product-edit.html", "admin/order-list.html",
                "admin/order-detail.html")) {
            assertThat(scanned).as("scanned " + template).anyMatch(d -> d.contains("templates/" + template));
        }
        assertThat(scanned).filteredOn(d -> d.contains("templates/admin/")).hasSizeGreaterThanOrEqualTo(9);

        List<String> violations = new ArrayList<>();
        for (Resource file : files) {
            violations.addAll(find(file.getDescription(), new String(file.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)));
        }

        assertThat(violations).as("CSP violations (file:line)").isEmpty();
    }

    /** Bộ phát hiện phải bắt được các đoạn vi phạm mẫu. */
    @ParameterizedTest(name = "detects: {0}")
    @CsvSource(delimiter = '|', value = {
            "<script>alert(1)</script>                          | inline <script> (no src)",
            "<script type=\"text/javascript\">x()</script>      | inline <script> (no src)",
            "<style>p{color:red}</style>                        | <style> tag",
            "<p style=\"color:red\">x</p>                       | style= / th:style attribute",
            "<p th:style=\"${s}\">x</p>                         | style= / th:style attribute",
            "<button onclick=\"go()\">x</button>                | on...= / th:on... event attribute",
            "<img src=\"a.png\" onerror=\"x()\">                | on...= / th:on... event attribute",
            "<a th:onclick=\"${x}\">x</a>                       | on...= / th:on... event attribute",
            "<a href=\"javascript:void(0)\">x</a>               | javascript: URL",
            "<p th:utext=\"${html}\">x</p>                      | th:utext (unescaped output)"})
    void detector_catchesKnownViolations(String html, String expectedRule) {
        assertThat(find("sample", html)).anyMatch(v -> v.contains(expectedRule));
    }

    /** Markup hợp lệ (script có src, thuộc tính chỉ chứa chữ "style"/"on" trong tên) không bị báo nhầm. */
    @Test
    void detector_acceptsCompliantMarkup() {
        String ok = """
                <script src="/js/app.js" defer></script>
                <link rel="stylesheet" href="/css/app.css">
                <input type="search" th:value="${q}" data-position="x" aria-label="Tìm">
                <p class="stylish" th:text="${name}">x</p>
                <form method="get" th:action="@{/products}"></form>
                """;

        assertThat(find("sample", ok)).isEmpty();
    }

    private static List<String> find(String fileName, String content) {
        List<String> violations = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        // mỗi dòng được quét riêng để báo số dòng
        for (int i = 0; i < lines.length; i++) {
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    violations.add(fileName + ":" + (i + 1) + " " + rule.name() + " -> " + lines[i].strip());
                }
            }
        }
        return violations;
    }
}

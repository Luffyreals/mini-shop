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

    // ---------- GĐ7 (AC-007-13, AC-007-24): page contract of every template ----------

    private static final Pattern H1 = Pattern.compile("<h1\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIN_ANY = Pattern.compile("<main\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIN_CONTRACT = Pattern.compile("<main\\s[^>]*\\bid=\"main\"[^>]*>");
    private static final Pattern SKIP_LINK_FIRST_IN_BODY = Pattern.compile(
            "<body\\b[^>]*>\\s*(?:<!--.*?-->\\s*)*<a\\s+th:replace=\"~\\{fragments/layout :: skipLink\\}\"\\s*>\\s*</a>",
            Pattern.DOTALL);
    private static final Pattern SVG_SCRIPT = Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE);

    /** Files that are fragments or parts of a page, not pages: they hold no skip link, no {@code main}, no h1 rule. */
    private static boolean isPageTemplate(String path) {
        return !path.contains("templates/fragments/");
    }

    private static List<Resource> pageTemplates() throws IOException {
        List<Resource> pages = new ArrayList<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html")) {
            if (isPageTemplate(r.getDescription().replace("\\", "/"))) {
                pages.add(r);
            }
        }
        return pages;
    }

    private static String read(Resource r) throws IOException {
        return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String name(Resource r) {
        String d = r.getDescription().replace("\\", "/");
        return d.substring(d.indexOf("templates/") + "templates/".length(), d.length() - 1);
    }

    /** Anti-false-green: the two GĐ7 error templates and the known pages must really be scanned. */
    @Test
    void pageTemplates_includeErrorPagesAndEveryKnownPage() throws IOException {
        List<String> names = pageTemplates().stream().map(TemplateCspComplianceTest::name).toList();

        assertThat(names).contains("error/page.html", "admin/error.html", "error/403.html", "product/list.html",
                "product/detail.html", "user/login.html", "user/register.html", "cart/view.html",
                "order/checkout.html", "admin/index.html", "admin/order-detail.html");
        assertThat(names).noneMatch(n -> n.startsWith("fragments/"));
        assertThat(names).hasSizeGreaterThanOrEqualTo(22);
    }

    /** AC-007-24: html lang="vi" on every page template. */
    @Test
    void everyPageTemplate_hasHtmlLangVi() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Resource page : pageTemplates()) {
            if (!Pattern.compile("<html\\b[^>]*\\blang=\"vi\"").matcher(read(page)).find()) {
                bad.add(name(page));
            }
        }

        assertThat(bad).as("page templates without <html lang=\"vi\">").isEmpty();
    }

    /** AC-007-21: the skip-link include is the first child of {@code <body>} (comments aside) on every page. */
    @Test
    void everyPageTemplate_hasSkipLinkIncludeAsFirstChildOfBody() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Resource page : pageTemplates()) {
            if (!SKIP_LINK_FIRST_IN_BODY.matcher(read(page)).find()) {
                bad.add(name(page));
            }
        }

        assertThat(bad).as("page templates whose <body> does not start with the skipLink include").isEmpty();
    }

    /** AC-007-21: exactly one {@code <main id="main" tabindex="-1" ...>} per page, the skip link's target. */
    @Test
    void everyPageTemplate_hasExactlyOneMainWithIdAndTabindex() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Resource page : pageTemplates()) {
            String html = read(page);
            long mains = MAIN_ANY.matcher(html).results().count();
            boolean contract = MAIN_CONTRACT.matcher(html).results()
                    .anyMatch(m -> m.group().contains("tabindex=\"-1\""));
            if (mains != 1 || !contract) {
                bad.add(name(page) + " (main tags: " + mains + ", id=main + tabindex=-1: " + contract + ")");
            }
        }

        assertThat(bad).as("page templates without exactly one <main id=\"main\" tabindex=\"-1\">").isEmpty();
    }

    /** AC-007-24: at most one h1 in the page's own markup (the layout fragments never add one). */
    @Test
    void everyPageTemplate_hasAtMostOneH1() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Resource page : pageTemplates()) {
            long h1s = H1.matcher(read(page)).results().count();
            if (h1s > 1) {
                bad.add(name(page) + " (" + h1s + " h1)");
            }
        }

        assertThat(bad).as("page templates with several <h1>").isEmpty();
    }

    /** AC-007-13: the two error templates have exactly one h1, printed from the model (no hard-coded text). */
    @ParameterizedTest
    @CsvSource({"error/page.html", "admin/error.html"})
    void errorTemplates_haveExactlyOneH1PrintingTheHeading(String template) throws IOException {
        Resource page = pageTemplates().stream().filter(r -> name(r).equals(template)).findFirst().orElseThrow();
        String html = read(page);

        assertThat(H1.matcher(html).results().count()).isEqualTo(1);
        assertThat(html).containsPattern("<h1[^>]*th:text=\"\\$\\{errorHeading\\}\"");
        assertThat(find(template, html)).isEmpty();
    }

    /** AC-007-20, AC-007-24: the self-drawn favicon is a plain SVG image: no script, no event handler. */
    @Test
    void faviconSvg_hasNoScriptOrEventHandlers() throws IOException {
        Resource[] svgs = new PathMatchingResourcePatternResolver().getResources("classpath*:static/images/favicon.svg");

        assertThat(svgs).hasSize(1);
        String svg = read(svgs[0]);
        assertThat(svg).contains("<svg");
        assertThat(SVG_SCRIPT.matcher(svg).find()).as("<script in favicon.svg").isFalse();
        assertThat(EVENT_ATTR.matcher(svg).find()).as("on...= in favicon.svg").isFalse();
        assertThat(JS_URL.matcher(svg).find()).as("javascript: in favicon.svg").isFalse();
    }

    /** Every self-hosted SVG (favicon and placeholder) is a plain image: nothing that could run script. */
    @Test
    void everyStaticSvg_hasNoScriptEventHandlerOrJavascriptUrl() throws IOException {
        Resource[] svgs = new PathMatchingResourcePatternResolver().getResources("classpath*:static/**/*.svg");

        assertThat(svgs).extracting(Resource::getFilename).contains("favicon.svg", "placeholder-product.svg");
        List<String> violations = new ArrayList<>();
        for (Resource svg : svgs) {
            String content = read(svg);
            for (Rule rule : List.of(new Rule("<script", SVG_SCRIPT), new Rule("on...= attribute", EVENT_ATTR),
                    new Rule("javascript: URL", JS_URL))) {
                if (rule.pattern().matcher(content).find()) {
                    violations.add(svg.getFilename() + " " + rule.name());
                }
            }
        }
        assertThat(violations).isEmpty();
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

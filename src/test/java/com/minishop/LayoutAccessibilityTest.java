package com.minishop;

import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-007-21 (skip link), AC-007-20 (favicon links) and AC-007-24 (lang, alt) on the rendered pages of a real server
 * (shop layout, admin layout and both error pages), plus the same contract on the layout sources.
 *
 * <p>A browser's first {@code Tab} lands on the first focusable element in document order, so the test finds that
 * element in the rendered HTML and checks it is the skip link, and that its target {@code main#main} exists once
 * with {@code tabindex="-1"} (the target must be focusable by script-less navigation).
 */
@LiveServerTest
class LayoutAccessibilityTest {

    private static final String SKIP_LINK = "<a class=\"skip-link\" href=\"#main\">Bỏ qua đến nội dung chính</a>";
    private static final String ICO_LINK = "<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"any\">";
    private static final String SVG_LINK = "<link rel=\"icon\" href=\"/images/favicon.svg\" type=\"image/svg+xml\">";

    /** Elements a keyboard user can reach with Tab, in a rendered page (hidden inputs and disabled ones excluded). */
    private static final Pattern FOCUSABLE = Pattern.compile(
            "<(?:a\\s[^>]*\\bhref=|button\\b(?![^>]*\\bdisabled)|select\\b|textarea\\b"
                    + "|input\\b(?![^>]*\\btype=\"hidden\")(?![^>]*\\bdisabled))[^>]*>");

    @LocalServerPort
    int port;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.seed.user-password}")
    String userPassword;

    private static String bodyOf(String html) {
        int start = html.indexOf("<body");
        assertThat(start).as("a <body> in the page").isGreaterThanOrEqualTo(0);
        return html.substring(start);
    }

    private static String firstFocusableTag(String html) {
        Matcher m = FOCUSABLE.matcher(bodyOf(html));
        assertThat(m.find()).as("a focusable element in <body>").isTrue();
        return m.group();
    }

    private static void assertAccessibleLayout(String what, String html) {
        String body = bodyOf(html);

        assertThat(html).as(what).containsPattern("<html[^>]*\\blang=\"vi\"");
        // The skip link is the first focusable element and the first element inside <body> (comments aside).
        assertThat(firstFocusableTag(html)).as(what + ": first focusable element")
                .isEqualTo("<a class=\"skip-link\" href=\"#main\">");
        assertThat(body.replaceFirst("(?s)^<body[^>]*>\\s*", "")).as(what + ": first child of <body>")
                .startsWith(SKIP_LINK);
        // Its target: exactly one main#main with tabindex -1, and the skip link comes before it.
        assertThat(Pattern.compile("<main\\b").matcher(html).results().count()).as(what + ": <main> count")
                .isEqualTo(1);
        assertThat(html).as(what).containsPattern("<main\\s[^>]*\\bid=\"main\"[^>]*\\btabindex=\"-1\"|"
                + "<main\\s[^>]*\\btabindex=\"-1\"[^>]*\\bid=\"main\"");
        assertThat(html.indexOf(SKIP_LINK)).as(what + ": skip link before main")
                .isLessThan(html.indexOf("<main"));
        // Favicons in <head>.
        String head = html.substring(0, html.indexOf("</head>"));
        assertThat(head).as(what + ": favicon links").contains(ICO_LINK).contains(SVG_LINK);
        assertThat(Pattern.compile("<link rel=\"icon\"").matcher(head).results().count()).as(what).isEqualTo(2);
    }

    /** AC-007-21, 24, 20: storefront pages (guest). */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/products", "/login", "/register", "/cart", "/credits.html"})
    void storefrontPages_forGuest_startWithSkipLinkAndHaveMainTarget(String path) throws Exception {
        HttpResponse<String> response = new LiveClient(port).get(path);

        assertThat(response.statusCode()).as(path).isEqualTo(200);
        if (path.equals("/credits.html")) {
            // A static page outside the Thymeleaf layouts: only the language is part of the contract.
            assertThat(response.body()).containsPattern("<html[^>]*\\blang=\"vi\"");
            return;
        }
        assertAccessibleLayout(path, response.body());
        assertThat(response.body()).as(path).doesNotContain("admin-body");
    }

    /** AC-007-21, 24, 20: storefront pages of a logged-in customer. */
    @ParameterizedTest
    @ValueSource(strings = {"/orders", "/cart"})
    void storefrontPages_forCustomer_startWithSkipLinkAndHaveMainTarget(String path) throws Exception {
        HttpResponse<String> response = new LiveClient(port).login("user@minishop.local", userPassword).get(path);

        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertAccessibleLayout(path, response.body());
    }

    /** AC-007-21, 24, 20: the shop's 404 page and the generic error page use the same shop layout. */
    @ParameterizedTest
    @ValueSource(strings = {"/products/abc", "/products/abc/xyz", "/khong-co-trang-nay-that"})
    void storefrontErrorPages_startWithSkipLinkAndHaveMainTarget(String path) throws Exception {
        LiveClient client = new LiveClient(port).login("user@minishop.local", userPassword);
        HttpResponse<String> response = client.get(path);

        assertThat(response.statusCode()).as(path).isEqualTo(404);
        assertAccessibleLayout(path, response.body());
    }

    /** AC-007-21, 24, 20: admin pages, including the admin error page and the admin 404 page. */
    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/products", "/admin/orders", "/admin/categories",
            "/admin/khong-co-trang-nay", "/admin/products/999999/edit"})
    void adminPages_startWithSkipLinkAndHaveMainTarget(String path) throws Exception {
        HttpResponse<String> response = new LiveClient(port).login("admin@minishop.local", adminPassword).get(path);

        assertThat(response.statusCode()).as(path).isIn(200, 404);
        assertThat(response.body()).as(path).contains("admin-body");
        assertAccessibleLayout(path, response.body());
    }

    /** AC-007-21: the skip link works without JavaScript because it points at an element with that exact id. */
    @Test
    void skipLinkHref_matchesTheIdOfTheMainElement() throws Exception {
        HttpResponse<String> response = new LiveClient(port).get("/");

        Matcher href = Pattern.compile("<a class=\"skip-link\" href=\"#([^\"]+)\"").matcher(response.body());
        assertThat(href.find()).isTrue();
        assertThat(response.body()).contains("<main id=\"" + href.group(1) + "\"");
        assertThat(Pattern.compile("\\bid=\"main\"").matcher(response.body()).results().count())
                .as("the id main is unique").isEqualTo(1);
    }

    // ---------- the same contract on the sources, so a broken layout fails even without a rendered page ----------

    /** AC-007-21: the one skip-link fragment, with the exact text, in the shop layout. */
    @Test
    void layoutFragment_definesTheSkipLinkWithExactText() throws IOException {
        String layout = read("templates/fragments/layout.html");

        assertThat(layout).contains("<a class=\"skip-link\" href=\"#main\" th:fragment=\"skipLink\">"
                + "Bỏ qua đến nội dung chính</a>");
        assertThat(Pattern.compile("th:fragment=\"skipLink\"").matcher(layout).results().count()).isEqualTo(1);
    }

    /** AC-007-20: both head fragments (shop and admin) declare the two icon links. */
    @ParameterizedTest
    @ValueSource(strings = {"templates/fragments/layout.html", "templates/fragments/admin.html"})
    void headFragments_declareBothFaviconLinks(String template) throws IOException {
        String source = read(template);
        String head = source.substring(source.indexOf("<head"), source.indexOf("</head>"));

        assertThat(Pattern.compile("<link rel=\"icon\"").matcher(head).results().count()).isEqualTo(2);
        assertThat(head).contains("<link rel=\"icon\" th:href=\"@{/favicon.ico}\" sizes=\"any\">")
                .contains("<link rel=\"icon\" th:href=\"@{/images/favicon.svg}\" type=\"image/svg+xml\">");
    }

    /** AC-007-24: every image in a template has an alt (a name, or empty for a decorative one). */
    @Test
    void everyImgInTemplates_hasAnAltAttribute() throws IOException {
        List<String> bad = new ArrayList<>();
        int images = 0;
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html")) {
            String html = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher img = Pattern.compile("<img\\b[^>]*>", Pattern.DOTALL).matcher(html);
            while (img.find()) {
                images++;
                if (!Pattern.compile("(?<![\\w-])(th:)?alt\\s*=").matcher(img.group()).find()) {
                    bad.add(r.getDescription() + " -> " + img.group());
                }
            }
        }

        assertThat(images).as("images scanned").isGreaterThanOrEqualTo(5);
        assertThat(bad).as("<img> without alt / th:alt").isEmpty();
    }

    private static String read(String classpathLocation) throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:" + classpathLocation);
        assertThat(found).as(classpathLocation).hasSize(1);
        return new String(found[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}

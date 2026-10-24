package com.minishop.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared checks of the admin layout (AC-006-4) for the slice tests: tab title, the single h1, the sidebar with
 * its five links and {@code aria-current="page"} on the open section only, the greeting and the logout form
 * (with a CSRF token), and no storefront header. Plus small HTML helpers.
 */
final class AdminPageAssertions {

    static final String ADMIN_NAME = "Quản trị viên";

    private record NavItem(String href, String text, String section) {
    }

    private static final List<NavItem> NAV = List.of(
            new NavItem("/admin", "Tổng quan", "dashboard"),
            new NavItem("/admin/orders", "Đơn hàng", "orders"),
            new NavItem("/admin/products", "Sản phẩm", "products"),
            new NavItem("/admin/categories", "Danh mục", "categories"),
            new NavItem("/", "Về cửa hàng", null));

    private static final Pattern ANCHOR = Pattern.compile("<a\\s([^>]*)>([^<]*)</a>");

    private AdminPageAssertions() {
    }

    /** @param section dashboard | orders | products | categories */
    static void assertLayout(String html, String title, String h1, String section) {
        assertThat(html).contains("<title>" + title + "</title>");
        assertThat(html).contains("<body class=\"admin-body\">");
        assertThat(html).as("exactly one h1").containsOnlyOnce("<h1");
        assertThat(matches("<h1[^>]*>\\s*" + Pattern.quote(h1) + "\\s*</h1>", html)).as("h1 %s", h1).isTrue();
        assertThat(html).doesNotContain("<header").doesNotContain("<footer");

        // Sidebar: five links in order, aria-current="page" only on the open section.
        Matcher nav = Pattern.compile("<nav[^>]*aria-label=\"Quản trị\"[^>]*>(.*?)</nav>", Pattern.DOTALL).matcher(html);
        assertThat(nav.find()).as("sidebar nav").isTrue();
        List<NavItem> found = new ArrayList<>();
        List<String> current = new ArrayList<>();
        Matcher a = ANCHOR.matcher(nav.group(1));
        while (a.find()) {
            String attrs = a.group(1);
            Matcher href = Pattern.compile("href=\"([^\"]*)\"").matcher(attrs);
            assertThat(href.find()).isTrue();
            found.add(new NavItem(href.group(1), a.group(2).strip(), null));
            if (attrs.contains("aria-current=\"page\"")) {
                current.add(a.group(2).strip());
            }
        }
        assertThat(found).extracting(NavItem::href).containsExactlyElementsOf(NAV.stream().map(NavItem::href).toList());
        assertThat(found).extracting(NavItem::text).containsExactlyElementsOf(NAV.stream().map(NavItem::text).toList());
        String expectedCurrent = NAV.stream().filter(n -> section.equals(n.section())).map(NavItem::text).findFirst()
                .orElseThrow();
        assertThat(current).as("aria-current=page links").containsExactly(expectedCurrent);

        // Greeting and logout (a POST form with a CSRF token).
        assertThat(html).contains("Xin chào, " + ADMIN_NAME);
        assertThat(matches("<form[^>]*action=\"/logout\"[^>]*method=\"post\"[^>]*>.*?name=\"_csrf\".*?Đăng xuất.*?</form>",
                html)).as("logout form with csrf").isTrue();
    }

    /** Thymeleaf writes some accented letters inside a textarea as entities (&ocirc;); compare on the decoded text. */
    static String unescape(String html) {
        return org.springframework.web.util.HtmlUtils.htmlUnescape(html);
    }

    static boolean matches(String regex, String html) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(html).find();
    }

    static String main(String html) {
        int start = html.indexOf("<main");
        return html.substring(start, html.indexOf("</main>", start));
    }

    /** The first {@code <form>} whose {@code action} equals the given path, from its tag to {@code </form>}. */
    static String formWithAction(String html, String action) {
        Matcher m = Pattern.compile("<form[^>]*action=\"" + Pattern.quote(action) + "\"[^>]*>.*?</form>",
                Pattern.DOTALL).matcher(html);
        assertThat(m.find()).as("a form with action %s", action).isTrue();
        return m.group();
    }

    /** All {@code <form>} blocks whose action equals the given path. */
    static List<String> formsWithAction(String html, String action) {
        Matcher m = Pattern.compile("<form[^>]*action=\"" + Pattern.quote(action) + "\"[^>]*>.*?</form>",
                Pattern.DOTALL).matcher(html);
        List<String> forms = new ArrayList<>();
        while (m.find()) {
            forms.add(m.group());
        }
        return forms;
    }

    /** The attribute list of the first tag with {@code id="…"}. */
    static String tagWithId(String html, String id) {
        Matcher m = Pattern.compile("<(?:input|select|textarea)[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>",
                Pattern.DOTALL).matcher(html);
        assertThat(m.find()).as("an input with id %s", id).isTrue();
        return unescape(m.group());
    }

    /** A form control is labelled: a {@code <label for="id">} exists. */
    static void assertLabelled(String html, String id) {
        assertThat(matches("<label[^>]*\\bfor=\"" + Pattern.quote(id) + "\"", html)).as("label for %s", id).isTrue();
    }
}

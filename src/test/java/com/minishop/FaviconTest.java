package com.minishop;

import com.minishop.support.LiveClient;
import com.minishop.support.LiveServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-007-20: the favicon files are public, have the right type and every layout links them. */
@LiveServerTest
class FaviconTest {

    private static final String ICO_LINK = "<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"any\">";
    private static final String SVG_LINK = "<link rel=\"icon\" href=\"/images/favicon.svg\" type=\"image/svg+xml\">";

    @LocalServerPort
    int port;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Test
    void faviconIco_forGuest_is200WithIcoSignature() throws Exception {
        HttpResponse<byte[]> response = new LiveClient(port).getBytes("/favicon.ico");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).containsIgnoringCase("icon");
        assertThat(response.body()).startsWith(0x00, 0x00, 0x01, 0x00);
    }

    @Test
    void faviconSvg_forGuest_is200SvgWithoutScript() throws Exception {
        HttpResponse<String> response = new LiveClient(port).get("/images/favicon.svg", "image/svg+xml,*/*");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("image/svg+xml");
        assertThat(response.body()).contains("<svg").doesNotContain("<script");
    }

    @Test
    void storefrontPages_linkBothFavicons() throws Exception {
        LiveClient guest = new LiveClient(port);

        for (String path : new String[]{"/", "/products/abc/xyz"}) {
            HttpResponse<String> response = guest.get(path);

            assertThat(response.body()).as(path).contains(ICO_LINK).contains(SVG_LINK);
        }
    }

    @Test
    void adminPagesAndAdminErrorPages_linkBothFavicons() throws Exception {
        LiveClient admin = new LiveClient(port).login("admin@minishop.local", adminPassword);

        for (String path : new String[]{"/admin", "/admin/khong-co"}) {
            HttpResponse<String> response = admin.get(path);

            assertThat(response.body()).as(path).contains("admin-body").contains(ICO_LINK).contains(SVG_LINK);
        }
    }
}

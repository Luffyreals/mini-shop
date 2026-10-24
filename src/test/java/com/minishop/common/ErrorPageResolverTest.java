package com.minishop.common;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which page and which layout an error gets (AC-007-1, 4, 7, 10, 11; BR-007-1). Plain unit test: the resolver reads
 * only the original URI attribute and the security context, so no Spring context is needed. The texts are pinned
 * character by character to spec 007 section 6.
 */
class ErrorPageResolverTest {

    private static final String STORE = "error/page";
    private static final String ADMIN = "admin/error";

    private final ErrorPageResolver resolver = new ErrorPageResolver();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ModelAndView resolve(HttpStatus status, String originalUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        if (originalUri != null) {
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        }
        return resolver.resolveErrorView(request, status, Map.of());
    }

    private static void signInWithRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("someone", "x", AuthorityUtils.createAuthorityList(role)));
    }

    // ----------------------------------------------------------- status table

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "400, Yêu cầu không hợp lệ",
            "401, Yêu cầu không hợp lệ",
            "405, Yêu cầu không hợp lệ",
            "415, Yêu cầu không hợp lệ",
            "429, Yêu cầu không hợp lệ",
            "404, Không tìm thấy trang",
            "413, Tệp tải lên quá lớn",
            "500, Đã có lỗi xảy ra",
            "503, Đã có lỗi xảy ra"})
    void resolveErrorView_picksThePageForTheStatus(int status, String heading) {
        ModelAndView mav = resolve(HttpStatus.valueOf(status), "/x");

        assertThat(mav.getViewName()).isEqualTo(STORE);
        assertThat(mav.getStatus()).isEqualTo(HttpStatus.valueOf(status));
        assertThat(mav.getModel()).containsEntry("errorHeading", heading)
                .containsEntry("errorTitle", heading + " | Mini Shop");
    }

    @Test
    void resolveErrorView_for403_returnsTheOldForbiddenViewAsASafetyNet() {
        ModelAndView mav = resolve(HttpStatus.FORBIDDEN, "/x");

        assertThat(mav.getViewName()).isEqualTo("error/403");
        assertThat(mav.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void resolveErrorView_for403UnderAdminAsAdmin_stillReturnsTheOldForbiddenView() {
        signInWithRole("ROLE_ADMIN");

        ModelAndView mav = resolve(HttpStatus.FORBIDDEN, "/admin/x");

        assertThat(mav.getViewName()).isEqualTo("error/403");
    }

    @Test
    void forStatus_belowFourHundred_isServerError() {
        assertThat(ErrorPage.forStatus(200)).isEqualTo(ErrorPage.SERVER_ERROR);
        assertThat(ErrorPage.forStatus(302)).isEqualTo(ErrorPage.SERVER_ERROR);
        assertThat(ErrorPage.forStatus(599)).isEqualTo(ErrorPage.SERVER_ERROR);
    }

    // --------------------------------------------------------------- the texts

    @Test
    void resolveErrorView_forEachPage_hasTheExactSpecTexts() {
        assertPage(resolve(HttpStatus.NOT_FOUND, "/x"), "Không tìm thấy trang",
                "Trang bạn tìm không tồn tại hoặc đã bị di chuyển.");
        assertPage(resolve(HttpStatus.METHOD_NOT_ALLOWED, "/x"), "Yêu cầu không hợp lệ",
                "Đường dẫn hoặc dữ liệu gửi lên không hợp lệ.");
        assertPage(resolve(HttpStatus.CONTENT_TOO_LARGE, "/x"), "Tệp tải lên quá lớn",
                "Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.");
        assertPage(resolve(HttpStatus.INTERNAL_SERVER_ERROR, "/x"), "Đã có lỗi xảy ra",
                "Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.");
    }

    private static void assertPage(ModelAndView mav, String heading, String message) {
        assertThat(mav.getModel()).containsOnlyKeys("errorTitle", "errorHeading", "errorMessage", "backUrl",
                "backLabel");
        assertThat(mav.getModel()).containsEntry("errorHeading", heading)
                .containsEntry("errorMessage", message)
                .containsEntry("errorTitle", heading + " | Mini Shop")
                .containsEntry("backUrl", "/")
                .containsEntry("backLabel", "Về trang chủ");
    }

    @Test
    void sessionExpiredPage_hasTheExactSpecTexts() {
        ModelAndView mav = ErrorPage.SESSION_EXPIRED.view(false, HttpStatus.FORBIDDEN);

        assertThat(mav.getViewName()).isEqualTo(STORE);
        assertThat(mav.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mav.getModel()).containsEntry("errorHeading", "Phiên làm việc đã hết hạn")
                .containsEntry("errorTitle", "Phiên làm việc đã hết hạn | Mini Shop")
                .containsEntry("errorMessage",
                        "Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại.")
                .containsEntry("backUrl", "/").containsEntry("backLabel", "Về trang chủ");
    }

    // ------------------------------------------------------------ layout matrix

    @ParameterizedTest(name = "ADMIN at {0} gets the admin layout")
    @ValueSource(strings = {"/admin", "/admin/", "/admin/x", "/admin/products/5/edit"})
    void resolveErrorView_underAdminAsAdmin_usesAdminLayout(String uri) {
        signInWithRole("ROLE_ADMIN");

        ModelAndView mav = resolve(HttpStatus.NOT_FOUND, uri);

        assertThat(mav.getViewName()).isEqualTo(ADMIN);
        assertThat(mav.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mav.getModel()).containsEntry("errorTitle", "Không tìm thấy trang | Quản trị Mini Shop")
                .containsEntry("backUrl", "/admin")
                .containsEntry("backLabel", "Về trang quản trị")
                .containsOnlyKeys("errorTitle", "errorHeading", "errorMessage", "backUrl", "backLabel");
    }

    @Test
    void resolveErrorView_forPayloadTooLargeUnderAdminAsAdmin_linksBackToTheProductList() {
        signInWithRole("ROLE_ADMIN");

        ModelAndView mav = resolve(HttpStatus.CONTENT_TOO_LARGE, "/admin/products");

        assertThat(mav.getViewName()).isEqualTo(ADMIN);
        assertThat(mav.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(mav.getModel()).containsEntry("errorTitle", "Tệp tải lên quá lớn | Quản trị Mini Shop")
                .containsEntry("errorMessage", "Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại.")
                .containsEntry("backUrl", "/admin/products")
                .containsEntry("backLabel", "Về danh sách sản phẩm");
    }

    @Test
    void resolveErrorView_forServerErrorAndBadRequestUnderAdminAsAdmin_useAdminTitleSuffix() {
        signInWithRole("ROLE_ADMIN");

        assertThat(resolve(HttpStatus.INTERNAL_SERVER_ERROR, "/admin/x").getModel())
                .containsEntry("errorTitle", "Đã có lỗi xảy ra | Quản trị Mini Shop");
        assertThat(resolve(HttpStatus.METHOD_NOT_ALLOWED, "/admin/orders/1/status").getModel())
                .containsEntry("errorTitle", "Yêu cầu không hợp lệ | Quản trị Mini Shop");
    }

    @Test
    void resolveErrorView_underAdminAsUser_usesStorefrontLayout() {
        signInWithRole("ROLE_USER");

        ModelAndView mav = resolve(HttpStatus.NOT_FOUND, "/admin/x");

        assertThat(mav.getViewName()).isEqualTo(STORE);
        assertThat(mav.getModel()).containsEntry("backUrl", "/").containsEntry("backLabel", "Về trang chủ");
    }

    @Test
    void resolveErrorView_underAdminAsAnonymous_usesStorefrontLayout() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(resolve(HttpStatus.NOT_FOUND, "/admin/x").getViewName()).isEqualTo(STORE);
    }

    @Test
    void resolveErrorView_underAdminWithNoAuthentication_usesStorefrontLayout() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        assertThat(resolve(HttpStatus.NOT_FOUND, "/admin/x").getViewName()).isEqualTo(STORE);
    }

    @Test
    void resolveErrorView_underAdminWithAnAdminTokenThatIsNotAuthenticated_usesStorefrontLayout() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("a", "x", "ROLE_ADMIN");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(resolve(HttpStatus.NOT_FOUND, "/admin/x").getViewName()).isEqualTo(STORE);
    }

    @ParameterizedTest(name = "ADMIN at {0} gets the storefront layout")
    @ValueSource(strings = {"/administrator", "/administrator/x", "/admins", "/khong-co", "/products/admin", "/",
            "/Admin/x", ""})
    void resolveErrorView_outsideAdminAsAdmin_usesStorefrontLayout(String uri) {
        signInWithRole("ROLE_ADMIN");

        assertThat(resolve(HttpStatus.NOT_FOUND, uri).getViewName()).isEqualTo(STORE);
    }

    @Test
    void resolveErrorView_withoutOriginalUriAsAdmin_usesStorefrontLayout() {
        signInWithRole("ROLE_ADMIN");

        assertThat(resolve(HttpStatus.INTERNAL_SERVER_ERROR, null).getViewName()).isEqualTo(STORE);
    }

    @Test
    void isAdminPath_matchesOnlyTheAdminAreaWithTheSlash() {
        assertThat(ErrorPageResolver.isAdminPath("/admin")).isTrue();
        assertThat(ErrorPageResolver.isAdminPath("/admin/")).isTrue();
        assertThat(ErrorPageResolver.isAdminPath("/admin/orders")).isTrue();
        assertThat(ErrorPageResolver.isAdminPath("/administrator")).isFalse();
        assertThat(ErrorPageResolver.isAdminPath("/x/admin")).isFalse();
        assertThat(ErrorPageResolver.isAdminPath("")).isFalse();
        assertThat(ErrorPageResolver.isAdminPath(null)).isFalse();
    }

    @Test
    void getOrder_isHighestPrecedenceSoItReplacesBootsDefaultResolver() {
        assertThat(resolver.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void resolveErrorView_neverReadsRequestParameters() {
        // a 413 request has a body that cannot be parsed: reading a parameter would throw again (QĐ4)
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error") {
            @Override
            public String getParameter(String name) {
                throw new AssertionError("the error page must not read parameters");
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                throw new AssertionError("the error page must not read parameters");
            }
        };
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/admin/products");

        ModelAndView mav = resolver.resolveErrorView(request, HttpStatus.CONTENT_TOO_LARGE, Map.of());

        assertThat(mav.getViewName()).isEqualTo(STORE);
    }
}

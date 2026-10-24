package com.minishop.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * The error pages and their Vietnamese texts, in one place for both layouts (storefront {@code error/page} and
 * admin {@code admin/error}). Templates only print the model attributes below; they never hold text of their own.
 *
 * <p>Model attributes, always all five: {@code errorTitle} (tab title, already with the site suffix),
 * {@code errorHeading}, {@code errorMessage}, {@code backUrl}, {@code backLabel}.
 */
enum ErrorPage {

    NOT_FOUND("Không tìm thấy trang", "Trang bạn tìm không tồn tại hoặc đã bị di chuyển."),
    BAD_REQUEST("Yêu cầu không hợp lệ", "Đường dẫn hoặc dữ liệu gửi lên không hợp lệ."),
    SESSION_EXPIRED("Phiên làm việc đã hết hạn",
            "Biểu mẫu đã hết hạn hoặc không còn hợp lệ. Vui lòng tải lại trang rồi thử lại."),
    PAYLOAD_TOO_LARGE("Tệp tải lên quá lớn", "Ảnh sản phẩm tối đa 2 MB. Vui lòng chọn ảnh nhỏ hơn rồi thử lại."),
    SERVER_ERROR("Đã có lỗi xảy ra", "Rất tiếc, hệ thống đang gặp sự cố. Vui lòng thử lại sau ít phút.");

    static final String STORE_VIEW = "error/page";
    static final String ADMIN_VIEW = "admin/error";

    private final String heading;
    private final String message;

    ErrorPage(String heading, String message) {
        this.heading = heading;
        this.message = message;
    }

    String heading() {
        return heading;
    }

    String message() {
        return message;
    }

    /**
     * Picks the page for a status. 403 is decided by the caller before this is reached (it has two meanings);
     * here it would fall in the generic 4xx group. A status below 400 on an error dispatch is not a client
     * mistake we can describe, so it is shown as a server error.
     */
    static ErrorPage forStatus(int status) {
        if (status == HttpStatus.NOT_FOUND.value()) {
            return NOT_FOUND;
        }
        if (status == HttpStatus.CONTENT_TOO_LARGE.value()) {
            return PAYLOAD_TOO_LARGE;
        }
        if (status >= 400 && status < 500) {
            return BAD_REQUEST;
        }
        return SERVER_ERROR;
    }

    ModelAndView view(boolean adminLayout, HttpStatus status) {
        String title;
        String backUrl;
        String backLabel;
        if (adminLayout) {
            title = heading + " | Quản trị Mini Shop";
            if (this == PAYLOAD_TOO_LARGE) {
                backUrl = "/admin/products";
                backLabel = "Về danh sách sản phẩm";
            } else {
                backUrl = "/admin";
                backLabel = "Về trang quản trị";
            }
        } else {
            title = heading + " | Mini Shop";
            backUrl = "/";
            backLabel = "Về trang chủ";
        }
        Map<String, Object> model = Map.of(
                "errorTitle", title,
                "errorHeading", heading,
                "errorMessage", message,
                "backUrl", backUrl,
                "backLabel", backLabel);
        return new ModelAndView(adminLayout ? ADMIN_VIEW : STORE_VIEW, model, status);
    }
}

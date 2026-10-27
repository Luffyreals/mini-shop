package com.minishop.admin;

import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import org.springframework.stereotype.Component;

/**
 * Chỗ duy nhất xử lý lỗi chung của các màn quản trị (QĐ5 của D5): mọi {@code onFailure} của presenter quản trị đi
 * qua {@link #handle} (trả {@code true} khi đã báo như lỗi lạ), sau khi tự xử lý các lỗi nghiệp vụ riêng của nó (ví dụ {@code CategoryNameTakenException},
 * {@code InvalidImageException}).
 *
 * <ul>
 *   <li>{@link AccessDeniedException} (phiên không phải ADMIN; không xảy ra qua giao diện, chỉ khi lỗi lập trình
 *       hoặc đua giữa đăng xuất và một việc nền): toast {@link #MSG_ACCESS_DENIED} rồi về trang chủ.</li>
 *   <li>{@link NotFoundException}: toast với câu của màn, rồi việc màn đưa vào (về danh sách, nạp lại).</li>
 *   <li>Mọi lỗi khác: {@link UnexpectedErrors#report} (một dòng ERROR đã lọc và hộp thoại lỗi chung, AC-009-12).</li>
 * </ul>
 *
 * <p>Bean đơn, không có trạng thái, không import JavaFX; gọi từ luồng giao diện (callback của
 * {@code BackgroundTasks}).
 */
@Component
class AdminFailures {

    /** Toast khi service từ chối vì phiên không phải ADMIN (câu trang 403 của GĐ3; câu hỏi 5, mục 6 của D5). */
    static final String MSG_ACCESS_DENIED = "Bạn không có quyền truy cập trang này.";
    /** Toast khi không còn sản phẩm (AC-009-13). */
    static final String MSG_PRODUCT_NOT_FOUND = "Không tìm thấy sản phẩm.";
    /** Toast khi không còn danh mục (AC-009-13). */
    static final String MSG_CATEGORY_NOT_FOUND = "Không tìm thấy danh mục.";
    /** Toast khi không còn đơn hàng (AC-009-13). */
    static final String MSG_ORDER_NOT_FOUND = "Không tìm thấy đơn hàng.";

    private final Toasts toasts;
    private final Navigator navigator;
    private final UnexpectedErrors errors;

    AdminFailures(Toasts toasts, Navigator navigator, UnexpectedErrors errors) {
        this.toasts = toasts;
        this.navigator = navigator;
        this.errors = errors;
    }

    /** {@code true} khi {@code failure} là {@link AccessDeniedException}. */
    boolean isAccessDenied(Throwable failure) {
        return failure instanceof AccessDeniedException;
    }

    /**
     * Xử lý một lỗi của việc nền.
     *
     * @param failure         lỗi nhận từ {@code BackgroundTasks}
     * @param notFoundMessage câu toast khi là {@link NotFoundException}; {@code null} nghĩa là màn không mong đợi
     *                        lỗi này, nó thành lỗi lạ
     * @param afterNotFound   việc làm sau toast "không tìm thấy" (về danh sách, nạp lại); có thể {@code null}
     * @return {@code true} khi lỗi đã được báo như lỗi lạ ({@link UnexpectedErrors#report}: dòng log và hộp thoại
     *         lỗi chung); {@code false} khi là {@link AccessDeniedException} hoặc {@link NotFoundException} đã được
     *         xử lý theo đường riêng. Presenter dùng giá trị này để bật {@code loadFailed} (D6, QĐ12) sau lỗi của
     *         lần nạp đầu.
     */
    boolean handle(Throwable failure, String notFoundMessage, Runnable afterNotFound) {
        if (isAccessDenied(failure)) {
            toasts.show(MSG_ACCESS_DENIED, ToastKind.ERROR);
            navigator.goHome();
            return false;
        }
        if (failure instanceof NotFoundException && notFoundMessage != null) {
            toasts.show(notFoundMessage, ToastKind.ERROR);
            if (afterNotFound != null) {
                afterNotFound.run();
            }
            return false;
        }
        errors.report(failure);
        return true;
    }

    /**
     * Như {@link #handle(Throwable, String, Runnable)} cho màn không có lỗi "không tìm thấy" (ví dụ Tổng quan).
     *
     * @return {@code true} khi đã báo như lỗi lạ
     */
    boolean handle(Throwable failure) {
        return handle(failure, null, null);
    }
}

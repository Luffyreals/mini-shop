package com.minishop.admin;

import com.minishop.common.AccessDeniedException;
import com.minishop.common.NotFoundException;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AdminFailures}: the one place that turns a failed background task of an admin screen into a toast, a
 * navigation or an unexpected-error report (QĐ5 of the D5 plan, AC-009-9, AC-009-12, AC-009-13). Since D6 (QĐ12)
 * {@code handle} returns {@code true} only when it reported the failure as an unexpected error, so a presenter can
 * switch a never-loaded screen to its "Thử lại" block.
 */
@ExtendWith(MockitoExtension.class)
class AdminFailuresTest {

    @Mock
    private Toasts toasts;
    @Mock
    private Navigator navigator;
    @Mock
    private UnexpectedErrors errors;

    private AdminFailures failures() {
        return new AdminFailures(toasts, navigator, errors);
    }

    @Test
    void handle_accessDenied_showsTheErrorToastAndGoesHome_withoutReporting_andReturnsFalse() {
        boolean unexpected = failures().handle(new AccessDeniedException());

        assertThat(unexpected).isFalse();
        verify(toasts).show("Bạn không có quyền truy cập trang này.", ToastKind.ERROR);
        verify(navigator).goHome();
        verifyNoInteractions(errors);
    }

    @Test
    void handle_accessDenied_ignoresTheNotFoundMessageAndCallback_andReturnsFalse() {
        AtomicInteger after = new AtomicInteger();

        boolean unexpected = failures().handle(new AccessDeniedException(), AdminFailures.MSG_ORDER_NOT_FOUND,
                after::incrementAndGet);

        assertThat(unexpected).isFalse();
        assertThat(after).hasValue(0);
        verify(toasts).show(AdminFailures.MSG_ACCESS_DENIED, ToastKind.ERROR);
        verify(navigator).goHome();
        verifyNoInteractions(errors);
    }

    @Test
    void handle_notFound_showsTheScreensMessageThenRunsTheFollowUpOnce_andReturnsFalse() {
        AtomicInteger after = new AtomicInteger();

        boolean unexpected = failures().handle(new NotFoundException("gone"), "Không tìm thấy sản phẩm.",
                after::incrementAndGet);

        assertThat(unexpected).isFalse();
        verify(toasts).show("Không tìm thấy sản phẩm.", ToastKind.ERROR);
        assertThat(after).hasValue(1);
        verifyNoInteractions(errors);
        verify(navigator, never()).goHome();
    }

    @Test
    void handle_notFound_withoutAFollowUp_justToasts_andReturnsFalse() {
        boolean unexpected = failures().handle(new NotFoundException("gone"), "Không tìm thấy đơn hàng.", null);

        assertThat(unexpected).isFalse();
        verify(toasts).show("Không tìm thấy đơn hàng.", ToastKind.ERROR);
        verifyNoInteractions(errors, navigator);
    }

    @Test
    void handle_notFound_onAScreenThatDoesNotExpectIt_isAnUnexpectedError_andReturnsTrue() {
        NotFoundException failure = new NotFoundException("gone");

        boolean unexpected = failures().handle(failure);

        assertThat(unexpected).isTrue();
        verify(errors).report(failure);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void handle_notFound_withNullMessage_runsNoFollowUp_reports_andReturnsTrue() {
        NotFoundException failure = new NotFoundException("gone");
        AtomicInteger after = new AtomicInteger();

        boolean unexpected = failures().handle(failure, null, after::incrementAndGet);

        assertThat(unexpected).isTrue();
        assertThat(after).hasValue(0);
        verify(errors).report(failure);
    }

    @Test
    void handle_anyOtherFailure_isReportedExactlyOnce_withNoToastAndNoNavigation_andReturnsTrue() {
        IllegalStateException failure = new IllegalStateException("boom");
        AtomicInteger after = new AtomicInteger();

        boolean unexpected = failures().handle(failure, AdminFailures.MSG_PRODUCT_NOT_FOUND, after::incrementAndGet);

        assertThat(unexpected).isTrue();
        verify(errors).report(failure);
        assertThat(after).hasValue(0);
        verifyNoInteractions(toasts, navigator);
    }

    @Test
    void handle_accessDeniedWithoutMessage_returnsFalse_andNeverReports() {
        boolean unexpected = failures().handle(new AccessDeniedException(), null, null);

        assertThat(unexpected).isFalse();
        verifyNoInteractions(errors);
    }

    @Test
    void isAccessDenied_isTrueOnlyForAccessDeniedException() {
        AdminFailures failures = failures();

        assertThat(failures.isAccessDenied(new AccessDeniedException())).isTrue();
        assertThat(failures.isAccessDenied(new NotFoundException("x"))).isFalse();
        assertThat(failures.isAccessDenied(new RuntimeException("x"))).isFalse();
        assertThat(failures.isAccessDenied(null)).isFalse();
    }

    @Test
    void messages_areTheApprovedTexts() {
        assertThat(AdminFailures.MSG_ACCESS_DENIED).isEqualTo("Bạn không có quyền truy cập trang này.");
        assertThat(AdminFailures.MSG_PRODUCT_NOT_FOUND).isEqualTo("Không tìm thấy sản phẩm.");
        assertThat(AdminFailures.MSG_CATEGORY_NOT_FOUND).isEqualTo("Không tìm thấy danh mục.");
        assertThat(AdminFailures.MSG_ORDER_NOT_FOUND).isEqualTo("Không tìm thấy đơn hàng.");
    }
}

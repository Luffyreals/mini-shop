package com.minishop.desktop;

import java.util.Objects;

/**
 * Bộ bắt lỗi không lường trước (QĐ6), đặt bằng {@link Thread#setDefaultUncaughtExceptionHandler}. Luồng giao
 * diện và các luồng nền ({@code minishop-bg-N}) đều rơi vào đây; việc ghi log và quyết định hiện hộp thoại nằm
 * ở {@link ErrorReporter}, hộp thoại luôn được mở trên luồng giao diện ({@link ErrorDialog#showLater}).
 * App vẫn chạy sau lỗi, vì JavaFX không chết khi có exception trên luồng giao diện.
 */
final class UncaughtErrorHandler implements Thread.UncaughtExceptionHandler {

    private final ErrorReporter reporter;

    UncaughtErrorHandler(ErrorReporter reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        reporter.report(thread == null ? "unknown" : thread.getName(), error);
    }
}

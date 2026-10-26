package com.minishop.desktop;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import org.springframework.stereotype.Component;

/**
 * Cài đặt {@link BackgroundTasks} bằng {@link Task} của JavaFX chạy trên executor virtual thread
 * ({@code minishop-bg-N}). Lời gọi service chủ yếu là chờ DB, mà virtual thread chờ rất rẻ; số truy vấn chạy
 * cùng lúc đã bị pool Hikari giới hạn. Executor đóng khi context Spring đóng ({@link #close()}).
 */
@Component
class FxBackgroundTasks implements BackgroundTasks, AutoCloseable {

    static final String THREAD_NAME_PREFIX = "minishop-bg-";
    private static final long SHUTDOWN_WAIT_SECONDS = 3;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name(THREAD_NAME_PREFIX, 0).factory());

    @Override
    public <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess,
                             Consumer<? super Throwable> onFailure) {
        Objects.requireNonNull(work, "work");
        AtomicBoolean cancelled = new AtomicBoolean();

        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> {
            if (!cancelled.get() && onSuccess != null) {
                deliver(() -> onSuccess.accept(task.getValue()));
            }
        });
        task.setOnFailed(event -> {
            if (cancelled.get()) {
                return;
            }
            Throwable error = task.getException();
            if (onFailure == null) {
                reportUncaught(error);
            } else {
                deliver(() -> onFailure.accept(error));
            }
        });

        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("BackgroundTasks đã đóng", e);
        }
        return new Handle() {
            @Override
            public void cancel() {
                cancelled.set(true);
                task.cancel(false);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    private static void deliver(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable e) {
            reportUncaught(e);
        }
    }

    /** Đưa lỗi cho uncaught handler của luồng hiện tại (luồng giao diện), không nuốt lỗi. */
    private static void reportUncaught(Throwable error) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, error);
    }

    /** Dừng nhận việc mới, chờ việc đang chạy tối đa vài giây rồi ngắt phần còn lại. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

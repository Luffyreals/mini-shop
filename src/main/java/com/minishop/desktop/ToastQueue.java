package com.minishop.desktop;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Hàng đợi của {@link Toasts}: mỗi lúc chỉ một toast hiện, các toast sau xếp hàng. Java thuần, không biết
 * node hay luồng. Thông báo trùng hệt toast đang hiện hoặc toast vừa xếp trước đó bị bỏ (nhiều lỗi dồn dập
 * không thành một chuỗi toast giống nhau); hàng chờ tối đa {@value #MAX_WAITING}, đầy thì bỏ toast chờ lâu nhất.
 * "Trùng" so theo chữ và loại; nút hành động ({@link ToastAction}) không tham gia so sánh vì {@code Runnable}
 * không so được.
 */
final class ToastQueue {

    static final int MAX_WAITING = 5;

    /** Thời gian tối thiểu (ms) một toast có nút hành động được giữ, để kịp đọc và bấm. */
    static final long ACTION_HOLD_MILLIS = 5_000;

    /**
     * Một toast. {@code action} là nút hành động, có thể {@code null}.
     * {@code equals}/{@code hashCode} chỉ xét {@code text} và {@code kind}.
     */
    record Entry(String text, ToastKind kind, ToastAction action) {

        Entry(String text, ToastKind kind) {
            this(text, kind, null);
        }

        /** Thời gian giữ toast trên màn, tính cả nút hành động. */
        long holdMillis() {
            long base = kind.displayMillis();
            return action == null ? base : Math.max(base, ACTION_HOLD_MILLIS);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry that && Objects.equals(text, that.text) && kind == that.kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, kind);
        }
    }

    private final Deque<Entry> waiting = new ArrayDeque<>();
    private Entry showing;

    /**
     * Thêm một toast.
     *
     * @return toast phải hiện ngay (khi chưa có toast nào đang hiện), hoặc {@code null} nếu nó xếp hàng/bị bỏ
     */
    synchronized Entry offer(Entry entry) {
        if (showing == null) {
            showing = entry;
            return entry;
        }
        if (entry.equals(showing) || entry.equals(waiting.peekLast())) {
            return null;
        }
        if (waiting.size() >= MAX_WAITING) {
            waiting.removeFirst();
        }
        waiting.addLast(entry);
        return null;
    }

    /** Toast đang hiện đã ẩn xong. Trả toast kế tiếp phải hiện, hoặc {@code null} nếu hàng đã hết. */
    synchronized Entry finished() {
        showing = waiting.pollFirst();
        return showing;
    }

    synchronized Entry showing() {
        return showing;
    }

    synchronized int waitingCount() {
        return waiting.size();
    }
}

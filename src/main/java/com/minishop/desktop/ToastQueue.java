package com.minishop.desktop;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Hàng đợi của {@link Toasts}: mỗi lúc chỉ một toast hiện, các toast sau xếp hàng. Java thuần, không biết
 * node hay luồng. Thông báo trùng hệt toast đang hiện hoặc toast vừa xếp trước đó bị bỏ (nhiều lỗi dồn dập
 * không thành một chuỗi toast giống nhau); hàng chờ tối đa {@value #MAX_WAITING}, đầy thì bỏ toast chờ lâu nhất.
 */
final class ToastQueue {

    static final int MAX_WAITING = 5;

    record Entry(String text, ToastKind kind) {
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

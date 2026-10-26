package com.minishop.desktop;

import com.minishop.desktop.ToastQueue.Entry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The queue behind {@code Toasts} (AC-009-11 foundation): one toast at a time, bounded, no duplicate bursts. */
class ToastQueueTest {

    private final ToastQueue queue = new ToastQueue();

    private static Entry info(String text) {
        return new Entry(text, ToastKind.INFO);
    }

    @Test
    void offer_whenNothingShows_returnsTheEntryToShowNow() {
        Entry first = info("a");

        assertThat(queue.offer(first)).isSameAs(first);
        assertThat(queue.showing()).isEqualTo(first);
        assertThat(queue.waitingCount()).isZero();
    }

    @Test
    void offer_whileOneShows_queuesTheNextInOrder() {
        queue.offer(info("a"));

        assertThat(queue.offer(info("b"))).isNull();
        assertThat(queue.offer(info("c"))).isNull();

        assertThat(queue.waitingCount()).isEqualTo(2);
        assertThat(queue.finished()).isEqualTo(info("b"));
        assertThat(queue.finished()).isEqualTo(info("c"));
        assertThat(queue.finished()).isNull();
        assertThat(queue.showing()).isNull();
    }

    @Test
    void offer_afterTheQueueRanOut_showsTheNextOneImmediately() {
        queue.offer(info("a"));
        queue.finished();

        assertThat(queue.offer(info("b"))).isEqualTo(info("b"));
    }

    @Test
    void offer_sameAsTheShowingToast_isDropped() {
        queue.offer(info("a"));

        assertThat(queue.offer(info("a"))).isNull();

        assertThat(queue.waitingCount()).isZero();
    }

    @Test
    void offer_sameAsTheLastWaitingToast_isDropped() {
        queue.offer(info("a"));
        queue.offer(info("b"));

        queue.offer(info("b"));

        assertThat(queue.waitingCount()).isEqualTo(1);
    }

    @Test
    void offer_sameTextWithAnotherKind_isNotADuplicate() {
        queue.offer(info("a"));

        queue.offer(new Entry("a", ToastKind.ERROR));

        assertThat(queue.waitingCount()).isEqualTo(1);
    }

    @Test
    void offer_theSameTextNotRightBeforeIt_isKept() {
        queue.offer(info("a"));
        queue.offer(info("b"));
        queue.offer(info("c"));

        queue.offer(info("b"));

        assertThat(queue.waitingCount()).isEqualTo(3);
    }

    @Test
    void offer_beyondMaxWaiting_dropsTheOldestWaitingToast() {
        queue.offer(info("shown"));
        int total = ToastQueue.MAX_WAITING + 2;
        for (int i = 1; i <= total; i++) {
            queue.offer(info("t" + i));
        }

        assertThat(queue.waitingCount()).isEqualTo(ToastQueue.MAX_WAITING);
        // t1 and t2 were the oldest, so the queue now starts at t3
        assertThat(queue.finished()).isEqualTo(info("t3"));
        for (int i = 4; i <= total; i++) {
            assertThat(queue.finished()).isEqualTo(info("t" + i));
        }
        assertThat(queue.finished()).isNull();
    }

    @Test
    void maxWaiting_isFive() {
        assertThat(ToastQueue.MAX_WAITING).isEqualTo(5);
    }
}

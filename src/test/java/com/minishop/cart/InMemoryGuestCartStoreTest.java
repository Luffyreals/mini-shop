package com.minishop.cart;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link InMemoryGuestCartStore}: the guest cart of the whole process (QĐ5 of the D2 plan, BR-009-2). */
class InMemoryGuestCartStoreTest {

    @Test
    void find_onAFreshStore_isEmpty_andNeverCreatesACart() {
        InMemoryGuestCartStore store = new InMemoryGuestCartStore();

        assertThat(store.find()).isEmpty();
        assertThat(store.find()).as("still nothing after reading twice").isEmpty();
    }

    @Test
    void getOrCreate_createsOnce_andFindThenSeesTheSameCart() {
        InMemoryGuestCartStore store = new InMemoryGuestCartStore();

        SessionCart first = store.getOrCreate();
        SessionCart second = store.getOrCreate();

        assertThat(second).isSameAs(first);
        assertThat(store.find()).containsSame(first);
    }

    @Test
    void getOrCreate_returnsALiveCart_soWritesAreVisibleThroughFind() {
        InMemoryGuestCartStore store = new InMemoryGuestCartStore();

        store.getOrCreate().put(5L, 3);

        assertThat(store.find()).hasValueSatisfying(cart -> assertThat(cart.quantityOf(5L)).isEqualTo(3));
    }

    @Test
    void save_replacesTheCart() {
        InMemoryGuestCartStore store = new InMemoryGuestCartStore();
        store.getOrCreate().put(1L, 1);
        SessionCart replacement = new SessionCart();
        replacement.put(2L, 2);

        store.save(replacement);

        assertThat(store.find()).containsSame(replacement);
        assertThat(store.getOrCreate()).isSameAs(replacement);
    }

    @Test
    void clear_thenFind_isEmpty_andTheNextGetOrCreateStartsFresh() {
        InMemoryGuestCartStore store = new InMemoryGuestCartStore();
        SessionCart old = store.getOrCreate();
        old.put(1L, 1);

        store.clear();

        assertThat(store.find()).isEmpty();
        SessionCart fresh = store.getOrCreate();
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.isEmpty()).isTrue();
    }

    @Test
    void clear_onAFreshStore_doesNothingAndDoesNotFail() {
        InMemoryGuestCartStore store = new InMemoryGuestCartStore();

        store.clear();

        assertThat(store.find()).isEmpty();
    }

    @Test
    void getOrCreate_from16ThreadsAtOnce_givesEveryoneTheSameCart() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                InMemoryGuestCartStore store = new InMemoryGuestCartStore();
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<SessionCart>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return store.getOrCreate();
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                Set<SessionCart> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Future<SessionCart> result : results) {
                    distinct.add(result.get(10, TimeUnit.SECONDS));
                }
                assertThat(distinct).as("round %d: one cart for all threads", round).hasSize(1);
                assertThat(store.find()).containsSame(distinct.iterator().next());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

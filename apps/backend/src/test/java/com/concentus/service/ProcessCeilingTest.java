package com.concentus.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The machine-wide cap over claude processes: acquire under the limit proceeds, over it waits,
 * a release wakes the waiter, and a stopped run stops waiting instead of holding a thread.
 */
class ProcessCeilingTest {

    /** A ceiling with a fixed limit — the settings read is the only part faked. */
    private static ProcessCeiling of(int limit) {
        return new ProcessCeiling(null) {
            @Override
            int max() {
                return limit;
            }
        };
    }

    @Test
    void under_the_limit_acquiring_neither_waits_nor_says_anything() throws Exception {
        ProcessCeiling ceiling = of(2);
        AtomicBoolean waited = new AtomicBoolean(false);

        ProcessCeiling.Slot a = ceiling.acquire(() -> false, m -> waited.set(true));
        ProcessCeiling.Slot b = ceiling.acquire(() -> false, m -> waited.set(true));

        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(ceiling.inUse()).isEqualTo(2);
        assertThat(waited).isFalse();
    }

    @Test
    void over_the_limit_the_caller_waits_and_a_release_lets_it_through() throws Exception {
        ProcessCeiling ceiling = of(1);
        ProcessCeiling.Slot held = ceiling.acquire(() -> false, null);

        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<String> waitMessage = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                ProcessCeiling.Slot s = ceiling.acquire(() -> false, m -> {
                    waitMessage.set(m);
                    waiting.countDown();
                });
                if (s != null) got.countDown();
            } catch (InterruptedException ignored) {
                // the test fails on the latches below
            }
        });
        t.start();

        assertThat(waiting.await(2, TimeUnit.SECONDS)).as("the second caller reports waiting").isTrue();
        assertThat(got.getCount()).isEqualTo(1);
        assertThat(waitMessage.get()).contains("execution.max-processes");

        held.close();
        assertThat(got.await(2, TimeUnit.SECONDS)).as("a released slot admits the waiter").isTrue();
        t.join(2000);
        assertThat(ceiling.inUse()).isEqualTo(1);
    }

    @Test
    void a_cancelled_waiter_gives_up_with_null_instead_of_holding_the_thread() throws Exception {
        ProcessCeiling ceiling = of(1);
        ProcessCeiling.Slot held = ceiling.acquire(() -> false, null);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>("unset");
        Thread t = new Thread(() -> {
            try {
                result.set(ceiling.acquire(cancelled::get, null));
            } catch (InterruptedException ignored) {
                result.set("interrupted");
            }
            done.countDown();
        });
        t.start();

        cancelled.set(true);
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isNull();
        held.close();
        assertThat(ceiling.inUse()).isZero();
    }

    @Test
    void closing_a_slot_twice_releases_it_once() throws Exception {
        ProcessCeiling ceiling = of(3);
        ProcessCeiling.Slot a = ceiling.acquire(() -> false, null);
        ceiling.acquire(() -> false, null);

        a.close();
        a.close();
        assertThat(ceiling.inUse()).isEqualTo(1);
    }

    @Test
    void the_unlimited_ceiling_admits_everything() throws Exception {
        ProcessCeiling ceiling = ProcessCeiling.unlimited();
        for (int i = 0; i < 100; i++) {
            assertThat(ceiling.acquire(() -> false, null)).isNotNull();
        }
        assertThat(ceiling.inUse()).isEqualTo(100);
    }
}

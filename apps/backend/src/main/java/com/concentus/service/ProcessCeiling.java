package com.concentus.service;

import com.concentus.config.Settings;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One ceiling over every {@code claude} process this backend starts, whichever pool asked for it.
 *
 * <p>The two pools are each bounded — {@code runs.max-concurrent} turns and
 * {@code workers.max-concurrent} workers per fan-out — but their product is not: eight runs each
 * fanning out four workers is thirty-two processes on a machine nobody sized for that, and each
 * one is hundreds of megabytes. The user notices the laptop crawling, not the multiplication.
 *
 * <p>So both spawn sites acquire a slot here before starting a process and give it back when the
 * process exits. A worker that does not fit waits — with a line in its run saying what it is
 * waiting for, because an unexplained pause reads as a hang — and proceeds when a slot frees.
 *
 * <p>The limit is read from settings on every check rather than captured once, so raising it under
 * Resources → Settings takes effect on the processes already queued, without a restart.
 */
@Component
public class ProcessCeiling {

    /**
     * How long one wait slice lasts. Bounds two latencies: how fast a waiter notices the ceiling
     * was raised in settings, and how fast it notices its run was stopped — {@code notifyAll} only
     * covers slots being released.
     */
    private static final long WAIT_SLICE_MS = 500;

    private final Settings settings;
    private int inUse;

    public ProcessCeiling(Settings settings) {
        this.settings = settings;
    }

    /** A ceiling that admits everything — what unit tests of the executors run under. */
    public static ProcessCeiling unlimited() {
        return new ProcessCeiling(null) {
            @Override
            int max() {
                return Integer.MAX_VALUE;
            }
        };
    }

    int max() {
        return Math.max(1, settings.number("execution.max-processes", 10));
    }

    /** A held slot. Closing twice is safe — the timeout path and the normal path may both try. */
    public final class Slot implements AutoCloseable {
        private boolean released;

        @Override
        public void close() {
            synchronized (ProcessCeiling.this) {
                if (released) return;
                released = true;
                inUse--;
                ProcessCeiling.this.notifyAll();
            }
        }
    }

    /**
     * Blocks until a process may start, or returns null when {@code cancelled} says the caller no
     * longer wants one. {@code onWait} is told once, on the transition into waiting — it is the
     * hook the executors use to put "waiting for a free slot" into the run's log.
     */
    public Slot acquire(BooleanSupplier cancelled, Consumer<String> onWait)
            throws InterruptedException {
        synchronized (this) {
            boolean told = false;
            while (inUse >= max()) {
                if (cancelled != null && cancelled.getAsBoolean()) return null;
                if (!told && onWait != null) {
                    onWait.accept("Waiting for a free claude process slot (" + inUse + " of "
                            + max() + " in use — execution.max-processes).");
                    told = true;
                }
                wait(WAIT_SLICE_MS);
            }
            if (cancelled != null && cancelled.getAsBoolean()) return null;
            inUse++;
            return new Slot();
        }
    }

    public synchronized int inUse() {
        return inUse;
    }
}

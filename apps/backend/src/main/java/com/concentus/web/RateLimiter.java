package com.concentus.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small fixed-window rate limiter for endpoints that are reachable without a session.
 *
 * <p>The webhook and the internal endpoints answer before authenticating anything expensive, which
 * makes them the cheapest thing on the server to hammer. Capping them keeps a flood from filling
 * the job queue or exhausting the database connection pool that the rest of the app shares.
 *
 * <p>Deliberately in-process and approximate: the deployment is a single Spring Boot instance, and
 * a limiter that needs its own datastore to protect the datastore would be the wrong shape.
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    private static final class Window {
        final AtomicLong count = new AtomicLong();
        volatile long startedAt;

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    /** True when this key may proceed; false when it has used up its allowance for the window. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(key == null ? "" : key, (k, existing) -> {
            if (existing == null || now - existing.startedAt >= windowMillis) {
                return new Window(now);
            }
            return existing;
        });
        // Keys are unbounded in principle (one per source), so evict opportunistically rather than
        // letting a scan of random addresses grow the map without limit.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startedAt >= windowMillis);
        }
        return window.count.incrementAndGet() <= maxRequests;
    }

    /** Test seam and operational reset. */
    public void clear() {
        windows.clear();
    }
}

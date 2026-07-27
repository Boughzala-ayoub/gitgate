package dev.gitgate.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple per-IP fixed-window rate limiter for unauthenticated (anonymous) requests.
 * Public repos invite traffic, so anonymous reads are capped; authenticated requests are
 * unaffected. Heavier, distributed rate limiting is a Phase 10 concern.
 */
@Component
public class AnonymousRateLimiter {

    private static final int LIMIT = 120;        // requests
    private static final long WINDOW_MS = 60_000; // per minute

    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    /** @return true if the request from {@code ip} is within the limit. */
    public boolean allow(String ip) {
        long now = System.currentTimeMillis();
        long[] bucket = buckets.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (bucket) {
            if (now - bucket[0] > WINDOW_MS) {
                bucket[0] = now;
                bucket[1] = 0;
            }
            bucket[1]++;
            return bucket[1] <= LIMIT;
        }
    }
}

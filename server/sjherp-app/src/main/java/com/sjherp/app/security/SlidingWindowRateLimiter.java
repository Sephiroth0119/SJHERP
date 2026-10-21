package com.sjherp.app.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固定窗口计数限流（小企业单机足够；不引入 Bucket4j 等未登记中间件）。
 */
public final class SlidingWindowRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @return true 表示本次请求放行
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (key == null || key.isBlank()) {
            return true;
        }
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long windowMs = Math.max(window.toMillis(), 1L);
        Window updated = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMs) {
                return new Window(now, 1);
            }
            return new Window(existing.startMillis, existing.count + 1);
        });
        return updated.count <= limit;
    }

    private record Window(long startMillis, int count) {
    }
}

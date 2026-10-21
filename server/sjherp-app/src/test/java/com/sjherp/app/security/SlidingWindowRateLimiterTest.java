package com.sjherp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    @Test
    void 未超限放行_超限拒绝() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        Duration window = Duration.ofMinutes(1);
        assertThat(limiter.tryAcquire("login:1.1.1.1", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("login:1.1.1.1", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("login:1.1.1.1", 2, window)).isFalse();
    }

    @Test
    void 不同键互不影响() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        Duration window = Duration.ofMinutes(1);
        assertThat(limiter.tryAcquire("a", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("b", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("a", 1, window)).isFalse();
    }

    @Test
    void 限额非正视为关闭() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertThat(limiter.tryAcquire("x", 0, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("x", -1, Duration.ofMinutes(1))).isTrue();
    }
}

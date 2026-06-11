package com.sjherp.domain.common.numbering;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存序号供给实现（仅供单元测试与本地开发）。
 *
 * <p>进程内并发安全，但序号不持久化——重启即归零，**禁止用于生产**。
 * 生产实现由 infra 层基于数据库提供（保证重启后序号延续、不重号）。
 */
public final class InMemorySequenceProvider implements SequenceProvider {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long next(String scopeKey) {
        Objects.requireNonNull(scopeKey, "scopeKey 不能为空");
        return counters.computeIfAbsent(scopeKey, k -> new AtomicLong(0)).incrementAndGet();
    }
}

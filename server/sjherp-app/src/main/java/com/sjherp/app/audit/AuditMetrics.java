package com.sjherp.app.audit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计失败计数器（M2-T07）：审计写入失败不阻塞业务（与 invocation listener 同哲学），
 * 但失败必须可发现——WARN 日志 + 本计数器。
 *
 * <p>TODO（X-6 观测看板 / M8 安全基线）：接入 Micrometer 指标并配告警；
 * 当前以单例计数器暴露，可由健康检查或测试断言读取。
 */
public class AuditMetrics {

    private final AtomicLong failureCount = new AtomicLong();

    /** 记一次审计写入失败 */
    public void recordFailure() {
        failureCount.incrementAndGet();
    }

    /** 累计失败次数（进程内） */
    public long failureCount() {
        return failureCount.get();
    }
}

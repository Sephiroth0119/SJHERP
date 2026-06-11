package com.sjherp.domain.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 领域事件基类（纯 Java，不依赖 Spring）。
 *
 * <p>统一承载三要素：事件 id（全局唯一，幂等消费依据）、发生时间、
 * 聚合标识（领域层以单据号等业务标识定位聚合）。具体事件继承本类并
 * 携带各自的业务载荷；事件一经创建即不可变（可审计原则，CLAUDE.md 原则 3）。
 */
public abstract class DomainEvent {

    /** 事件唯一标识（UUID），下游幂等消费依据 */
    private final String eventId;

    /** 事件发生时间 */
    private final Instant occurredAt;

    /** 聚合标识（如单据号），定位事件源自哪个聚合实例 */
    private final String aggregateId;

    protected DomainEvent(String aggregateId) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId 不能为空");
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{eventId=" + eventId
                + ", occurredAt=" + occurredAt
                + ", aggregateId=" + aggregateId + "}";
    }
}

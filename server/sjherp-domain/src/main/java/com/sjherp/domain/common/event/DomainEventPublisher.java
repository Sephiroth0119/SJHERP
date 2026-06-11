package com.sjherp.domain.common.event;

/**
 * 领域事件发布接口（端口）。
 *
 * <p>领域层只依赖本接口发布事件，具体投递方式（同步分发、消息队列、
 * 事务性发件箱等）由 infra 层实现并注入。保持领域层纯 Java 零依赖。
 */
@FunctionalInterface
public interface DomainEventPublisher {

    /** 发布一个领域事件 */
    void publish(DomainEvent event);
}

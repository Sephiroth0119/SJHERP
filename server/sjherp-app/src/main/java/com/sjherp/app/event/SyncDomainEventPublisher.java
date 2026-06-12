package com.sjherp.app.event;

import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 领域事件发布器的同步分发实现（M2-T07 接线，还 M2-T01 待办）。
 *
 * <p>在发布线程上按注册顺序依次调用各监听器（小企业单机部署，事务内同步分发
 * 足够；消息队列/事务性发件箱留待真实需要时引入并记 ADR）。单个监听器抛异常
 * 只 WARN 并继续后续监听器——事件消费失败不得反噬业务写操作。
 *
 * <p>M3 单据落地后：各单据领域服务通过 {@code registerEventPublisher} 注入本发布器，
 * 状态流转事件（DocumentStatusChangedEvent）即自动流向审计等订阅方。
 */
public class SyncDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SyncDomainEventPublisher.class);

    private final List<Consumer<DomainEvent>> listeners;

    public SyncDomainEventPublisher(List<Consumer<DomainEvent>> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void publish(DomainEvent event) {
        for (Consumer<DomainEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("领域事件监听器处理失败（event={}, listener={}），继续分发后续监听器",
                        event, listener.getClass().getSimpleName(), e);
            }
        }
    }
}

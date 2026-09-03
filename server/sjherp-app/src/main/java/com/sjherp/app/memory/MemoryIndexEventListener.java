package com.sjherp.app.memory;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 在 MySQL 真源事务提交后执行派生索引操作。 */
@Component
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryIndexEventListener {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexEventListener.class);

    private final MemoryIndexingService indexingService;

    public MemoryIndexEventListener(MemoryIndexingService indexingService) {
        this.indexingService = Objects.requireNonNull(indexingService, "indexingService 不能为空");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MemoryIndexRequestedEvent event) {
        try {
            if (event.operation() == MemoryIndexOperation.UPSERT) {
                indexingService.indexOne(event.memoryNo(), "system:memory-indexer");
            } else {
                indexingService.deletePoint(event.memoryEntryId());
            }
        } catch (RuntimeException exception) {
            // 事务已经提交：这里只能记录脱敏告警，绝不把异常反抛给业务请求。
            log.warn("大记忆提交后索引未完成: operation={}, memoryNo={}, memoryEntryId={}, errorType={}",
                    event.operation(), event.memoryNo(), event.memoryEntryId(),
                    exception.getClass().getSimpleName());
        }
    }
}

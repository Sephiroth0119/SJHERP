package com.sjherp.app.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryRepository;

/** 到期记忆索引的有界、逐条隔离重试任务。 */
@Component
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryIndexRetryJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexRetryJob.class);
    private static final String SYSTEM_OPERATOR = "system:memory-indexer";

    private final MemoryEntryRepository repository;
    private final MemoryIndexingService indexingService;
    private final MemoryProperties properties;
    private final Clock clock;

    public MemoryIndexRetryJob(MemoryEntryRepository repository,
                               MemoryIndexingService indexingService,
                               MemoryProperties properties) {
        this(repository, indexingService, properties, Clock.systemUTC());
    }

    MemoryIndexRetryJob(MemoryEntryRepository repository,
                        MemoryIndexingService indexingService,
                        MemoryProperties properties,
                        Clock clock) {
        this.repository = Objects.requireNonNull(repository, "memory repository 不能为空");
        this.indexingService = Objects.requireNonNull(indexingService, "indexingService 不能为空");
        this.properties = Objects.requireNonNull(properties, "memory properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 单轮最多处理 batchSize 条候选；单条意外失败只记录非敏感元数据并继续。
     * 候选是否到期、是否达到重试上限由 MySQL 查询口径统一裁定。
     */
    @Scheduled(fixedDelayString = "${sjherp.memory.indexing.retry-delay-seconds:30}000")
    public void retryDueEntries() {
        List<MemoryEntry> due = repository.findIndexCandidates(
                Instant.now(clock), properties.indexing().batchSize());
        for (MemoryEntry entry : due) {
            try {
                indexingService.indexOne(entry.getMemoryNo(), SYSTEM_OPERATOR);
            } catch (RuntimeException exception) {
                log.warn("记忆索引重试未完成: memoryNo={}, retryCount={}, errorType={}",
                        entry.getMemoryNo(), entry.getRetryCount(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}

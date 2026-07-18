package com.sjherp.app.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryNotFoundException;
import com.sjherp.domain.memory.MemoryEntryRepository;

/** 以独立短事务持久化派生索引状态，避免网络调用占用数据库事务。 */
public class MemoryIndexStateService {

    private final MemoryEntryRepository repository;
    private final Clock clock;

    public MemoryIndexStateService(MemoryEntryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MemoryIndexStateService(MemoryEntryRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "memory repository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexed(String memoryNo, String collection, String model,
                            int dimension, String operator) {
        MemoryEntry entry = require(memoryNo);
        entry.markIndexed(collection, model, dimension, operator, Instant.now(clock));
        repository.save(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String memoryNo, String error, Instant nextRetryAt,
                           String operator) {
        MemoryEntry entry = require(memoryNo);
        entry.markIndexFailed(error, nextRetryAt, operator, Instant.now(clock));
        repository.save(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPending(String memoryNo, String operator) {
        MemoryEntry entry = require(memoryNo);
        entry.markPending(operator, Instant.now(clock));
        repository.save(entry);
    }

    private MemoryEntry require(String memoryNo) {
        return repository.findByMemoryNo(memoryNo)
                .orElseThrow(() -> new MemoryEntryNotFoundException(memoryNo));
    }
}

package com.sjherp.app.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemoryEntryNotFoundException;
import com.sjherp.domain.memory.MemoryEntryQuery;
import com.sjherp.domain.memory.MemoryEntryRepository;

/** 大记忆 MySQL 真源的唯一应用写入口。 */
public class MemoryService {

    static final DocumentNumberRule MEMORY_NUMBER_RULE = DocumentNumberRule.of("MEM");

    private final MemoryEntryRepository repository;
    private final DocumentNumberGenerator numberGenerator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MemoryService(MemoryEntryRepository repository,
                         DocumentNumberGenerator numberGenerator,
                         ApplicationEventPublisher events) {
        this(repository, numberGenerator, events, Clock.systemUTC());
    }

    MemoryService(MemoryEntryRepository repository,
                  DocumentNumberGenerator numberGenerator,
                  ApplicationEventPublisher events, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "memory repository 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.events = Objects.requireNonNull(events, "events 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Transactional
    @Audited(action = "memory.create", targetType = "memory")
    public MemoryEntry create(MemoryEntryCommand command, String operator) {
        Objects.requireNonNull(command, "大记忆命令不能为空");
        Instant now = Instant.now(clock);
        String memoryNo = numberGenerator.generate(MEMORY_NUMBER_RULE);
        MemoryEntry entry = MemoryEntry.create(memoryNo, memoryNo, 1,
                command.memoryType(), command.title(), command.content(),
                command.sourceType(), command.sourceRef(),
                effectiveValidFrom(command, now), command.validTo(), operator, now);
        repository.save(entry);
        events.publishEvent(event(MemoryIndexOperation.UPSERT, entry));
        return entry;
    }

    @Transactional
    @Audited(action = "memory.replace", targetType = "memory")
    public MemoryEntry replace(String memoryNo, MemoryEntryCommand command, String operator) {
        Objects.requireNonNull(command, "大记忆命令不能为空");
        MemoryEntry previous = get(memoryNo);
        Instant now = Instant.now(clock);
        String newMemoryNo = numberGenerator.generate(MEMORY_NUMBER_RULE);
        MemoryEntry replacement = MemoryEntry.createReplacement(newMemoryNo, previous,
                command.memoryType(), command.title(), command.content(),
                command.sourceType(), command.sourceRef(),
                effectiveValidFrom(command, now), command.validTo(), operator, now);

        previous.markSuperseded(operator, now);
        repository.save(previous);
        repository.save(replacement);
        events.publishEvent(event(MemoryIndexOperation.DELETE, previous));
        events.publishEvent(event(MemoryIndexOperation.UPSERT, replacement));
        return replacement;
    }

    @Transactional
    @Audited(action = "memory.expire", targetType = "memory")
    public MemoryEntry expire(String memoryNo, String operator) {
        MemoryEntry entry = get(memoryNo);
        entry.expire(operator, Instant.now(clock));
        repository.save(entry);
        events.publishEvent(event(MemoryIndexOperation.DELETE, entry));
        return entry;
    }

    @Transactional(readOnly = true)
    public MemoryEntry get(String memoryNo) {
        return repository.findByMemoryNo(memoryNo)
                .orElseThrow(() -> new MemoryEntryNotFoundException(memoryNo));
    }

    @Transactional(readOnly = true)
    public PageResult<MemoryEntry> search(MemoryEntryQuery query) {
        return repository.search(Objects.requireNonNull(query, "查询条件不能为空"));
    }

    private static Instant effectiveValidFrom(MemoryEntryCommand command, Instant now) {
        return command.validFrom() == null ? now : command.validFrom();
    }

    private static MemoryIndexRequestedEvent event(
            MemoryIndexOperation operation, MemoryEntry entry) {
        if (entry.getId() == null) {
            throw new IllegalStateException("大记忆保存后未回填主键: " + entry.getMemoryNo());
        }
        return new MemoryIndexRequestedEvent(operation, entry.getMemoryNo(), entry.getId());
    }
}

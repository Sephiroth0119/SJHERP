package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemoryEntryNotFoundException;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemoryIndexStatus;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T03:00:00Z");

    @Mock
    private MemoryEntryRepository repository;
    @Mock
    private DocumentNumberGenerator numberGenerator;
    @Mock
    private ApplicationEventPublisher events;

    private MemoryService service;

    @BeforeEach
    void setUp() {
        service = new MemoryService(repository, numberGenerator, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubSaveAssignsIds() {
        doAnswer(invocation -> {
            MemoryEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                entry.assignId(entry.getVersion() == 1 ? 11L : 12L);
            }
            return null;
        }).when(repository).save(any(MemoryEntry.class));
    }

    @Test
    void 创建先保存MySQL待索引再发布事件() {
        stubSaveAssignsIds();
        when(numberGenerator.generate(any())).thenReturn("MEM-202607-0001");

        MemoryEntry created = service.create(command(), "user:1");

        assertThat(created.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        assertThat(created.getValidFrom()).isEqualTo(NOW);
        InOrder order = inOrder(repository, events);
        order.verify(repository).save(created);
        order.verify(events).publishEvent(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.UPSERT, created.getMemoryNo(), created.getId()));
    }

    @Test
    void 更新创建新版本且旧版本被替代() {
        stubSaveAssignsIds();
        MemoryEntry version1 = version1();
        when(repository.findByMemoryNo("MEM-202607-0001")).thenReturn(Optional.of(version1));
        when(numberGenerator.generate(any())).thenReturn("MEM-202607-0002");

        MemoryEntry version2 = service.replace("MEM-202607-0001", replacement(), "user:1");

        assertThat(version1.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(version2.getVersion()).isEqualTo(2);
        assertThat(version2.getPreviousId()).isEqualTo(version1.getId());
        assertThat(version2.getMemoryKey()).isEqualTo(version1.getMemoryKey());
        InOrder order = inOrder(repository, events);
        order.verify(repository).save(version1);
        order.verify(repository).save(version2);
        order.verify(events).publishEvent(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.DELETE, version1.getMemoryNo(), version1.getId()));
        order.verify(events).publishEvent(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.UPSERT, version2.getMemoryNo(), version2.getId()));
    }

    @Test
    void 失效只改MySQL状态并发布派生删除事件() {
        stubSaveAssignsIds();
        MemoryEntry entry = version1();
        when(repository.findByMemoryNo(entry.getMemoryNo())).thenReturn(Optional.of(entry));

        MemoryEntry expired = service.expire(entry.getMemoryNo(), "user:1");

        assertThat(expired.getStatus()).isEqualTo(MemoryStatus.EXPIRED);
        verify(repository).save(entry);
        verify(events).publishEvent(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.DELETE, entry.getMemoryNo(), entry.getId()));
    }

    @Test
    void 查询不存在时返回领域异常() {
        when(repository.findByMemoryNo("MEM-NOT-FOUND")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("MEM-NOT-FOUND"))
                .isInstanceOf(MemoryEntryNotFoundException.class);
    }

    @Test
    void 幂等创建命中活动memoryKey时不重复写入或发布事件() {
        MemoryEntry existing = version1();
        when(repository.findActiveByMemoryKey("write:session-1:term"))
                .thenReturn(Optional.of(existing));

        MemoryEntry result = service.createIdempotent(
                "write:session-1:term", command(), "agent:1");

        assertThat(result).isSameAs(existing);
        verify(repository).findActiveByMemoryKey("write:session-1:term");
        org.mockito.Mockito.verifyNoInteractions(numberGenerator, events);
    }

    @Test
    void 幂等键命中不同内容时拒绝静默复用() {
        MemoryEntry existing = version1();
        when(repository.findActiveByMemoryKey("write:session-1:term"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createIdempotent(
                "write:session-1:term", replacement(), "agent:1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("幂等键冲突");
        org.mockito.Mockito.verifyNoInteractions(numberGenerator, events);
    }

    private static MemoryEntryCommand command() {
        return new MemoryEntryCommand(MemoryType.BUSINESS_TERM, "大客户口径",
                "年采购金额超过50万元", MemorySourceType.USER_INPUT,
                "session-1", null, null);
    }

    private static MemoryEntryCommand replacement() {
        return new MemoryEntryCommand(MemoryType.METRIC_DEFINITION, "大客户口径V2",
                "年采购金额超过80万元", MemorySourceType.USER_INPUT,
                "session-2", null, null);
    }

    private static MemoryEntry version1() {
        MemoryEntry entry = MemoryEntry.create("MEM-202607-0001", "MEM-202607-0001", 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", NOW.minusSeconds(60),
                null, "user:1", NOW.minusSeconds(60));
        entry.assignId(11L);
        return entry;
    }
}

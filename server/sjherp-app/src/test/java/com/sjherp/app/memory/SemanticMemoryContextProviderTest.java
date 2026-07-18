package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SemanticMemoryContextProviderTest {

    @Mock
    private MemoryRecallService recallService;
    @Mock
    private MemoryPromptFormatter formatter;

    @Test
    void 成功召回时委托格式化器生成上下文() {
        List<MemoryRecallHit> hits = List.of();
        when(recallService.recall("大客户怎么定义")).thenReturn(hits);
        when(formatter.format(hits)).thenReturn("memory-context");
        SemanticMemoryContextProvider provider =
                new SemanticMemoryContextProvider(recallService, formatter);

        assertThat(provider.contextFor("大客户怎么定义")).isEqualTo("memory-context");
        verify(formatter).format(hits);
    }

    @Test
    void 召回异常时降级为空上下文() {
        when(recallService.recall("敏感查询"))
                .thenThrow(new IllegalStateException("http://secret/query-body"));
        SemanticMemoryContextProvider provider =
                new SemanticMemoryContextProvider(recallService, formatter);

        assertThat(provider.contextFor("敏感查询")).isEmpty();
    }

    @Test
    void 关闭态提供器始终返回空上下文() {
        assertThat(MemoryContextProvider.none().contextFor("任意问题")).isEmpty();
    }
}

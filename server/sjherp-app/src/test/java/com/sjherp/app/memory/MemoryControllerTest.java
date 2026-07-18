package com.sjherp.app.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemoryEntryQuery;
import com.sjherp.domain.memory.MemoryIndexStatus;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;

class MemoryControllerTest {

    private MemoryService memoryService;
    private MemoryIndexingService indexingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memoryService = Mockito.mock(MemoryService.class);
        indexingService = Mockito.mock(MemoryIndexingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MemoryController(memoryService, indexingService))
                .setControllerAdvice(new MemoryExceptionHandler())
                .build();

        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝", Set.of(Role.BOSS));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_BOSS")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_returns201_andMapsCommand() throws Exception {
        Mockito.when(memoryService.create(any(MemoryEntryCommand.class), eq("alice")))
                .thenReturn(entry(1L, "MEM-202607-0001", 1, MemoryStatus.ACTIVE,
                        MemoryIndexStatus.PENDING, null));

        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"BUSINESS_TERM",
                                  "title":"含税单价",
                                  "content":"含税单价包含适用税率对应的税额。",
                                  "sourceType":"USER_INPUT",
                                  "sourceRef":"chat-100"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memoryNo").value("MEM-202607-0001"))
                .andExpect(jsonPath("$.content").value("含税单价包含适用税率对应的税额。"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));

        ArgumentCaptor<MemoryEntryCommand> captor = ArgumentCaptor.forClass(MemoryEntryCommand.class);
        Mockito.verify(memoryService).create(captor.capture(), eq("alice"));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().memoryType())
                .isEqualTo(MemoryType.BUSINESS_TERM);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().sourceType())
                .isEqualTo(MemorySourceType.USER_INPUT);
    }

    @Test
    void create_missingTitle_returns400_withoutCallingService() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"BUSINESS_TERM",
                                  "content":"内容",
                                  "sourceType":"USER_INPUT",
                                  "sourceRef":"chat-100"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(memoryService, indexingService);
    }

    @Test
    void create_invalidEnum_returns400_withoutInternalDetails() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"UNKNOWN",
                                  "title":"标题",
                                  "content":"内容",
                                  "sourceType":"USER_INPUT",
                                  "sourceRef":"chat-100"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求体不是合法的 JSON 或字段类型不匹配"));
        Mockito.verifyNoInteractions(memoryService, indexingService);
    }

    @Test
    void replace_returnsNewImmutableVersion() throws Exception {
        Mockito.when(memoryService.replace(eq("MEM-202607-0001"), any(MemoryEntryCommand.class), eq("alice")))
                .thenReturn(entry(2L, "MEM-202607-0002", 2, MemoryStatus.ACTIVE,
                        MemoryIndexStatus.PENDING, null));

        mockMvc.perform(put("/api/memories/MEM-202607-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryNo").value("MEM-202607-0002"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void search_mapsFiltersAndPagination() throws Exception {
        Mockito.when(memoryService.search(any(MemoryEntryQuery.class)))
                .thenReturn(new PageResult<>(List.of(entry(1L, "MEM-202607-0001", 1,
                        MemoryStatus.ACTIVE, MemoryIndexStatus.INDEXED, null)), 1, 2, 50));

        mockMvc.perform(get("/api/memories")
                        .param("type", "BUSINESS_TERM")
                        .param("status", "ACTIVE")
                        .param("indexStatus", "INDEXED")
                        .param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0].memoryNo").value("MEM-202607-0001"));

        ArgumentCaptor<MemoryEntryQuery> captor = ArgumentCaptor.forClass(MemoryEntryQuery.class);
        Mockito.verify(memoryService).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().memoryType())
                .isEqualTo(MemoryType.BUSINESS_TERM);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().indexStatus())
                .isEqualTo(MemoryIndexStatus.INDEXED);
    }

    @Test
    void retryFailure_returnsCurrentFailedState_withoutSensitiveError() throws Exception {
        MemoryEntry failed = entry(1L, "MEM-202607-0001", 1, MemoryStatus.ACTIVE,
                MemoryIndexStatus.FAILED, "Ollama 暂不可用");
        Mockito.when(indexingService.retryIndex("MEM-202607-0001", "alice"))
                .thenReturn(failed);
        Mockito.when(memoryService.get("MEM-202607-0001")).thenReturn(failed);

        mockMvc.perform(post("/api/memories/MEM-202607-0001/retry-index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexStatus").value("FAILED"))
                .andExpect(jsonPath("$.lastIndexError").value("Ollama 暂不可用"))
                .andExpect(jsonPath("$.lastIndexError").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("http://"))));
    }

    @Test
    void rebuild_returnsCounters() throws Exception {
        Mockito.when(indexingService.rebuildIndex("alice"))
                .thenReturn(new MemoryIndexingService.RebuildResult(7, 2, 99));

        mockMvc.perform(post("/api/memories/rebuild-index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(7))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.lastProcessedId").value(99));
    }

    private static String validJson() {
        return """
                {
                  "type":"BUSINESS_TERM",
                  "title":"含税单价（修订）",
                  "content":"修订后的定义。",
                  "sourceType":"USER_INPUT",
                  "sourceRef":"chat-101"
                }
                """;
    }

    private static MemoryEntry entry(long id, String memoryNo, int version,
                                     MemoryStatus status, MemoryIndexStatus indexStatus,
                                     String lastIndexError) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return MemoryEntry.restore(id, 0, memoryNo, "MEM-202607-0001", version,
                version == 1 ? null : 1L, MemoryType.BUSINESS_TERM, "含税单价",
                "含税单价包含适用税率对应的税额。", "abc123",
                MemorySourceType.USER_INPUT, "chat-100", status, now, null,
                indexStatus, indexStatus == MemoryIndexStatus.INDEXED ? "sjherp_memory" : null,
                indexStatus == MemoryIndexStatus.INDEXED ? "qwen3-embedding:0.6b" : null,
                indexStatus == MemoryIndexStatus.INDEXED ? 1024 : null,
                indexStatus == MemoryIndexStatus.FAILED ? 1 : 0, null, lastIndexError,
                "alice", now, "alice", now);
    }
}

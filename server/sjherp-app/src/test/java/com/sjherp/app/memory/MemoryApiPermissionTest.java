package com.sjherp.app.memory;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemoryIndexStatus;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;

@WebMvcTest(controllers = MemoryController.class,
        properties = {
                "sjherp.memory.enabled=true",
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, MemoryExceptionHandler.class})
class MemoryApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemoryService memoryService;

    @MockitoBean
    private MemoryIndexingService indexingService;

    @MockitoBean
    private MemoryGovernanceService governanceService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(memoryService.create(Mockito.any(MemoryEntryCommand.class), Mockito.anyString()))
                .thenReturn(entry());
        Mockito.when(memoryService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
        Mockito.when(governanceService.findCandidates(50))
                .thenReturn(new MemoryGovernanceService.Candidates(List.of(), List.of()));
        Mockito.when(memoryService.markConflict(Mockito.anyList(), Mockito.anyString()))
                .thenReturn(new MemoryConflictResult(List.of(entry(1), entry(2))));
        Mockito.when(memoryService.activate(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(entry(1));
    }

    @Test
    void adminAndBoss_canManageMemories() throws Exception {
        mockMvc.perform(post("/api/memories").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/memories").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void accountantAndBusinessRoles_cannotReadOrWriteMemories() throws Exception {
        for (Role role : List.of(Role.ACCOUNTANT, Role.PURCHASER, Role.SALES, Role.WAREHOUSE)) {
            mockMvc.perform(get("/api/memories").with(asUser(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("无权限执行该操作"));
            mockMvc.perform(get("/api/memories/governance/candidates").with(asUser(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/memories/governance/conflicts").with(asUser(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(conflictJson()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/memories/MEM-202607-0001/activate").with(asUser(role)))
                    .andExpect(status().isForbidden());
        }
        Mockito.verifyNoInteractions(memoryService, indexingService, governanceService);
    }

    @Test
    void unauthenticatedRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/memories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void adminAndBoss_canUseGovernanceEndpoints() throws Exception {
        mockMvc.perform(get("/api/memories/governance/candidates").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/memories/governance/conflicts").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictJson()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/memories/MEM-202607-0001/activate")
                        .with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor asUser(Role role) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private static String validJson() {
        return """
                {
                  "type":"BUSINESS_TERM",
                  "title":"含税单价",
                  "content":"含税单价包含适用税率对应的税额。",
                  "sourceType":"USER_INPUT",
                  "sourceRef":"chat-100"
                }
                """;
    }

    private static String conflictJson() {
        return """
                {"memoryNos":["MEM-202607-0001","MEM-202607-0002"]}
                """;
    }

    private static MemoryEntry entry() {
        return entry(1);
    }

    private static MemoryEntry entry(long id) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return MemoryEntry.restore(id, 0, "MEM-202607-000" + id,
                "MEM-202607-000" + id, 1,
                null, MemoryType.BUSINESS_TERM, "含税单价", "内容", "hash",
                MemorySourceType.USER_INPUT, "chat-100", MemoryStatus.ACTIVE,
                now, null, MemoryIndexStatus.PENDING, null, null, null,
                0, null, null, "tester", now, "tester", now);
    }
}

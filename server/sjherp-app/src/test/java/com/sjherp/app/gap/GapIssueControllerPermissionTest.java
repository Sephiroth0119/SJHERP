package com.sjherp.app.gap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.sjherp.app.security.*;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

@WebMvcTest(GapIssueController.class)
@Import({SecurityConfig.class, PermissionGuard.class})
class GapIssueControllerPermissionTest {
    @Autowired MockMvc mvc;
    @MockitoBean GapIssueService service;
    @MockitoBean UserRepository users;
    @org.junit.jupiter.api.BeforeEach void setUp(){when(service.list()).thenReturn(List.of());}
    @Test void adminAndBossMayReadAndOperateButSalesCannot() throws Exception {
        for (Role role : List.of(Role.ADMIN,Role.BOSS)) mvc.perform(get("/api/gap-issues/candidates").with(user(role))).andExpect(status().isOk());
        mvc.perform(get("/api/gap-issues/candidates").with(user(Role.SALES))).andExpect(status().isForbidden());
        mvc.perform(get("/api/gap-issues/candidates")).andExpect(status().isUnauthorized());
        verify(service, never()).cluster(anyString());
    }
    @Test void postOperationsRequireAdminOrBoss() throws Exception {
        when(service.cluster(anyString())).thenReturn(List.of());
        when(service.approve(eq(1L), anyString())).thenReturn(null);
        when(service.deliver(eq(1L), anyString())).thenReturn(null);
        for (Role role : List.of(Role.ADMIN, Role.BOSS)) {
            mvc.perform(post("/api/gap-issues/candidates").with(user(role))).andExpect(status().isOk());
            mvc.perform(post("/api/gap-issues/candidates/1/approve").with(user(role))).andExpect(status().isOk());
            mvc.perform(post("/api/gap-issues/candidates/1/deliver").with(user(role))).andExpect(status().isOk());
        }
        for (String path : List.of("/api/gap-issues/candidates", "/api/gap-issues/candidates/1/approve", "/api/gap-issues/candidates/1/deliver")) {
            mvc.perform(post(path).with(user(Role.SALES))).andExpect(status().isForbidden());
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
        }
    }
    private static RequestPostProcessor user(Role role){var p=new AuthenticatedUser(7,"u","用户",Set.of(role));return authentication(new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
}

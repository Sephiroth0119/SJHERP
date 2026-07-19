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
@Import({SecurityConfig.class, GapExceptionHandler.class})
class GapIssueControllerPermissionTest {
    @Autowired MockMvc mvc;
    @MockitoBean GapIssueService service;
    @MockitoBean UserRepository users;
    @org.junit.jupiter.api.BeforeEach void setUp(){when(service.list()).thenReturn(List.of());}
    @Test void adminAndBossMayReadAndOperateButSalesCannot() throws Exception {
        for (Role role : List.of(Role.ADMIN,Role.BOSS)) mvc.perform(get("/api/gap-issues/candidates").with(user(role))).andExpect(status().isOk());
        mvc.perform(get("/api/gap-issues/candidates").with(user(Role.SALES))).andExpect(status().isForbidden());
        mvc.perform(get("/api/gap-issues/candidates")).andExpect(status().isUnauthorized());
        verify(service, never()).cluster();
    }
    private static RequestPostProcessor user(Role role){var p=new AuthenticatedUser(7,"u","用户",Set.of(role));return authentication(new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
}

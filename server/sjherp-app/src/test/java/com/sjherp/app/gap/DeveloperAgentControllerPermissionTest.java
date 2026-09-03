package com.sjherp.app.gap;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.sjherp.app.security.*;
import com.sjherp.domain.gap.DeveloperAgentTask;
import com.sjherp.domain.identity.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

@WebMvcTest(DeveloperAgentController.class)
@Import({SecurityConfig.class, PermissionGuard.class})
@TestPropertySource(properties = {
        "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
})
class DeveloperAgentControllerPermissionTest {
    @Autowired MockMvc mvc;
    @MockitoBean DeveloperAgentService service;
    @MockitoBean com.sjherp.domain.identity.UserRepository users;

    @Test void onlyAdminAndBossMayStartOrRun() throws Exception {
        when(service.start(1,"u")).thenReturn(null);
        when(service.run(1,"u")).thenReturn(null);
        for(Role role: List.of(Role.ADMIN,Role.BOSS)) {
            mvc.perform(post("/api/developer-agent/tasks/from-candidate/1").with(user(role))).andExpect(status().isOk());
            mvc.perform(post("/api/developer-agent/tasks/1/run").with(user(role))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/developer-agent/tasks/from-candidate/1").with(user(Role.SALES))).andExpect(status().isForbidden());
        mvc.perform(post("/api/developer-agent/tasks/1/run")).andExpect(status().isUnauthorized());
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(Role role){
        var p=new AuthenticatedUser(7,"u","用户",Set.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
}

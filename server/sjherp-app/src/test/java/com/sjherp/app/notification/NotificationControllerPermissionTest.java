package com.sjherp.app.notification;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

@WebMvcTest(controllers = NotificationController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, NotificationExceptionHandler.class})
class NotificationControllerPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(notificationService.search(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
        Mockito.when(notificationService.countUnread(anyLong())).thenReturn(0L);
    }

    @Test
    void everyAuthenticatedRoleCanUsePersonalNotificationEndpoints() throws Exception {
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/notifications").with(asUser(7, role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/notifications/unread-count").with(asUser(7, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreadCount").value(0));
        }
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/99/read"))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor asUser(long userId, Role role) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, "tester", "测试用户", Set.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}

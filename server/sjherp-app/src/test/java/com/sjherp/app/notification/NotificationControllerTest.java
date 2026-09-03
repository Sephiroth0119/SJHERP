package com.sjherp.app.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.notification.SystemNotification;

class NotificationControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-19T00:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-07-19T01:00:00Z");

    private final NotificationService service = mock(NotificationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new NotificationExceptionHandler())
                .build();
        AuthenticatedUser principal = new AuthenticatedUser(
                7L, "sales", "销售员", Set.of(Role.SALES));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAlwaysDerivesRecipientFromCurrentUserAndMapsPage() throws Exception {
        SystemNotification notification = notification(null);
        when(service.search(7, 2, 10))
                .thenReturn(new PageResult<>(List.of(notification), 1, 2, 10));

        mockMvc.perform(get("/api/notifications")
                        .param("page", "2").param("size", "10")
                        .param("recipientUserId", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0].id").value(99))
                .andExpect(jsonPath("$.items[0].category").value("CONSISTENCY"))
                .andExpect(jsonPath("$.items[0].sourceRef").value("CHK-202607-0001"))
                .andExpect(jsonPath("$.items[0].read").value(false));

        verify(service).search(7, 2, 10);
    }

    @Test
    void unreadCountUsesCurrentRecipient() throws Exception {
        when(service.countUnread(7)).thenReturn(3L);

        mockMvc.perform(get("/api/notifications/unread-count").param("recipient", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3));

        verify(service).countUnread(7);
    }

    @Test
    void markReadUsesCurrentRecipientAndReturnsIdempotentState() throws Exception {
        when(service.markRead(7, 99)).thenReturn(notification(READ_AT));

        mockMvc.perform(post("/api/notifications/99/read").param("recipientUserId", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").exists());

        verify(service).markRead(7, 99);
    }

    @Test
    void foreignOrMissingNotificationReturns404WithoutOwnershipDisclosure() throws Exception {
        when(service.markRead(7, 99)).thenThrow(new NotificationNotFoundException(99));

        mockMvc.perform(post("/api/notifications/99/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("通知不存在"));
    }

    @Test
    void invalidPagingReturns400() throws Exception {
        when(service.search(7, 0, 20)).thenThrow(new IllegalArgumentException("分页参数不合法"));

        mockMvc.perform(get("/api/notifications").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("分页参数不合法"));
    }

    private static SystemNotification notification(Instant readAt) {
        return SystemNotification.restore(99, 0, 7,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "一致性检查异常", "运行编号=CHK-202607-0001，错误=1，警告=0，提示=0",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-202607-0001",
                readAt, CREATED_AT);
    }
}

package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ClosureFeedbackPermissionTest {
    @Test
    void confirmationEndpointUsesDeveloperAgentAdminBossPermissionBoundary() {
        PreAuthorize annotation = ClosureFeedbackController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("@perm.has('developer:agent')");
    }
}

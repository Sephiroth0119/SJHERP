package com.sjherp.app.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LicenseEnforceFilterTest {

    @Test
    void 未阻断时放行业务接口() throws Exception {
        LicenseService service = mock(LicenseService.class);
        when(service.current()).thenReturn(LicenseEvaluation.unconfigured(true));
        LicenseEnforceFilter filter = new LicenseEnforceFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/purchase/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void 强制过期时拦截业务接口() throws Exception {
        LicenseService service = mock(LicenseService.class);
        when(service.current()).thenReturn(expiredBlocking());
        LicenseEnforceFilter filter = new LicenseEnforceFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/purchase/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("许可无效或已过期");
    }

    @Test
    void 强制过期仍放行探活登录与许可状态() throws Exception {
        LicenseService service = mock(LicenseService.class);
        when(service.current()).thenReturn(expiredBlocking());
        LicenseEnforceFilter filter = new LicenseEnforceFilter(service);
        assertAllowed(filter, "GET", "/api/health");
        assertAllowed(filter, "POST", "/api/auth/login");
        assertAllowed(filter, "GET", "/api/auth/me");
        assertAllowed(filter, "GET", "/api/license/status");
        assertAllowed(filter, "GET", "/actuator/health");
    }

    private static void assertAllowed(LicenseEnforceFilter filter, String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as(method + " " + path).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static LicenseEvaluation expiredBlocking() {
        return new LicenseEvaluation(
                LicenseStatus.EXPIRED,
                true,
                true,
                true,
                "试点客户",
                null,
                null,
                null,
                null,
                List.of(),
                null,
                "已过期");
    }
}

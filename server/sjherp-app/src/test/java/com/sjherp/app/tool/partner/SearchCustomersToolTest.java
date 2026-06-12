package com.sjherp.app.tool.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerQuery;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * search_customers 工具单测（M2-T08）：查询条件映射、精简列表字段（结算方式中文标签）、
 * JSON Schema 枚举校验。
 */
class SearchCustomersToolTest {

    private CustomerService customerService;
    private SearchCustomersTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "查客户");

    @BeforeEach
    void setUp() {
        customerService = mock(CustomerService.class);
        tool = new SearchCustomersTool(customerService);
    }

    @Test
    void 风险级别为普通() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
    }

    @Test
    void 查询条件映射与精简列表字段() {
        Customer customer = Customer.restore(1L, "CUS-202606-0001", "华东金属", "王经理", "13900000000",
                null, null, SettlementMethod.MONTHLY, null, "CNY", ArchiveStatus.ENABLED,
                "admin", Instant.now(), "admin", Instant.now());
        when(customerService.search(eq(new CustomerQuery("华东", null, 1, 10))))
                .thenReturn(new PageResult<>(List.of(customer), 1, 1, 10));

        ToolResult result = tool.execute(Map.of("keyword", "华东"), context);

        verify(customerService).search(eq(new CustomerQuery("华东", null, 1, 10)));
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items.get(0))
                .containsEntry("code", "CUS-202606-0001")
                .containsEntry("name", "华东金属")
                .containsEntry("contactPerson", "王经理")
                .containsEntry("settlementMethod", "月结")
                .containsEntry("status", "启用");
    }

    @Test
    void schema校验_非法状态枚举拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("status", "ACTIVE"));
        assertThat(errors).isNotEmpty();
    }
}

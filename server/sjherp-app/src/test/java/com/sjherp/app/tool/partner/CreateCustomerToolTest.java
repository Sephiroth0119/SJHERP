package com.sjherp.app.tool.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * create_customer 工具单测（M2-T08）：参数映射 / 默认结算方式 / 审计操作人
 * agent:&lt;userId&gt; / 领域校验拒绝转 fail / JSON Schema 参数校验。
 */
class CreateCustomerToolTest {

    private CustomerService customerService;
    private CreateCustomerTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "新建客户 南方贸易");

    @BeforeEach
    void setUp() {
        customerService = mock(CustomerService.class);
        tool = new CreateCustomerTool(customerService);
    }

    private static Customer customer(String code, String name, SettlementMethod method) {
        return Customer.restore(100L, code, name, "张三", "13800138000", null, null,
                method, null, "CNY", ArchiveStatus.ENABLED,
                "agent:1", Instant.now(), "agent:1", Instant.now());
    }

    @Test
    void 风险级别为高_权限点已声明() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("partner:create_customer");
    }

    @Test
    void 完整参数映射到领域命令_操作人记agent前缀() {
        when(customerService.create(any(), any()))
                .thenReturn(customer("CUS-202606-0002", "南方贸易", SettlementMethod.MONTHLY));

        ToolResult result = tool.execute(Map.of(
                "name", "南方贸易",
                "contact_person", "张三",
                "contact_phone", "13800138000",
                "settlement_method", "MONTHLY"), context);

        ArgumentCaptor<CustomerCommand> captor = ArgumentCaptor.forClass(CustomerCommand.class);
        verify(customerService).create(captor.capture(), eq("agent:1"));
        CustomerCommand command = captor.getValue();
        assertThat(command.code()).isNull(); // 编码自动生成
        assertThat(command.name()).isEqualTo("南方贸易");
        assertThat(command.contactPerson()).isEqualTo("张三");
        assertThat(command.contactPhone()).isEqualTo("13800138000");
        assertThat(command.settlementMethod()).isEqualTo(SettlementMethod.MONTHLY);
        assertThat(command.creditLimit()).isNull();

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("code", "CUS-202606-0002")
                .containsEntry("settlementMethod", "月结");
    }

    @Test
    void 结算方式缺省为月结() {
        when(customerService.create(any(), any()))
                .thenReturn(customer("CUS-202606-0003", "某客户", SettlementMethod.MONTHLY));

        tool.execute(Map.of("name", "某客户"), context);

        ArgumentCaptor<CustomerCommand> captor = ArgumentCaptor.forClass(CustomerCommand.class);
        verify(customerService).create(captor.capture(), eq("agent:1"));
        assertThat(captor.getValue().settlementMethod()).isEqualTo(SettlementMethod.MONTHLY);
    }

    @Test
    void 领域校验拒绝转为失败结果_不抛异常() {
        when(customerService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("客户名称不能为空"));

        ToolResult result = tool.execute(mapWithName(null), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("客户名称不能为空");
    }

    @Test
    void schema校验_缺少必填name拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("contact_person", "张三"));
        assertThat(errors).anyMatch(e -> e.contains("name"));
    }

    @Test
    void schema校验_非法结算方式枚举拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(),
                        Map.of("name", "南方贸易", "settlement_method", "WEEKLY"));
        assertThat(errors).isNotEmpty();
    }

    @Test
    void schema校验_多余字段拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("name", "南方贸易", "foo", "bar"));
        assertThat(errors).isNotEmpty();
    }

    private static Map<String, Object> mapWithName(String name) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        return map;
    }
}

package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordCommand;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gap.GapStatus;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * record_process_gap 工具单测（M1-T04）：参数映射 / 提出人取会话用户（缺失记
 * anonymous）/ 审计操作人 agent:&lt;userId&gt; / 领域校验拒绝转 fail /
 * JSON Schema 参数校验 / 风险级别 NORMAL（不走高风险拦截）。
 */
class RecordProcessGapToolTest {

    private GapRecordService gapRecordService;
    private RecordProcessGapTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "希望支持按月导出对账单");

    @BeforeEach
    void setUp() {
        gapRecordService = mock(GapRecordService.class);
        tool = new RecordProcessGapTool(gapRecordService);
    }

    /** 构造一条已落库的缺口记录（初始状态 NEW） */
    private static GapRecord record(String gapNo) {
        return new GapRecord(gapNo, "session-1", "按月导出对账单",
                "用户月底需要给客户发对账单", "系统自动汇总并导出 Excel",
                "缺少对账单导出能力", BusinessModule.FINANCE, GapSeverity.MEDIUM,
                "1", "agent:1");
    }

    private static Map<String, Object> fullArguments() {
        return Map.of(
                "title", "按月导出对账单",
                "scenario", "用户月底需要给客户发对账单",
                "expected_behavior", "系统自动汇总并导出 Excel",
                "missing_capability", "缺少对账单导出能力",
                "business_module", "FINANCE",
                "severity", "MEDIUM");
    }

    @Test
    void 风险级别为普通_无权限点() {
        // 记录缺口本身不产生业务影响：NORMAL，不走框架高风险拦截
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
        assertThat(tool.name()).isEqualTo("record_process_gap");
    }

    @Test
    void 完整参数映射到领域命令_操作人记agent前缀() {
        when(gapRecordService.create(any(), any())).thenReturn(record("GAP-202606-0001"));

        ToolResult result = tool.execute(fullArguments(), context);

        ArgumentCaptor<GapRecordCommand> captor = ArgumentCaptor.forClass(GapRecordCommand.class);
        verify(gapRecordService).create(captor.capture(), eq("agent:1"));
        GapRecordCommand command = captor.getValue();
        assertThat(command.sessionId()).isEqualTo("session-1"); // 来源会话随上下文携带（M6-T10 回写依据）
        assertThat(command.title()).isEqualTo("按月导出对账单");
        assertThat(command.scenario()).isEqualTo("用户月底需要给客户发对账单");
        assertThat(command.expectedBehavior()).isEqualTo("系统自动汇总并导出 Excel");
        assertThat(command.missingCapability()).isEqualTo("缺少对账单导出能力");
        assertThat(command.businessModule()).isEqualTo(BusinessModule.FINANCE);
        assertThat(command.severity()).isEqualTo(GapSeverity.MEDIUM);
        assertThat(command.reporter()).isEqualTo("1"); // 提出人 = 会话所属用户

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("gapNo", "GAP-202606-0001")
                .containsEntry("status", GapStatus.NEW.name());
        assertThat(String.valueOf(result.data().get("guidance")))
                .contains("GAP-202606-0001").contains("开发团队会评估");
    }

    @Test
    void 用户标识缺失时提出人记anonymous() {
        when(gapRecordService.create(any(), any())).thenReturn(record("GAP-202606-0002"));

        tool.execute(fullArguments(), new ToolContext("session-2", " ", "导出对账单"));

        ArgumentCaptor<GapRecordCommand> captor = ArgumentCaptor.forClass(GapRecordCommand.class);
        verify(gapRecordService).create(captor.capture(), eq("agent:anonymous"));
        assertThat(captor.getValue().reporter()).isEqualTo("anonymous");
    }

    @Test
    void 领域校验拒绝转为失败结果_不抛异常() {
        when(gapRecordService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("缺口标题不能超过 200 个字符"));

        ToolResult result = tool.execute(fullArguments(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("缺口记录被拒绝").contains("缺口标题不能超过 200 个字符");
    }

    @Test
    void schema校验_缺少必填字段拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("title", "按月导出对账单"));
        assertThat(errors).isNotEmpty();
    }

    @Test
    void schema校验_非法模块枚举拒绝() {
        Map<String, Object> arguments = new java.util.HashMap<>(fullArguments());
        arguments.put("business_module", "HR");
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), arguments);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void schema校验_非法严重度枚举拒绝() {
        Map<String, Object> arguments = new java.util.HashMap<>(fullArguments());
        arguments.put("severity", "CRITICAL");
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), arguments);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void schema校验_多余字段拒绝() {
        Map<String, Object> arguments = new java.util.HashMap<>(fullArguments());
        arguments.put("foo", "bar");
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), arguments);
        assertThat(errors).isNotEmpty();
    }
}

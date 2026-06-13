package com.sjherp.app.tool.fund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * create_payment_account / search_payment_accounts 工具单测（M4-T04a，照
 * {@code WarehouseToolsTest} 范式）：风险级别/权限点声明、命令与查询条件映射、
 * 审计操作人 agent 前缀、glAccountCode 透传、领域校验拒绝转失败结果、Schema 必填校验。
 *
 * <p>注意：两工具均为 {@link ToolRiskLevel#NORMAL}（派工 §5/§1 明确——建档不涉资金过账，
 * 区别于收付款单的 HIGH）；create 须 {@code finance:payment_account}，search 登录即可（null）。
 */
class PaymentAccountToolsTest {

    private PaymentAccountService paymentAccountService;
    private CreatePaymentAccountTool createTool;
    private SearchPaymentAccountsTool searchTool;
    private final ToolContext context = new ToolContext("session-1", "1", "资金账户操作");

    @BeforeEach
    void setUp() {
        paymentAccountService = mock(PaymentAccountService.class);
        createTool = new CreatePaymentAccountTool(paymentAccountService);
        searchTool = new SearchPaymentAccountsTool(paymentAccountService);
    }

    private static PaymentAccount account(String code, String name, PaymentAccountType type,
                                          String glAccountCode, String bankName, String accountNo) {
        return PaymentAccount.restore(5L, code, name, type, glAccountCode, bankName, accountNo,
                ArchiveStatus.ENABLED, "agent:1", Instant.now(), "agent:1", Instant.now());
    }

    // ================================================================ 元信息

    @Test
    void 工具名与风险级别与权限点声明正确() {
        assertThat(createTool.name()).isEqualTo("create_payment_account");
        assertThat(searchTool.name()).isEqualTo("search_payment_accounts");
        // 派工：资金账户两工具均 NORMAL（建档不涉资金过账）
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(searchTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        // create 受 finance:payment_account 守护；search 登录即可
        assertThat(createTool.requiredPermission()).isEqualTo("finance:payment_account");
        assertThat(searchTool.requiredPermission()).isNull();
    }

    // ================================================================ create

    @Test
    void 创建命令映射_操作人记agent前缀_glAccountCode透传() {
        when(paymentAccountService.create(any(), any()))
                .thenReturn(account("FA-202606-0001", "工行户", PaymentAccountType.BANK, "1002", "工行", "62"));

        ToolResult result = createTool.execute(
                Map.of("name", "工行户", "accountType", "BANK", "glAccountCode", "1002",
                        "bankName", "工行", "accountNo", "62"),
                context);

        ArgumentCaptor<PaymentAccountCommand> captor = ArgumentCaptor.forClass(PaymentAccountCommand.class);
        verify(paymentAccountService).create(captor.capture(), eq("agent:1"));
        PaymentAccountCommand command = captor.getValue();
        assertThat(command.code()).isNull(); // 编码自动生成
        assertThat(command.name()).isEqualTo("工行户");
        assertThat(command.accountType()).isEqualTo(PaymentAccountType.BANK);
        assertThat(command.glAccountCode()).isEqualTo("1002");
        assertThat(command.bankName()).isEqualTo("工行");
        assertThat(command.accountNo()).isEqualTo("62");

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("code", "FA-202606-0001")
                .containsEntry("name", "工行户")
                .containsEntry("accountType", "银行存款")  // 返回中文标签
                .containsEntry("glAccountCode", "1002")
                .containsEntry("status", "启用");
    }

    @Test
    void 创建小写类别也能解析_CASH() {
        when(paymentAccountService.create(any(), any()))
                .thenReturn(account("FA-1", "现金", PaymentAccountType.CASH, "1001", null, null));

        ToolResult result = createTool.execute(
                Map.of("name", "现金", "accountType", "cash", "glAccountCode", "1001"), context);

        ArgumentCaptor<PaymentAccountCommand> captor = ArgumentCaptor.forClass(PaymentAccountCommand.class);
        verify(paymentAccountService).create(captor.capture(), any());
        assertThat(captor.getValue().accountType()).isEqualTo(PaymentAccountType.CASH);
        assertThat(result.success()).isTrue();
    }

    @Test
    void 创建类别非法_直接失败不触达service() {
        ToolResult result = createTool.execute(
                Map.of("name", "x", "accountType", "INVALID", "glAccountCode", "1001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("资金账户创建被拒绝").contains("CASH / BANK / OTHER");
        verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 创建被领域校验拒绝转失败结果_glAccountCode非末级() {
        when(paymentAccountService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("GL 科目不是末级科目，不能用于资金账户挂账: 1001"));

        ToolResult result = createTool.execute(
                Map.of("name", "现金", "accountType", "CASH", "glAccountCode", "1001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("资金账户创建被拒绝").contains("末级");
    }

    @Test
    void schema校验_创建缺glAccountCode拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(createTool.parameterSchema(), Map.of("name", "x", "accountType", "BANK"));
        assertThat(errors).anyMatch(e -> e.contains("glAccountCode"));
    }

    @Test
    void schema校验_创建缺name与accountType拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(createTool.parameterSchema(), Map.of("glAccountCode", "1002"));
        assertThat(errors).anyMatch(e -> e.contains("name"));
        assertThat(errors).anyMatch(e -> e.contains("accountType"));
    }

    // ================================================================ search

    @Test
    void 查询条件映射与列表字段() {
        when(paymentAccountService.search(eq(new PaymentAccountQuery("工行", null, 1, 10))))
                .thenReturn(new PageResult<>(
                        List.of(account("FA-202606-0001", "工行户", PaymentAccountType.BANK, "1002", "工行", "62")),
                        1L, 1, 10));

        ToolResult result = searchTool.execute(Map.of("keyword", "工行"), context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0))
                .containsEntry("code", "FA-202606-0001")
                .containsEntry("name", "工行户")
                .containsEntry("accountType", "银行存款")
                .containsEntry("glAccountCode", "1002")
                .containsEntry("bankName", "工行")
                .containsEntry("accountNo", "62")
                .containsEntry("status", "启用");
        assertThat(result.data()).containsEntry("total", 1L);
    }

    @Test
    void 查询状态过滤映射_最多10条上限() {
        when(paymentAccountService.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 10));

        searchTool.execute(Map.of("status", "DISABLED"), context);

        ArgumentCaptor<PaymentAccountQuery> captor = ArgumentCaptor.forClass(PaymentAccountQuery.class);
        verify(paymentAccountService).search(captor.capture());
        PaymentAccountQuery query = captor.getValue();
        assertThat(query.status()).isEqualTo(ArchiveStatus.DISABLED);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(10); // ArchiveToolSupport.MAX_ITEMS
    }

    @Test
    void 查询无参数_keyword与status均不过滤() {
        when(paymentAccountService.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 10));

        searchTool.execute(new HashMap<>(), context);

        ArgumentCaptor<PaymentAccountQuery> captor = ArgumentCaptor.forClass(PaymentAccountQuery.class);
        verify(paymentAccountService).search(captor.capture());
        assertThat(captor.getValue().keyword()).isNull();
        assertThat(captor.getValue().status()).isNull();
    }

    @Test
    void 查询超量给出note提示() {
        when(paymentAccountService.search(any()))
                .thenReturn(new PageResult<>(
                        List.of(account("FA-1", "户1", PaymentAccountType.CASH, "1001", null, null)),
                        25L, 1, 10));

        ToolResult result = searchTool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("note");
        assertThat(String.valueOf(result.data().get("note"))).contains("25");
    }
}

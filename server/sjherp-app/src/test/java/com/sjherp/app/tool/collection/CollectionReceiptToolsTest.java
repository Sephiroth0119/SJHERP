package com.sjherp.app.tool.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptNotFoundException;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccountNotFoundException;
import com.sjherp.domain.receivable.ReceivableNotFoundException;

/**
 * 收款单 Agent 工具组单测（M4-T04c）：create/approve/post/query_collection_receipt（s）
 * 的风险级别/权限点、parameterSchema 合法、多行分摊解析与参数透传、各异常映射 ToolResult.fail。
 *
 * <p>照采购/销售发票工具测试范式（PurchaseInvoiceToolsTest / SalesInvoiceToolsTest）：
 * AppService 全 mock，verify 参数（含多行 lines、金额 BigDecimal）透传，operator 前缀 agent:&lt;userId&gt;。
 */
class CollectionReceiptToolsTest {

    private CollectionReceiptAppService appService;
    private CreateCollectionReceiptTool createTool;
    private ApproveCollectionReceiptTool approveTool;
    private PostCollectionReceiptTool postTool;
    private QueryCollectionReceiptsTool queryTool;
    private final ToolContext context = new ToolContext("session-9", "7", "操作收款单");

    @BeforeEach
    void setUp() {
        appService = mock(CollectionReceiptAppService.class);
        createTool = new CreateCollectionReceiptTool(appService);
        approveTool = new ApproveCollectionReceiptTool(appService);
        postTool = new PostCollectionReceiptTool(appService);
        queryTool = new QueryCollectionReceiptsTool(appService);
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    /**
     * 构造一张可被序列化（toData/toDetail/toList 全字段）的真实收款单。
     *
     * <p>{@link CollectionReceipt} 为 {@code final} 类、且 docNo/status 取自 final 的
     * {@link com.sjherp.domain.common.BusinessDocument}，无法用 Mockito 安全打桩，故用领域
     * {@code restore} 工厂构造真实对象（不重跑业务校验，直接置目标状态）。
     */
    private static CollectionReceipt receiptMock(DocumentStatus status) {
        CollectionReceiptLine line = CollectionReceiptLine.restore(1L, 1, 11L, new BigDecimal("800.00"));
        return CollectionReceipt.restore("RCPT-202606-0001", 5L, 3L, LocalDate.of(2026, 6, 13),
                "货款回收", status, List.of(line), "t");
    }

    // ====================================================================== create

    @Test
    void create_风险级别HIGH_权限点settlement_schema合法() {
        assertThat(createTool.name()).isEqualTo("create_collection_receipt");
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(createTool.requiredPermission()).isEqualTo("finance:settlement");
        assertThat(createTool.parameterSchema()).contains("customer_id", "payment_account_id", "lines",
                "receivable_id", "allocated_amount");
        // schema 复述要点 + 框架级确认提示
        assertThat(createTool.description()).contains("确认");
    }

    @Test
    void create_customer_id缺失_失败_不触碰AppService() {
        ToolResult result = createTool.execute(Map.of(
                "payment_account_id", 3,
                "lines", List.of(Map.of("receivable_id", 11, "allocated_amount", "800.00"))),
                context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("customer_id");
        verifyNoInteractions(appService);
    }

    @Test
    void create_payment_account_id缺失_失败() {
        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5,
                "lines", List.of(Map.of("receivable_id", 11, "allocated_amount", "800.00"))),
                context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("payment_account_id");
        verifyNoInteractions(appService);
    }

    @Test
    void create_lines为空_失败() {
        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3, "lines", List.of()), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("一行");
        verifyNoInteractions(appService);
    }

    @Test
    void create_行缺receivable_id_失败() {
        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3,
                "lines", List.of(Map.of("allocated_amount", "800.00"))), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("receivable_id");
        verifyNoInteractions(appService);
    }

    @Test
    void create_行金额非法_失败() {
        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3,
                "lines", List.of(Map.of("receivable_id", 11, "allocated_amount", "abc"))), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("金额");
        verifyNoInteractions(appService);
    }

    @Test
    void create_收款日期格式非法_失败() {
        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3, "receipt_date", "2026/06/13",
                "lines", List.of(Map.of("receivable_id", 11, "allocated_amount", "800.00"))), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("receipt_date");
        verifyNoInteractions(appService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_正常多行解析_verify参数透传含金额与operator前缀() {
        when(appService.create(eq(5L), eq(3L), eq(LocalDate.of(2026, 6, 13)), eq("货款回收"),
                any(), eq("agent:7"))).thenReturn(receiptMock(DocumentStatus.DRAFT));

        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5,
                "payment_account_id", 3,
                "receipt_date", "2026-06-13",
                "remark", "货款回收",
                "lines", List.of(
                        Map.of("receivable_id", 11, "allocated_amount", "500.00"),
                        Map.of("receivable_id", 12, "allocated_amount", "300.00")
                )), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "RCPT-202606-0001");
        assertThat(result.data()).containsKey("lines");

        ArgumentCaptor<List<CollectionReceiptLineRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(appService).create(eq(5L), eq(3L), eq(LocalDate.of(2026, 6, 13)), eq("货款回收"),
                captor.capture(), eq("agent:7"));
        List<CollectionReceiptLineRequest> lines = captor.getValue();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).receivableId()).isEqualTo(11L);
        assertThat(lines.get(0).allocatedAmount()).isEqualByComparingTo("500.00");
        assertThat(lines.get(1).receivableId()).isEqualTo(12L);
        assertThat(lines.get(1).allocatedAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void create_日期省略_透传null日期() {
        when(appService.create(eq(5L), eq(3L), isNull(), isNull(), any(), eq("agent:7")))
                .thenReturn(receiptMock(DocumentStatus.DRAFT));

        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3,
                "lines", List.of(Map.of("receivable_id", 11, "allocated_amount", "800.00"))), context);

        assertThat(result.success()).isTrue();
        verify(appService).create(eq(5L), eq(3L), isNull(), isNull(), any(), eq("agent:7"));
    }

    @Test
    void create_领域拒绝_转fail() {
        when(appService.create(anyLong(), anyLong(), any(), any(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("分摊金额必须大于 0"));
        ToolResult result = createTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3,
                "lines", List.of(Map.of("receivable_id", 11, "allocated_amount", "800.00"))), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("创建收款单被拒绝");
    }

    // ====================================================================== approve

    @Test
    void approve_风险级别HIGH_权限点settlement_schema合法() {
        assertThat(approveTool.name()).isEqualTo("approve_collection_receipt");
        assertThat(approveTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(approveTool.requiredPermission()).isEqualTo("finance:settlement");
        assertThat(approveTool.parameterSchema()).contains("doc_no");
        assertThat(approveTool.description()).contains("确认");
    }

    @Test
    void approve_doc_no缺失_失败() {
        ToolResult result = approveTool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(appService);
    }

    @Test
    void approve_正常调用_verify() {
        when(appService.approve(eq("RCPT-202606-0001"), eq("agent:7")))
                .thenReturn(receiptMock(DocumentStatus.APPROVED));
        ToolResult result = approveTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("status", "APPROVED");
        verify(appService).approve(eq("RCPT-202606-0001"), eq("agent:7"));
    }

    @Test
    void approve_单据不存在_转fail() {
        when(appService.approve(any(), any()))
                .thenThrow(new CollectionReceiptNotFoundException("RCPT-NOT-EXIST"));
        ToolResult result = approveTool.execute(Map.of("doc_no", "RCPT-NOT-EXIST"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("收款单不存在");
    }

    @Test
    void approve_状态非法流转_转fail() {
        when(appService.approve(any(), any()))
                .thenThrow(new IllegalStateTransitionException("RCPT-202606-0001",
                        DocumentStatus.COMPLETED, DocumentStatus.APPROVED));
        ToolResult result = approveTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("审核收款单被拒绝");
    }

    // ====================================================================== post

    @Test
    void post_风险级别HIGH_权限点settlement_schema合法_复述要点() {
        assertThat(postTool.name()).isEqualTo("post_collection_receipt");
        assertThat(postTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(postTool.requiredPermission()).isEqualTo("finance:settlement");
        assertThat(postTool.parameterSchema()).contains("doc_no");
        // 过账确认卡片靠 description 复述：冲减应收、资金账户、现金侧凭证、不可撤销
        assertThat(postTool.description()).contains("应收", "凭证", "不可撤销", "确认");
    }

    @Test
    void post_正常调用_含核销现金侧提示() {
        when(appService.post(eq("RCPT-202606-0001"), eq("agent:7")))
                .thenReturn(receiptMock(DocumentStatus.COMPLETED));
        ToolResult result = postTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("status", "COMPLETED");
        assertThat(result.data()).containsKey("note");
        assertThat(result.data().get("note").toString()).contains("应收");
        verify(appService).post(eq("RCPT-202606-0001"), eq("agent:7"));
    }

    @Test
    void post_单据不存在_转fail() {
        when(appService.post(any(), any()))
                .thenThrow(new CollectionReceiptNotFoundException("RCPT-NOT-EXIST"));
        ToolResult result = postTool.execute(Map.of("doc_no", "RCPT-NOT-EXIST"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("收款单不存在");
    }

    @Test
    void post_资金账户不存在_转fail() {
        when(appService.post(any(), any()))
                .thenThrow(PaymentAccountNotFoundException.account(3L));
        ToolResult result = postTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("资金账户不存在");
    }

    @Test
    void post_应收不存在_转fail() {
        when(appService.post(any(), any())).thenThrow(new ReceivableNotFoundException(11L));
        ToolResult result = postTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("应收账款不存在");
    }

    @Test
    void post_超额核销_转fail走IAE分支() {
        // OverSettlementException extends IllegalArgumentException → 落 IAE 分支「过账被拒绝」
        when(appService.post(any(), any())).thenThrow(new OverSettlementException(
                new BigDecimal("900.00"), new BigDecimal("0.00"), new BigDecimal("800.00")));
        ToolResult result = postTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("过账被拒绝");
        // 不应误落「过账收款单被拒绝」（ISE 分支）——超额是 IAE
        assertThat(result.error()).doesNotContain("过账收款单被拒绝");
    }

    @Test
    void post_账期已关或状态非法_转fail走ISE分支() {
        when(appService.post(any(), any()))
                .thenThrow(new IllegalStateException("账期已关，禁止过账"));
        ToolResult result = postTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("过账收款单被拒绝");
    }

    // ====================================================================== query

    @Test
    void query_风险级别NORMAL_权限点null_schema合法() {
        assertThat(queryTool.name()).isEqualTo("query_collection_receipts");
        assertThat(queryTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(queryTool.requiredPermission()).isNull();
        assertThat(queryTool.parameterSchema()).contains("doc_no", "customer_id", "payment_account_id",
                "status");
    }

    @Test
    void query_传doc_no_返回单据明细() {
        when(appService.get("RCPT-202606-0001")).thenReturn(receiptMock(DocumentStatus.COMPLETED));
        ToolResult result = queryTool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "RCPT-202606-0001");
        assertThat(result.data()).containsEntry("customerId", 5L);
        assertThat(result.data()).containsKey("lines");
    }

    @Test
    void query_doc_no不存在_转fail() {
        when(appService.get("RCPT-NOT-EXIST"))
                .thenThrow(new CollectionReceiptNotFoundException("RCPT-NOT-EXIST"));
        ToolResult result = queryTool.execute(Map.of("doc_no", "RCPT-NOT-EXIST"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("收款单不存在");
    }

    @Test
    void query_状态不合法_转fail() {
        ToolResult result = queryTool.execute(Map.of("status", "WRONG"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("单据状态不合法");
        verifyNoInteractions(appService);
    }

    @Test
    void query_按过滤条件_走search返回列表() {
        when(appService.search(any(CollectionReceiptQuery.class)))
                .thenReturn(page(List.of(receiptMock(DocumentStatus.COMPLETED))));
        ToolResult result = queryTool.execute(Map.of(
                "customer_id", 5, "payment_account_id", 3, "status", "completed"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("total");
        assertThat(result.data()).containsKey("items");

        ArgumentCaptor<CollectionReceiptQuery> captor =
                ArgumentCaptor.forClass(CollectionReceiptQuery.class);
        verify(appService).search(captor.capture());
        CollectionReceiptQuery q = captor.getValue();
        assertThat(q.customerId()).isEqualTo(5L);
        assertThat(q.paymentAccountId()).isEqualTo(3L);
        assertThat(q.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(q.size()).isEqualTo(10);
    }
}

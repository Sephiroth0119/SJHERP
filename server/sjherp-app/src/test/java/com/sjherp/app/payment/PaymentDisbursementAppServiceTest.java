package com.sjherp.app.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest;
import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.payable.PayableStatus;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;
import com.sjherp.domain.payment.PaymentDisbursementLineInput;
import com.sjherp.domain.payment.PaymentDisbursementService;
import com.sjherp.domain.settlement.SettlementService;

/**
 * 付款单应用服务编排单测（M4-T04b，对称收款单）：用 Mockito 替身验证 {@code post} 的<b>同事务原子编排</b>——
 * 逐行核销应付 + 生成现金侧凭证、对手方一致性校验、异常整单回滚（核销/凭证不持久化）、调用次序。
 *
 * <p>不连真库、不起 Spring 容器；跨聚合依赖（领域 PaymentDisbursementService / PaymentAccountService /
 * AccountsPayableRepository / SettlementService / AutoVoucherService / DocumentNumberGenerator）全 mock。
 * 验收核心（设计真源 §2.3）：post 在同一事务内 (1) 推状态机 (2) 取资金账户 glAccountCode
 * (3) 逐行校验供应商一致 + settlePayable (4) generateForPaymentDisbursement(借 220202 / 贷 glAccountCode)。
 */
class PaymentDisbursementAppServiceTest {

    private static final long SUPPLIER = 1L;
    private static final long OTHER_SUPPLIER = 999L;
    private static final long ACCOUNT_ID = 10L;
    private static final long PAYABLE_1 = 100L;
    private static final long PAYABLE_2 = 200L;
    private static final String GL_BANK = "1002";
    private static final LocalDate PAY_DATE = LocalDate.of(2026, 6, 14);
    private static final String OPERATOR = "tester";

    private PaymentDisbursementService paymentDisbursementService;
    private PaymentAccountService paymentAccountService;
    private AccountsPayableRepository payableRepository;
    private SettlementService settlementService;
    private AutoVoucherService autoVoucherService;
    private DocumentNumberGenerator numberGenerator;
    private PaymentDisbursementAppService appService;

    @BeforeEach
    void setUp() {
        paymentDisbursementService = Mockito.mock(PaymentDisbursementService.class);
        paymentAccountService = Mockito.mock(PaymentAccountService.class);
        payableRepository = Mockito.mock(AccountsPayableRepository.class);
        settlementService = Mockito.mock(SettlementService.class);
        autoVoucherService = Mockito.mock(AutoVoucherService.class);
        numberGenerator = Mockito.mock(DocumentNumberGenerator.class);
        appService = new PaymentDisbursementAppService(paymentDisbursementService, paymentAccountService,
                payableRepository, settlementService, autoVoucherService, numberGenerator);
    }

    // ===================================================== 建单

    @Test
    void 建单_自动PAYV编号_默认付款日今天_映射领域入参() {
        Mockito.when(numberGenerator.generate(any(DocumentNumberRule.class))).thenReturn("PAYV-202606-0001");
        Mockito.when(paymentDisbursementService.create(anyString(), anyLong(), anyLong(), any(), any(),
                any(), anyString())).thenReturn(Mockito.mock(PaymentDisbursement.class));

        appService.create(SUPPLIER, ACCOUNT_ID, null, "付货款",
                List.of(new PaymentDisbursementLineRequest(PAYABLE_1, new BigDecimal("300.00"))), OPERATOR);

        // 编号规则前缀 PAYV
        ArgumentCaptor<DocumentNumberRule> ruleCaptor = ArgumentCaptor.forClass(DocumentNumberRule.class);
        Mockito.verify(numberGenerator).generate(ruleCaptor.capture());
        assertEquals("PAYV", ruleCaptor.getValue().getPrefix());

        // paymentDate 为空 → 默认今天；docNo / lines 透传到领域服务
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaymentDisbursementLineInput>> linesCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(paymentDisbursementService).create(eq("PAYV-202606-0001"), eq(SUPPLIER),
                eq(ACCOUNT_ID), dateCaptor.capture(), eq("付货款"), linesCaptor.capture(), eq(OPERATOR));
        assertEquals(LocalDate.now(), dateCaptor.getValue());
        assertEquals(1, linesCaptor.getValue().size());
        assertEquals(PAYABLE_1, linesCaptor.getValue().get(0).payableId());
        assertEqualsDecimal("300.00", linesCaptor.getValue().get(0).allocatedAmount());
    }

    @Test
    void 建单_显式付款日透传() {
        Mockito.when(numberGenerator.generate(any(DocumentNumberRule.class))).thenReturn("PAYV-1");
        Mockito.when(paymentDisbursementService.create(anyString(), anyLong(), anyLong(), any(), any(),
                any(), anyString())).thenReturn(Mockito.mock(PaymentDisbursement.class));

        appService.create(SUPPLIER, ACCOUNT_ID, PAY_DATE, null,
                List.of(new PaymentDisbursementLineRequest(PAYABLE_1, new BigDecimal("100.00"))), OPERATOR);

        Mockito.verify(paymentDisbursementService).create(eq("PAYV-1"), eq(SUPPLIER), eq(ACCOUNT_ID),
                eq(PAY_DATE), any(), any(), eq(OPERATOR));
    }

    @Test
    void 建单_空行集合拒绝_不生成单号不调领域() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.create(SUPPLIER, ACCOUNT_ID, PAY_DATE, null, List.of(), OPERATOR));
        Mockito.verifyNoInteractions(numberGenerator, paymentDisbursementService);
    }

    @Test
    void 建单_行应付id为空拒绝() {
        Mockito.when(numberGenerator.generate(any(DocumentNumberRule.class))).thenReturn("PAYV-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> appService.create(SUPPLIER, ACCOUNT_ID, PAY_DATE, null,
                        List.of(new PaymentDisbursementLineRequest(null, new BigDecimal("100.00"))),
                        OPERATOR));
        assertTrue(ex.getMessage().contains("应付账款 id 不能为空"), ex.getMessage());
        Mockito.verifyNoInteractions(paymentDisbursementService);
    }

    // ===================================================== 过账编排（验收核心）

    @Test
    void 过账_逐行核销应付_再生成现金侧凭证_次序正确() {
        PaymentDisbursement posted = postedDisbursement(SUPPLIER,
                line(1, PAYABLE_1, "300.00"), line(2, PAYABLE_2, "150.00"));
        stubPost(posted);
        stubAccount(GL_BANK);
        stubPayable(PAYABLE_1, SUPPLIER);
        stubPayable(PAYABLE_2, SUPPLIER);

        PaymentDisbursement result = appService.post("PAYV-1", OPERATOR);
        assertSame(posted, result);

        // 逐行核销：两笔应付各核销一次，金额/业务日/付款单号/操作人正确
        Mockito.verify(settlementService).settlePayable(eq(PAYABLE_1), argEq("300.00"), eq(PAY_DATE),
                eq("PAYV-1"), eq(OPERATOR));
        Mockito.verify(settlementService).settlePayable(eq(PAYABLE_2), argEq("150.00"), eq(PAY_DATE),
                eq("PAYV-1"), eq(OPERATOR));
        // 现金侧凭证：传入已过账付款单 + 资金账户 glAccountCode + 操作人
        Mockito.verify(autoVoucherService).generateForPaymentDisbursement(posted, GL_BANK, OPERATOR);

        // 次序：先推状态机 post → 取资金账户 → 逐行核销 → 最后生成凭证
        InOrder inOrder = Mockito.inOrder(paymentDisbursementService, paymentAccountService,
                settlementService, autoVoucherService);
        inOrder.verify(paymentDisbursementService).post("PAYV-1", OPERATOR);
        inOrder.verify(paymentAccountService).get(ACCOUNT_ID);
        inOrder.verify(settlementService).settlePayable(eq(PAYABLE_1), any(), any(), any(), any());
        inOrder.verify(settlementService).settlePayable(eq(PAYABLE_2), any(), any(), any(), any());
        inOrder.verify(autoVoucherService).generateForPaymentDisbursement(posted, GL_BANK, OPERATOR);
    }

    @Test
    void 过账_跨供应商核销_整单拒绝_凭证不生成() {
        PaymentDisbursement posted = postedDisbursement(SUPPLIER, line(1, PAYABLE_1, "100.00"));
        stubPost(posted);
        stubAccount(GL_BANK);
        // 应付属于另一个供应商 → 对手方不一致
        stubPayable(PAYABLE_1, OTHER_SUPPLIER);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> appService.post("PAYV-1", OPERATOR));
        assertTrue(ex.getMessage().contains("禁止跨供应商核销"), ex.getMessage());

        // 跨供应商在 settlePayable 之前被拦：既不核销也不生成凭证（整单回滚由外层 @Transactional 保证）
        Mockito.verify(settlementService, Mockito.never())
                .settlePayable(anyLong(), any(), any(), anyString(), anyString());
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForPaymentDisbursement(any(), anyString(), anyString());
    }

    @Test
    void 过账_分摊行引用不存在应付_抛PayableNotFound_凭证不生成() {
        PaymentDisbursement posted = postedDisbursement(SUPPLIER, line(1, PAYABLE_1, "100.00"));
        stubPost(posted);
        stubAccount(GL_BANK);
        Mockito.when(payableRepository.findById(PAYABLE_1)).thenReturn(Optional.empty());

        assertThrows(PayableNotFoundException.class, () -> appService.post("PAYV-1", OPERATOR));

        Mockito.verify(settlementService, Mockito.never())
                .settlePayable(anyLong(), any(), any(), anyString(), anyString());
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForPaymentDisbursement(any(), anyString(), anyString());
    }

    @Test
    void 过账_超额核销由核销引擎抛OverSettlement_凭证不生成() {
        PaymentDisbursement posted = postedDisbursement(SUPPLIER, line(1, PAYABLE_1, "100.00"));
        stubPost(posted);
        stubAccount(GL_BANK);
        stubPayable(PAYABLE_1, SUPPLIER);
        // 核销引擎硬拒超额（不能付超过欠款）
        Mockito.when(settlementService.settlePayable(eq(PAYABLE_1), any(), any(), anyString(), anyString()))
                .thenThrow(new OverSettlementException(new BigDecimal("100.00"),
                        new BigDecimal("50.00"), new BigDecimal("80.00")));

        assertThrows(OverSettlementException.class, () -> appService.post("PAYV-1", OPERATOR));

        // 超额发生在核销阶段 → 现金侧凭证不生成（整单回滚）
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForPaymentDisbursement(any(), anyString(), anyString());
    }

    @Test
    void 过账_第二行跨供应商_第一行已核销但凭证仍不生成() {
        // 第一行供应商一致（会调 settlePayable），第二行供应商不一致（抛异常）→ 凭证不生成、整单回滚
        PaymentDisbursement posted = postedDisbursement(SUPPLIER,
                line(1, PAYABLE_1, "100.00"), line(2, PAYABLE_2, "50.00"));
        stubPost(posted);
        stubAccount(GL_BANK);
        stubPayable(PAYABLE_1, SUPPLIER);
        stubPayable(PAYABLE_2, OTHER_SUPPLIER);

        assertThrows(IllegalArgumentException.class, () -> appService.post("PAYV-1", OPERATOR));

        // 第一行核销已发生（事务尚未回滚，回滚由容器在异常抛出后执行——此处只验证编排路径）
        Mockito.verify(settlementService).settlePayable(eq(PAYABLE_1), any(), any(), anyString(), anyString());
        Mockito.verify(settlementService, Mockito.never())
                .settlePayable(eq(PAYABLE_2), any(), any(), anyString(), anyString());
        // 凭证一定不生成（凭证在所有行核销之后才生成）
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForPaymentDisbursement(any(), anyString(), anyString());
    }

    @Test
    void 过账_资金账户已停用_抛非法参数_核销与凭证均不发生() {
        PaymentDisbursement posted = postedDisbursement(SUPPLIER, line(1, PAYABLE_1, "100.00"));
        stubPost(posted);
        // 构造停用状态的资金账户
        PaymentAccount disabledAccount = PaymentAccount.restore(ACCOUNT_ID, "FA-1", "工行户",
                PaymentAccountType.BANK, GL_BANK, "工行", "62",
                com.sjherp.domain.common.ArchiveStatus.DISABLED,
                "alice", Instant.now(), "alice", Instant.now());
        Mockito.when(paymentAccountService.get(ACCOUNT_ID)).thenReturn(disabledAccount);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> appService.post("PAYV-1", OPERATOR));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());

        // 停用拦截在核销/凭证之前：两者均不应被调用
        Mockito.verify(settlementService, Mockito.never())
                .settlePayable(anyLong(), any(), any(), anyString(), anyString());
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForPaymentDisbursement(any(), anyString(), anyString());
    }

    @Test
    void 过账_资金账户不存在_状态机已推进但核销凭证不发生() {
        PaymentDisbursement posted = postedDisbursement(SUPPLIER, line(1, PAYABLE_1, "100.00"));
        stubPost(posted);
        Mockito.when(paymentAccountService.get(ACCOUNT_ID))
                .thenThrow(new com.sjherp.domain.fund.PaymentAccountNotFoundException("资金账户不存在: id=10"));

        assertThrows(RuntimeException.class, () -> appService.post("PAYV-1", OPERATOR));

        Mockito.verify(settlementService, Mockito.never())
                .settlePayable(anyLong(), any(), any(), anyString(), anyString());
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForPaymentDisbursement(any(), anyString(), anyString());
    }

    // ===================================================== 查询透传

    @Test
    void 按单号查透传领域服务() {
        PaymentDisbursement d = Mockito.mock(PaymentDisbursement.class);
        Mockito.when(paymentDisbursementService.get("PAYV-1")).thenReturn(d);
        assertSame(d, appService.get("PAYV-1"));
        Mockito.verify(paymentDisbursementService).get("PAYV-1");
    }

    // ===================================================== 工具

    private void stubPost(PaymentDisbursement posted) {
        Mockito.when(paymentDisbursementService.post("PAYV-1", OPERATOR)).thenReturn(posted);
    }

    private void stubAccount(String glAccountCode) {
        PaymentAccount account = PaymentAccount.restore(ACCOUNT_ID, "FA-1", "工行户",
                PaymentAccountType.BANK, glAccountCode, "工行", "62", com.sjherp.domain.common.ArchiveStatus.ENABLED,
                "alice", Instant.now(), "alice", Instant.now());
        Mockito.when(paymentAccountService.get(ACCOUNT_ID)).thenReturn(account);
    }

    private void stubPayable(long payableId, long supplierId) {
        AccountsPayable ap = AccountsPayable.restore(payableId, supplierId, new BigDecimal("1000.00"),
                "PINV-1", PAY_DATE, PayableStatus.OPEN, BigDecimal.ZERO, "alice", Instant.now());
        Mockito.when(payableRepository.findById(payableId)).thenReturn(Optional.of(ap));
    }

    /** 构造一张「已过账」付款单（直接用领域工厂建好并推进到 COMPLETED，供 post 编排断言其 getter） */
    private static PaymentDisbursement postedDisbursement(long supplierId, PaymentDisbursementLine... lines) {
        PaymentDisbursement d = PaymentDisbursement.create("PAYV-1", supplierId, ACCOUNT_ID, PAY_DATE,
                null, List.of(lines), OPERATOR);
        d.approve(OPERATOR);
        d.startExecution(OPERATOR);
        d.complete(OPERATOR);
        return d;
    }

    private static PaymentDisbursementLine line(int lineNo, long payableId, String amount) {
        return PaymentDisbursementLine.create(lineNo, payableId, new BigDecimal(amount));
    }

    private static BigDecimal argEq(String value) {
        return Mockito.argThat(actual -> actual != null && new BigDecimal(value).compareTo(actual) == 0);
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}

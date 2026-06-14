package com.sjherp.app.collection;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableService;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.receivable.ReceivableStatus;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementRecordRepository;
import com.sjherp.domain.settlement.SettlementService;
import com.sjherp.domain.settlement.SettlementType;

/**
 * 收款单应用服务编排单测（M4-T04b）：mock 全部依赖，验证 {@link CollectionReceiptAppService#post}
 * 的同事务编排原子性与调用契约（设计真源 §2.3）：
 * <ul>
 *   <li>逐行核销应收（以收款单号 docNo 为 paymentDocNo、收款日期为核销业务日、行金额为核销额）；</li>
 *   <li>对手方一致性：应收客户 != 收款单客户 → IllegalArgumentException（跨客户核销硬拒）；</li>
 *   <li>生成现金侧凭证（以资金账户 glAccountCode 为借方科目）；</li>
 *   <li>调用次序：先逐行 settleReceivable 后 generateForCollectionReceipt；</li>
 *   <li>超额核销由核销引擎抛 OverSettlementException → 异常上抛（外层事务回滚），凭证不生成。</li>
 * </ul>
 *
 * <p>不连真库、不验证事务回滚的持久化效果（@Transactional 由 Spring 容器保障，单测只验证编排逻辑与
 * 异常传播 + 调用契约）；建单自动编号委托 DocumentNumberGenerator 亦在此覆盖。
 */
class CollectionReceiptAppServiceTest {

    private static final long CUSTOMER = 7L;
    private static final long OTHER_CUSTOMER = 8L;
    private static final long PAYMENT_ACCOUNT = 3L;
    private static final long RECEIVABLE_A = 100L;
    private static final long RECEIVABLE_B = 200L;
    private static final String GL_ACCOUNT = "1002";
    private static final LocalDate D = LocalDate.of(2026, 6, 14);
    private static final String OPERATOR = "alice";
    private static final String DOC_NO = "RCPT-202606-0001";

    private CollectionReceiptService collectionReceiptService;
    private PaymentAccountService paymentAccountService;
    private ReceivableService receivableService;
    private SettlementService settlementService;
    private SettlementRecordRepository settlementRecordRepository;
    private AutoVoucherService autoVoucherService;
    private VoucherService voucherService;
    private VoucherAppService voucherAppService;
    private DocumentNumberGenerator numberGenerator;
    private CollectionReceiptAppService appService;

    @BeforeEach
    void setUp() {
        collectionReceiptService = Mockito.mock(CollectionReceiptService.class);
        paymentAccountService = Mockito.mock(PaymentAccountService.class);
        receivableService = Mockito.mock(ReceivableService.class);
        settlementService = Mockito.mock(SettlementService.class);
        settlementRecordRepository = Mockito.mock(SettlementRecordRepository.class);
        autoVoucherService = Mockito.mock(AutoVoucherService.class);
        voucherService = Mockito.mock(VoucherService.class);
        voucherAppService = Mockito.mock(VoucherAppService.class);
        numberGenerator = Mockito.mock(DocumentNumberGenerator.class);
        appService = new CollectionReceiptAppService(collectionReceiptService, paymentAccountService,
                receivableService, settlementService, settlementRecordRepository, autoVoucherService,
                voucherService, voucherAppService, numberGenerator);
    }

    // ----------------------------------------------------- 建单：自动编号 + 委托领域服务

    @Test
    void 建单自动编号并委托领域服务_默认收款日期为今天() {
        Mockito.when(numberGenerator.generate(CollectionReceiptAppService.COLLECTION_RECEIPT_RULE))
                .thenReturn(DOC_NO);
        CollectionReceipt stub = postedReceipt(CUSTOMER, DocumentStatus.DRAFT,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.create(eq(DOC_NO), eq(CUSTOMER), eq(PAYMENT_ACCOUNT),
                any(LocalDate.class), any(), any(), eq(OPERATOR))).thenReturn(stub);

        CollectionReceipt created = appService.create(CUSTOMER, PAYMENT_ACCOUNT, null, "回款",
                List.of(new CollectionDtos.CollectionReceiptLineRequest(RECEIVABLE_A,
                        new BigDecimal("300.00"))), OPERATOR);

        assertSame(stub, created);
        // 收款日期为空 → 默认今天（非 null 传入领域服务）
        ArgumentCaptor<LocalDate> dateCap = ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(collectionReceiptService).create(eq(DOC_NO), eq(CUSTOMER), eq(PAYMENT_ACCOUNT),
                dateCap.capture(), eq("回款"), any(), eq(OPERATOR));
        assertEquals(LocalDate.now(), dateCap.getValue());
    }

    @Test
    void 建单分摊行应收id为空拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.create(CUSTOMER, PAYMENT_ACCOUNT, D, null,
                        List.of(new CollectionDtos.CollectionReceiptLineRequest(null,
                                new BigDecimal("100.00"))), OPERATOR));
        Mockito.verifyNoInteractions(collectionReceiptService);
    }

    @Test
    void 建单空行集合拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> appService.create(CUSTOMER, PAYMENT_ACCOUNT, D, null, List.of(), OPERATOR));
        Mockito.verifyNoInteractions(collectionReceiptService, numberGenerator);
    }

    // ----------------------------------------------------- 过账：同事务编排

    @Test
    void 过账_逐行核销以单号为paymentDocNo_再生成现金侧凭证() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"), line(2, RECEIVABLE_B, "200.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT)).thenReturn(bankAccount());
        Mockito.when(receivableService.get(RECEIVABLE_A)).thenReturn(receivable(RECEIVABLE_A, CUSTOMER));
        Mockito.when(receivableService.get(RECEIVABLE_B)).thenReturn(receivable(RECEIVABLE_B, CUSTOMER));

        CollectionReceipt result = appService.post(DOC_NO, OPERATOR);

        assertSame(posted, result);
        // 逐行核销：金额=行分摊额、业务日=收款日期、paymentDocNo=收款单号、operator 透传
        Mockito.verify(settlementService).settleReceivable(eq(RECEIVABLE_A),
                argEq("300.00"), eq(D), eq(DOC_NO), eq(OPERATOR));
        Mockito.verify(settlementService).settleReceivable(eq(RECEIVABLE_B),
                argEq("200.00"), eq(D), eq(DOC_NO), eq(OPERATOR));
        // 现金侧凭证：以资金账户 glAccountCode 为借方、收款单为来源
        Mockito.verify(autoVoucherService).generateForCollectionReceipt(eq(posted), eq(GL_ACCOUNT),
                eq(OPERATOR));
    }

    @Test
    void 过账_先核销后凭证的调用次序() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT)).thenReturn(bankAccount());
        Mockito.when(receivableService.get(RECEIVABLE_A)).thenReturn(receivable(RECEIVABLE_A, CUSTOMER));

        appService.post(DOC_NO, OPERATOR);

        // 次序：状态机 post → 取资金账户 → settleReceivable → generateForCollectionReceipt
        InOrder inOrder = Mockito.inOrder(collectionReceiptService, settlementService, autoVoucherService);
        inOrder.verify(collectionReceiptService).post(DOC_NO, OPERATOR);
        inOrder.verify(settlementService).settleReceivable(eq(RECEIVABLE_A), argEq("300.00"),
                eq(D), eq(DOC_NO), eq(OPERATOR));
        inOrder.verify(autoVoucherService).generateForCollectionReceipt(eq(posted), eq(GL_ACCOUNT),
                eq(OPERATOR));
    }

    @Test
    void 过账_跨客户核销被拒_凭证不生成() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT)).thenReturn(bankAccount());
        // 应收属于另一客户 → 跨客户核销
        Mockito.when(receivableService.get(RECEIVABLE_A))
                .thenReturn(receivable(RECEIVABLE_A, OTHER_CUSTOMER));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> appService.post(DOC_NO, OPERATOR));
        assertTrue(ex.getMessage().contains("跨客户核销"), ex.getMessage());

        // 跨客户在核销之前拦截：既不核销、也不生成凭证（整单回滚由外层事务保障）
        Mockito.verifyNoInteractions(settlementService);
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForCollectionReceipt(any(), anyString(), anyString());
    }

    @Test
    void 过账_第二行跨客户_第一行已核销但凭证不生成() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"), line(2, RECEIVABLE_B, "200.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT)).thenReturn(bankAccount());
        Mockito.when(receivableService.get(RECEIVABLE_A)).thenReturn(receivable(RECEIVABLE_A, CUSTOMER));
        // 第二行应收属于另一客户
        Mockito.when(receivableService.get(RECEIVABLE_B))
                .thenReturn(receivable(RECEIVABLE_B, OTHER_CUSTOMER));

        assertThrows(IllegalArgumentException.class, () -> appService.post(DOC_NO, OPERATOR));

        // 第一行已核销，第二行抛错中断；凭证不生成（外层事务整体回滚，单测仅验证编排不再继续）
        Mockito.verify(settlementService).settleReceivable(eq(RECEIVABLE_A), argEq("300.00"),
                eq(D), eq(DOC_NO), eq(OPERATOR));
        Mockito.verify(settlementService, Mockito.never())
                .settleReceivable(eq(RECEIVABLE_B), any(), any(), any(), any());
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForCollectionReceipt(any(), anyString(), anyString());
    }

    @Test
    void 过账_超额核销由核销引擎抛错上抛_凭证不生成() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT)).thenReturn(bankAccount());
        Mockito.when(receivableService.get(RECEIVABLE_A)).thenReturn(receivable(RECEIVABLE_A, CUSTOMER));
        // 核销引擎硬拒超额（应收余额不足）
        Mockito.when(settlementService.settleReceivable(eq(RECEIVABLE_A), any(), any(), any(), any()))
                .thenThrow(new OverSettlementException(new BigDecimal("300.00"),
                        new BigDecimal("0.00"), new BigDecimal("100.00")));

        assertThrows(OverSettlementException.class, () -> appService.post(DOC_NO, OPERATOR));

        // 超额核销中断 → 凭证不生成（整单回滚）
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForCollectionReceipt(any(), anyString(), anyString());
    }

    @Test
    void 过账_资金账户已停用_抛非法参数_核销与凭证均不发生() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        // 构造停用状态的资金账户
        Instant now = Instant.now();
        PaymentAccount disabledAccount = PaymentAccount.restore(PAYMENT_ACCOUNT, "FA-1", "测试账户",
                PaymentAccountType.BANK, GL_ACCOUNT, null, null, ArchiveStatus.DISABLED,
                OPERATOR, now, OPERATOR, now);
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT)).thenReturn(disabledAccount);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> appService.post(DOC_NO, OPERATOR));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());

        // 停用拦截：核销与凭证均不应被调用
        Mockito.verifyNoInteractions(settlementService);
        Mockito.verify(autoVoucherService, Mockito.never())
                .generateForCollectionReceipt(any(), anyString(), anyString());
    }

    @Test
    void 过账_凭证以资金账户glAccountCode为借方科目() {
        CollectionReceipt posted = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.post(DOC_NO, OPERATOR)).thenReturn(posted);
        // 现金账户 glAccountCode=1001
        Mockito.when(paymentAccountService.get(PAYMENT_ACCOUNT))
                .thenReturn(account("1001", PaymentAccountType.CASH));
        Mockito.when(receivableService.get(RECEIVABLE_A)).thenReturn(receivable(RECEIVABLE_A, CUSTOMER));

        appService.post(DOC_NO, OPERATOR);

        Mockito.verify(autoVoucherService).generateForCollectionReceipt(eq(posted), eq("1001"),
                eq(OPERATOR));
    }

    // ----------------------------------------------------- 冲销编排（M4-T07c）

    @Test
    void 冲销_反查正向核销记录逐条unsettle_红冲现金侧凭证_原单转REVERSED_次序正确() {
        CollectionReceipt receipt = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"), line(2, RECEIVABLE_B, "200.00"));
        Mockito.when(collectionReceiptService.get(DOC_NO)).thenReturn(receipt);
        // 反查核销记录：两条正向（amount>0）
        Mockito.when(settlementRecordRepository.findByPaymentDocNo(DOC_NO)).thenReturn(List.of(
                SettlementRecord.record(SettlementType.RECEIVABLE, RECEIVABLE_A, "SINV-A",
                        new BigDecimal("300.00"), D, DOC_NO, OPERATOR),
                SettlementRecord.record(SettlementType.RECEIVABLE, RECEIVABLE_B, "SINV-B",
                        new BigDecimal("200.00"), D, DOC_NO, OPERATOR)));
        // 现金侧凭证：findBySourceDocNo 命中一张 COLLECTION_RECEIPT 凭证
        Voucher cashVoucher = Mockito.mock(Voucher.class);
        Mockito.when(cashVoucher.getSourceDocType()).thenReturn(VoucherSourceType.COLLECTION_RECEIPT.name());
        Mockito.when(cashVoucher.getDocNo()).thenReturn("VCH-1");
        Mockito.when(voucherService.findBySourceDocNo(DOC_NO)).thenReturn(List.of(cashVoucher));
        Voucher redVoucher = Mockito.mock(Voucher.class);
        Mockito.when(redVoucher.getDocNo()).thenReturn("VCH-RED-1");
        Mockito.when(voucherAppService.reverse("VCH-1", OPERATOR)).thenReturn(redVoucher);
        CollectionReceipt reversed = postedReceipt(CUSTOMER, DocumentStatus.REVERSED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.reverse(eq(DOC_NO), eq("VCH-RED-1"), eq(OPERATOR)))
                .thenReturn(reversed);

        CollectionReceipt result = appService.reverse(DOC_NO, OPERATOR);
        assertSame(reversed, result);

        // 逐条反向核销（金额=正向记录额、业务日=收款日、paymentDocNo=单号）
        Mockito.verify(settlementService).unsettleReceivable(eq(RECEIVABLE_A), argEq("300.00"),
                eq(D), eq(DOC_NO), eq(OPERATOR));
        Mockito.verify(settlementService).unsettleReceivable(eq(RECEIVABLE_B), argEq("200.00"),
                eq(D), eq(DOC_NO), eq(OPERATOR));
        // 红冲现金侧凭证用红字号回传领域 reverse
        Mockito.verify(voucherAppService).reverse("VCH-1", OPERATOR);
        Mockito.verify(collectionReceiptService).reverse(DOC_NO, "VCH-RED-1", OPERATOR);

        // 次序：先逐条 unsettle → 红冲凭证 → 原单 reverse
        InOrder inOrder = Mockito.inOrder(settlementService, voucherAppService, collectionReceiptService);
        inOrder.verify(settlementService).unsettleReceivable(eq(RECEIVABLE_A), any(), any(), any(), any());
        inOrder.verify(settlementService).unsettleReceivable(eq(RECEIVABLE_B), any(), any(), any(), any());
        inOrder.verify(voucherAppService).reverse("VCH-1", OPERATOR);
        inOrder.verify(collectionReceiptService).reverse(DOC_NO, "VCH-RED-1", OPERATOR);
    }

    @Test
    void 冲销_只对正向核销记录unsettle_负额反向记录跳过() {
        // 多次红冲场景：同 paymentDocNo 同时含正向与负额记录——只对正向 unsettle，避免对自己追加的负额二次反向
        CollectionReceipt receipt = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.get(DOC_NO)).thenReturn(receipt);
        Mockito.when(settlementRecordRepository.findByPaymentDocNo(DOC_NO)).thenReturn(List.of(
                SettlementRecord.record(SettlementType.RECEIVABLE, RECEIVABLE_A, "SINV-A",
                        new BigDecimal("300.00"), D, DOC_NO, OPERATOR),
                SettlementRecord.recordReversal(SettlementType.RECEIVABLE, RECEIVABLE_A, "SINV-A",
                        new BigDecimal("-300.00"), D, DOC_NO, OPERATOR)));
        // 现金侧凭证命中（已过账收款单必有，reverseAutoVoucher 无凭证现抛账证不符异常）
        Voucher cashVoucher = Mockito.mock(Voucher.class);
        Mockito.when(cashVoucher.getSourceDocType()).thenReturn(VoucherSourceType.COLLECTION_RECEIPT.name());
        Mockito.when(cashVoucher.getDocNo()).thenReturn("VCH-1");
        Mockito.when(voucherService.findBySourceDocNo(DOC_NO)).thenReturn(List.of(cashVoucher));
        Voucher redVoucher = Mockito.mock(Voucher.class);
        Mockito.when(redVoucher.getDocNo()).thenReturn("VCH-RED-1");
        Mockito.when(voucherAppService.reverse("VCH-1", OPERATOR)).thenReturn(redVoucher);
        Mockito.when(collectionReceiptService.reverse(eq(DOC_NO), anyString(), eq(OPERATOR)))
                .thenReturn(postedReceipt(CUSTOMER, DocumentStatus.REVERSED, line(1, RECEIVABLE_A, "300.00")));

        appService.reverse(DOC_NO, OPERATOR);

        // 只对正向记录 unsettle 一次（负额记录被 signum()>0 过滤）
        Mockito.verify(settlementService, Mockito.times(1))
                .unsettleReceivable(eq(RECEIVABLE_A), argEq("300.00"), eq(D), eq(DOC_NO), eq(OPERATOR));
        Mockito.verifyNoMoreInteractions(settlementService);
    }

    @Test
    void 冲销_无现金侧凭证_抛账证不符异常_不转REVERSED() {
        // 已过账收款单必有现金侧凭证；缺失即账证不符（异常数据）→ 抛 IllegalStateException 整事务回滚，
        // 绝不静默把单标 REVERSED 而无红字凭证（评审 P3，账证一致红线）
        CollectionReceipt receipt = postedReceipt(CUSTOMER, DocumentStatus.COMPLETED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.get(DOC_NO)).thenReturn(receipt);
        Mockito.when(settlementRecordRepository.findByPaymentDocNo(DOC_NO)).thenReturn(List.of());
        Mockito.when(voucherService.findBySourceDocNo(DOC_NO)).thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> appService.reverse(DOC_NO, OPERATOR));
        assertTrue(ex.getMessage().contains("账证不符") || ex.getMessage().contains("无对应现金侧"),
                ex.getMessage());
        // 未转 REVERSED（整事务回滚，不调领域 reverse）
        Mockito.verify(collectionReceiptService, Mockito.never())
                .reverse(anyString(), anyString(), anyString());
    }

    @Test
    void 冲销_原单已REVERSED_前置守门拒_未触反向核销() {
        // 前置状态守门（评审 P2）：已 REVERSED 直接拒，不触达反向核销/凭证红冲/领域 reverse
        CollectionReceipt receipt = postedReceipt(CUSTOMER, DocumentStatus.REVERSED,
                line(1, RECEIVABLE_A, "300.00"));
        Mockito.when(collectionReceiptService.get(DOC_NO)).thenReturn(receipt);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> appService.reverse(DOC_NO, OPERATOR));
        assertTrue(ex.getMessage().contains("已冲销"), ex.getMessage());
        Mockito.verifyNoInteractions(settlementService);
        Mockito.verify(collectionReceiptService, Mockito.never())
                .reverse(anyString(), anyString(), anyString());
    }

    // ----------------------------------------------------- 审核 / 查询委托

    @Test
    void 审核委托领域服务() {
        CollectionReceipt approved = postedReceipt(CUSTOMER, DocumentStatus.APPROVED,
                line(1, RECEIVABLE_A, "100.00"));
        Mockito.when(collectionReceiptService.approve(DOC_NO, OPERATOR)).thenReturn(approved);
        assertSame(approved, appService.approve(DOC_NO, OPERATOR));
    }

    @Test
    void 查询委托领域服务() {
        CollectionReceipt receipt = postedReceipt(CUSTOMER, DocumentStatus.DRAFT,
                line(1, RECEIVABLE_A, "100.00"));
        Mockito.when(collectionReceiptService.get(DOC_NO)).thenReturn(receipt);
        assertSame(receipt, appService.get(DOC_NO));
    }

    // ----------------------------------------------------- 工具

    /** 按 restore 工厂重建一张收款单 stub（指定客户与状态） */
    private static CollectionReceipt postedReceipt(long customerId, DocumentStatus status,
                                                   CollectionReceiptLine... lines) {
        return CollectionReceipt.restore(DOC_NO, customerId, PAYMENT_ACCOUNT, D, null, status,
                List.of(lines), OPERATOR);
    }

    private static CollectionReceiptLine line(int lineNo, long receivableId, String amount) {
        return CollectionReceiptLine.create(lineNo, receivableId, new BigDecimal(amount));
    }

    private static AccountsReceivable receivable(long id, long customerId) {
        return AccountsReceivable.restore(id, customerId, new BigDecimal("1000.00"),
                new BigDecimal("0.00"), "SINV-" + id, D, ReceivableStatus.OPEN, OPERATOR);
    }

    private static PaymentAccount bankAccount() {
        return account(GL_ACCOUNT, PaymentAccountType.BANK);
    }

    private static PaymentAccount account(String glAccountCode, PaymentAccountType type) {
        Instant now = Instant.now();
        return PaymentAccount.restore(PAYMENT_ACCOUNT, "FA-1", "测试账户", type, glAccountCode,
                null, null, ArchiveStatus.ENABLED, OPERATOR, now, OPERATOR, now);
    }

    /** BigDecimal 按值（compareTo）匹配，避免标度差异（300.00 vs 300）导致 equals 不命中 */
    private static BigDecimal argEq(String expected) {
        BigDecimal target = new BigDecimal(expected);
        return Mockito.argThat(actual -> actual != null && target.compareTo(actual) == 0);
    }
}

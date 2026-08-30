package com.sjherp.app.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.sales.SalesDtos.SalesInvoiceLineRequest;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLineInput;
import com.sjherp.domain.sales.SalesInvoiceQuery;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderService;

/**
 * 销售发票应用服务（M3-T10）：REST {@code SalesInvoiceController} 的入口。
 *
 * <p>职责：
 * <ul>
 *   <li>建单：自动 SINV- 编号；客户 id 从关联出库单的订单推导（保证一致），其余开票数量校验
 *       在领域 {@link SalesInvoiceService#create}；</li>
 *   <li>审核 / 过账 / 作废：委托领域服务；</li>
 *   <li><b>外层事务</b>：过账写方法标 {@code @Transactional}，把发票状态变更 + 应收挂账
 *       （经 {@link com.sjherp.domain.sales.ReceivablePostingPort}，REQUIRED 加入本事务）
 *       包成一个原子事务。</li>
 * </ul>
 *
 * <p>客户 id 由出库单 → 销售订单链路推导：发票头客户必须等于其引用出库单关联订单的客户，
 * 避免开票挂错客户。
 */
@Service
public class SalesInvoiceAppService {

    /** 发票编号规则：SINV-202606-0001 */
    static final DocumentNumberRule SINV_RULE = DocumentNumberRule.of("SINV");

    private final SalesInvoiceService salesInvoiceService;
    private final SalesDeliveryService salesDeliveryService;
    private final SalesOrderService salesOrderService;
    private final DocumentNumberGenerator numberGenerator;
    private final AutoVoucherService autoVoucherService;
    private final VoucherService voucherService;
    private final VoucherAppService voucherAppService;

    public SalesInvoiceAppService(SalesInvoiceService salesInvoiceService,
                                  SalesDeliveryService salesDeliveryService,
                                  SalesOrderService salesOrderService,
                                  DocumentNumberGenerator numberGenerator,
                                  AutoVoucherService autoVoucherService,
                                  VoucherService voucherService,
                                  VoucherAppService voucherAppService) {
        this.salesInvoiceService = Objects.requireNonNull(salesInvoiceService, "salesInvoiceService 不能为空");
        this.salesDeliveryService = Objects.requireNonNull(salesDeliveryService, "salesDeliveryService 不能为空");
        this.salesOrderService = Objects.requireNonNull(salesOrderService, "salesOrderService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService, "autoVoucherService 不能为空");
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.voucherAppService = Objects.requireNonNull(voucherAppService, "voucherAppService 不能为空");
    }

    /**
     * 创建销售发票（草稿）：自动 SINV- 编号；客户 id 从关联出库单的订单推导。
     *
     * @param salesDeliveryNo 引用的销售出库单号（必须存在且已过账）
     * @param invoiceDate     开票日期（空则取当天）
     * @param dueDate         到期日（可空）
     * @param remark          发票说明（可空）
     * @param lines           行输入（关联出库行号 + 商品 + 开票数量 + 单价）
     * @param operator        操作人
     */
    @Transactional
    public SalesInvoice create(String salesDeliveryNo, LocalDate invoiceDate, LocalDate dueDate,
                               String remark, List<SalesInvoiceLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("销售发票至少要有一行");
        }
        // 客户 id 从出库单 → 销售订单链路推导（保证挂应收的客户正确）
        SalesDelivery delivery = salesDeliveryService.get(salesDeliveryNo);
        long customerId = salesOrderService.get(delivery.getSalesOrderNo()).getCustomerId();

        List<SalesInvoiceLineInput> domainLines = new ArrayList<>(lines.size());
        for (SalesInvoiceLineRequest input : lines) {
            if (input.deliveryLineNo() == null) {
                throw new IllegalArgumentException("发票行关联出库行号 deliveryLineNo 不能为空");
            }
            if (input.productId() == null) {
                throw new IllegalArgumentException("发票行商品 id 不能为空");
            }
            domainLines.add(new SalesInvoiceLineInput(input.deliveryLineNo(), input.productId(),
                    input.quantity(), input.unitPrice()));
        }
        LocalDate effectiveDate = invoiceDate != null ? invoiceDate : LocalDate.now();
        String docNo = numberGenerator.generate(SINV_RULE);
        return salesInvoiceService.create(docNo, salesDeliveryNo, customerId, effectiveDate, dueDate,
                remark, domainLines, operator);
    }

    /** 审核发票（DRAFT → APPROVED） */
    @Transactional
    public SalesInvoice approve(String docNo, String operator) {
        return salesInvoiceService.approve(docNo, operator);
    }

    /**
     * 过账发票（APPROVED → EXECUTING → COMPLETED，生成应收 OPEN）；
     * 同事务内自动生成记账凭证（借 1122 应收账款 / 贷 6001 主营业务收入，T02）。
     */
    @Transactional
    public SalesInvoice post(String docNo, String operator) {
        SalesInvoice invoice = salesInvoiceService.post(docNo, operator);
        autoVoucherService.generateForSalesInvoice(invoice, operator);   // T02 自动凭证
        return invoice;
    }

    /**
     * 冲销已过账发票（红字发票，M4-T07b）：同一 {@code @Transactional} 内
     * ①红冲发票自动凭证（借贷对调 SALES_INVOICE 凭证，{@link VoucherAppService#reverse}——内含账期 OPEN
     * 校验，闭月时抛 PeriodClosedException 使整单回滚）→②应收整笔冲回（前置校验无核销，已核销引导先冲收款单）
     * + 回退出库行已开票量 + 单据 COMPLETED → REVERSED（{@link SalesInvoiceService#reverse}）。
     *
     * <p>顺序：先红冲凭证拿到红字号作锚点，再驱动应收/出库/单据反向；任一步失败整事务回滚（设计真源 §2）。
     * 带核销的发票其应收 {@code canBeReversed()=false}，在 domain reverse 内抛 IllegalStateException 整事务回滚。
     */
    @Transactional
    public SalesInvoice reverse(String docNo, String operator) {
        // ① 先锁发票头并校验 COMPLETED；避免与尚未提交的 post 竞态时先建立缺应收/凭证的旧快照。
        salesInvoiceService.lockForReverse(docNo);
        // ② 红冲发票自动凭证（按来源单据号反查 SALES_INVOICE 凭证）→ 红字号作冲销链路锚点
        String reversalAnchor = reverseAutoVoucher(docNo, operator);
        // ③ 应收冲回 + 回退开票量 + 单据冲销（同事务原子；带核销在此步硬拒回滚）
        return salesInvoiceService.reverse(docNo, reversalAnchor, operator);
    }

    /**
     * 红冲某发票的自动凭证（SALES_INVOICE 来源），返回冲销链路锚点（红字凭证号）。
     * 发票额恒 &gt; 0 必有自动凭证；防御性：若未命中（异常态）退化为原单号作锚点。
     */
    private String reverseAutoVoucher(String docNo, String operator) {
        Voucher source = voucherService.findBySourceDocNo(docNo).stream()
                .filter(v -> VoucherSourceType.SALES_INVOICE.name().equals(v.getSourceDocType()))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return docNo;
        }
        return voucherAppService.reverse(source.getDocNo(), operator).getDocNo();
    }

    /** 作废发票（仅 DRAFT 可作废） */
    @Transactional
    public SalesInvoice cancel(String docNo, String operator) {
        return salesInvoiceService.cancel(docNo, operator);
    }

    /** 按单据号查（不存在抛 SalesInvoiceNotFoundException → 404） */
    @Transactional(readOnly = true)
    public SalesInvoice get(String docNo) {
        return salesInvoiceService.get(docNo);
    }

    /** 分页查询（按客户/出库单/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<SalesInvoice> search(SalesInvoiceQuery query) {
        return salesInvoiceService.search(query);
    }
}

package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 销售发票领域服务（M3-T10，路线图 §5 销售线）。
 *
 * <p>所有发票写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖发票仓储端口
 * {@link SalesInvoiceRepository}、出库单服务 {@link SalesDeliveryService}（取出库单做开票数量
 * 校验）、应收能力端口 {@link ReceivablePostingPort}，由 app 层装配并把发票状态变更 + 应收挂账
 * 包进同一外层事务（@Transactional）。
 *
 * <h2>建单校验</h2>
 * <ul>
 *   <li>引用的出库单必须存在且<b>已过账（COMPLETED）</b>——未发货不能开票；</li>
 *   <li>每个发票行的关联出库行必须存在、商品一致；</li>
 *   <li>累计开票量 ≤ 出库行已发货数量——<b>跨发票累计</b>校验（出库行 invoicedQty 记录历史累计
 *       开票量，本发票内多行引用同一出库行再叠加）。防跨发票超额开票虚增应收（CLAUDE.md 原则 2）。</li>
 * </ul>
 *
 * <h2>过账</h2>
 * 审核（APPROVED）后过账：单据 EXECUTING → COMPLETED，<b>同事务回写出库行累计已开票量</b>
 * （{@link SalesDeliveryService#recordInvoiced}，守门累计开票 ≤ 发货量），按发票金额经
 * {@link ReceivablePostingPort#open} 生成应收（OPEN，客户取发票头客户，到期日透传），同事务原子提交。
 */
public class SalesInvoiceService {

    private final SalesInvoiceRepository repository;
    private final SalesDeliveryService salesDeliveryService;
    private final ReceivablePostingPort receivable;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计（app 装配 SyncDomainEventPublisher） */
    private final DomainEventPublisher eventPublisher;

    public SalesInvoiceService(SalesInvoiceRepository repository,
                               SalesDeliveryService salesDeliveryService,
                               ReceivablePostingPort receivable, DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.salesDeliveryService = Objects.requireNonNull(salesDeliveryService, "salesDeliveryService 不能为空");
        this.receivable = Objects.requireNonNull(receivable, "receivable 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建销售发票（草稿）：引用某已过账出库单对已发货商品开票。
     *
     * @param docNo           单据号（SINV-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param salesDeliveryNo 引用的销售出库单号（必须存在且已过账）
     * @param customerId      客户 id
     * @param invoiceDate     开票日期
     * @param dueDate         到期日（可空）
     * @param remark          发票说明（可空）
     * @param lines           行输入（关联出库行号 + 商品 + 开票数量 + 单价）
     * @param operator        操作人
     */
    @Audited(action = "sales_invoice.create", targetType = "sales_invoice")
    public SalesInvoice create(String docNo, String salesDeliveryNo, long customerId,
                               LocalDate invoiceDate, LocalDate dueDate, String remark,
                               List<SalesInvoiceLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(salesDeliveryNo, "关联出库单号不能为空");
        Objects.requireNonNull(lines, "发票行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("销售发票至少要有一行");
        }
        // 关联出库单必须存在且已过账（已发货才能开票）
        SalesDelivery delivery = salesDeliveryService.get(salesDeliveryNo);
        if (delivery.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalArgumentException("销售出库单[" + salesDeliveryNo + "] 当前状态 "
                    + delivery.getStatus() + " 未过账（未发货），不能开票");
        }

        // 跨发票累计校验「累计开票量 ≤ 已发货量」：起点取出库行已开票量 invoicedQty（跨发票累计），
        // 本发票内多行引用同一出库行再叠加，守住「应收不超实发」红线（CLAUDE.md 原则 2，
        // 防跨发票超额开票虚增应收）。
        Map<Integer, BigDecimal> invoicedByDeliveryLine = new LinkedHashMap<>();
        List<SalesInvoiceLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (SalesInvoiceLineInput input : lines) {
            SalesDeliveryLine deliveryLine = deliveryLineByNo(delivery, input.deliveryLineNo());
            if (deliveryLine.getProductId() != input.productId()) {
                throw new IllegalArgumentException("发票行商品（" + input.productId()
                        + "）与出库行 " + input.deliveryLineNo() + " 商品（"
                        + deliveryLine.getProductId() + "）不一致");
            }
            SalesInvoiceLine invoiceLine = SalesInvoiceLine.create(lineNo++, input.deliveryLineNo(),
                    input.productId(), input.quantity(), input.unitPrice());
            BigDecimal cumulative = invoicedByDeliveryLine
                    .getOrDefault(input.deliveryLineNo(), deliveryLine.getInvoicedQty())
                    .add(invoiceLine.getQuantity());
            if (cumulative.compareTo(deliveryLine.getQuantity()) > 0) {
                throw new IllegalArgumentException("出库行 " + input.deliveryLineNo() + "（商品 "
                        + deliveryLine.getProductId() + "）累计开票 " + cumulative.toPlainString()
                        + " 超过已发货数量 " + deliveryLine.getQuantity().toPlainString()
                        + "（含跨发票累计）");
            }
            invoicedByDeliveryLine.put(input.deliveryLineNo(), cumulative);
            domainLines.add(invoiceLine);
        }

        SalesInvoice invoice = SalesInvoice.create(docNo, salesDeliveryNo, customerId, invoiceDate,
                dueDate, remark, domainLines, operator);
        invoice.registerEventPublisher(eventPublisher);
        repository.save(invoice);
        return invoice;
    }

    /** 审核发票：DRAFT → APPROVED（业务内容自此锁定）。 */
    @Audited(action = "sales_invoice.approve", targetType = "sales_invoice")
    public SalesInvoice approve(String docNo, String operator) {
        requireOperator(operator);
        SalesInvoice invoice = get(docNo);
        invoice.registerEventPublisher(eventPublisher);
        invoice.approve(operator);
        repository.save(invoice);
        return invoice;
    }

    /** 作废发票：仅 DRAFT 可作废（未挂应收）。 */
    @Audited(action = "sales_invoice.cancel", targetType = "sales_invoice")
    public SalesInvoice cancel(String docNo, String operator) {
        requireOperator(operator);
        SalesInvoice invoice = get(docNo);
        invoice.registerEventPublisher(eventPublisher);
        invoice.cancel(operator);
        repository.save(invoice);
        return invoice;
    }

    /**
     * 过账发票：APPROVED → EXECUTING → COMPLETED，按发票金额生成应收（OPEN）。
     *
     * <p>调用方（app 装配的本服务）须以外层事务包住「状态流转 + 应收挂账」，二者原子提交。
     * 应收挂账同来源单据号幂等（应收服务保证），发票过账重试安全不重复挂账。
     */
    @Audited(action = "sales_invoice.post", targetType = "sales_invoice")
    public SalesInvoice post(String docNo, String operator) {
        requireOperator(operator);
        SalesInvoice invoice = get(docNo);
        invoice.registerEventPublisher(eventPublisher);
        invoice.startExecution(operator);
        // 同事务回写出库行累计已开票量（守门累计开票 ≤ 发货量，防跨发票超额虚增应收）；
        // 状态机保证每张发票只过账一次，故回写恰好一次。
        salesDeliveryService.recordInvoiced(invoice.getSalesDeliveryNo(), buildInvoicedLines(invoice));
        // 按发票金额挂应收（OPEN）；客户取发票头客户，到期日透传
        receivable.open(invoice.getCustomerId(), invoice.totalAmount(), invoice.getDocNo(),
                invoice.getDueDate(), operator);
        invoice.complete(operator);
        repository.save(invoice);
        return invoice;
    }

    /**
     * 冲销已完成发票（M4-T07b）：<b>应收整笔冲回 + 回退出库行累计已开票量 + 单据 COMPLETED → REVERSED</b>，
     * 三者在同一外层事务原子完成（与 {@link #post} 对称——post 做回写开票量+挂应收+状态推进，
     * 本方法做应收冲回+回退开票量+冲销）。
     *
     * <p><b>前置校验「应收无核销」</b>（设计真源 §1.7/§2）：先 {@link ReceivablePostingPort#reverse}
     * 整笔冲回应收（OPEN → REVERSED），已（部分）核销的发票其应收 {@code canBeReversed()=false} →
     * {@link IllegalStateException} 整事务回滚，引导先冲对应收款单（T07c），不做带核销的递归级联（保模型不破碎）。
     * 通过后再回退出库行 {@code invoicedQty}（{@link SalesDeliveryService#reverseInvoiced}，下溢守门）。
     *
     * <p>红冲发票自动凭证（借贷对调 SALES_INVOICE 凭证）由 app 层 {@code SalesInvoiceAppService.reverse}
     * 在同一 {@code @Transactional} 内追加（设计真源 §2），红字凭证号作冲销链路锚点。
     *
     * @param docNo         被冲销的发票号（须 COMPLETED）
     * @param reversalDocNo 红字关联锚点（发票自动凭证的红字凭证号）
     * @param operator      操作人
     * @return 已冲销（REVERSED）的发票
     */
    @Audited(action = "sales_invoice.reverse", targetType = "sales_invoice")
    public SalesInvoice reverse(String docNo, String reversalDocNo, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(reversalDocNo, "红字关联锚点 reversalDocNo 不能为空");
        SalesInvoice invoice = get(docNo);
        // 仅已过账（COMPLETED）发票可冲销——只有它挂了应收 + 回写了已开票量，才有反向对象。
        // 其余状态提前抛流转异常（与 BusinessDocument.reverse 流转表一致）。
        if (invoice.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalStateTransitionException(docNo, invoice.getStatus(),
                    DocumentStatus.REVERSED);
        }
        invoice.registerEventPublisher(eventPublisher);
        // ① 应收整笔冲回（前置校验无核销，已核销引导先冲对应收款单）——先冲应收再回退开票量，
        //    带核销时此步即抛 IllegalStateException 整事务回滚，不留半截副作用
        receivable.reverse(invoice.getDocNo(), operator);
        // ② 回退出库行累计已开票量（与 recordInvoiced 对称，下溢守门）
        salesDeliveryService.reverseInvoiced(invoice.getSalesDeliveryNo(), buildInvoicedLines(invoice));
        // ③ 单据冲销（COMPLETED → REVERSED + 回填红字关联）
        invoice.reverse(operator, reversalDocNo);
        repository.save(invoice);
        return invoice;
    }

    /** 按单据号查（不存在抛 {@link SalesInvoiceNotFoundException} → API 404） */
    public SalesInvoice get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new SalesInvoiceNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<SalesInvoice> search(SalesInvoiceQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    /** 开票回写行：发票各行的关联出库行号 → 开票数量（供过账同事务回写出库行累计已开票量） */
    private static List<SalesDeliveryService.InvoicedLine> buildInvoicedLines(SalesInvoice invoice) {
        List<SalesDeliveryService.InvoicedLine> invoiced = new ArrayList<>(invoice.getLines().size());
        for (SalesInvoiceLine line : invoice.getLines()) {
            invoiced.add(new SalesDeliveryService.InvoicedLine(line.getDeliveryLineNo(), line.getQuantity()));
        }
        return invoiced;
    }

    private static SalesDeliveryLine deliveryLineByNo(SalesDelivery delivery, int lineNo) {
        return delivery.getLines().stream().filter(line -> line.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("销售出库单[" + delivery.getDocNo()
                        + "] 不存在行号 " + lineNo));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}

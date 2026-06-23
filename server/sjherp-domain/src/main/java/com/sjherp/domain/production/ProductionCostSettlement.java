package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 月末成本结转单聚合根（M5-T06，PC- 前缀，全项目最难财务点）。
 *
 * <p>一张结转单管一个账期（{@link #period}，CHAR(6) yyyyMM）的全部待结转工单（D5），
 * 每工单一行（{@link ProductionCostSettlementLine}）。走 {@link BusinessDocument} 状态机
 * （DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED），不覆写 beforeTransition
 * （使用基类六态全表，同 ProductionReport）。过账 = APPROVED → EXECUTING → COMPLETED 双步，
 * 由 {@link ProductionCostSettlementService#post} 编排：每行 CostAdjustCommand 追加完工工费增量
 * 到产成品仓 → 出 GL（料/工费归集 + 完工结转）→ 回填 costAdjustIdemKey/voucherDocNo。
 *
 * <p>本批不实现 COMPLETED → REVERSED 冲销（结转单冲销留后续，R-T06-6）。
 */
public final class ProductionCostSettlement extends BusinessDocument implements AuditTarget {

    /** 数据库自增主键（建单时为 null，仓储 save 后回填） */
    private Long id;

    /** 账期键 yyyyMM（CHAR(6)，过账时校验 OPEN） */
    private final String period;

    /** 备注（可空） */
    private final String remark;

    /** 结转行（每工单一行；建单后行集合不变） */
    private final List<ProductionCostSettlementLine> lines;

    private ProductionCostSettlement(String docNo, String period, String remark,
                                     List<ProductionCostSettlementLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.period = Objects.requireNonNull(period, "账期不能为空");
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建月末成本结转单（草稿）。
     *
     * @param docNo    单号（PC- 前缀，由 DocumentNumberGenerator 生成）
     * @param period   账期键 yyyyMM
     * @param remark   备注（可空）
     * @param lines    结转行（每工单一行，至少一行；行号单据内唯一）
     * @param operator 创建人
     */
    public static ProductionCostSettlement create(String docNo, String period, String remark,
                                                  List<ProductionCostSettlementLine> lines,
                                                  String operator) {
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(period, "账期不能为空");
        Objects.requireNonNull(lines, "结转行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("成本结转单至少要有一行（本期无待结转工单）");
        }
        List<Integer> lineNos = lines.stream()
                .map(ProductionCostSettlementLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("成本结转单行号不能重复");
        }
        List<String> woNos = lines.stream()
                .map(ProductionCostSettlementLine::getWorkOrderDocNo).distinct().toList();
        if (woNos.size() != lines.size()) {
            throw new IllegalArgumentException("成本结转单同一工单不能出现多行");
        }
        return new ProductionCostSettlement(docNo, period, remark, lines, operator);
    }

    /** 持久层重建工厂（完整签名，含 id / 冲销链路，不重跑业务校验）。 */
    public static ProductionCostSettlement restore(long id, String docNo, String period, String remark,
                                                   DocumentStatus status,
                                                   String reversalOfId, String reversedById,
                                                   List<ProductionCostSettlementLine> lines,
                                                   String createdBy, String updatedBy) {
        ProductionCostSettlement s = new ProductionCostSettlement(docNo, period, remark, lines, createdBy);
        s.id = id;
        s.restoreStatus(status);
        s.restoreReversalLinks(reversalOfId, reversedById);
        return s;
    }

    /** 数据库自增主键回填（仅供仓储层调用） */
    public void assignId(long id) {
        this.id = id;
    }

    public Long getId() { return id; }
    public String getPeriod() { return period; }
    public String getRemark() { return remark; }

    /** 行只读视图（防外部增删行；行对象自身的回填方法仍可被领域服务调用） */
    public List<ProductionCostSettlementLine> getLines() { return List.copyOf(lines); }

    // ---------------------------------------------------------------- AuditTarget

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return getDocNo(); }

    @Override
    public String auditSummary() {
        BigDecimal totalIncremental = lines.stream()
                .map(ProductionCostSettlementLine::completedLaborOverhead)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return "账期=" + period + ", 状态=" + getStatus() + ", 工单行数=" + lines.size()
                + ", 完工工费合计=" + totalIncremental.toPlainString()
                + ", 说明=" + AuditTarget.text(remark);
    }
}

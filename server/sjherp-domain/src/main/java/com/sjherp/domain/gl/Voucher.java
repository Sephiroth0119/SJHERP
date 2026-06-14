package com.sjherp.domain.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 会计凭证（记账凭证）聚合根（M4-T01，全系统最高风险的财务核心）。
 *
 * <p>继承 {@link BusinessDocument} 状态机。凭证头固定属于一个账期 {@link #period}（yyyyMM），
 * 凭证日期 {@link #voucherDate} 落在账期内（由 {@link VoucherService} 校验）；行项目
 * （{@link VoucherLine}）逐行记借或记贷，<b>保存即校验借贷平衡</b>。
 *
 * <h2>状态映射（拆解 §3）</h2>
 * 凭证过账是原子记账动作，<b>不使用 EXECUTING / COMPLETED</b>：
 * <ul>
 *   <li>DRAFT：草稿；</li>
 *   <li>APPROVED：已过账（{@link #post} 经 {@code changeStatus(APPROVED)} 一步流转）；</li>
 *   <li>REVERSED：已冲销（APPROVED→REVERSED 承载"过账后只可冲销不可改"，红字实现留 T07）；</li>
 *   <li>CANCELLED：草稿作废。</li>
 * </ul>
 *
 * <h2>平衡不变式（验收①，CLAUDE.md 原则 2）</h2>
 * {@link #create} 工厂强制：①≥2 行；②行号唯一；③Σ借 == Σ贷（{@link BigDecimal#compareTo}==0）；
 * ④总额 &gt; 0。违反抛 {@link VoucherNotBalancedException}——不平凭证连聚合都构造不出，到不了仓储。
 * {@link #restore} 工厂不重跑校验（历史已合法）。
 */
public final class Voucher extends BusinessDocument implements AuditTarget {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 所属账期键 yyyyMM */
    private final String period;

    /** 凭证日期（业务日期，须落在账期内） */
    private final LocalDate voucherDate;

    /** 凭证字（默认"记"，供打印；中文不兼容 DocumentNumberRule [A-Z] 前缀，故单列存储） */
    private final String word;

    /** 凭证总额 = Σ借（= Σ贷，落库前已校验，2 位小数） */
    private final BigDecimal totalAmount;

    /** 凭证摘要（可空） */
    private final String summary;

    /** 来源单据类型（T02 自动凭证回填，T01 为 null） */
    private final String sourceDocType;

    /** 来源单据号（T02 自动凭证回填，T01 为 null） */
    private final String sourceDocNo;

    /** 行项目（按行号有序，建单后行集合不变） */
    private final List<VoucherLine> lines;

    private Voucher(String docNo, String period, LocalDate voucherDate, String word, BigDecimal totalAmount,
                    String summary, String sourceDocType, String sourceDocNo, List<VoucherLine> lines,
                    String createdBy) {
        super(docNo, createdBy);
        this.period = period;
        this.voucherDate = voucherDate;
        this.word = word;
        this.totalAmount = totalAmount;
        this.summary = summary;
        this.sourceDocType = sourceDocType;
        this.sourceDocNo = sourceDocNo;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建凭证（草稿），强制凭证级平衡不变式（验收①）。
     *
     * @param docNo         单据号（VCH-yyyyMM-序号，app 层用 DocumentNumberGenerator 生成）
     * @param period        所属账期键 yyyyMM
     * @param voucherDate   凭证日期（须落在账期内，由 VoucherService 校验）
     * @param word          凭证字（null/空白默认"记"）
     * @param summary       凭证摘要（可空）
     * @param sourceDocType 来源单据类型（T01 传 null）
     * @param sourceDocNo   来源单据号（T01 传 null）
     * @param createdBy     创建人
     * @param lines         行项目（≥2 行、行号唯一、Σ借==Σ贷、总额>0）
     */
    public static Voucher create(String docNo, String period, LocalDate voucherDate, String word,
                                 String summary, String sourceDocType, String sourceDocNo,
                                 String createdBy, List<VoucherLine> lines) {
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(period, "账期不能为空");
        Objects.requireNonNull(voucherDate, "凭证日期不能为空");
        Objects.requireNonNull(lines, "凭证行不能为空");

        // ① ≥2 行
        if (lines.size() < 2) {
            throw new VoucherNotBalancedException("凭证至少要有 2 行（一借一贷起），当前行数: " + lines.size());
        }
        // ② 行号唯一
        long distinctLineNos = lines.stream().map(VoucherLine::getLineNo).distinct().count();
        if (distinctLineNos != lines.size()) {
            throw new VoucherNotBalancedException("凭证行号不能重复");
        }
        // ③ Σ借 == Σ贷
        BigDecimal totalDebit = lines.stream().map(VoucherLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        BigDecimal totalCredit = lines.stream().map(VoucherLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new VoucherNotBalancedException(totalDebit, totalCredit);
        }
        // ④ 总额 > 0
        if (totalDebit.signum() <= 0) {
            throw new VoucherNotBalancedException("凭证总额必须大于 0: " + totalDebit.toPlainString());
        }

        String normalizedWord = (word == null || word.isBlank()) ? "记" : word.strip();
        String normalizedSummary = (summary == null || summary.isBlank()) ? null : summary.strip();
        String normalizedSourceType = (sourceDocType == null || sourceDocType.isBlank())
                ? null : sourceDocType.strip();
        String normalizedSourceNo = (sourceDocNo == null || sourceDocNo.isBlank())
                ? null : sourceDocNo.strip();
        return new Voucher(docNo, period.strip(), voucherDate, normalizedWord, totalDebit,
                normalizedSummary, normalizedSourceType, normalizedSourceNo, lines, createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验）：用既有状态恢复凭证。 */
    public static Voucher restore(String docNo, String period, LocalDate voucherDate, String word,
                                  BigDecimal totalAmount, String summary, String sourceDocType,
                                  String sourceDocNo, DocumentStatus status, List<VoucherLine> lines,
                                  String createdBy) {
        Voucher voucher = new Voucher(docNo, period, voucherDate, word, totalAmount, summary,
                sourceDocType, sourceDocNo, lines, createdBy);
        voucher.restoreStatus(status);
        return voucher;
    }

    /**
     * 过账：DRAFT → APPROVED 一步流转（凭证过账是原子记账动作，不经 EXECUTING/COMPLETED）。
     * 账期 OPEN 校验在 {@link VoucherService#post}（服务层可访问账期仓储）。
     */
    public void post(String operator) {
        changeStatus(DocumentStatus.APPROVED, operator);
    }

    /**
     * 凭证状态流转白名单（兜底防线，CLAUDE.md 原则 2「过账后只可冲销不可改」）：
     * 仅允许 DRAFT→APPROVED（过账）、DRAFT→CANCELLED（草稿作废）、APPROVED→REVERSED（红字冲销，T07）。
     * 凭证不经 EXECUTING/COMPLETED——继承自基类的 startExecution()/complete() 等流转一律在此否决，
     * 防止任何调用方（含 T02/T07/Agent 持有聚合引用时）把已过账凭证推入会计语义无意义的状态。
     */
    @Override
    protected void beforeTransition(DocumentStatus from, DocumentStatus to, String operator) {
        boolean allowed =
                (from == DocumentStatus.DRAFT && to == DocumentStatus.APPROVED)
                        || (from == DocumentStatus.DRAFT && to == DocumentStatus.CANCELLED)
                        || (from == DocumentStatus.APPROVED && to == DocumentStatus.REVERSED);
        if (!allowed) {
            throw new IllegalStateTransitionException(getDocNo(), from, to);
        }
    }

    /**
     * 标记本凭证为某原凭证的红字冲销凭证（M4-T07a）：暴露基类 {@code protected markAsReversalOf}
     * 供凭证领域服务 {@link VoucherService#reverse} 在红字凭证 <b>DRAFT 状态、过账前</b> 回填
     * {@code reversalOfId}（建立红字凭证 → 原凭证的反向 linkage，原凭证侧 reversedById 经
     * {@link BusinessDocument#reverse} 回填，二者双向落库可查）。基类约束不变：仅 DRAFT 可标记、只一次。
     */
    public void markAsReversalVoucher(String originalDocNo) {
        markAsReversalOf(originalDocNo);
    }

    /** 仓储落库后回填头自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("凭证 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getPeriod() {
        return period;
    }

    public LocalDate getVoucherDate() {
        return voucherDate;
    }

    public String getWord() {
        return word;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getSummary() {
        return summary;
    }

    public String getSourceDocType() {
        return sourceDocType;
    }

    public String getSourceDocNo() {
        return sourceDocNo;
    }

    /** 行项目只读视图（不可变，防外部直接增删行） */
    public List<VoucherLine> getLines() {
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------
    // AuditTarget（M2-T07）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        return "账期=" + period + ", 凭证字=" + word + ", 凭证日期=" + voucherDate
                + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 总金额=" + totalAmount.toPlainString()
                + ", 摘要=" + AuditTarget.text(summary)
                + ", 来源单据=" + AuditTarget.text(sourceDocNo);
    }
}

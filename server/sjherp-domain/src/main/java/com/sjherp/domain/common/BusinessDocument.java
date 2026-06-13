package com.sjherp.domain.common;

import com.sjherp.domain.common.event.DocumentStatusChangedEvent;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 业务单据基类（采购/销售/出入库/凭证/工单等所有单据的共同地基）。
 *
 * <p>统一承载：单据号、状态机、冲销关联、审计字段、领域事件。
 *
 * <h2>状态机</h2>
 * 状态流转只能通过语义方法（{@link #approve}/{@link #startExecution}/
 * {@link #complete}/{@link #reverse}/{@link #cancel}）或受保护的
 * {@link #changeStatus} 进行，合法流转表见 {@link DocumentStatus}，
 * 非法流转抛 {@link IllegalStateTransitionException}。每次流转自动发布
 * {@link DocumentStatusChangedEvent}，并提供 {@link #beforeTransition}/
 * {@link #afterTransition} 钩子供子类追加校验与联动（只可收紧、不可放宽）。
 *
 * <h2>冲销语义（CLAUDE.md 原则 2）</h2>
 * 已审核/执行中/已完成的单据只可冲销、不可物理修改/删除——本类**刻意不
 * 提供任何删除方法**。冲销不修改原单业务内容，仅做"冲销标记 + 红字关联"：
 * <ul>
 *   <li>原单：状态置为 REVERSED，{@code reversedById} 记录红字冲销单的单据号；</li>
 *   <li>红字单：由子类领域服务另行创建（反向金额/数量），并通过
 *       {@link #markAsReversalOf} 在 {@code reversalOfId} 中关联原单单据号。</li>
 * </ul>
 *
 * <p>多租户说明：按 ADR-002，tenant_id 属持久层关注点，领域层不出现租户概念。
 */
public abstract class BusinessDocument {

    /** 单据编号（业务唯一标识，由 DocumentNumberGenerator 生成） */
    private final String docNo;

    /** 单据状态 */
    private DocumentStatus status;

    /** 红字关联：本单是红字冲销单时，指向被冲销原单的单据号；否则为 null */
    private String reversalOfId;

    /** 冲销标记：本单被冲销后，指向红字冲销单的单据号；否则为 null */
    private String reversedById;

    /** 创建人（用户或 Agent 标识，审计要求） */
    private final String createdBy;

    private final Instant createdAt;

    /** 最后操作人（审计要求） */
    private String updatedBy;

    private Instant updatedAt;

    /** 事件发布器（可选，由应用层/infra 注入；未注入时事件缓存在 pendingEvents） */
    private transient DomainEventPublisher eventPublisher;

    /** 未注入发布器时缓存的待发布事件，由应用层 {@link #pullPendingEvents} 取走 */
    private final transient List<DomainEvent> pendingEvents = new ArrayList<>();

    protected BusinessDocument(String docNo, String createdBy) {
        this.docNo = Objects.requireNonNull(docNo, "docNo 不能为空");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy 不能为空");
        this.status = DocumentStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedBy = createdBy;
        this.updatedAt = this.createdAt;
    }

    // ---------------------------------------------------------------
    // 状态流转语义方法（公开 API）
    // ---------------------------------------------------------------

    /** 审核：DRAFT → APPROVED，业务内容自此锁定 */
    public void approve(String operator) {
        changeStatus(DocumentStatus.APPROVED, operator);
    }

    /** 开始执行：APPROVED → EXECUTING */
    public void startExecution(String operator) {
        changeStatus(DocumentStatus.EXECUTING, operator);
    }

    /** 完成（过账）：EXECUTING → COMPLETED，自此只可冲销 */
    public void complete(String operator) {
        changeStatus(DocumentStatus.COMPLETED, operator);
    }

    /** 作废：仅 DRAFT 可作废（未产生任何业务影响） */
    public void cancel(String operator) {
        changeStatus(DocumentStatus.CANCELLED, operator);
    }

    /**
     * 冲销：APPROVED/EXECUTING/COMPLETED → REVERSED。
     *
     * <p>不修改原单业务内容，仅置冲销状态并记录红字关联。红字冲销单
     * （反向金额/数量的新单据）由子类领域服务负责创建并调用
     * {@link #markAsReversalOf} 关联本单。
     *
     * @param operator      操作人
     * @param reversalDocNo 红字冲销单的单据号（必填，保证冲销链路可审计）
     */
    public void reverse(String operator, String reversalDocNo) {
        Objects.requireNonNull(reversalDocNo, "reversalDocNo 不能为空：冲销必须关联红字单据");
        changeStatus(DocumentStatus.REVERSED, operator);
        this.reversedById = reversalDocNo;
    }

    /**
     * 将本单标记为某原单的红字冲销单（只能在 DRAFT 状态、且只能标记一次）。
     * 供子类创建红字单时调用。
     */
    protected void markAsReversalOf(String originalDocNo) {
        Objects.requireNonNull(originalDocNo, "originalDocNo 不能为空");
        if (this.status != DocumentStatus.DRAFT) {
            throw new IllegalStateException(
                    "单据[" + docNo + "] 只有草稿状态可标记为红字冲销单，当前状态: " + status);
        }
        if (this.reversalOfId != null) {
            throw new IllegalStateException(
                    "单据[" + docNo + "] 已是 [" + reversalOfId + "] 的红字冲销单，不可重复标记");
        }
        this.reversalOfId = originalDocNo;
    }

    // ---------------------------------------------------------------
    // 状态流转核心（唯一入口）
    // ---------------------------------------------------------------

    /**
     * 状态流转的唯一入口：校验合法性 → beforeTransition 钩子 → 落状态与审计字段
     * → afterTransition 钩子 → 自动发布 DocumentStatusChangedEvent。
     *
     * <p>钩子抛异常时状态不变更、事件不发布（保证模型不破碎）。
     * 子类如有标准五法之外的自定义流转需求，可调用本方法，但不能绕过流转表。
     *
     * @throws IllegalStateTransitionException 非法流转（含单据号、当前态、目标态）
     */
    protected final void changeStatus(DocumentStatus target, String operator) {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(operator, "operator 不能为空");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(docNo, status, target);
        }
        DocumentStatus from = this.status;
        beforeTransition(from, target, operator);
        this.status = target;
        this.updatedBy = operator;
        this.updatedAt = Instant.now();
        afterTransition(from, target, operator);
        publishEvent(new DocumentStatusChangedEvent(docNo, from, target, operator));
    }

    /**
     * 持久层重建专用：直接置入已落库的状态，<b>不校验流转、不发布事件、不改审计字段</b>。
     *
     * <p>仅供子类的 {@code restore(...)} 工厂调用（从数据库恢复单据时，状态本就是
     * 历史合法流转的结果，无需重放）。绝不可用于业务流转——业务流转一律走
     * {@link #changeStatus}（受流转表与钩子约束）。
     */
    protected final void restoreStatus(DocumentStatus persistedStatus) {
        this.status = Objects.requireNonNull(persistedStatus, "persistedStatus 不能为空");
    }

    /**
     * 流转前钩子：子类在此追加业务校验（如审核前明细不能为空、冲销前
     * 检查账期未关）。抛异常即否决本次流转。只可收紧规则、不可放宽流转表。
     */
    protected void beforeTransition(DocumentStatus from, DocumentStatus to, String operator) {
        // 默认空实现，子类按需覆写
    }

    /**
     * 流转后钩子：子类在此追加联动逻辑（如完成后登记额外领域事件）。
     * 此时状态已变更；钩子内不应再修改单据业务内容。
     */
    protected void afterTransition(DocumentStatus from, DocumentStatus to, String operator) {
        // 默认空实现，子类按需覆写
    }

    // ---------------------------------------------------------------
    // 领域事件
    // ---------------------------------------------------------------

    /**
     * 注册事件发布器（由应用层/infra 注入）。注册后流转事件即时发布；
     * 未注册时事件缓存在本单据内，由应用层 {@link #pullPendingEvents} 取走发布。
     */
    public void registerEventPublisher(DomainEventPublisher publisher) {
        this.eventPublisher = Objects.requireNonNull(publisher, "publisher 不能为空");
    }

    /** 子类登记自定义领域事件的统一出口（与状态事件同一投递通道） */
    protected final void publishEvent(DomainEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (eventPublisher != null) {
            eventPublisher.publish(event);
        } else {
            pendingEvents.add(event);
        }
    }

    /** 取走并清空缓存的待发布事件（未注册发布器时由应用层调用） */
    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    // ---------------------------------------------------------------
    // 只读访问器
    // ---------------------------------------------------------------

    public String getDocNo() {
        return docNo;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    /** 本单是红字冲销单时返回原单单据号，否则 null */
    public String getReversalOfId() {
        return reversalOfId;
    }

    /** 本单被冲销后返回红字冲销单单据号，否则 null */
    public String getReversedById() {
        return reversedById;
    }

    /** 本单是否为红字冲销单 */
    public boolean isReversalDocument() {
        return reversalOfId != null;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

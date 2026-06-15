package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 工单领域服务（M5-T03）。
 *
 * <p>全部写操作标注 {@link Audited}，由切面自动落 audit_log；
 * 领域事件在 {@link com.sjherp.domain.common.BusinessDocument} 内部发布（状态流转时）。
 *
 * <p>本类无 Spring 注解，由 {@code ProductionInfraConfig} 显式装配。
 */
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final MrpRunRepository mrpRunRepository;
    private final BillOfMaterialsRepository billOfMaterialsRepository;
    private final DocumentNumberGenerator documentNumberGenerator;
    private final DomainEventPublisher eventPublisher;

    public WorkOrderService(
            WorkOrderRepository workOrderRepository,
            MrpRunRepository mrpRunRepository,
            BillOfMaterialsRepository billOfMaterialsRepository,
            DocumentNumberGenerator documentNumberGenerator,
            DomainEventPublisher eventPublisher) {
        this.workOrderRepository = Objects.requireNonNull(workOrderRepository);
        this.mrpRunRepository = Objects.requireNonNull(mrpRunRepository);
        this.billOfMaterialsRepository = Objects.requireNonNull(billOfMaterialsRepository);
        this.documentNumberGenerator = Objects.requireNonNull(documentNumberGenerator);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    // ---------------------------------------------------------------- 写操作

    /**
     * 手工建单。
     *
     * @param productId        生产商品 id
     * @param plannedQty       计划数量（>0）
     * @param unitId           计量单位 id
     * @param bomVersion       BOM 版本（预留，可为 null）
     * @param routingVersion   工艺路线版本（预留，可为 null）
     * @param warehouseId      生产仓库（预留，可为 null）
     * @param plannedStartDate 计划开始日期（可为 null）
     * @param plannedEndDate   计划结束日期（可为 null）
     * @param remark           备注（可为 null）
     * @param operator         操作人
     * @return 已保存的工单
     */
    @Audited(action = "work_order.create", targetType = "work_order")
    public WorkOrder createManual(
            long productId,
            BigDecimal plannedQty,
            long unitId,
            Integer bomVersion,
            Integer routingVersion,
            Long warehouseId,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            String remark,
            String operator) {
        String docNo = documentNumberGenerator.generate(DocumentNumberRule.of("WO"));
        WorkOrder wo = WorkOrder.create(docNo, productId, plannedQty, unitId,
                bomVersion, routingVersion, warehouseId,
                plannedStartDate, plannedEndDate, remark, operator);
        workOrderRepository.save(wo);
        return wo;
    }

    /**
     * 从 MRP PRODUCTION 建议建单。
     *
     * @param mrpRunDocNo MRP 运行单号
     * @param productId   生产商品 id（用于在建议行中定位）
     * @param operator    操作人
     * @return 已保存的工单
     */
    @Audited(action = "work_order.create_from_suggestion", targetType = "work_order")
    public WorkOrder createFromSuggestion(
            String mrpRunDocNo,
            long productId,
            String operator) {
        // 加载 MRP 运行（不存在则 404）
        MrpRun mrpRun = mrpRunRepository.findByDocNo(mrpRunDocNo)
                .orElseThrow(() -> new MrpRunNotFoundException(mrpRunDocNo));

        // 找到对应商品的 PRODUCTION 建议（取首条，同品多层级登记 R2）
        MrpSuggestion suggestion = mrpRun.getSuggestions().stream()
                .filter(s -> s.type() == SuggestionType.PRODUCTION && s.productId() == productId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "MRP 运行[" + mrpRunDocNo + "] 中未找到商品[" + productId + "] 的 PRODUCTION 建议"));

        // 净需求量必须 > 0（否则无需建单）
        BigDecimal netRequirement = suggestion.netRequirement();
        if (netRequirement.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "MRP 净需求量为 0，商品[" + productId + "] 无需生产");
        }

        // 校验商品有启用的 BOM（T04 领料依赖 BOM，提前拦截无意义工单）
        billOfMaterialsRepository.findActiveByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "商品[" + productId + "] 没有启用的 BOM，无法建立生产工单"));

        String docNo = documentNumberGenerator.generate(DocumentNumberRule.of("WO"));
        WorkOrder wo = WorkOrder.createFromSuggestion(
                docNo, productId, netRequirement, suggestion.baseUnitId(), mrpRunDocNo, operator);
        workOrderRepository.save(wo);
        return wo;
    }

    /**
     * 下达工单（DRAFT → APPROVED）。
     *
     * @param docNo    工单号
     * @param operator 操作人
     * @return 已下达工单
     */
    @Audited(action = "work_order.release", targetType = "work_order")
    public WorkOrder release(String docNo, String operator) {
        WorkOrder wo = load(docNo);
        wo.registerEventPublisher(eventPublisher);
        wo.release(operator);
        workOrderRepository.save(wo);
        return wo;
    }

    /**
     * 开工（APPROVED → EXECUTING）。
     *
     * @param docNo    工单号
     * @param operator 操作人
     * @return 已开工工单
     */
    @Audited(action = "work_order.start", targetType = "work_order")
    public WorkOrder start(String docNo, String operator) {
        WorkOrder wo = load(docNo);
        wo.registerEventPublisher(eventPublisher);
        wo.start(operator);
        workOrderRepository.save(wo);
        return wo;
    }

    /**
     * 完工（EXECUTING → COMPLETED）。
     *
     * @param docNo    工单号
     * @param operator 操作人
     * @return 已完工工单
     */
    @Audited(action = "work_order.complete", targetType = "work_order")
    public WorkOrder complete(String docNo, String operator) {
        WorkOrder wo = load(docNo);
        wo.registerEventPublisher(eventPublisher);
        wo.complete(operator);
        workOrderRepository.save(wo);
        return wo;
    }

    /**
     * 作废工单（DRAFT → CANCELLED）。
     *
     * @param docNo    工单号
     * @param operator 操作人
     * @return 已作废工单
     */
    @Audited(action = "work_order.cancel", targetType = "work_order")
    public WorkOrder cancel(String docNo, String operator) {
        WorkOrder wo = load(docNo);
        wo.registerEventPublisher(eventPublisher);
        wo.cancel(operator);
        workOrderRepository.save(wo);
        return wo;
    }

    /**
     * 冲销工单（APPROVED → REVERSED，未投产可冲销；本批无副作用）。
     *
     * @param docNo    工单号
     * @param operator 操作人
     * @return 已冲销工单
     */
    @Audited(action = "work_order.reverse", targetType = "work_order")
    public WorkOrder reverse(String docNo, String operator) {
        WorkOrder wo = load(docNo);
        wo.registerEventPublisher(eventPublisher);
        wo.reverse(operator);
        workOrderRepository.save(wo);
        return wo;
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 按单号查询工单（不存在抛 WorkOrderNotFoundException → 404）。
     */
    public WorkOrder get(String docNo) {
        return workOrderRepository.findByDocNo(docNo)
                .orElseThrow(() -> new WorkOrderNotFoundException(docNo));
    }

    /**
     * 分页查询工单。
     */
    public PageResult<WorkOrder> search(WorkOrderQuery query) {
        return workOrderRepository.search(query);
    }

    // ---------------------------------------------------------------- 内部工具

    private WorkOrder load(String docNo) {
        return workOrderRepository.findByDocNo(docNo)
                .orElseThrow(() -> new WorkOrderNotFoundException(docNo));
    }
}

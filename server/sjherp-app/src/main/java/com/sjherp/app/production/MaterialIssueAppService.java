package com.sjherp.app.production;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLineInput;
import com.sjherp.domain.production.MaterialIssueQuery;
import com.sjherp.domain.production.MaterialIssueService;

/**
 * 领料单应用服务（M5-T04）。
 *
 * <p>直接标 {@code @Transactional}（领料过账做跨库存编排，照 SalesDeliveryAppService，
 * 不另建 Transactional 包装类）。领域服务零 Spring 依赖，本类是唯一事务边界入口。
 * 单据编号 MI-YYYYMM-NNNN 在本类生成，不暴露给控制器层。
 */
public class MaterialIssueAppService {

    /** 领料单编号规则：MI-202606-0001 */
    static final DocumentNumberRule MATERIAL_ISSUE_RULE = DocumentNumberRule.of("MI");

    private final MaterialIssueService domainService;
    private final DocumentNumberGenerator numberGenerator;

    public MaterialIssueAppService(MaterialIssueService domainService,
                                   DocumentNumberGenerator numberGenerator) {
        this.domainService = domainService;
        this.numberGenerator = numberGenerator;
    }

    /** 创建领料单（草稿），自动生成 MI- 编号。 */
    @Transactional
    public MaterialIssue create(String workOrderDocNo, long warehouseId,
                                String remark, List<MaterialIssueLineInput> lines, String operator) {
        String docNo = numberGenerator.generate(MATERIAL_ISSUE_RULE);
        return domainService.create(docNo, workOrderDocNo, warehouseId, remark, lines, operator);
    }

    /** 审核领料单：DRAFT → APPROVED。 */
    @Transactional
    public MaterialIssue approve(String docNo, String operator) {
        return domainService.approve(docNo, operator);
    }

    /**
     * 过账领料单：APPROVED → EXECUTING → COMPLETED，批量 PRODUCTION_ISSUE 库存出库（唯一入口）。
     * 库存不足时整批回滚，单据状态不前进（外层事务保证）。
     */
    @Transactional
    public MaterialIssue post(String docNo, String operator) {
        return domainService.post(docNo, operator);
    }

    /** 作废领料单：DRAFT → CANCELLED。 */
    @Transactional
    public MaterialIssue cancel(String docNo, String operator) {
        return domainService.cancel(docNo, operator);
    }

    /** 按单号查询（不存在抛 MaterialIssueNotFoundException → 404）。 */
    @Transactional(readOnly = true)
    public MaterialIssue get(String docNo) {
        return domainService.get(docNo);
    }

    /** 分页查询。 */
    @Transactional(readOnly = true)
    public PageResult<MaterialIssue> search(MaterialIssueQuery query) {
        return domainService.search(query);
    }
}

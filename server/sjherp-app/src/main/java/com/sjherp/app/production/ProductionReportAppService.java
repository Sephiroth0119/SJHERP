package com.sjherp.app.production;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLineInput;
import com.sjherp.domain.production.ProductionReportQuery;
import com.sjherp.domain.production.ProductionReportService;

import java.math.BigDecimal;

/**
 * 报工单应用服务（M5-T05）。
 *
 * <p>直接标 {@code @Transactional}（报工过账做跨库存+工单编排，照 MaterialIssueAppService）。
 * 领域服务零 Spring 依赖，本类是唯一事务边界入口。
 * 单据编号 PR-YYYYMM-NNNN 在本类生成，不暴露给控制器层。
 */
public class ProductionReportAppService {

    /** 报工单编号规则：PR-202606-0001 */
    static final DocumentNumberRule PRODUCTION_REPORT_RULE = DocumentNumberRule.of("PR");

    private final ProductionReportService domainService;
    private final DocumentNumberGenerator numberGenerator;

    public ProductionReportAppService(ProductionReportService domainService,
                                      DocumentNumberGenerator numberGenerator) {
        this.domainService = domainService;
        this.numberGenerator = numberGenerator;
    }

    /** 创建报工单（草稿），自动生成 PR- 编号。 */
    @Transactional
    public ProductionReport create(String workOrderDocNo, long warehouseId,
                                   long productId, BigDecimal completedQty, BigDecimal scrapQty,
                                   long unitId, String remark,
                                   List<ProductionReportLineInput> lines, String operator) {
        String docNo = numberGenerator.generate(PRODUCTION_REPORT_RULE);
        return domainService.create(docNo, workOrderDocNo, warehouseId, productId,
                completedQty, scrapQty, unitId, remark, lines, operator);
    }

    /** 审核报工单：DRAFT → APPROVED。 */
    @Transactional
    public ProductionReport approve(String docNo, String operator) {
        return domainService.approve(docNo, operator);
    }

    /**
     * 过账报工单：APPROVED → EXECUTING → COMPLETED，PRODUCTION_IN 完工入库（唯一入口）。
     * 工单无已过账领料成本（issuedCost=0）时整批回滚，单据状态不前进（外层事务保证）。
     */
    @Transactional
    public ProductionReport post(String docNo, String operator) {
        return domainService.post(docNo, operator);
    }

    /** 作废报工单：DRAFT → CANCELLED。 */
    @Transactional
    public ProductionReport cancel(String docNo, String operator) {
        return domainService.cancel(docNo, operator);
    }

    /** 按单号查询（不存在抛 ProductionReportNotFoundException → 404）。 */
    @Transactional(readOnly = true)
    public ProductionReport get(String docNo) {
        return domainService.get(docNo);
    }

    /** 分页查询。 */
    @Transactional(readOnly = true)
    public PageResult<ProductionReport> search(ProductionReportQuery query) {
        return domainService.search(query);
    }
}

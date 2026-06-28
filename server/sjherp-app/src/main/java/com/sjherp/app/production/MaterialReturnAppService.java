package com.sjherp.app.production;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLineInput;
import com.sjherp.domain.production.MaterialReturnQuery;
import com.sjherp.domain.production.MaterialReturnService;

/**
 * 退料单应用服务（M5-T04）。直接标 {@code @Transactional}，同 {@link MaterialIssueAppService}。
 * 单据编号 MR-YYYYMM-NNNN 在本类生成，不暴露给控制器层。
 */
public class MaterialReturnAppService {

    /** 退料单编号规则：MR-202606-0001 */
    static final DocumentNumberRule MATERIAL_RETURN_RULE = DocumentNumberRule.of("MR");

    private final MaterialReturnService domainService;
    private final DocumentNumberGenerator numberGenerator;

    public MaterialReturnAppService(MaterialReturnService domainService,
                                    DocumentNumberGenerator numberGenerator) {
        this.domainService = domainService;
        this.numberGenerator = numberGenerator;
    }

    /** 创建退料单（草稿），自动生成 MR- 编号。 */
    @Transactional
    public MaterialReturn create(String materialIssueDocNo, long warehouseId,
                                  String remark, List<MaterialReturnLineInput> lines, String operator) {
        String docNo = numberGenerator.generate(MATERIAL_RETURN_RULE);
        return domainService.create(docNo, materialIssueDocNo, warehouseId, remark, lines, operator);
    }

    /** 审核退料单：DRAFT → APPROVED。 */
    @Transactional
    public MaterialReturn approve(String docNo, String operator) {
        return domainService.approve(docNo, operator);
    }

    /**
     * 过账退料单：APPROVED → EXECUTING → COMPLETED，按原领料成本 PRODUCTION_RETURN 入库。
     */
    @Transactional
    public MaterialReturn post(String docNo, String operator) {
        return domainService.post(docNo, operator);
    }

    /** 按单号查询（不存在抛 MaterialReturnNotFoundException → 404）。 */
    @Transactional(readOnly = true)
    public MaterialReturn get(String docNo) {
        return domainService.get(docNo);
    }

    /** 分页查询。 */
    @Transactional(readOnly = true)
    public PageResult<MaterialReturn> search(MaterialReturnQuery query) {
        return domainService.search(query);
    }
}

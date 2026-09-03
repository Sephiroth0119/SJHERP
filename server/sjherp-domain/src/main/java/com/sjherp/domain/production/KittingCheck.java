package com.sjherp.domain.production;

import java.util.List;

/**
 * 齐套检查结果（M5-T04，只读值对象）。
 *
 * @param workOrderDocNo 被检查的工单号
 * @param warehouseId    检查仓库 id
 * @param kitted         是否所有子件均满足需求（true = 全套齐套，false = 有缺料）
 * @param lines          各子件检查明细
 */
public record KittingCheck(String workOrderDocNo, long warehouseId, boolean kitted,
                            List<KittingCheckLine> lines) {
}

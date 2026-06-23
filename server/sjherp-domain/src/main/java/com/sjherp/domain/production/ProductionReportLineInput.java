package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 报工单工时行输入（M5-T05）。
 *
 * @param operationSeqNo  工序序号快照（可空）
 * @param operationName   工序名称快照（可空）
 * @param workCenter      工作中心快照（可空，T06 归集用）
 * @param reportedHours   报工工时（必须 > 0，6 位小数）
 * @param reportedQty     报工数量（可空，默认等于头部 completedQty）
 * @param unitId          计量单位 id
 */
public record ProductionReportLineInput(
        Integer operationSeqNo,
        String operationName,
        String workCenter,
        BigDecimal reportedHours,
        BigDecimal reportedQty,
        long unitId) {
}

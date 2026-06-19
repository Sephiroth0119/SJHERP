package com.sjherp.app.production;

import java.util.List;

import com.sjherp.domain.production.KittingCheck;
import com.sjherp.domain.production.KittingCheckLine;

/**
 * 齐套检查 REST DTO 集（M5-T04）。只读——不存在建单请求，只有响应体。
 * 数量一律 {@code toPlainString()} 序列化。
 */
public final class KittingCheckDtos {

    private KittingCheckDtos() {}

    // ================================================================ 响应 DTO

    /** 单行检查结果。 */
    public record KittingLineResponse(
            long productId,
            long unitId,
            String required,
            String available,
            String shortage
    ) {
        public static KittingLineResponse from(KittingCheckLine line) {
            return new KittingLineResponse(
                    line.productId(),
                    line.unitId(),
                    line.required().toPlainString(),
                    line.available().toPlainString(),
                    line.shortage().toPlainString()
            );
        }
    }

    /** 整张工单齐套检查结果。 */
    public record KittingCheckResponse(
            String workOrderDocNo,
            long warehouseId,
            boolean kitted,
            List<KittingLineResponse> lines
    ) {
        public static KittingCheckResponse from(KittingCheck kc) {
            return new KittingCheckResponse(
                    kc.workOrderDocNo(),
                    kc.warehouseId(),
                    kc.kitted(),
                    kc.lines().stream().map(KittingLineResponse::from).toList()
            );
        }
    }
}

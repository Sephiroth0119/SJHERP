package com.sjherp.app.production;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.BillOfMaterials;
import com.sjherp.domain.production.BillOfMaterialsCommand;
import com.sjherp.domain.production.BomExplosion;
import com.sjherp.domain.production.BomExplosionNode;
import com.sjherp.domain.production.BomLine;
import com.sjherp.domain.production.BomLineCommand;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * BOM API 的请求/响应 DTO（Bean Validation 做字段级格式校验，
 * 业务规则校验仍在领域层，M5-T01）。
 *
 * <p>口径同 fund/payment_account：BigDecimal 金额/数量以 toPlainString() 呈现；
 * 状态用枚举英文名传入。
 */
public final class BomDtos {

    private BomDtos() {
    }

    // ================================================================ BOM 行请求

    public record BomLineRequest(
            @NotNull(message = "子件商品 id 不能为空") Long childProductId,
            @NotNull(message = "用量不能为空") @DecimalMin(value = "0.000001", message = "用量必须大于 0") BigDecimal quantity,
            BigDecimal scrapRate,
            @NotNull(message = "计量单位 id 不能为空") Long unitId) {

        /** 转换为领域命令（scrapRate 为空时传 null，由领域层默认为 0） */
        public BomLineCommand toCommand() {
            return new BomLineCommand(childProductId, quantity, scrapRate, unitId);
        }
    }

    // ================================================================ BOM 头请求

    public record BomRequest(
            @NotNull(message = "产品 id 不能为空") Long productId,
            @NotNull(message = "版本号不能为空") @Min(value = 1, message = "版本号必须 >= 1") Integer version,
            String remark,
            @NotNull(message = "BOM 行列表不能为空") @NotEmpty(message = "BOM 至少需要一行子件") @Valid List<BomLineRequest> lines) {

        /** 将请求 DTO 转换为领域命令 */
        public BillOfMaterialsCommand toCommand() {
            return new BillOfMaterialsCommand(
                    productId,
                    version,
                    remark,
                    lines.stream().map(BomLineRequest::toCommand).toList());
        }
    }

    // ================================================================ BOM 行响应

    public record BomLineResponse(
            long childProductId,
            String quantity,
            String scrapRate,
            long unitId) {

        public static BomLineResponse from(BomLine line) {
            return new BomLineResponse(
                    line.childProductId(),
                    line.quantity().toPlainString(),
                    line.scrapRate().toPlainString(),
                    line.unitId());
        }
    }

    // ================================================================ BOM 头响应

    public record BomResponse(
            long id,
            long productId,
            int version,
            String status,
            String remark,
            List<BomLineResponse> lines,
            String createdBy,
            String createdAt,
            String updatedBy,
            String updatedAt) {

        public static BomResponse from(BillOfMaterials bom) {
            return new BomResponse(
                    bom.getId(),
                    bom.getProductId(),
                    bom.getVersion(),
                    bom.getStatus().name(),
                    bom.getRemark(),
                    bom.getLines().stream().map(BomLineResponse::from).toList(),
                    bom.getCreatedBy(),
                    bom.getCreatedAt().toString(),
                    bom.getUpdatedBy(),
                    bom.getUpdatedAt().toString());
        }
    }

    // ================================================================ BOM 展开响应

    public record BomExplosionNodeResponse(
            long productId,
            String quantity,
            long unitId,
            int level,
            List<BomExplosionNodeResponse> children) {

        public static BomExplosionNodeResponse from(BomExplosionNode node) {
            return new BomExplosionNodeResponse(
                    node.productId(),
                    node.quantity().toPlainString(),
                    node.unitId(),
                    node.level(),
                    node.children().stream().map(BomExplosionNodeResponse::from).toList());
        }
    }

    public record BomExplosionResponse(
            long rootProductId,
            String rootQuantity,
            List<BomExplosionNodeResponse> nodes) {

        public static BomExplosionResponse from(BomExplosion explosion) {
            return new BomExplosionResponse(
                    explosion.rootProductId(),
                    explosion.rootQuantity().toPlainString(),
                    explosion.nodes().stream().map(BomExplosionNodeResponse::from).toList());
        }
    }

    // ================================================================ 分页响应

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public static <T> PageResponse<T> from(PageResult<T> page) {
            return new PageResponse<>(page.items(), page.total(), page.page(), page.size());
        }

        /** 从 BOM 分页结果构建响应（常用便捷方法） */
        public static PageResponse<BomResponse> fromBoms(PageResult<BillOfMaterials> result) {
            return new PageResponse<>(
                    result.items().stream().map(BomResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}

package com.sjherp.app.inventory;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.app.inventory.InventoryQueryDao.BalanceRow;
import com.sjherp.app.inventory.InventoryQueryDao.TransactionRow;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.StockMovementResult;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * inventory API 的请求/响应 DTO（M3-T01c）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：数量/单价/金额在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与选项返回协议的 decimal 口径一致。
 * 加权单价是派生值（金额/数量，6 位 HALF_UP），数量 ≤ 0 时为 null（前端显示「—」）。
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    // ---------------------------------------------------------------- 请求

    /**
     * 库存调整请求：type=OPENING（期初建账，quantity/unitCost 必填）
     * 或 COST_ADJUST（成本调整，adjustAmount 必填，可负）。
     * 字段级格式校验在此，业务规则（数量>0、调整后金额≥0 等）仍在领域层。
     */
    public record AdjustmentRequest(
            @NotBlank(message = "调整类型不能为空（OPENING / COST_ADJUST）") String type,
            @NotNull(message = "仓库 id 不能为空") Long warehouseId,
            @NotNull(message = "商品 id 不能为空") Long productId,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal adjustAmount) {
    }

    // ---------------------------------------------------------------- 响应

    /** 调整结果（即过账流水 + 过账后余额快照） */
    public record AdjustmentResponse(long transactionId, long warehouseId, long productId,
                                     String txnType, String quantity, String unitCost, String totalCost,
                                     String balanceQuantityAfter, String balanceAmountAfter,
                                     String srcDocType, String srcDocNo, String idempotencyKey) {

        static AdjustmentResponse from(StockMovementResult result) {
            return new AdjustmentResponse(
                    result.transactionId(),
                    result.warehouseId(),
                    result.productId(),
                    result.txnType().name(),
                    plain(result.quantity()),
                    plain(result.unitCost()),
                    plain(result.totalCost()),
                    plain(result.balanceQuantityAfter()),
                    plain(result.balanceAmountAfter()),
                    result.srcDocType(),
                    result.srcDocNo(),
                    result.idempotencyKey());
        }
    }

    /** 余额行（联查仓库/商品编码与名称；unitCost 为派生加权单价，数量 ≤ 0 时 null） */
    public record BalanceResponse(long warehouseId, String warehouseCode, String warehouseName,
                                  long productId, String productCode, String productName,
                                  String quantity, String costAmount, String unitCost) {

        static BalanceResponse from(BalanceRow row) {
            // 派生单价口径复用领域视图（6 位 HALF_UP，数量 ≤ 0 返回 null）
            InventoryBalanceView view = new InventoryBalanceView(
                    row.warehouseId(), row.productId(), row.quantity(), row.costAmount());
            return new BalanceResponse(
                    row.warehouseId(), row.warehouseCode(), row.warehouseName(),
                    row.productId(), row.productCode(), row.productName(),
                    plain(row.quantity()), plain(row.costAmount()), plain(view.derivedUnitCost()));
        }
    }

    /** 流水行（unitCost 成本调整为 null；srcLineNo 可空） */
    public record TransactionResponse(long id, String txnType, String quantity, String unitCost,
                                      String totalCost, String balanceQuantityAfter,
                                      String balanceAmountAfter, String srcDocType, String srcDocNo,
                                      Integer srcLineNo, String operator, String createdAt) {

        static TransactionResponse from(TransactionRow row) {
            return new TransactionResponse(
                    row.id(), row.txnType(),
                    plain(row.quantity()), plain(row.unitCost()), plain(row.totalCost()),
                    plain(row.balanceQuantityAfter()), plain(row.balanceAmountAfter()),
                    row.srcDocType(), row.srcDocNo(), row.srcLineNo(),
                    row.operator(), row.createdAt().toString());
        }
    }

    /** 分页响应（与领域层 PageResult 同构，约定同各档案 API） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<BalanceResponse> fromBalances(PageResult<BalanceRow> result) {
            return new PageResponse<>(
                    result.items().stream().map(BalanceResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        static PageResponse<TransactionResponse> fromTransactions(PageResult<TransactionRow> result) {
            return new PageResponse<>(
                    result.items().stream().map(TransactionResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}

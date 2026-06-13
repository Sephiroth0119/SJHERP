package com.sjherp.app.stocktake;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 盘点单 API 的请求/响应 DTO（M3-T03）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：数量/单价在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与库存 API 同口径。
 */
public final class StocktakeDtos {

    private StocktakeDtos() {
    }

    // ---------------------------------------------------------------- 请求

    /**
     * 建单请求：单仓盘点（warehouseId）+ 行数组。建单账面快照由后端用库存余额填，
     * 不接受前端传入；enteredUnitCost 仅零库存盘盈需要（可空）。
     */
    public record CreateRequest(
            @NotNull(message = "仓库 id 不能为空") Long warehouseId,
            String remark,
            @NotEmpty(message = "盘点单至少要有一行") @Valid List<CountLineInput> lines) {
    }

    /** 建单行输入：商品 id（必填）+ 零库存盘盈录入单价（可空） */
    public record CountLineInput(
            @NotNull(message = "盘点行商品 id 不能为空") Long productId,
            BigDecimal enteredUnitCost) {
    }

    /** 录入实盘请求：行号 + 实盘数量 */
    public record EnterCountRequest(
            @NotNull(message = "行号不能为空") Integer lineNo,
            @NotNull(message = "实盘数量不能为空") BigDecimal countedQty) {
    }

    // ---------------------------------------------------------------- 响应

    /** 盘点单响应（单据头 + 行项目；差异 = 实盘 − 账面，由领域派生） */
    public record StockCountResponse(String docNo, long warehouseId, String remark, String status,
                                     List<LineResponse> lines) {

        public static StockCountResponse from(StockCountDocument document) {
            List<LineResponse> lines = document.getLines().stream()
                    .map(LineResponse::from).toList();
            return new StockCountResponse(document.getDocNo(), document.getWarehouseId(),
                    document.getRemark(), document.getStatus().name(), lines);
        }
    }

    /** 行响应：账面/实盘/差异/录入单价（数量为字符串；实盘未录入时 countedQty/diffQty 为 null） */
    public record LineResponse(int lineNo, long productId, String snapshotQty, String countedQty,
                               String diffQty, String enteredUnitCost) {

        static LineResponse from(StockCountLine line) {
            return new LineResponse(line.getLineNo(), line.getProductId(),
                    plain(line.getSnapshotQty()), plain(line.getCountedQty()),
                    plain(line.diffQty()), plain(line.getEnteredUnitCost()));
        }
    }

    /** 分页响应（与库存 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<StockCountResponse> fromDocuments(PageResult<StockCountDocument> result) {
            return new PageResponse<>(
                    result.items().stream().map(StockCountResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}

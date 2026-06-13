package com.sjherp.app.transfer;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 调拨单 API 的请求/响应 DTO（M3-T04）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：数量在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与库存/盘点 API 同口径。
 */
public final class TransferDtos {

    private TransferDtos() {
    }

    // ---------------------------------------------------------------- 请求

    /**
     * 建单请求：调出仓（fromWarehouseId）→ 调入仓（toWarehouseId，须 ≠ 调出仓）+ 行数组。
     */
    public record CreateRequest(
            @NotNull(message = "调出仓库 id 不能为空") Long fromWarehouseId,
            @NotNull(message = "调入仓库 id 不能为空") Long toWarehouseId,
            String remark,
            @NotEmpty(message = "调拨单至少要有一行") @Valid List<TransferLineRequest> lines) {
    }

    /** 建单行输入：商品 id（必填）+ 调拨数量（必填，> 0，业务校验在领域层） */
    public record TransferLineRequest(
            @NotNull(message = "调拨行商品 id 不能为空") Long productId,
            @NotNull(message = "调拨数量不能为空") BigDecimal quantity) {
    }

    // ---------------------------------------------------------------- 响应

    /** 调拨单响应（单据头 + 行项目） */
    public record TransferResponse(String docNo, long fromWarehouseId, long toWarehouseId,
                                   String remark, String status, List<LineResponse> lines) {

        public static TransferResponse from(TransferDocument document) {
            List<LineResponse> lines = document.getLines().stream().map(LineResponse::from).toList();
            return new TransferResponse(document.getDocNo(), document.getFromWarehouseId(),
                    document.getToWarehouseId(), document.getRemark(), document.getStatus().name(), lines);
        }
    }

    /** 行响应：商品 + 调拨数量（数量为字符串） */
    public record LineResponse(int lineNo, long productId, String quantity) {

        static LineResponse from(TransferLine line) {
            return new LineResponse(line.getLineNo(), line.getProductId(), plain(line.getQuantity()));
        }
    }

    /** 分页响应（与库存/盘点 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<TransferResponse> fromDocuments(PageResult<TransferDocument> result) {
            return new PageResponse<>(
                    result.items().stream().map(TransferResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}

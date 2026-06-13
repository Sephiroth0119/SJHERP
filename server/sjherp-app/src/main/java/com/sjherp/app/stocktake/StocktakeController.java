package com.sjherp.app.stocktake;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.security.CurrentUser;
import com.sjherp.app.stocktake.StocktakeDtos.CreateRequest;
import com.sjherp.app.stocktake.StocktakeDtos.EnterCountRequest;
import com.sjherp.app.stocktake.StocktakeDtos.PageResponse;
import com.sjherp.app.stocktake.StocktakeDtos.StockCountResponse;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.stocktake.StockCountQuery;

import jakarta.validation.Valid;

/**
 * 库存盘点单 API（M3-T03）：
 * <ul>
 *   <li>POST /api/inventory/stock-counts → 201 建单（自动 SC- 编号 + 建单账面快照）；</li>
 *   <li>POST /api/inventory/stock-counts/{docNo}/lines/count → 200 录入某行实盘；</li>
 *   <li>POST /api/inventory/stock-counts/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/inventory/stock-counts/{docNo}/post → 200 过账（产生盘盈/盘亏流水）；</li>
 *   <li>GET  /api/inventory/stock-counts/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/inventory/stock-counts?warehouseId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code inventory:count}（ADMIN/BOSS/WAREHOUSE）。
 * 错误契约见 {@link StocktakeExceptionHandler}：单据不存在 404、业务/参数不合法 400、
 * 非法状态流转 409、库存不足 400，错误体一律 {"error": "..."}。
 *
 * <p>写操作唯一经 {@code StocktakeService}（外层事务）→ 领域 StockCountService →
 * 库存唯一写入口 TransactionalInventoryService（CLAUDE.md 原则 1）。
 */
@RestController
@RequestMapping("/api/inventory/stock-counts")
@PreAuthorize("@perm.has('inventory:count')")
public class StocktakeController {

    private final StocktakeService stocktakeService;

    public StocktakeController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    /** 建单（草稿，自动编号 + 账面快照） */
    @PostMapping
    public ResponseEntity<StockCountResponse> create(@Valid @RequestBody CreateRequest request) {
        StockCountResponse body = StockCountResponse.from(stocktakeService.create(
                request.warehouseId(), request.remark(), request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 录入某行实盘数量（仅草稿可改） */
    @PostMapping("/{docNo}/lines/count")
    public StockCountResponse enterCount(@PathVariable String docNo,
                                         @Valid @RequestBody EnterCountRequest request) {
        return StockCountResponse.from(stocktakeService.enterCount(docNo, request.lineNo(),
                request.countedQty(), CurrentUser.operator()));
    }

    /** 审核（DRAFT → APPROVED，审核前必须每行已录入实盘） */
    @PostMapping("/{docNo}/approve")
    public StockCountResponse approve(@PathVariable String docNo) {
        return StockCountResponse.from(stocktakeService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → EXECUTING → COMPLETED，产生盘盈/盘亏流水） */
    @PostMapping("/{docNo}/post")
    public StockCountResponse post(@PathVariable String docNo) {
        return StockCountResponse.from(stocktakeService.post(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public StockCountResponse get(@PathVariable String docNo) {
        return StockCountResponse.from(stocktakeService.get(docNo));
    }

    /** 分页（warehouseId / status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<StockCountResponse> search(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromDocuments(
                stocktakeService.search(new StockCountQuery(warehouseId, parseStatus(status), page, size)));
    }

    /** 状态过滤解析（非法值给友好 400，不透出枚举内部异常） */
    private static DocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 非法（DRAFT/APPROVED/EXECUTING/COMPLETED/"
                    + "CANCELLED/REVERSED）: " + status);
        }
    }
}

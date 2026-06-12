package com.sjherp.app.inventory;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.inventory.InventoryDtos.AdjustmentRequest;
import com.sjherp.app.inventory.InventoryDtos.AdjustmentResponse;
import com.sjherp.app.inventory.InventoryDtos.BalanceResponse;
import com.sjherp.app.inventory.InventoryDtos.PageResponse;
import com.sjherp.app.inventory.InventoryDtos.TransactionResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementResult;

import jakarta.validation.Valid;

/**
 * 库存 API（M3-T01c）：
 * <ul>
 *   <li>GET  /api/inventory/balances?warehouseId=&productId=&keyword=&page=&size=
 *       → 200 余额分页（keyword 模糊匹配商品名称/编码，只读联查）；</li>
 *   <li>GET  /api/inventory/transactions?warehouseId=&productId=&page=&size=
 *       → 200 流水分页（仓库 × 商品必填，按过账倒序）；</li>
 *   <li>POST /api/inventory/adjustments → 201 调整流水
 *       （type=OPENING 期初建账 / COST_ADJUST 成本调整；单据号 OP-/CA- 自动编号）。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写入口须 {@code inventory:adjust}（ADMIN/BOSS/WAREHOUSE），
 * 查询登录即可。错误契约见 {@link InventoryExceptionHandler}：业务拒绝/参数不合法 400、
 * 仓库或商品不存在 404、库存不足 400、幂等键冲突 409，错误体一律 {"error": "..."}。
 *
 * <p>写操作唯一经 {@code InventoryAdjustmentService} →
 * {@code TransactionalInventoryService} → InventoryService（CLAUDE.md 原则 1）。
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryAdjustmentService adjustmentService;
    private final InventoryQueryDao queryDao;

    public InventoryController(InventoryAdjustmentService adjustmentService, InventoryQueryDao queryDao) {
        this.adjustmentService = adjustmentService;
        this.queryDao = queryDao;
    }

    /** 余额分页（warehouseId / productId / keyword 均可选；keyword 匹配商品名称或编码） */
    @GetMapping("/balances")
    public PageResponse<BalanceResponse> balances(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromBalances(queryDao.balances(warehouseId, productId, keyword, page, size));
    }

    /** 流水分页（仓库 × 商品必填，缺参 400；按 id 倒序即最近过账在前） */
    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> transactions(
            @RequestParam long warehouseId,
            @RequestParam long productId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromTransactions(queryDao.transactions(warehouseId, productId, page, size));
    }

    /**
     * 库存调整（唯一写入口，M3-T03 盘点单落地后保持收窄为期初与成本调整）：
     * OPENING 须 quantity + unitCost；COST_ADJUST 须 adjustAmount（可负）。
     */
    @PreAuthorize("@perm.has('inventory:adjust')")
    @PostMapping("/adjustments")
    public ResponseEntity<AdjustmentResponse> adjust(@Valid @RequestBody AdjustmentRequest request) {
        InventoryTxnType type = parseType(request.type());
        String operator = CurrentUser.operator();
        StockMovementResult result = switch (type) {
            case OPENING -> adjustmentService.opening(request.warehouseId(), request.productId(),
                    request.quantity(), request.unitCost(), operator);
            case COST_ADJUST -> adjustmentService.costAdjust(request.warehouseId(), request.productId(),
                    request.adjustAmount(), operator);
            default -> throw new IllegalArgumentException(
                    "调整类型仅支持 OPENING / COST_ADJUST: " + request.type());
        };
        return ResponseEntity.status(HttpStatus.CREATED).body(AdjustmentResponse.from(result));
    }

    /** 类型解析（非法值给出友好 400 信息，不透出枚举内部异常） */
    private static InventoryTxnType parseType(String type) {
        try {
            InventoryTxnType parsed = InventoryTxnType.valueOf(type.strip().toUpperCase(Locale.ROOT));
            if (parsed != InventoryTxnType.OPENING && parsed != InventoryTxnType.COST_ADJUST) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("调整类型仅支持 OPENING / COST_ADJUST: " + type);
        }
    }
}

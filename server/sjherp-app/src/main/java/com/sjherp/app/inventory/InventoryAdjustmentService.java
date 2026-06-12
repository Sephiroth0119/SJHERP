package com.sjherp.app.inventory;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.inventory.CostAdjustCommand;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 库存调整应用服务（M3-T01c）：REST POST /api/inventory/adjustments 与 Agent 工具
 * adjust_inventory 的公共入口，仅支持<b>期初建账（OPENING）</b>与<b>成本调整
 * （COST_ADJUST）</b>两类（正式盘点单 M3-T03 落地后本入口保持收窄口径）。
 *
 * <p>职责（拆解 §1.3：仓库/商品存在性与启用校验在入口层完成）：
 * <ul>
 *   <li>校验仓库/商品存在（不存在抛各域 NotFound 异常 → REST 404）且均为启用状态
 *       （停用抛 IllegalArgumentException → REST 400）；</li>
 *   <li>自动编号：OPENING → OP-年月-序号 / COST_ADJUST → CA-年月-序号
 *       （复用 {@link DocumentNumberGenerator}，REQUIRES_NEW 独立短事务，
 *       取号先于锁 balance 行——拆解 §1.4 锁顺序约定）；</li>
 *   <li>幂等键按全仓约定 docType:docNo:lineNo 生成（单行调整恒为行号 1）；</li>
 *   <li>过账一律经 {@link TransactionalInventoryService}（库存两表唯一写入口的
 *       事务包装），本类不触碰任何库存表。</li>
 * </ul>
 */
@Service
public class InventoryAdjustmentService {

    /** 期初建账编号规则：OP-202606-0001（拆解 §1.7，doc_type=OPENING） */
    static final DocumentNumberRule OPENING_RULE = DocumentNumberRule.of("OP");

    /** 成本调整编号规则：CA-202606-0001 */
    static final DocumentNumberRule COST_ADJUST_RULE = DocumentNumberRule.of("CA");

    /** 单行调整的来源单据行号（本入口一次只过账一行） */
    private static final int SINGLE_LINE_NO = 1;

    private final TransactionalInventoryService inventoryService;
    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final DocumentNumberGenerator numberGenerator;

    public InventoryAdjustmentService(TransactionalInventoryService inventoryService,
                                      WarehouseService warehouseService,
                                      ProductService productService,
                                      DocumentNumberGenerator numberGenerator) {
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 期初建账（OPENING 入库）：quantity（正数，基本单位）与 unitCost（≥0）必填，
     * 数量/单价/金额口径由领域服务强制（拆解 §1.6.1）。
     */
    public StockMovementResult opening(long warehouseId, long productId,
                                       BigDecimal quantity, BigDecimal unitCost, String operator) {
        requireEnabledReferences(warehouseId, productId);
        if (quantity == null) {
            throw new IllegalArgumentException("期初建账数量不能为空");
        }
        if (unitCost == null) {
            throw new IllegalArgumentException("期初建账单价不能为空");
        }
        String docNo = numberGenerator.generate(OPENING_RULE);
        String docType = InventoryTxnType.OPENING.name();
        return inventoryService.inbound(new InboundCommand(warehouseId, productId,
                InventoryTxnType.OPENING, quantity, unitCost, null,
                docType, docNo, SINGLE_LINE_NO, idempotencyKey(docType, docNo)), operator);
    }

    /**
     * 成本调整（COST_ADJUST）：数量不变只调金额，adjustAmount 可正可负（典型场景：
     * 到票价差、运费入成本）；当前结存数量 > 0 且调整后金额 ≥ 0 由领域服务强制（拆解 §1.6.4）。
     */
    public StockMovementResult costAdjust(long warehouseId, long productId,
                                          BigDecimal adjustAmount, String operator) {
        requireEnabledReferences(warehouseId, productId);
        if (adjustAmount == null) {
            throw new IllegalArgumentException("成本调整额不能为空");
        }
        String docNo = numberGenerator.generate(COST_ADJUST_RULE);
        String docType = InventoryTxnType.COST_ADJUST.name();
        return inventoryService.adjustCost(new CostAdjustCommand(warehouseId, productId, adjustAmount,
                docType, docNo, SINGLE_LINE_NO, idempotencyKey(docType, docNo)), operator);
    }

    /** 仓库/商品存在性与启用校验（不存在 → 各域 NotFound 异常；停用 → IllegalArgumentException） */
    private void requireEnabledReferences(long warehouseId, long productId) {
        Warehouse warehouse = warehouseService.get(warehouseId);
        if (warehouse.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("仓库已停用，禁止库存调整: " + warehouse.getName()
                    + "（" + warehouse.getCode() + "）");
        }
        Product product = productService.get(productId);
        if (product.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("商品已停用，禁止库存调整: " + product.getName()
                    + "（" + product.getCode() + "）");
        }
    }

    /** 幂等键约定 docType:docNo:lineNo（拆解 §1.3） */
    private static String idempotencyKey(String docType, String docNo) {
        return docType + ":" + docNo + ":" + SINGLE_LINE_NO;
    }
}

package com.sjherp.app.stocktake;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.stocktake.StocktakeDtos.CountLineInput;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountLineInput;
import com.sjherp.domain.stocktake.StockCountQuery;
import com.sjherp.domain.stocktake.StockCountService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 盘点单应用服务（M3-T03）：REST {@code StocktakeController} 与 Agent 工具的公共入口。
 *
 * <p>职责（拆解 §1.3：仓库/商品存在性与启用校验在入口层；§1.4 取号先于库存锁行）：
 * <ul>
 *   <li>建单：校验仓库存在且启用 + 各行商品存在且启用 → 自动 SC- 编号 →
 *       用库存 {@code balanceOf} 取建单账面快照 → 调领域 {@link StockCountService#create}；</li>
 *   <li>录入实盘 / 审核 / 过账：直接委托领域服务（业务规则在领域层）；</li>
 *   <li><b>外层事务</b>：写方法标 {@code @Transactional}，把单据状态变更 + 库存过账
 *       （领域服务内经 {@link TransactionalInventoryService}，REQUIRED 加入本事务）包成一个
 *       原子事务（参照 TransactionalInventoryService 模式，拆解 §1.4）。</li>
 * </ul>
 *
 * <p>领域 {@code StockCountService} 不加事务（保持可独立测试），事务边界一律由本类提供。
 * 审计：领域服务写方法 @Audited，状态流转经 SyncDomainEventPublisher 自动落
 * document.status_changed 审计——均延迟到本事务 afterCommit（D-8）。
 */
@Service
public class StocktakeService {

    /** 盘点单编号规则：SC-202606-0001（拆解已拍板单据前缀 SC-） */
    static final DocumentNumberRule STOCK_COUNT_RULE = DocumentNumberRule.of("SC");

    private final StockCountService stockCountService;
    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final TransactionalInventoryService inventoryService;
    private final DocumentNumberGenerator numberGenerator;

    public StocktakeService(StockCountService stockCountService, WarehouseService warehouseService,
                            ProductService productService, TransactionalInventoryService inventoryService,
                            DocumentNumberGenerator numberGenerator) {
        this.stockCountService = Objects.requireNonNull(stockCountService, "stockCountService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建盘点单（草稿）：自动 SC- 编号 + 建单账面快照。
     *
     * @param warehouseId 盘点仓库 id
     * @param remark      盘点说明（可空）
     * @param lines       行输入（商品 + 可选零库存盘盈录入单价）
     * @param operator    操作人
     */
    @Transactional
    public StockCountDocument create(long warehouseId, String remark, List<CountLineInput> lines,
                                     String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("盘点单至少要有一行");
        }
        Warehouse warehouse = requireEnabledWarehouse(warehouseId);
        Set<Long> seen = new LinkedHashSet<>();
        List<StockCountLineInput> domainLines = new ArrayList<>(lines.size());
        for (CountLineInput input : lines) {
            if (input.productId() == null) {
                throw new IllegalArgumentException("盘点行商品 id 不能为空");
            }
            long productId = input.productId();
            if (!seen.add(productId)) {
                throw new IllegalArgumentException("同一盘点单内商品不能重复: 商品 id " + productId);
            }
            requireEnabledProduct(productId);
            // 建单账面快照：用库存余额真源（无余额行返回零视图）
            InventoryBalanceView balance = inventoryService.balanceOf(warehouse.getId(), productId);
            domainLines.add(new StockCountLineInput(productId, balance.quantity(),
                    input.enteredUnitCost()));
        }
        String docNo = numberGenerator.generate(STOCK_COUNT_RULE);
        return stockCountService.create(docNo, warehouse.getId(), remark, domainLines, operator);
    }

    /** 录入某行实盘数量（仅草稿可改） */
    @Transactional
    public StockCountDocument enterCount(String docNo, int lineNo, BigDecimal counted, String operator) {
        return stockCountService.enterCount(docNo, lineNo, counted, operator);
    }

    /** 审核盘点单（DRAFT → APPROVED） */
    @Transactional
    public StockCountDocument approve(String docNo, String operator) {
        return stockCountService.approve(docNo, operator);
    }

    /** 过账盘点单（APPROVED → EXECUTING → COMPLETED，产生盘盈/盘亏流水） */
    @Transactional
    public StockCountDocument post(String docNo, String operator) {
        return stockCountService.post(docNo, operator);
    }

    /**
     * 冲销盘点单（红字盘点单，M4-T07c，最高风险路径，不可逆）：对已过账（COMPLETED）的盘点单
     * 按原盘盈/盘亏成本<b>对称反向库存</b>（原盘盈→反向出库、原盘亏→反向入库），原单
     * COMPLETED → REVERSED。<b>盘点不出 GL 凭证</b>，故只反向库存、不红冲凭证（设计真源 §77）。
     *
     * <p>外层 {@code @Transactional}：领域 {@link StockCountService#reverse} 内库存反向经
     * {@link TransactionalInventoryService}（REQUIRED 加入本事务）原子提交——任一行失败整事务回滚
     * （库存与单据状态一致）。幂等：原单已 REVERSED / 非 COMPLETED → 领域层拒（→ 409）。
     */
    @Transactional
    public StockCountDocument reverse(String docNo, String operator) {
        return stockCountService.reverse(docNo, operator);
    }

    /** 按单据号查（不存在抛 StockCountNotFoundException → 404） */
    @Transactional(readOnly = true)
    public StockCountDocument get(String docNo) {
        return stockCountService.get(docNo);
    }

    /** 分页查询（按仓库/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<StockCountDocument> search(StockCountQuery query) {
        return stockCountService.search(query);
    }

    // ---------------------------------------------------------------
    // 入口层校验（拆解 §1.3：仓库/商品存在性与启用校验在此，不在领域服务）
    // ---------------------------------------------------------------

    private Warehouse requireEnabledWarehouse(long warehouseId) {
        Warehouse warehouse = warehouseService.get(warehouseId);
        if (warehouse.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("仓库已停用，禁止盘点: " + warehouse.getName()
                    + "（" + warehouse.getCode() + "）");
        }
        return warehouse;
    }

    private Product requireEnabledProduct(long productId) {
        Product product = productService.get(productId);
        if (product.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("商品已停用，禁止盘点: " + product.getName()
                    + "（" + product.getCode() + "）");
        }
        return product;
    }
}

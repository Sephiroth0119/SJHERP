package com.sjherp.app.transfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.transfer.TransferDtos.TransferLineRequest;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferLineInput;
import com.sjherp.domain.transfer.TransferQuery;
import com.sjherp.domain.transfer.TransferService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 调拨单应用服务（M3-T04）：REST {@code TransferController} 与 Agent 工具的公共入口。
 *
 * <p>职责（拆解 §1.3：仓库/商品存在性与启用校验在入口层；§1.4 取号先于库存锁行）：
 * <ul>
 *   <li>建单：校验调出仓/调入仓存在且启用、调出仓 ≠ 调入仓 + 各行商品存在且启用 →
 *       自动 TR- 编号 → 调领域 {@link TransferService#create}；</li>
 *   <li>审核 / 过账：直接委托领域服务（业务规则在领域层）；</li>
 *   <li><b>外层事务</b>：写方法标 {@code @Transactional}，把单据状态变更 + 库存两腿过账
 *       （领域服务内经 {@link TransactionalInventoryService}，REQUIRED 加入本事务）包成一个
 *       原子事务（参照 TransactionalInventoryService 模式，拆解 §1.4）。</li>
 * </ul>
 *
 * <p>领域 {@code TransferService} 不加事务（保持可独立测试），事务边界一律由本类提供。
 * 审计：领域服务写方法 @Audited，状态流转经 SyncDomainEventPublisher 自动落
 * document.status_changed 审计——均延迟到本事务 afterCommit（D-8）。
 */
@Service
public class TransferAppService {

    /** 调拨单编号规则：TR-202606-0001（拆解已拍板单据前缀 TR-） */
    static final DocumentNumberRule TRANSFER_RULE = DocumentNumberRule.of("TR");

    private final TransferService transferService;
    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final DocumentNumberGenerator numberGenerator;

    public TransferAppService(TransferService transferService, WarehouseService warehouseService,
                              ProductService productService, DocumentNumberGenerator numberGenerator) {
        this.transferService = Objects.requireNonNull(transferService, "transferService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建调拨单（草稿）：自动 TR- 编号。
     *
     * @param fromWarehouseId 调出仓库 id
     * @param toWarehouseId   调入仓库 id（必须 ≠ 调出仓）
     * @param remark          调拨说明（可空）
     * @param lines           行输入（商品 + 调拨数量）
     * @param operator        操作人
     */
    @Transactional
    public TransferDocument create(long fromWarehouseId, long toWarehouseId, String remark,
                                   List<TransferLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("调拨单至少要有一行");
        }
        if (fromWarehouseId == toWarehouseId) {
            throw new IllegalArgumentException("调出仓与调入仓不能相同（同仓调拨无意义）: 仓库 " + fromWarehouseId);
        }
        Warehouse fromWarehouse = requireEnabledWarehouse(fromWarehouseId, "调出仓");
        Warehouse toWarehouse = requireEnabledWarehouse(toWarehouseId, "调入仓");
        Set<Long> seen = new LinkedHashSet<>();
        List<TransferLineInput> domainLines = new ArrayList<>(lines.size());
        for (TransferLineRequest input : lines) {
            if (input.productId() == null) {
                throw new IllegalArgumentException("调拨行商品 id 不能为空");
            }
            long productId = input.productId();
            if (!seen.add(productId)) {
                throw new IllegalArgumentException("同一调拨单内商品不能重复: 商品 id " + productId);
            }
            requireEnabledProduct(productId);
            domainLines.add(new TransferLineInput(productId, input.quantity()));
        }
        String docNo = numberGenerator.generate(TRANSFER_RULE);
        return transferService.create(docNo, fromWarehouse.getId(), toWarehouse.getId(), remark,
                domainLines, operator);
    }

    /** 审核调拨单（DRAFT → APPROVED） */
    @Transactional
    public TransferDocument approve(String docNo, String operator) {
        return transferService.approve(docNo, operator);
    }

    /** 过账调拨单（APPROVED → EXECUTING → COMPLETED，产生两腿调拨流水） */
    @Transactional
    public TransferDocument post(String docNo, String operator) {
        return transferService.post(docNo, operator);
    }

    /**
     * 冲销调拨单（红字调拨单，M4-T07c，最高风险路径，不可逆）：对已过账（COMPLETED）的调拨单
     * 按原两腿成本<b>对称反向库存</b>（调出仓回补、调入仓出库），原单 COMPLETED → REVERSED。
     * <b>调拨不出 GL 凭证</b>（企业内部库存转移），故只反向库存、不红冲凭证（设计真源 §75）。
     *
     * <p>外层 {@code @Transactional}：领域 {@link TransferService#reverse} 内库存两腿反向经
     * {@link TransactionalInventoryService}（REQUIRED 加入本事务）原子提交——任一腿失败整事务回滚
     * （库存与单据状态一致）。幂等：原单已 REVERSED / 非 COMPLETED → 领域层拒（→ 409）。
     */
    @Transactional
    public TransferDocument reverse(String docNo, String operator) {
        return transferService.reverse(docNo, operator);
    }

    /** 按单据号查（不存在抛 TransferNotFoundException → 404） */
    @Transactional(readOnly = true)
    public TransferDocument get(String docNo) {
        return transferService.get(docNo);
    }

    /** 分页查询（按仓库/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<TransferDocument> search(TransferQuery query) {
        return transferService.search(query);
    }

    // ---------------------------------------------------------------
    // 入口层校验（拆解 §1.3：仓库/商品存在性与启用校验在此，不在领域服务）
    // ---------------------------------------------------------------

    private Warehouse requireEnabledWarehouse(long warehouseId, String label) {
        Warehouse warehouse = warehouseService.get(warehouseId);
        if (warehouse.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException(label + "已停用，禁止调拨: " + warehouse.getName()
                    + "（" + warehouse.getCode() + "）");
        }
        return warehouse;
    }

    private Product requireEnabledProduct(long productId) {
        Product product = productService.get(productId);
        if (product.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("商品已停用，禁止调拨: " + product.getName()
                    + "（" + product.getCode() + "）");
        }
        return product;
    }
}

package com.sjherp.app.sales;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.sales.SalesDtos.SalesDeliveryLineRequest;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLineInput;
import com.sjherp.domain.sales.SalesDeliveryQuery;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 销售出库单应用服务（M3-T09）：REST {@code SalesDeliveryController} 的入口。
 *
 * <p>职责：
 * <ul>
 *   <li>建单：校验出库仓存在且启用 → 自动 SD- 编号 → 调领域 {@link SalesDeliveryService#create}
 *       （订单存在/已审核、行剩余可发量等业务校验在领域服务）；</li>
 *   <li>审核 / 过账 / 作废：委托领域服务；</li>
 *   <li><b>外层事务</b>：过账写方法标 {@code @Transactional}，把单据状态变更 + SALES_OUT 库存过账
 *       （经 {@link TransactionalInventoryService}，REQUIRED 加入本事务）+ COGS 回填 + 回写订单
 *       累计发货量包成一个原子事务（拆解 §1.4）。库存不足整批回滚（销售出库强校验库存）。</li>
 * </ul>
 */
@Service
public class SalesDeliveryAppService {

    /** 出库单编号规则：SD-202606-0001 */
    static final DocumentNumberRule SD_RULE = DocumentNumberRule.of("SD");

    private final SalesDeliveryService salesDeliveryService;
    private final WarehouseService warehouseService;
    private final DocumentNumberGenerator numberGenerator;

    public SalesDeliveryAppService(SalesDeliveryService salesDeliveryService,
                                   WarehouseService warehouseService,
                                   DocumentNumberGenerator numberGenerator) {
        this.salesDeliveryService = Objects.requireNonNull(salesDeliveryService, "salesDeliveryService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建销售出库单（草稿）：自动 SD- 编号。
     *
     * @param salesOrderNo 引用的销售订单号
     * @param warehouseId  出库仓库 id
     * @param remark       出库说明（可空）
     * @param lines        行输入（关联订单行号 + 商品 + 发货数量）
     * @param operator     操作人
     */
    @Transactional
    public SalesDelivery create(String salesOrderNo, long warehouseId, String remark,
                                List<SalesDeliveryLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("销售出库单至少要有一行");
        }
        Warehouse warehouse = requireEnabledWarehouse(warehouseId);
        List<SalesDeliveryLineInput> domainLines = new ArrayList<>(lines.size());
        for (SalesDeliveryLineRequest input : lines) {
            if (input.soLineNo() == null) {
                throw new IllegalArgumentException("出库行关联订单行号 soLineNo 不能为空");
            }
            if (input.productId() == null) {
                throw new IllegalArgumentException("出库行商品 id 不能为空");
            }
            domainLines.add(new SalesDeliveryLineInput(input.soLineNo(), input.productId(),
                    input.quantity()));
        }
        String docNo = numberGenerator.generate(SD_RULE);
        return salesDeliveryService.create(docNo, salesOrderNo, warehouse.getId(), remark,
                domainLines, operator);
    }

    /** 审核出库单（DRAFT → APPROVED） */
    @Transactional
    public SalesDelivery approve(String docNo, String operator) {
        return salesDeliveryService.approve(docNo, operator);
    }

    /** 过账出库单（APPROVED → EXECUTING → COMPLETED，产生 SALES_OUT 流水 + COGS 回填 + 回写订单发货量） */
    @Transactional
    public SalesDelivery post(String docNo, String operator) {
        return salesDeliveryService.post(docNo, operator);
    }

    /** 作废出库单（仅 DRAFT 可作废） */
    @Transactional
    public SalesDelivery cancel(String docNo, String operator) {
        return salesDeliveryService.cancel(docNo, operator);
    }

    /** 按单据号查（不存在抛 SalesDeliveryNotFoundException → 404） */
    @Transactional(readOnly = true)
    public SalesDelivery get(String docNo) {
        return salesDeliveryService.get(docNo);
    }

    /** 分页查询（按关联订单/仓库/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<SalesDelivery> search(SalesDeliveryQuery query) {
        return salesDeliveryService.search(query);
    }

    private Warehouse requireEnabledWarehouse(long warehouseId) {
        Warehouse warehouse = warehouseService.get(warehouseId);
        if (warehouse.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("出库仓已停用，禁止出库: " + warehouse.getName()
                    + "（" + warehouse.getCode() + "）");
        }
        return warehouse;
    }
}

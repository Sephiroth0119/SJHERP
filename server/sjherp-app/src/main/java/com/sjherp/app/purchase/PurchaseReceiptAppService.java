package com.sjherp.app.purchase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseReceiptLineRequest;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptQuery;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 采购入库单应用服务（M3-T06）：REST {@code PurchaseReceiptController} 与（后续）Agent 工具的入口。
 *
 * <p>职责（拆解 §1.3：仓库存在性与启用校验在入口层；引用采购订单的状态/部分收货校验在领域服务）：
 * <ul>
 *   <li>建单：校验收货仓库存在且启用 → 自动 PR- 编号 → 调领域 {@link PurchaseReceiptService#create}
 *       （引用采购订单、部分收货数量校验在领域层）；</li>
 *   <li>审核 / 过账 / 查询：直接委托领域服务；</li>
 *   <li><b>外层事务</b>：写方法标 {@code @Transactional}，把单据状态变更 + 库存 PURCHASE_IN 过账
 *       （领域服务内经 {@code TransactionalInventoryService}，REQUIRED 加入本事务）+ 采购订单
 *       到货量回写包成一个原子事务（拆解 §1.4）。</li>
 * </ul>
 */
@Service
public class PurchaseReceiptAppService {

    /** 采购入库单编号规则：PR-202606-0001 */
    static final DocumentNumberRule PURCHASE_RECEIPT_RULE = DocumentNumberRule.of("PR");

    private final PurchaseReceiptService purchaseReceiptService;
    private final WarehouseService warehouseService;
    private final DocumentNumberGenerator numberGenerator;
    private final AutoVoucherService autoVoucherService;

    public PurchaseReceiptAppService(PurchaseReceiptService purchaseReceiptService,
                                     WarehouseService warehouseService,
                                     DocumentNumberGenerator numberGenerator,
                                     AutoVoucherService autoVoucherService) {
        this.purchaseReceiptService = Objects.requireNonNull(purchaseReceiptService,
                "purchaseReceiptService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService,
                "autoVoucherService 不能为空");
    }

    /**
     * 创建采购入库单（草稿）：引用某采购订单收货，自动 PR- 编号。
     *
     * @param purchaseOrderNo 引用的采购订单号（必须 APPROVED）
     * @param warehouseId     收货仓库 id
     * @param receiptDate     收货日期（为空时默认今天）
     * @param remark          收货说明（可空）
     * @param lines           行输入（引用采购订单行 + 收货数量 + 可选收货单价）
     * @param operator        操作人
     */
    @Transactional
    public PurchaseReceipt create(String purchaseOrderNo, long warehouseId, LocalDate receiptDate,
                                  String remark, List<PurchaseReceiptLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("采购入库单至少要有一行");
        }
        Warehouse warehouse = requireEnabledWarehouse(warehouseId);
        List<PurchaseReceiptLineInput> domainLines = new ArrayList<>(lines.size());
        for (PurchaseReceiptLineRequest input : lines) {
            if (input.poLineNo() == null) {
                throw new IllegalArgumentException("收货行引用的采购订单行号不能为空");
            }
            domainLines.add(new PurchaseReceiptLineInput(input.poLineNo(), input.quantity(),
                    input.unitCost()));
        }
        LocalDate effectiveDate = receiptDate != null ? receiptDate : LocalDate.now();
        String docNo = numberGenerator.generate(PURCHASE_RECEIPT_RULE);
        return purchaseReceiptService.create(docNo, purchaseOrderNo, warehouse.getId(), effectiveDate,
                remark, domainLines, operator);
    }

    /** 审核采购入库单（DRAFT → APPROVED） */
    @Transactional
    public PurchaseReceipt approve(String docNo, String operator) {
        return purchaseReceiptService.approve(docNo, operator);
    }

    /**
     * 过账采购入库单（APPROVED → EXECUTING → COMPLETED，产生 PURCHASE_IN 入库流水 + 回写到货量）；
     * 同事务内自动生成记账凭证（借 1405 库存商品 / 贷 220201 暂估应付款，T02）。
     */
    @Transactional
    public PurchaseReceipt post(String docNo, String operator) {
        PurchaseReceipt receipt = purchaseReceiptService.post(docNo, operator);
        autoVoucherService.generateForPurchaseReceipt(receipt, operator);   // T02 自动凭证
        return receipt;
    }

    /** 按单据号查（不存在抛 PurchaseReceiptNotFoundException → 404） */
    @Transactional(readOnly = true)
    public PurchaseReceipt get(String docNo) {
        return purchaseReceiptService.get(docNo);
    }

    /** 分页查询（按仓库/采购订单号/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<PurchaseReceipt> search(PurchaseReceiptQuery query) {
        return purchaseReceiptService.search(query);
    }

    // ---------------------------------------------------------------
    // 入口层校验（拆解 §1.3：仓库存在性与启用校验在此）
    // ---------------------------------------------------------------

    private Warehouse requireEnabledWarehouse(long warehouseId) {
        Warehouse warehouse = warehouseService.get(warehouseId);
        if (warehouse.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("收货仓库已停用，禁止收货: " + warehouse.getName()
                    + "（" + warehouse.getCode() + "）");
        }
        return warehouse;
    }
}

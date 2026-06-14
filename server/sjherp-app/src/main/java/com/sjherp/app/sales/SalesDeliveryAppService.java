package com.sjherp.app.sales;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.sales.SalesDtos.SalesDeliveryLineRequest;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
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
    private final AutoVoucherService autoVoucherService;
    private final VoucherService voucherService;
    private final VoucherAppService voucherAppService;

    public SalesDeliveryAppService(SalesDeliveryService salesDeliveryService,
                                   WarehouseService warehouseService,
                                   DocumentNumberGenerator numberGenerator,
                                   AutoVoucherService autoVoucherService,
                                   VoucherService voucherService,
                                   VoucherAppService voucherAppService) {
        this.salesDeliveryService = Objects.requireNonNull(salesDeliveryService, "salesDeliveryService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService, "autoVoucherService 不能为空");
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.voucherAppService = Objects.requireNonNull(voucherAppService, "voucherAppService 不能为空");
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

    /**
     * 过账出库单（APPROVED → EXECUTING → COMPLETED，产生 SALES_OUT 流水 + COGS 回填 + 回写订单发货量）；
     * 同事务内自动生成记账凭证（借 6401 主营业务成本 / 贷 1405 库存商品，金额=Σ COGS，T02；COGS=0 跳过）。
     */
    @Transactional
    public SalesDelivery post(String docNo, String operator) {
        SalesDelivery delivery = salesDeliveryService.post(docNo, operator);
        autoVoucherService.generateForSalesDelivery(delivery, operator);   // T02 自动凭证（COGS 已回填）
        return delivery;
    }

    /**
     * 冲销已过账出库单（红字出库，M4-T07b）：同一 {@code @Transactional} 内
     * ①红冲出库自动凭证（借贷对调 SALES_DELIVERY 凭证，{@link VoucherAppService#reverse}——内含账期 OPEN
     * 校验，闭月时抛 PeriodClosedException 使整单回滚）→②库存按原 COGS 反向入库 + 回退订单累计发货量 +
     * 单据 COMPLETED → REVERSED（{@link SalesDeliveryService#reverse}），红字凭证号作冲销链路锚点。
     *
     * <p>顺序：先红冲凭证拿到红字号作锚点，再驱动库存/单据反向；任一步失败整事务回滚（库存/订单/凭证/单据
     * 一并撤销，设计真源 §2 原子性）。原单已 REVERSED 时 {@code salesDeliveryService.reverse} 经状态机非法流转拒
     * （幂等兜底）。COGS=0 的出库单无自动凭证（无金额无凭证），此时无凭证可红冲、锚点退化为原单号。
     */
    @Transactional
    public SalesDelivery reverse(String docNo, String operator) {
        // ① 红冲出库自动凭证（按来源单据号反查 SALES_DELIVERY 凭证）→ 红字号作冲销链路锚点
        String reversalAnchor = reverseAutoVoucher(docNo, operator);
        // ② 库存按原 COGS 反向入库 + 回退订单发货量 + 单据冲销（同事务原子）
        return salesDeliveryService.reverse(docNo, reversalAnchor, operator);
    }

    /**
     * 红冲某出库单的自动凭证（SALES_DELIVERY 来源），返回冲销链路锚点：
     * 命中则红冲并返回红字凭证号；COGS=0 无自动凭证时返回原单号（仍是可审计的有意义锚点）。
     */
    private String reverseAutoVoucher(String docNo, String operator) {
        Voucher source = voucherService.findBySourceDocNo(docNo).stream()
                .filter(v -> VoucherSourceType.SALES_DELIVERY.name().equals(v.getSourceDocType()))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return docNo;   // COGS=0 无自动凭证：锚点退化为原出库单号
        }
        return voucherAppService.reverse(source.getDocNo(), operator).getDocNo();
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

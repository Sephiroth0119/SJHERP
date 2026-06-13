package com.sjherp.app.purchase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.purchase.PurchaseDtos.PurchaseOrderLineRequest;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderQuery;
import com.sjherp.domain.purchase.PurchaseOrderService;

/**
 * 采购订单应用服务（M3-T05）：REST {@code PurchaseOrderController} 与 Agent 工具的公共入口。
 *
 * <p>职责（拆解 §1.3：供应商/商品存在性与启用校验在入口层）：
 * <ul>
 *   <li>建单：校验供应商存在且启用 + 各行商品存在且启用 → 自动 PO- 编号 →
 *       调领域 {@link PurchaseOrderService#create}；</li>
 *   <li>审核 / 关闭 / 查询：直接委托领域服务（业务规则在领域层）；</li>
 *   <li><b>外层事务</b>：写方法标 {@code @Transactional}。下单不动库存，但状态流转的审计落库
 *       延迟到本事务 afterCommit（D-8），事务边界由本类统一提供。</li>
 * </ul>
 *
 * <p>领域 {@code PurchaseOrderService} 不加事务（保持可独立测试）。审计：领域服务写方法 @Audited，
 * 状态流转经 SyncDomainEventPublisher 自动落 document.status_changed 审计。
 */
@Service
public class PurchaseOrderAppService {

    /** 采购订单编号规则：PO-202606-0001 */
    static final DocumentNumberRule PURCHASE_ORDER_RULE = DocumentNumberRule.of("PO");

    private final PurchaseOrderService purchaseOrderService;
    private final SupplierService supplierService;
    private final ProductService productService;
    private final DocumentNumberGenerator numberGenerator;

    public PurchaseOrderAppService(PurchaseOrderService purchaseOrderService,
                                   SupplierService supplierService, ProductService productService,
                                   DocumentNumberGenerator numberGenerator) {
        this.purchaseOrderService = Objects.requireNonNull(purchaseOrderService,
                "purchaseOrderService 不能为空");
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建采购订单（草稿）：自动 PO- 编号。
     *
     * @param supplierId 供应商 id
     * @param orderDate  下单日期（为空时默认今天）
     * @param remark     采购说明（可空）
     * @param lines      行输入（商品 + 订购数量 + 采购单价）
     * @param operator   操作人
     */
    @Transactional
    public PurchaseOrder create(long supplierId, LocalDate orderDate, String remark,
                                List<PurchaseOrderLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("采购订单至少要有一行");
        }
        Supplier supplier = requireEnabledSupplier(supplierId);
        Set<Long> seen = new LinkedHashSet<>();
        List<PurchaseOrderLineInput> domainLines = new ArrayList<>(lines.size());
        for (PurchaseOrderLineRequest input : lines) {
            if (input.productId() == null) {
                throw new IllegalArgumentException("采购订单行商品 id 不能为空");
            }
            long productId = input.productId();
            if (!seen.add(productId)) {
                throw new IllegalArgumentException("同一采购订单内商品不能重复: 商品 id " + productId);
            }
            requireEnabledProduct(productId);
            domainLines.add(new PurchaseOrderLineInput(productId, input.quantity(), input.unitPrice()));
        }
        LocalDate effectiveDate = orderDate != null ? orderDate : LocalDate.now();
        String docNo = numberGenerator.generate(PURCHASE_ORDER_RULE);
        return purchaseOrderService.create(docNo, supplier.getId(), effectiveDate, remark,
                domainLines, operator);
    }

    /** 审核采购订单（DRAFT → APPROVED） */
    @Transactional
    public PurchaseOrder approve(String docNo, String operator) {
        return purchaseOrderService.approve(docNo, operator);
    }

    /** 关闭采购订单（APPROVED → EXECUTING → COMPLETED） */
    @Transactional
    public PurchaseOrder close(String docNo, String operator) {
        return purchaseOrderService.close(docNo, operator);
    }

    /** 按单据号查（不存在抛 PurchaseOrderNotFoundException → 404） */
    @Transactional(readOnly = true)
    public PurchaseOrder get(String docNo) {
        return purchaseOrderService.get(docNo);
    }

    /** 分页查询（按供应商/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<PurchaseOrder> search(PurchaseOrderQuery query) {
        return purchaseOrderService.search(query);
    }

    // ---------------------------------------------------------------
    // 入口层校验（拆解 §1.3：供应商/商品存在性与启用校验在此，不在领域服务）
    // ---------------------------------------------------------------

    private Supplier requireEnabledSupplier(long supplierId) {
        Supplier supplier = supplierService.get(supplierId);
        if (supplier.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("供应商已停用，禁止下单: " + supplier.getName()
                    + "（" + supplier.getCode() + "）");
        }
        return supplier;
    }

    private Product requireEnabledProduct(long productId) {
        Product product = productService.get(productId);
        if (product.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("商品已停用，禁止采购: " + product.getName()
                    + "（" + product.getCode() + "）");
        }
        return product;
    }
}

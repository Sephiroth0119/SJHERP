package com.sjherp.app.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.sales.SalesDtos.SalesOrderLineRequest;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderLineInput;
import com.sjherp.domain.sales.SalesOrderQuery;
import com.sjherp.domain.sales.SalesOrderService;

/**
 * 销售订单应用服务（M3-T08）：REST {@code SalesOrderController} 与 Agent 工具的公共入口。
 *
 * <p>职责（拆解口径：客户/商品存在性与启用校验在入口层）：
 * <ul>
 *   <li>建单：校验客户存在且启用、各行商品存在且启用 → 自动 SO- 编号 →
 *       调领域 {@link SalesOrderService#create}；</li>
 *   <li><b>可用库存检查（仅警告不阻断）</b>：下单时按给定仓库（可选）查各行商品可用量，
 *       不足仅在返回中给出警告 {@link CreateResult#warnings()}，不拒绝下单（下单不动库存，
 *       默认提示，可配置 {@code sjherp.sales.order.available-stock-warning}）；</li>
 *   <li>审核 / 作废：直接委托领域服务。</li>
 * </ul>
 *
 * <p>领域 {@code SalesOrderService} 不加事务（保持可独立测试），事务边界由本类提供。
 */
@Service
public class SalesOrderAppService {

    /** 销售订单编号规则：SO-202606-0001 */
    static final DocumentNumberRule SO_RULE = DocumentNumberRule.of("SO");

    private final SalesOrderService salesOrderService;
    private final CustomerService customerService;
    private final ProductService productService;
    private final TransactionalInventoryService inventoryService;
    private final DocumentNumberGenerator numberGenerator;

    /** 下单可用库存检查开关：true=不足时给警告（默认），false=不检查 */
    private final boolean availableStockWarning;

    public SalesOrderAppService(SalesOrderService salesOrderService, CustomerService customerService,
                                ProductService productService,
                                TransactionalInventoryService inventoryService,
                                DocumentNumberGenerator numberGenerator,
                                @Value("${sjherp.sales.order.available-stock-warning:true}")
                                boolean availableStockWarning) {
        this.salesOrderService = Objects.requireNonNull(salesOrderService, "salesOrderService 不能为空");
        this.customerService = Objects.requireNonNull(customerService, "customerService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.availableStockWarning = availableStockWarning;
    }

    /**
     * 建单结果：单据 + 可用库存不足警告（不阻断下单）。
     *
     * @param order    创建好的订单
     * @param warnings 可用库存不足的行警告（空表示无不足或未检查）
     */
    public record CreateResult(SalesOrder order, List<String> warnings) {
    }

    /**
     * 创建销售订单（草稿）：自动 SO- 编号；可用库存不足仅警告不阻断。
     *
     * @param customerId  客户 id
     * @param orderDate   订单日期（空则取当天）
     * @param remark      订单说明（可空）
     * @param checkWarehouseId 可用库存检查仓库 id（可空——空则跳过可用库存检查）
     * @param lines       行输入（商品 + 数量 + 单价）
     * @param operator    操作人
     */
    @Transactional
    public CreateResult create(long customerId, LocalDate orderDate, String remark,
                               Long checkWarehouseId, List<SalesOrderLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("销售订单至少要有一行");
        }
        Customer customer = requireEnabledCustomer(customerId);
        Set<Long> seen = new LinkedHashSet<>();
        List<SalesOrderLineInput> domainLines = new ArrayList<>(lines.size());
        List<String> warnings = new ArrayList<>();
        for (SalesOrderLineRequest input : lines) {
            if (input.productId() == null) {
                throw new IllegalArgumentException("订单行商品 id 不能为空");
            }
            long productId = input.productId();
            if (!seen.add(productId)) {
                throw new IllegalArgumentException("同一销售订单内商品不能重复: 商品 id " + productId);
            }
            Product product = requireEnabledProduct(productId);
            domainLines.add(new SalesOrderLineInput(productId, input.quantity(), input.unitPrice()));
            // 可用库存检查（仅警告，不阻断）
            if (availableStockWarning && checkWarehouseId != null) {
                appendStockWarningIfShort(warnings, checkWarehouseId, product, input.quantity());
            }
        }

        LocalDate effectiveDate = orderDate != null ? orderDate : LocalDate.now();
        String docNo = numberGenerator.generate(SO_RULE);
        SalesOrder order = salesOrderService.create(docNo, customer.getId(), effectiveDate, remark,
                domainLines, operator);
        return new CreateResult(order, warnings);
    }

    /** 审核销售订单（DRAFT → APPROVED） */
    @Transactional
    public SalesOrder approve(String docNo, String operator) {
        return salesOrderService.approve(docNo, operator);
    }

    /** 作废销售订单（仅 DRAFT 可作废） */
    @Transactional
    public SalesOrder cancel(String docNo, String operator) {
        return salesOrderService.cancel(docNo, operator);
    }

    /** 按单据号查（不存在抛 SalesOrderNotFoundException → 404） */
    @Transactional(readOnly = true)
    public SalesOrder get(String docNo) {
        return salesOrderService.get(docNo);
    }

    /** 分页查询（按客户/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<SalesOrder> search(SalesOrderQuery query) {
        return salesOrderService.search(query);
    }

    // ---------------------------------------------------------------
    // 入口层校验
    // ---------------------------------------------------------------

    private void appendStockWarningIfShort(List<String> warnings, long warehouseId, Product product,
                                           BigDecimal quantity) {
        if (quantity == null) {
            return;
        }
        InventoryBalanceView balance = inventoryService.balanceOf(warehouseId, product.getId());
        if (balance.quantity().compareTo(quantity) < 0) {
            warnings.add("商品「" + product.getName() + "」（" + product.getCode() + "）下单量 "
                    + quantity.toPlainString() + " 超过仓库现存可用量 "
                    + balance.quantity().toPlainString() + "（下单不动库存，发货时请确保补足）");
        }
    }

    private Customer requireEnabledCustomer(long customerId) {
        Customer customer = customerService.get(customerId);
        if (customer.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("客户已停用，禁止下单: " + customer.getName()
                    + "（" + customer.getCode() + "）");
        }
        return customer;
    }

    private Product requireEnabledProduct(long productId) {
        Product product = productService.get(productId);
        if (product.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("商品已停用，禁止下单: " + product.getName()
                    + "（" + product.getCode() + "）");
        }
        return product;
    }
}

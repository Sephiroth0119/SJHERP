package com.sjherp.app.dataimport;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.dataimport.ExcelWorkbookReader.FileImportException;
import com.sjherp.app.dataimport.ImportDtos.ImportResult;
import com.sjherp.app.dataimport.ImportDtos.RowFailure;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.SupplierCommand;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 导入应用服务（M2-T09）：解析 Excel → 逐行经领域服务写入，全有或全无。
 *
 * <p>架构要点：
 * <ul>
 *   <li>{@code @Transactional}：单文件单事务，任一行失败抛 {@link ImportRejectedException}
 *       触发整体回滚——保证建账期数据基线一致性。</li>
 *   <li>写操作一律经领域/应用服务（ProductService / CustomerService / SupplierService /
 *       InventoryAdjustmentService），绝不裸 SQL 写库（CLAUDE.md 原则 1）。</li>
 *   <li>操作人为当前登录名（{@code CurrentUser.operator()}），由控制器传入，
 *       区别于 Agent 工具的 {@code agent:<userId>} 前缀。</li>
 *   <li>数量/金额一律 BigDecimal，经 {@link ImportSupport#decimal} 从纯文本解析
 *       （严禁经 double，保证精度，CLAUDE.md 原则 5）。</li>
 * </ul>
 */
@Service
public class ImportService {

    private final ExcelWorkbookReader reader = new ExcelWorkbookReader();

    private final ProductService productService;
    private final CustomerService customerService;
    private final SupplierService supplierService;
    private final WarehouseService warehouseService;
    private final UnitService unitService;
    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final TransactionalInventoryService inventoryService;

    public ImportService(ProductService productService,
                         CustomerService customerService,
                         SupplierService supplierService,
                         WarehouseService warehouseService,
                         UnitService unitService,
                         InventoryAdjustmentService inventoryAdjustmentService,
                         TransactionalInventoryService inventoryService) {
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.customerService = Objects.requireNonNull(customerService, "customerService 不能为空");
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.unitService = Objects.requireNonNull(unitService, "unitService 不能为空");
        this.inventoryAdjustmentService = Objects.requireNonNull(inventoryAdjustmentService,
                "inventoryAdjustmentService 不能为空");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
    }

    // ----------------------------------------------------------------
    // 商品档案导入
    // ----------------------------------------------------------------

    /**
     * 商品档案批量导入（全有或全无）。
     *
     * @param file     上传的 .xlsx 文件
     * @param operator 操作人（登录名，CurrentUser.operator()）
     * @return ImportResult 成功或携带失败清单
     * @throws FileImportException     文件级错误（非法格式/缺列）→ 控制器返回 400
     * @throws ImportRejectedException 行级失败（任一行拒绝）→ 整体回滚 + 返回结构化报告
     */
    @Transactional
    public ImportResult importProducts(MultipartFile file, String operator) {
        ExcelWorkbookReader.ParsedSheet sheet;
        try (InputStream is = file.getInputStream()) {
            sheet = reader.open(is, ImportColumns.PRODUCT_REQUIRED);
        } catch (IOException e) {
            throw new FileImportException("无法读取上传文件：" + e.getMessage());
        }

        List<Map<String, String>> rows = sheet.rows();
        List<RowFailure> failures = new ArrayList<>();

        for (Map<String, String> row : rows) {
            int rowNum = ExcelWorkbookReader.rowNum(row);
            try {
                String name = requireCol(row, ImportColumns.PRODUCT_NAME, rowNum, failures);
                if (name == null) {
                    continue;
                }
                String unitName = requireCol(row, ImportColumns.PRODUCT_UNIT, rowNum, failures);
                if (unitName == null) {
                    continue;
                }
                Unit unit = ImportSupport.resolveUnit(unitService, unitName);
                String code = ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_CODE);
                String spec = ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_SPEC);
                String barcode = ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_BARCODE);
                String remark = ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_REMARK);

                ProductCommand cmd = new ProductCommand(code, name, spec, null,
                        unit.getId(), barcode, remark, List.of(), InventoryCategory.MERCHANDISE);
                productService.create(cmd, operator);

            } catch (IllegalArgumentException e) {
                failures.add(RowFailure.ofRow(rowNum, e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            throw new ImportRejectedException("products", rows.size(), failures);
        }
        return ImportResult.ok("products", rows.size());
    }

    // ----------------------------------------------------------------
    // 客户档案导入
    // ----------------------------------------------------------------

    /**
     * 客户档案批量导入（全有或全无）。
     */
    @Transactional
    public ImportResult importCustomers(MultipartFile file, String operator) {
        ExcelWorkbookReader.ParsedSheet sheet;
        try (InputStream is = file.getInputStream()) {
            sheet = reader.open(is, ImportColumns.CUSTOMER_REQUIRED);
        } catch (IOException e) {
            throw new FileImportException("无法读取上传文件：" + e.getMessage());
        }

        List<Map<String, String>> rows = sheet.rows();
        List<RowFailure> failures = new ArrayList<>();

        for (Map<String, String> row : rows) {
            int rowNum = ExcelWorkbookReader.rowNum(row);
            try {
                String name = requireCol(row, ImportColumns.CUSTOMER_NAME, rowNum, failures);
                if (name == null) {
                    continue;
                }
                String settlementStr = requireCol(row, ImportColumns.CUSTOMER_SETTLEMENT, rowNum, failures);
                if (settlementStr == null) {
                    continue;
                }
                SettlementMethod settlement = ImportSupport.parseSettlementMethod(settlementStr);
                String code = ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_CODE);
                String contactPerson = ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_CONTACT_PERSON);
                String contactPhone = ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_CONTACT_PHONE);
                String address = ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_ADDRESS);
                String taxNo = ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_TAX_NO);
                BigDecimal creditLimit = parseCreditLimit(
                        ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_CREDIT_LIMIT));

                CustomerCommand cmd = new CustomerCommand(code, name, contactPerson, contactPhone,
                        address, taxNo, settlement, creditLimit);
                customerService.create(cmd, operator);

            } catch (IllegalArgumentException e) {
                failures.add(RowFailure.ofRow(rowNum, e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            throw new ImportRejectedException("customers", rows.size(), failures);
        }
        return ImportResult.ok("customers", rows.size());
    }

    // ----------------------------------------------------------------
    // 供应商档案导入
    // ----------------------------------------------------------------

    /**
     * 供应商档案批量导入（全有或全无）。
     */
    @Transactional
    public ImportResult importSuppliers(MultipartFile file, String operator) {
        ExcelWorkbookReader.ParsedSheet sheet;
        try (InputStream is = file.getInputStream()) {
            sheet = reader.open(is, ImportColumns.SUPPLIER_REQUIRED);
        } catch (IOException e) {
            throw new FileImportException("无法读取上传文件：" + e.getMessage());
        }

        List<Map<String, String>> rows = sheet.rows();
        List<RowFailure> failures = new ArrayList<>();

        for (Map<String, String> row : rows) {
            int rowNum = ExcelWorkbookReader.rowNum(row);
            try {
                String name = requireCol(row, ImportColumns.SUPPLIER_NAME, rowNum, failures);
                if (name == null) {
                    continue;
                }
                String settlementStr = requireCol(row, ImportColumns.SUPPLIER_SETTLEMENT, rowNum, failures);
                if (settlementStr == null) {
                    continue;
                }
                SettlementMethod settlement = ImportSupport.parseSettlementMethod(settlementStr);
                String code = ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_CODE);
                String contactPerson = ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_CONTACT_PERSON);
                String contactPhone = ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_CONTACT_PHONE);
                String address = ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_ADDRESS);
                String taxNo = ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_TAX_NO);

                SupplierCommand cmd = new SupplierCommand(code, name, contactPerson, contactPhone,
                        address, taxNo, settlement);
                supplierService.create(cmd, operator);

            } catch (IllegalArgumentException e) {
                failures.add(RowFailure.ofRow(rowNum, e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            throw new ImportRejectedException("suppliers", rows.size(), failures);
        }
        return ImportResult.ok("suppliers", rows.size());
    }

    // ----------------------------------------------------------------
    // 期初库存导入
    // ----------------------------------------------------------------

    /**
     * 期初库存批量导入（全有或全无），经 {@link InventoryAdjustmentService#opening} 唯一合法写入口。
     */
    @Transactional
    public ImportResult importOpeningStock(MultipartFile file, String operator) {
        ExcelWorkbookReader.ParsedSheet sheet;
        try (InputStream is = file.getInputStream()) {
            sheet = reader.open(is, ImportColumns.OPENING_STOCK_REQUIRED);
        } catch (IOException e) {
            throw new FileImportException("无法读取上传文件：" + e.getMessage());
        }

        List<Map<String, String>> rows = sheet.rows();
        List<RowFailure> failures = new ArrayList<>();
        // 期初基线一致性（防双倍建账）：同一(仓库,商品)在本次文件内只能一行
        Set<String> seenKeys = new HashSet<>();

        for (Map<String, String> row : rows) {
            int rowNum = ExcelWorkbookReader.rowNum(row);
            try {
                String warehouseText = requireCol(row, ImportColumns.OPENING_WAREHOUSE, rowNum, failures);
                if (warehouseText == null) {
                    continue;
                }
                String productText = requireCol(row, ImportColumns.OPENING_PRODUCT, rowNum, failures);
                if (productText == null) {
                    continue;
                }
                String quantityText = requireCol(row, ImportColumns.OPENING_QUANTITY, rowNum, failures);
                if (quantityText == null) {
                    continue;
                }
                String unitCostText = requireCol(row, ImportColumns.OPENING_UNIT_COST, rowNum, failures);
                if (unitCostText == null) {
                    continue;
                }

                Warehouse warehouse = ImportSupport.resolveWarehouse(warehouseService, warehouseText);
                Product product = ImportSupport.resolveProduct(productService, productText);

                // 防双倍建账（CLAUDE.md 原则 1，建账基线一致性）：
                // 1) 同一(仓库,商品)在本次文件内重复——期初每个仓库+商品只能一行；
                // 2) 该(仓库,商品)已有库存（如上一批已导入或已有业务流水）——不可再次期初，
                //    重传同一文件会因此被整体拒绝（而非把期初余额再叠加一次）。
                String key = warehouse.getId() + ":" + product.getId();
                if (!seenKeys.add(key)) {
                    throw new IllegalArgumentException("仓库[" + warehouse.getCode() + "]+商品["
                            + product.getCode() + "] 在本次导入中重复——期初每个仓库+商品只能一行");
                }
                if (inventoryService.balanceOf(warehouse.getId(), product.getId())
                        .quantity().signum() != 0) {
                    throw new IllegalArgumentException("仓库[" + warehouse.getCode() + "]+商品["
                            + product.getCode() + "] 已有库存，不可重复期初导入"
                            + "（如需调整请走盘点或成本调整）");
                }

                BigDecimal quantity = ImportSupport.decimal(quantityText, ImportColumns.OPENING_QUANTITY);
                BigDecimal unitCost = ImportSupport.decimal(unitCostText, ImportColumns.OPENING_UNIT_COST);

                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("期初数量必须为正数：" + quantityText);
                }
                if (unitCost.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("期初单价不能为负数：" + unitCostText);
                }

                inventoryAdjustmentService.opening(warehouse.getId(), product.getId(),
                        quantity, unitCost, operator);

            } catch (IllegalArgumentException e) {
                failures.add(RowFailure.ofRow(rowNum, e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            throw new ImportRejectedException("opening-stock", rows.size(), failures);
        }
        return ImportResult.ok("opening-stock", rows.size());
    }

    // ----------------------------------------------------------------
    // 内部工具
    // ----------------------------------------------------------------

    /**
     * 读取必填列：为空时收集失败并返回 null（调用方检查 null 并跳过后续处理）。
     * 如果 failures 列表已因本行有其他错误而被污染，仍收集本条（完整报错）。
     */
    private static String requireCol(Map<String, String> row, String column,
                                     int rowNum, List<RowFailure> failures) {
        String val = ExcelWorkbookReader.col(row, column);
        if (val == null) {
            failures.add(RowFailure.of(rowNum, column, null, column + "不能为空"));
            return null;
        }
        return val;
    }

    /**
     * 解析信用额度（可选；为空则 null；非负数值 BigDecimal）。
     * 解析失败抛 IllegalArgumentException，由调用方的外层 try/catch 收集为 RowFailure。
     */
    private static BigDecimal parseCreditLimit(String text) {
        if (text == null) {
            return null;
        }
        BigDecimal val = ImportSupport.decimal(text, ImportColumns.CUSTOMER_CREDIT_LIMIT);
        if (val.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("信用额度不能为负数：" + text);
        }
        return val;
    }
}

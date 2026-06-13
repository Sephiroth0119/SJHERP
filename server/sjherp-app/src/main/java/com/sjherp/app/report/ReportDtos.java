package com.sjherp.app.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.sjherp.app.report.ReportQueryDao.InventoryBalanceReport;
import com.sjherp.app.report.ReportQueryDao.InventoryBalanceRow;
import com.sjherp.app.report.ReportQueryDao.PurchaseDetailReport;
import com.sjherp.app.report.ReportQueryDao.PurchaseDetailRow;
import com.sjherp.app.report.ReportQueryDao.SalesDetailReport;
import com.sjherp.app.report.ReportQueryDao.SalesDetailRow;
import com.sjherp.app.report.ReportQueryDao.StockMovementReport;
import com.sjherp.app.report.ReportQueryDao.StockMovementRow;
import com.sjherp.domain.inventory.InventoryBalanceView;

/**
 * 进销存报表 API 的响应 DTO（M3-T12）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：数量/单价/金额在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——口径同库存/选项返回协议。
 * 派生值（加权单价、销售额、毛利）用 BigDecimal 现算（2/6 位 HALF_UP），不可计算时为 null（前端显示「—」）。
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    // ================================================================ 1. 库存余额表

    /** 库存余额报表响应：分页明细 + 库存总值（Σ结存金额）。 */
    public record InventoryBalanceReportResponse(List<BalanceItem> items, long total, int page, int size,
                                                 String totalCostAmount) {

        static InventoryBalanceReportResponse from(InventoryBalanceReport report) {
            return new InventoryBalanceReportResponse(
                    report.page().items().stream().map(BalanceItem::from).toList(),
                    report.page().total(), report.page().page(), report.page().size(),
                    plain(report.totalCostAmount()));
        }
    }

    /** 余额行（unitCost 为派生加权单价，数量 ≤ 0 时 null）。 */
    public record BalanceItem(long warehouseId, String warehouseCode, String warehouseName,
                              long productId, String productCode, String productName,
                              String quantity, String costAmount, String unitCost) {

        static BalanceItem from(InventoryBalanceRow row) {
            InventoryBalanceView view = new InventoryBalanceView(
                    row.warehouseId(), row.productId(), row.quantity(), row.costAmount());
            return new BalanceItem(row.warehouseId(), row.warehouseCode(), row.warehouseName(),
                    row.productId(), row.productCode(), row.productName(),
                    plain(row.quantity()), plain(row.costAmount()), plain(view.derivedUnitCost()));
        }
    }

    // ================================================================ 2. 收发存汇总

    /** 收发存汇总响应：分页明细 + 金额合计（数量跨商品不可加，故合计只给金额）。 */
    public record StockMovementReportResponse(List<MovementItem> items, long total, int page, int size,
                                              String totalOpeningAmount, String totalInAmount,
                                              String totalOutAmount, String totalEndingAmount) {

        static StockMovementReportResponse from(StockMovementReport report) {
            var s = report.summary();
            return new StockMovementReportResponse(
                    report.page().items().stream().map(MovementItem::from).toList(),
                    report.page().total(), report.page().page(), report.page().size(),
                    plain(s.totalOpeningAmount()), plain(s.totalInAmount()),
                    plain(s.totalOutAmount()), plain(s.totalEndingAmount()));
        }
    }

    /** 收发存行（期初/收/发/期末 数量+金额；tie-out：期初+收−发=期末）。 */
    public record MovementItem(long warehouseId, String warehouseCode, String warehouseName,
                               long productId, String productCode, String productName,
                               String openingQuantity, String openingAmount,
                               String inQuantity, String inAmount,
                               String outQuantity, String outAmount,
                               String endingQuantity, String endingAmount) {

        static MovementItem from(StockMovementRow r) {
            return new MovementItem(r.warehouseId(), r.warehouseCode(), r.warehouseName(),
                    r.productId(), r.productCode(), r.productName(),
                    plain(r.openingQuantity()), plain(r.openingAmount()),
                    plain(r.inQuantity()), plain(r.inAmount()),
                    plain(r.outQuantity()), plain(r.outAmount()),
                    plain(r.endingQuantity()), plain(r.endingAmount()));
        }
    }

    // ================================================================ 3. 采购明细

    /** 采购入库明细响应：分页明细 + 总进货额。 */
    public record PurchaseDetailReportResponse(List<PurchaseItem> items, long total, int page, int size,
                                               String totalAmount) {

        static PurchaseDetailReportResponse from(PurchaseDetailReport report) {
            return new PurchaseDetailReportResponse(
                    report.page().items().stream().map(PurchaseItem::from).toList(),
                    report.page().total(), report.page().page(), report.page().size(),
                    plain(report.totalAmount()));
        }
    }

    /** 采购入库明细行。 */
    public record PurchaseItem(String receiptNo, String receiptDate, String purchaseOrderNo,
                               Long supplierId, String supplierCode, String supplierName,
                               long warehouseId, String warehouseCode, String warehouseName,
                               int lineNo, long productId, String productCode, String productName,
                               String quantity, String unitCost, String amount, String status) {

        static PurchaseItem from(PurchaseDetailRow r) {
            return new PurchaseItem(r.receiptNo(),
                    r.receiptDate() == null ? null : r.receiptDate().toString(), r.purchaseOrderNo(),
                    r.supplierId(), r.supplierCode(), r.supplierName(),
                    r.warehouseId(), r.warehouseCode(), r.warehouseName(),
                    r.lineNo(), r.productId(), r.productCode(), r.productName(),
                    plain(r.quantity()), plain(r.unitCost()), plain(r.amount()), r.status());
        }
    }

    // ================================================================ 4. 销售明细

    /** 销售出库明细响应：分页明细 + 销售额/成本/毛利合计。 */
    public record SalesDetailReportResponse(List<SalesItem> items, long total, int page, int size,
                                            String totalSalesAmount, String totalCogsAmount,
                                            String totalGrossProfit) {

        static SalesDetailReportResponse from(SalesDetailReport report) {
            var s = report.summary();
            return new SalesDetailReportResponse(
                    report.page().items().stream().map(SalesItem::from).toList(),
                    report.page().total(), report.page().page(), report.page().size(),
                    plain(s.totalSalesAmount()), plain(s.totalCogsAmount()), plain(s.totalGrossProfit()));
        }
    }

    /**
     * 销售出库明细行：salesAmount=数量×售价（2 位 HALF_UP，售价缺失则 null）；
     * grossProfit=销售额−COGS（任一缺失则 null）。
     */
    public record SalesItem(String deliveryNo, String deliveryDate, String salesOrderNo,
                            Long customerId, String customerCode, String customerName,
                            long warehouseId, String warehouseCode, String warehouseName,
                            int lineNo, long productId, String productCode, String productName,
                            String quantity, String unitPrice, String salesAmount,
                            String cogsAmount, String grossProfit, String status) {

        static SalesItem from(SalesDetailRow r) {
            BigDecimal salesAmount = (r.quantity() == null || r.unitPrice() == null)
                    ? null : r.quantity().multiply(r.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal grossProfit = (salesAmount == null || r.cogsAmount() == null)
                    ? null : salesAmount.subtract(r.cogsAmount());
            return new SalesItem(r.deliveryNo(),
                    r.deliveryDate() == null ? null : r.deliveryDate().toString(), r.salesOrderNo(),
                    r.customerId(), r.customerCode(), r.customerName(),
                    r.warehouseId(), r.warehouseCode(), r.warehouseName(),
                    r.lineNo(), r.productId(), r.productCode(), r.productName(),
                    plain(r.quantity()), plain(r.unitPrice()), plain(salesAmount),
                    plain(r.cogsAmount()), plain(grossProfit), r.status());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}

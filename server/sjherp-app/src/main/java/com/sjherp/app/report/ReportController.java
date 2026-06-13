package com.sjherp.app.report;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.report.ReportDtos.InventoryBalanceReportResponse;
import com.sjherp.app.report.ReportDtos.PurchaseDetailReportResponse;
import com.sjherp.app.report.ReportDtos.SalesDetailReportResponse;
import com.sjherp.app.report.ReportDtos.StockMovementReportResponse;

/**
 * 进销存报表 API（M3-T12，<b>只读</b>查询）：
 * <ul>
 *   <li>GET /api/reports/inventory-balance → 库存余额表（含库存总值），登录即可（口径同 /api/inventory/balances）；</li>
 *   <li>GET /api/reports/stock-movement-summary?fromDate=&toDate= → 收发存汇总（期初/收/发/期末），登录即可；</li>
 *   <li>GET /api/reports/purchase-detail → 采购入库明细（含总进货额），须 {@code purchase:order}（同采购读口径）；</li>
 *   <li>GET /api/reports/sales-detail → 销售出库明细（含销售额/成本/毛利），须 {@code sales:order}（同销售读口径）。</li>
 * </ul>
 *
 * <p>权限取舍（docs/权限矩阵.md）：库存类报表登录即可（与库存查询一致）；采购/销售明细沿用各自模块读权限，
 * 不新增权限点。错误契约：参数不合法（如 fromDate>toDate、缺必填期间）→ 400 {"error": "..."}。
 * 全部经 {@link ReportQueryDao} 只读 SQL（CLAUDE.md「报表只读查询除外」），绝不写库。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportQueryDao reportQueryDao;

    public ReportController(ReportQueryDao reportQueryDao) {
        this.reportQueryDao = Objects.requireNonNull(reportQueryDao, "reportQueryDao 不能为空");
    }

    /** 库存余额表：warehouseId/productId/keyword/includeZero 均可选（includeZero 默认 true）。 */
    @GetMapping("/inventory-balance")
    public InventoryBalanceReportResponse inventoryBalance(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "true") boolean includeZero,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return InventoryBalanceReportResponse.from(
                reportQueryDao.inventoryBalance(warehouseId, productId, keyword, includeZero, page, size));
    }

    /** 收发存汇总：fromDate/toDate 必填（业务日闭区间，from ≤ to）；warehouseId/productId/keyword 可选。 */
    @GetMapping("/stock-movement-summary")
    public StockMovementReportResponse stockMovementSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireRange(fromDate, toDate);
        return StockMovementReportResponse.from(
                reportQueryDao.stockMovementSummary(fromDate, toDate, warehouseId, productId, keyword,
                        page, size));
    }

    /** 采购入库明细：fromDate/toDate（按 receipt_date）、supplierId、productId、warehouseId、status 均可选。 */
    @PreAuthorize("@perm.has('purchase:order')")
    @GetMapping("/purchase-detail")
    public PurchaseDetailReportResponse purchaseDetail(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireRange(fromDate, toDate);
        return PurchaseDetailReportResponse.from(
                reportQueryDao.purchaseDetail(fromDate, toDate, supplierId, productId, warehouseId,
                        status, page, size));
    }

    /** 销售出库明细：fromDate/toDate（按 created_at）、customerId、productId、warehouseId、status 均可选。 */
    @PreAuthorize("@perm.has('sales:order')")
    @GetMapping("/sales-detail")
    public SalesDetailReportResponse salesDetail(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireRange(fromDate, toDate);
        return SalesDetailReportResponse.from(
                reportQueryDao.salesDetail(fromDate, toDate, customerId, productId, warehouseId,
                        status, page, size));
    }

    /** 起止日期同时给出时校验 from ≤ to（收发存为必填，明细为可选；任一为 null 跳过）。 */
    private static void requireRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("起始日期不能晚于结束日期: " + fromDate + " > " + toDate);
        }
    }

    /** 参数不合法 → 400 {"error": "..."}（口径同其它模块异常契约）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}

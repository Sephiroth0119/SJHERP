package com.sjherp.app.sales;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.security.CurrentUser;
import com.sjherp.app.sales.SalesDtos.PageResponse;
import com.sjherp.app.sales.SalesDtos.SalesDeliveryCreateRequest;
import com.sjherp.app.sales.SalesDtos.SalesDeliveryOrderOptionResponse;
import com.sjherp.app.sales.SalesDtos.SalesDeliveryResponse;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.sales.SalesDeliveryQuery;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderNotFoundException;
import com.sjherp.domain.sales.SalesOrderQuery;

import jakarta.validation.Valid;

/**
 * 销售出库单 API（M3-T09）：
 * <ul>
 *   <li>POST /api/sales/deliveries → 201 建单（引用某已审核销售订单，自动 SD- 编号）；</li>
 *   <li>POST /api/sales/deliveries/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/sales/deliveries/{docNo}/post → 200 过账（SALES_OUT 出库 + COGS 回填 + 回写订单发货量）；</li>
 *   <li>POST /api/sales/deliveries/{docNo}/cancel → 200 作废（仅 DRAFT）；</li>
 *   <li>GET  /api/sales/deliveries/order-options → 200 已审核/执行中且仍可发货的订单候选；</li>
 *   <li>GET  /api/sales/deliveries/order-options/{docNo} → 200 候选订单未发完行详情；</li>
 *   <li>GET  /api/sales/deliveries/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/sales/deliveries?salesOrderNo=&warehouseId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code sales:delivery}（ADMIN/BOSS/SALES/WAREHOUSE）。
 * 过账经库存唯一写入口扣减库存并算 COGS；库存不足整批回滚（销售出库强校验库存）。
 */
@RestController
@RequestMapping("/api/sales/deliveries")
@PreAuthorize("@perm.has('sales:delivery')")
public class SalesDeliveryController {

    private final SalesDeliveryAppService salesDeliveryAppService;
    private final SalesOrderAppService salesOrderAppService;

    public SalesDeliveryController(SalesDeliveryAppService salesDeliveryAppService,
                                   SalesOrderAppService salesOrderAppService) {
        this.salesDeliveryAppService = salesDeliveryAppService;
        this.salesOrderAppService = salesOrderAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<SalesDeliveryResponse> create(@Valid @RequestBody SalesDeliveryCreateRequest request) {
        SalesDeliveryResponse body = SalesDeliveryResponse.from(salesDeliveryAppService.create(
                request.salesOrderNo(), request.warehouseId(), request.remark(),
                request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public SalesDeliveryResponse approve(@PathVariable String docNo) {
        return SalesDeliveryResponse.from(salesDeliveryAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → EXECUTING → COMPLETED，产生 SALES_OUT 流水 + COGS 回填 + 回写订单发货量） */
    @PostMapping("/{docNo}/post")
    public SalesDeliveryResponse post(@PathVariable String docNo) {
        return SalesDeliveryResponse.from(salesDeliveryAppService.post(docNo, CurrentUser.operator()));
    }

    /**
     * 出库建单销售订单候选：在 sales:delivery 权限边界内复用订单只读服务，
     * 仅返回 APPROVED/EXECUTING 且仍有未发完行的订单；不授予 sales:order 或订单写能力。
     */
    @GetMapping("/order-options")
    public PageResponse<SalesDeliveryOrderOptionResponse> orderOptions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.ofDeliveryOrderOptions(salesOrderAppService.search(
                new SalesOrderQuery(null, null, true, page, size)));
    }

    /** 出库建单销售订单候选详情：状态变化或已全部发完时按不可用返回 404。 */
    @GetMapping("/order-options/{docNo}")
    public SalesDeliveryOrderOptionResponse orderOption(@PathVariable String docNo) {
        SalesOrder order = salesOrderAppService.get(docNo);
        if (!SalesDeliveryOrderOptionResponse.isDeliverable(order)) {
            throw new SalesOrderNotFoundException(docNo);
        }
        return SalesDeliveryOrderOptionResponse.from(order);
    }

    /** 冲销（红字出库，M4-T07b：COMPLETED → REVERSED，库存按原 COGS 反向入库 + 红冲出库凭证 + 回退订单发货量） */
    @PostMapping("/{docNo}/reverse")
    public SalesDeliveryResponse reverse(@PathVariable String docNo) {
        return SalesDeliveryResponse.from(salesDeliveryAppService.reverse(docNo, CurrentUser.operator()));
    }

    /** 作废（仅 DRAFT） */
    @PostMapping("/{docNo}/cancel")
    public SalesDeliveryResponse cancel(@PathVariable String docNo) {
        return SalesDeliveryResponse.from(salesDeliveryAppService.cancel(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public SalesDeliveryResponse get(@PathVariable String docNo) {
        return SalesDeliveryResponse.from(salesDeliveryAppService.get(docNo));
    }

    /** 分页（salesOrderNo、warehouseId、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<SalesDeliveryResponse> search(
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.ofDeliveries(salesDeliveryAppService.search(
                new SalesDeliveryQuery(salesOrderNo, warehouseId, parseStatus(status), page, size)));
    }

    private static DocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 非法（DRAFT/APPROVED/EXECUTING/COMPLETED/"
                    + "CANCELLED/REVERSED）: " + status);
        }
    }
}

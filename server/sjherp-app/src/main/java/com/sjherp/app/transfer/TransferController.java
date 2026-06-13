package com.sjherp.app.transfer;

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
import com.sjherp.app.transfer.TransferDtos.CreateRequest;
import com.sjherp.app.transfer.TransferDtos.PageResponse;
import com.sjherp.app.transfer.TransferDtos.TransferResponse;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.transfer.TransferQuery;

import jakarta.validation.Valid;

/**
 * 库存调拨单 API（M3-T04）：
 * <ul>
 *   <li>POST /api/inventory/transfers → 201 建单（自动 TR- 编号）；</li>
 *   <li>POST /api/inventory/transfers/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/inventory/transfers/{docNo}/post → 200 过账（产生调出/调入两腿流水）；</li>
 *   <li>GET  /api/inventory/transfers/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/inventory/transfers?warehouseId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code inventory:transfer}（ADMIN/BOSS/WAREHOUSE）。
 * 错误契约见 {@link TransferExceptionHandler}：单据不存在 404、业务/参数不合法 400、
 * 非法状态流转 409、库存不足 400、幂等键冲突 409，错误体一律 {"error": "..."}。
 *
 * <p>写操作唯一经 {@code TransferAppService}（外层事务）→ 领域 TransferService →
 * 库存唯一写入口 TransactionalInventoryService（CLAUDE.md 原则 1）。
 */
@RestController
@RequestMapping("/api/inventory/transfers")
@PreAuthorize("@perm.has('inventory:transfer')")
public class TransferController {

    private final TransferAppService transferAppService;

    public TransferController(TransferAppService transferAppService) {
        this.transferAppService = transferAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateRequest request) {
        TransferResponse body = TransferResponse.from(transferAppService.create(
                request.fromWarehouseId(), request.toWarehouseId(), request.remark(),
                request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public TransferResponse approve(@PathVariable String docNo) {
        return TransferResponse.from(transferAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → EXECUTING → COMPLETED，产生调出/调入两腿流水） */
    @PostMapping("/{docNo}/post")
    public TransferResponse post(@PathVariable String docNo) {
        return TransferResponse.from(transferAppService.post(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public TransferResponse get(@PathVariable String docNo) {
        return TransferResponse.from(transferAppService.get(docNo));
    }

    /** 分页（warehouseId 命中调出/调入任一、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<TransferResponse> search(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromDocuments(
                transferAppService.search(new TransferQuery(warehouseId, parseStatus(status), page, size)));
    }

    /** 状态过滤解析（非法值给友好 400，不透出枚举内部异常） */
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

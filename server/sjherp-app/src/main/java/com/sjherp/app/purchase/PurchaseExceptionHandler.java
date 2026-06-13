package com.sjherp.app.purchase;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.sjherp.domain.catalog.CatalogNotFoundException;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.partner.PartnerNotFoundException;
import com.sjherp.domain.purchase.PurchaseInvoiceNotFoundException;
import com.sjherp.domain.purchase.PurchaseOrderNotFoundException;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;
import com.sjherp.domain.warehouse.WarehouseNotFoundException;

/**
 * 采购线 API 统一错误响应（作用于本包 PurchaseOrder/Receipt/Invoice/Payable 控制器，
 * 不影响其他包既有处理器）。错误体与既有契约一致：{"error": "..."}（同 TransferExceptionHandler 风格）。
 *
 * <p>领域异常映射（M3-T05/T06/T07 验收契约）：
 * <ul>
 *   <li>采购订单/入库单/发票/供应商/商品/仓库不存在 → 404；</li>
 *   <li>{@link IllegalStateTransitionException} / {@link IllegalStateException} → 409
 *       （重复审核、对已完成单过账、对非审核订单收货等）；</li>
 *   <li>{@link InsufficientStockException} → 400（理论上采购入库不出库，列出以防御）；</li>
 *   <li>{@link IdempotencyConflictException} → 409（过账幂等键冲突）；</li>
 *   <li>业务规则拒绝（部分收货超量、三单匹配超额、引用订单未审核/收货单未过账、停用、数量非法等）→ 400。</li>
 * </ul>
 */
@RestControllerAdvice(basePackageClasses = PurchaseExceptionHandler.class)
public class PurchaseExceptionHandler {

    /** 采购订单不存在 → 404 */
    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePurchaseOrderNotFound(PurchaseOrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 采购入库单不存在 → 404 */
    @ExceptionHandler(PurchaseReceiptNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePurchaseReceiptNotFound(PurchaseReceiptNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 采购发票不存在 → 404 */
    @ExceptionHandler(PurchaseInvoiceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePurchaseInvoiceNotFound(PurchaseInvoiceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 供应商不存在 → 404 */
    @ExceptionHandler(PartnerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePartnerNotFound(PartnerNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 仓库不存在 → 404 */
    @ExceptionHandler(WarehouseNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleWarehouseNotFound(WarehouseNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 商品不存在 → 404 */
    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCatalogNotFound(CatalogNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 非法状态流转（重复审核、对已完成单再过账等）→ 409 */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleIllegalTransition(IllegalStateTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 状态约束拒绝（对非审核订单收货等）→ 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 库存不足（防御性，采购入库通常不触发）→ 400 */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientStock(InsufficientStockException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 过账幂等键冲突（同键不同参）→ 409 */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 业务规则拒绝（部分收货超量、三单匹配超额、引用单据状态不符、停用、数量非法、状态过滤值非法等）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Bean Validation 失败 → 400，取第一条字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? "请求参数不合法"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /** 必填查询参数缺失 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    /** 查询参数类型不匹配 → 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "参数类型不合法: " + e.getName());
    }

    /** 请求体不可解析 → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON 或字段类型不匹配");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

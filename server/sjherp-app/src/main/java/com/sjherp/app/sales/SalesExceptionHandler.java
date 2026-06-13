package com.sjherp.app.sales;

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
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;
import com.sjherp.domain.sales.SalesInvoiceNotFoundException;
import com.sjherp.domain.sales.SalesOrderNotFoundException;
import com.sjherp.domain.warehouse.WarehouseNotFoundException;

/**
 * 销售线 API 统一错误响应（作用于本包的控制器，M3-T08/T09/T10）。
 * 错误体与既有契约一致：{"error": "..."}（同 TransferExceptionHandler 风格）。
 *
 * <p>领域异常映射：
 * <ul>
 *   <li>销售订单/出库单/发票/客户/商品/仓库不存在 → 404；</li>
 *   <li>{@link IllegalStateTransitionException} / {@link IllegalStateException} → 409；</li>
 *   <li>{@link InsufficientStockException} → 400（出库库存不足，默认强校验）；</li>
 *   <li>{@link IdempotencyConflictException} → 409（过账幂等键冲突）；</li>
 *   <li>业务/参数不合法（停用、超发、超开、状态不可发货/开票等）→ 400。</li>
 * </ul>
 */
@RestControllerAdvice(basePackageClasses = SalesExceptionHandler.class)
public class SalesExceptionHandler {

    @ExceptionHandler(SalesOrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSalesOrderNotFound(SalesOrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SalesDeliveryNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSalesDeliveryNotFound(SalesDeliveryNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SalesInvoiceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSalesInvoiceNotFound(SalesInvoiceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PartnerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePartnerNotFound(PartnerNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCatalogNotFound(CatalogNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WarehouseNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleWarehouseNotFound(WarehouseNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 非法状态流转（重复审核、对草稿订单发货前未审核、对已完成单再过账等）→ 409 */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleIllegalTransition(IllegalStateTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 状态约束拒绝 → 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 库存不足（出库超量，默认强校验库存）→ 400 */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientStock(InsufficientStockException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 过账幂等键冲突（同键不同参）→ 409 */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 业务规则拒绝（停用、超发、超开、不可发货/开票、数量非法等）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? "请求参数不合法"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "参数类型不合法: " + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON 或字段类型不匹配");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

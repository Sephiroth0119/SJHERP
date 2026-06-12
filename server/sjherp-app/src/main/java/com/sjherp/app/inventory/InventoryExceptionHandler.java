package com.sjherp.app.inventory;

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
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.warehouse.WarehouseNotFoundException;

/**
 * inventory API 统一错误响应（仅作用于本包的控制器，不影响其他包既有处理器）。
 * 错误体与既有契约一致：{"error": "..."}（同 WarehouseExceptionHandler 风格）。
 *
 * <p>领域异常映射（M3-T01c 验收契约）：
 * <ul>
 *   <li>{@link InsufficientStockException} → 400（库存不足，文案含现存量/需求量，Agent 可读）；</li>
 *   <li>{@link IdempotencyConflictException} → 409（同幂等键不同参数，拒绝过账防吞单）；</li>
 *   <li>仓库/商品不存在 → 404；业务规则拒绝（停用、数量非法等）→ 400。</li>
 * </ul>
 */
@RestControllerAdvice(basePackageClasses = InventoryExceptionHandler.class)
public class InventoryExceptionHandler {

    /** 库存不足 → 400 {"error": "..."}（默认禁止负库存，拆解 §1.5） */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientStock(InsufficientStockException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 幂等键冲突（同键不同参）→ 409 {"error": "..."}（拆解 §1.3，防键误用静默吞单） */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
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

    /** 业务规则拒绝（类型非法、档案停用、数量/金额校验失败等）→ 400 */
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

    /** 必填查询参数缺失（如流水查询缺 warehouseId/productId）→ 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    /** 查询参数类型不匹配（如 warehouseId=abc）→ 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "参数类型不合法: " + e.getName());
    }

    /** 请求体不可解析（JSON 语法错误、类型不匹配）→ 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON 或字段类型不匹配");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

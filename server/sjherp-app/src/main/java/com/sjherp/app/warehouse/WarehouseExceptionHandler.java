package com.sjherp.app.warehouse;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sjherp.domain.warehouse.WarehouseNotFoundException;

/**
 * warehouse API 统一错误响应（仅作用于本包的控制器，不影响其他包既有处理器）。
 *
 * <p>错误体与既有契约一致：{"error": "..."}（同 CatalogExceptionHandler 风格）。
 */
@RestControllerAdvice(basePackageClasses = WarehouseExceptionHandler.class)
public class WarehouseExceptionHandler {

    /** 档案不存在 → 404 {"error": "..."} */
    @ExceptionHandler(WarehouseNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(WarehouseNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 业务规则拒绝（编码重复、重复启停等）→ 400 {"error": "..."} */
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

    /** 请求体不可解析（JSON 语法错误、类型不匹配）→ 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON 或字段类型不匹配");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

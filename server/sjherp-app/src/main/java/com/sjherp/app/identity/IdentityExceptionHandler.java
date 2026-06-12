package com.sjherp.app.identity;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sjherp.domain.identity.UserNotFoundException;

/**
 * identity API 统一错误响应（仅作用于本包，错误体 {"error": "..."} 与既有契约一致，
 * 约定同 {@code CatalogExceptionHandler}）。
 */
@RestControllerAdvice(basePackageClasses = IdentityExceptionHandler.class)
public class IdentityExceptionHandler {

    /** 用户不存在 → 404 {"error": "..."} */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UserNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 业务规则拒绝（登录名重复、密码强度不足、重复启停、非法角色名等）→ 400 */
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

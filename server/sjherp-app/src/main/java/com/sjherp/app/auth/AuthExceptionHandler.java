package com.sjherp.app.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sjherp.domain.identity.AuthenticationFailedException;

/**
 * auth API 统一错误响应（仅作用于本包，错误体 {"error": "..."} 与既有契约一致）。
 */
@RestControllerAdvice(basePackageClasses = AuthExceptionHandler.class)
public class AuthExceptionHandler {

    /** 认证失败（用户名/密码错误、账号停用）→ 401 {"error": "..."} */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, String>> handleAuthFailed(AuthenticationFailedException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /** Bean Validation 失败（用户名/密码为空）→ 400 */
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

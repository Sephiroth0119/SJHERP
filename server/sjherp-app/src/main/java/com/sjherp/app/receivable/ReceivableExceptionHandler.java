package com.sjherp.app.receivable;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.sjherp.domain.receivable.ReceivableNotFoundException;

/**
 * 应收账款 API 统一错误响应（作用于本包的控制器，M3-T10）。
 * 错误体与既有契约一致：{"error": "..."}。
 */
@RestControllerAdvice(basePackageClasses = ReceivableExceptionHandler.class)
public class ReceivableExceptionHandler {

    @ExceptionHandler(ReceivableNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ReceivableNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "参数类型不合法: " + e.getName());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

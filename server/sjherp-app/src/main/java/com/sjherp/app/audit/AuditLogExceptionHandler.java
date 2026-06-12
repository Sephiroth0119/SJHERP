package com.sjherp.app.audit;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 审计日志 API 统一错误响应（仅作用于本包的控制器）。
 *
 * <p>错误体与既有契约一致：{"error": "..."}。
 */
@RestControllerAdvice(basePackageClasses = AuditLogExceptionHandler.class)
public class AuditLogExceptionHandler {

    /** 参数类型不匹配（targetId/page/size 非数字）→ 400 {"error"} */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error("参数类型不合法: " + e.getName());
    }

    /** 参数取值非法（page/size 越界、时间格式错误）→ 400 {"error"} */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return error(e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}

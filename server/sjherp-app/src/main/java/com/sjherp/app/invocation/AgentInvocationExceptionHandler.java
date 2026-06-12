package com.sjherp.app.invocation;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.sjherp.app.chat.SessionNotFoundException;

/**
 * Agent 调用观测 API 统一错误响应（仅作用于本包的控制器）。
 *
 * <p>错误体与既有契约一致：{"error": "..."}。
 */
@RestControllerAdvice(basePackageClasses = AgentInvocationExceptionHandler.class)
public class AgentInvocationExceptionHandler {

    /** 会话不存在或不属于当前用户 → 404 {"error"}（与会话 API 的 404 风格一致） */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 缺少必填参数（sessionId）→ 400 {"error"} */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return error("缺少必填参数: " + e.getParameterName());
    }

    /** 参数类型不匹配（page/size 非数字）→ 400 {"error"} */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error("参数类型不合法: " + e.getName());
    }

    /** 参数取值非法（空 sessionId、page/size 越界）→ 400 {"error"} */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return error(e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}

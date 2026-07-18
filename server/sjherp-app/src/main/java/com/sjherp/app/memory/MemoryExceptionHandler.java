package com.sjherp.app.memory;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.sjherp.domain.memory.MemoryEntryNotFoundException;
import com.sjherp.infra.memory.OllamaEmbeddingException;
import com.sjherp.infra.memory.QdrantVectorException;

/** 大记忆管理 API 的稳定、脱敏错误响应。 */
@RestControllerAdvice(basePackageClasses = MemoryController.class)
public class MemoryExceptionHandler {

    @ExceptionHandler(MemoryEntryNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(MemoryEntryNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        FieldError field = exception.getBindingResult().getFieldError();
        String message = field == null ? "请求参数不合法"
                : field.getField() + ": " + field.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleUnreadable(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON 或字段类型不匹配");
    }

    /** 派生服务异常仅返回固定文案，禁止泄漏本地 URL、响应体、原文或密钥。 */
    @ExceptionHandler({OllamaEmbeddingException.class, QdrantVectorException.class})
    public ResponseEntity<Map<String, String>> handleLocalIndexUnavailable(RuntimeException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "本地记忆索引服务暂不可用");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

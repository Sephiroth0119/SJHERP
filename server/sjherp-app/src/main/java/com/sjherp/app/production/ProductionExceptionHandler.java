package com.sjherp.app.production;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sjherp.domain.production.BillOfMaterialsNotFoundException;
import com.sjherp.domain.production.BomCycleException;
import com.sjherp.domain.production.RoutingNotFoundException;

/**
 * 生产模块 API 统一错误响应（仅作用于本包的控制器，不影响其他包既有处理器）。
 *
 * <p>错误体与既有契约一致：{"error": "..."}（同 PaymentAccountExceptionHandler 风格）。
 */
@RestControllerAdvice(basePackageClasses = ProductionExceptionHandler.class)
public class ProductionExceptionHandler {

    /** BOM 不存在 → 404 {"error": "..."} */
    @ExceptionHandler(BillOfMaterialsNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleBomNotFound(BillOfMaterialsNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 工艺路线不存在 → 404 {"error": "..."} */
    @ExceptionHandler(RoutingNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRoutingNotFound(RoutingNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** BOM 环形依赖（保存时检测到成环）→ 400 {"error": "..."} */
    @ExceptionHandler(BomCycleException.class)
    public ResponseEntity<Map<String, String>> handleBomCycle(BomCycleException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 业务规则拒绝（商品不存在/已停用、版本重复、数量非法等）→ 400 {"error": "..."} */
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
        return error(HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

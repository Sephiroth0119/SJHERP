package com.sjherp.app.collection;

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

import com.sjherp.domain.collection.CollectionReceiptNotFoundException;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.fund.PaymentAccountNotFoundException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.receivable.ReceivableNotFoundException;

/**
 * 收款单 API 统一错误响应（作用于本包 CollectionReceipt 控制器，不影响其他包既有处理器）。
 * 错误体与既有契约一致：{"error": "..."}（同 PurchaseExceptionHandler / GlExceptionHandler 风格）。
 *
 * <p>领域异常映射（M4-T04b 验收契约）：
 * <ul>
 *   <li>收款单/应收/资金账户不存在 → 404；</li>
 *   <li>{@link PeriodClosedException}（现金侧凭证落在关账期，extends IllegalStateException）/
 *       {@link IllegalStateTransitionException}（重复审核、对已完成单再过账）/
 *       {@link IllegalStateException} → 409；</li>
 *   <li>{@code OverSettlementException}（超额核销，extends IllegalArgumentException）/
 *       {@link IllegalArgumentException}（跨客户核销、金额非法、状态过滤值非法等）→ 400。</li>
 * </ul>
 *
 * <p>映射顺序：{@link PeriodClosedException} extends {@link IllegalStateException}，
 * {@code OverSettlementException} extends {@link IllegalArgumentException}——Spring 按最近祖先选处理器，
 * 二者各命中专属/泛型处理器（关账期 409、超额核销 400），不会错配。
 */
@RestControllerAdvice(basePackageClasses = CollectionExceptionHandler.class)
public class CollectionExceptionHandler {

    /** 收款单不存在 → 404 */
    @ExceptionHandler(CollectionReceiptNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCollectionNotFound(CollectionReceiptNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 应收账款不存在（分摊行引用非法）→ 404 */
    @ExceptionHandler(ReceivableNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleReceivableNotFound(ReceivableNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 资金账户不存在 → 404 */
    @ExceptionHandler(PaymentAccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePaymentAccountNotFound(PaymentAccountNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 关账期过账（现金侧凭证落在关账期）→ 409 */
    @ExceptionHandler(PeriodClosedException.class)
    public ResponseEntity<Map<String, String>> handlePeriodClosed(PeriodClosedException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 非法状态流转（重复审核、对已完成单再过账等）→ 409 */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleIllegalTransition(IllegalStateTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 状态约束拒绝 → 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 业务规则拒绝（超额核销、跨客户核销、金额非法、状态过滤值非法等）→ 400 */
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

    /** 必填查询参数缺失 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    /** 查询参数类型不匹配 → 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "参数类型不合法: " + e.getName());
    }

    /** 请求体不可解析 → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON 或字段类型不匹配");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

package com.sjherp.app.gl;

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

import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.AccountNotFoundException;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.VoucherNotBalancedException;
import com.sjherp.domain.gl.VoucherNotFoundException;

/**
 * 总账线 API 统一错误响应（作用于本包 GlAccount/GlPeriod/GlVoucher 控制器，不影响其他包既有处理器）。
 * 错误体与既有契约一致：{"error": "..."}（同 PurchaseExceptionHandler 风格）。
 *
 * <p>领域异常映射（M4-T01 验收契约）：
 * <ul>
 *   <li>科目/账期/凭证不存在 → 404；</li>
 *   <li>{@link PeriodClosedException}（关账期过账，验收②）/{@link IllegalStateTransitionException}
 *       （重复过账等非法流转）/{@link IllegalStateException}（账期重复关账/重开等）→ 409；</li>
 *   <li>{@link VoucherNotBalancedException}（借贷不平，验收①）/{@link IllegalArgumentException}
 *       （科目非末级/停用、凭证日期不在账期内、枚举非法、行金额非法等）→ 400。</li>
 * </ul>
 *
 * <p>注意映射顺序：{@link PeriodClosedException} extends {@link IllegalStateException}、
 * {@link VoucherNotBalancedException} extends {@link IllegalArgumentException}，更具体的处理器优先匹配
 * （Spring 按异常类型最近祖先选择处理器，故二者各自命中专属处理器，不会落到泛型 400/409）。
 */
@RestControllerAdvice(basePackageClasses = GlExceptionHandler.class)
public class GlExceptionHandler {

    /** 科目不存在 → 404 */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleAccountNotFound(AccountNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 账期不存在 → 404 */
    @ExceptionHandler(AccountingPeriodNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePeriodNotFound(AccountingPeriodNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 凭证不存在 → 404 */
    @ExceptionHandler(VoucherNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleVoucherNotFound(VoucherNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 关账期过账（验收②）→ 409 */
    @ExceptionHandler(PeriodClosedException.class)
    public ResponseEntity<Map<String, String>> handlePeriodClosed(PeriodClosedException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 非法状态流转（重复过账、对已过账凭证再过账等）→ 409 */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleIllegalTransition(IllegalStateTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 状态约束拒绝（账期重复关账/重复重开等）→ 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 借贷不平（验收①）→ 400 */
    @ExceptionHandler(VoucherNotBalancedException.class)
    public ResponseEntity<Map<String, String>> handleNotBalanced(VoucherNotBalancedException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 业务规则拒绝（科目非末级/停用、凭证日期不在账期内、编码重复、枚举非法、行金额非法等）→ 400 */
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

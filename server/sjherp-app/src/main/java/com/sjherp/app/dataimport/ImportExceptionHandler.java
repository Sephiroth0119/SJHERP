package com.sjherp.app.dataimport;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.sjherp.app.dataimport.ExcelWorkbookReader.FileImportException;
import com.sjherp.app.dataimport.ImportDtos.ImportResult;

/**
 * 导入包统一错误响应（M2-T09）：仅作用于本包控制器，不影响其他包已有处理器。
 *
 * <p>错误映射：
 * <ul>
 *   <li>文件级错误（非法格式/缺列/超限）→ 400 {@code {"error": "..."}}</li>
 *   <li>行级校验失败（{@link ImportRejectedException}）→ 200 结构化 {@link ImportResult}
 *       （{@code success=false + failures[]}，前端可逐行高亮）</li>
 * </ul>
 */
@RestControllerAdvice(basePackageClasses = ImportExceptionHandler.class)
public class ImportExceptionHandler {

    /** 文件级错误（不是合法 xlsx / 缺必填列 / 列头不符）→ 400 {"error": "..."} */
    @ExceptionHandler(FileImportException.class)
    public ResponseEntity<Map<String, String>> handleFileError(FileImportException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 上传文件超过 multipart 配置上限 → 400 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleFileSizeExceeded(MaxUploadSizeExceededException e) {
        return error(HttpStatus.BAD_REQUEST, "上传文件过大，请检查 Excel 行数或联系管理员调整上传限制");
    }

    /**
     * 行级校验失败（全有或全无：任一行失败整体回滚）→ 200 携带失败清单。
     *
     * <p>返回 200 而非 422：便于前端统一以 {@code success} 字段判断，
     * 并对 {@code failures[]} 逐行高亮；不用 422 避免某些 HTTP 客户端直接报错。
     */
    @ExceptionHandler(ImportRejectedException.class)
    public ImportResult handleImportRejected(ImportRejectedException e) {
        return e.toResult();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

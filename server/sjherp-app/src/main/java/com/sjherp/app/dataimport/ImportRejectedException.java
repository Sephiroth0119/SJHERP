package com.sjherp.app.dataimport;

import java.util.List;

/**
 * 行级导入拒绝异常（M2-T09）：携带全部失败行清单，由事务边界感知——
 * {@link ImportService} 在逐行收集完失败后抛出本异常触发 {@code @Transactional} 回滚
 * （RuntimeException 默认触发回滚），同时将 failures 传回给控制器响应体。
 *
 * <p>由 {@link ImportExceptionHandler} 处理，返回结构化的 200 {@link ImportDtos.ImportResult}。
 */
class ImportRejectedException extends RuntimeException {

    private final String importType;
    private final int total;
    private final List<ImportDtos.RowFailure> failures;

    ImportRejectedException(String importType, int total, List<ImportDtos.RowFailure> failures) {
        super("导入被拒绝：" + failures.size() + " 行校验失败（共 " + total + " 行）");
        this.importType = importType;
        this.total = total;
        this.failures = List.copyOf(failures);
    }

    ImportDtos.ImportResult toResult() {
        return ImportDtos.ImportResult.rejected(importType, total, failures);
    }
}

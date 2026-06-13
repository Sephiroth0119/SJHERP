package com.sjherp.app.dataimport;

import java.util.List;

/**
 * 导入功能 DTO（M2-T09）：导入结果与行级失败报告。
 *
 * <p>行级失败采用 200 + 结构化报告体方案，便于前端逐行高亮：
 * {@code ImportResult.success=false} 时 {@code failures} 非空；
 * {@code success=true} 时 {@code failures} 为空列表。
 */
public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * 导入结果（导入端点的统一响应体）。
     *
     * @param type      导入类型（products / customers / suppliers / opening-stock）
     * @param total     文件数据行总数（不含表头）
     * @param succeeded 成功写入行数（全有或全无策略：要么等于 total，要么 0）
     * @param failed    失败行数（任一行失败则 = total，否则 = 0）
     * @param success   整体是否成功
     * @param failures  行级失败清单（成功时为空列表）
     */
    public record ImportResult(
            String type,
            int total,
            int succeeded,
            int failed,
            boolean success,
            List<RowFailure> failures) {

        /** 全部成功结果 */
        static ImportResult ok(String type, int total) {
            return new ImportResult(type, total, total, 0, true, List.of());
        }

        /** 全部失败结果（全有或全无：有任一行失败即整体失败，succeeded=0） */
        static ImportResult rejected(String type, int total, List<RowFailure> failures) {
            return new ImportResult(type, total, 0, failures.size(), false, failures);
        }
    }

    /**
     * 行级失败记录。
     *
     * @param row    Excel 显示行号（表头为第 1 行，数据从第 2 行起；1-based）
     * @param column 出错的列名（如"商品名称"）；文件级错误填 null
     * @param value  该单元格的填写值（可能为 null 或空）
     * @param reason 失败原因（直接来自领域异常 message 或参数校验描述）
     */
    public record RowFailure(int row, String column, String value, String reason) {

        /** 行级失败（带列信息） */
        static RowFailure of(int row, String column, String value, String reason) {
            return new RowFailure(row, column, value, reason);
        }

        /** 行级失败（不指定列，如整行格式错误） */
        static RowFailure ofRow(int row, String reason) {
            return new RowFailure(row, null, null, reason);
        }
    }

    // ---- Excel 行数据 DTO（字段全 String，解析/转换在 ImportService 内完成） ----

    /** 商品档案行（对应模板列） */
    record ProductRow(int rowNum, String code, String name, String unit,
                      String spec, String barcode, String remark) {
    }

    /** 客户档案行 */
    record CustomerRow(int rowNum, String code, String name, String settlementMethod,
                       String contactPerson, String contactPhone, String address,
                       String taxNo, String creditLimit) {
    }

    /** 供应商档案行 */
    record SupplierRow(int rowNum, String code, String name, String settlementMethod,
                       String contactPerson, String contactPhone, String address, String taxNo) {
    }

    /** 期初库存行 */
    record OpeningStockRow(int rowNum, String warehouse, String product,
                           String quantity, String unitCost) {
    }
}

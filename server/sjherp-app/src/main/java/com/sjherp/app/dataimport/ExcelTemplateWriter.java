package com.sjherp.app.dataimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * POI 模板生成助手（包私有，M2-T09）：
 * 按导入类型写出仅含表头行（+ 一行示例数据）的 .xlsx 字节流，供模板下载端点返回。
 *
 * <p>表头列定义与 {@link ExcelWorkbookReader} 的必填列校验保持一致——
 * 两处列名均通过 {@link ImportColumns} 常量引用，避免漂移。
 *
 * <p>程序化生成，不预置二进制 .xlsx 文件，避免仓库存大二进制。
 */
final class ExcelTemplateWriter {

    private ExcelTemplateWriter() {
    }

    /** 商品档案模板（对应 /api/import/templates/products） */
    static byte[] products() {
        String[] headers = ImportColumns.PRODUCT_HEADERS;
        String[] example = {"SKU-001", "不锈钢板 304L", "千克", "4×1500×3000", "6901234567890", "示例商品"};
        return build(headers, example);
    }

    /** 客户档案模板（对应 /api/import/templates/customers） */
    static byte[] customers() {
        String[] headers = ImportColumns.CUSTOMER_HEADERS;
        String[] example = {"CUS-001", "示例客户公司", "月结", "张三", "13800138000",
                "广东省广州市", "91440100XXX", "50000"};
        return build(headers, example);
    }

    /** 供应商档案模板（对应 /api/import/templates/suppliers） */
    static byte[] suppliers() {
        String[] headers = ImportColumns.SUPPLIER_HEADERS;
        String[] example = {"SUP-001", "示例供应商公司", "月结", "李四", "13900139000",
                "广东省深圳市", "91440300XXX"};
        return build(headers, example);
    }

    /** 期初库存模板（对应 /api/import/templates/opening-stock） */
    static byte[] openingStock() {
        String[] headers = ImportColumns.OPENING_STOCK_HEADERS;
        String[] example = {"WH-001", "SKU-001", "100", "10.00"};
        return build(headers, example);
    }

    // ---- 内部构建 ----

    private static byte[] build(String[] headers, String[] exampleValues) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("导入数据");

            // 表头行（加粗）
            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, Math.max(headers[i].length() * 512 + 1024, 4096));
            }

            // 示例行（文本格式，避免数值被 Excel 转为科学计数法）
            Row exampleRow = sheet.createRow(1);
            for (int i = 0; i < exampleValues.length && i < headers.length; i++) {
                exampleRow.createCell(i).setCellValue(exampleValues[i]);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new IllegalStateException("生成 Excel 模板失败", e);
        }
    }
}

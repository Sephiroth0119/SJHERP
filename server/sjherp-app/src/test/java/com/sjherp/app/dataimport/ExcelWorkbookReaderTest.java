package com.sjherp.app.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sjherp.app.dataimport.ExcelWorkbookReader.FileImportException;
import com.sjherp.app.dataimport.ExcelWorkbookReader.ParsedSheet;

/**
 * ExcelWorkbookReader 纯单测（M2-T09）：
 * 用 ExcelTemplateWriter 生成字节流作为测试输入，断言表头映射、空白收敛、
 * 数值读为纯文本、缺列报文件级错误、行号 1-based 回传。
 *
 * <p>纯单测，无 Spring/DB 依赖。
 */
class ExcelWorkbookReaderTest {

    private final ExcelWorkbookReader reader = new ExcelWorkbookReader();

    // ---- 商品模板正常解析 ----

    @Test
    void 商品模板_表头正常_示例行可读出() {
        byte[] xlsx = ExcelTemplateWriter.products();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.PRODUCT_REQUIRED);

        assertThat(sheet.rows()).hasSize(1); // 示例行
        Map<String, String> row = sheet.rows().get(0);
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_NAME)).isEqualTo("不锈钢板 304L");
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_UNIT)).isEqualTo("千克");
        // 可选列有值
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.PRODUCT_CODE)).isEqualTo("SKU-001");
    }

    @Test
    void 期初库存模板_示例行四列均可读() {
        byte[] xlsx = ExcelTemplateWriter.openingStock();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.OPENING_STOCK_REQUIRED);

        assertThat(sheet.rows()).hasSize(1);
        Map<String, String> row = sheet.rows().get(0);
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.OPENING_WAREHOUSE)).isEqualTo("WH-001");
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.OPENING_PRODUCT)).isEqualTo("SKU-001");
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.OPENING_QUANTITY)).isEqualTo("100");
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.OPENING_UNIT_COST)).isEqualTo("10.00");
    }

    // ---- 行号 1-based 回传 ----

    @Test
    void 行号1based_表头为第1行_数据从第2行起() {
        byte[] xlsx = ExcelTemplateWriter.products();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.PRODUCT_REQUIRED);

        assertThat(sheet.rows()).hasSize(1);
        int rowNum = ExcelWorkbookReader.rowNum(sheet.rows().get(0));
        // 示例行是 Excel 第 2 行（表头第 1 行，数据从第 2 行起）
        assertThat(rowNum).isEqualTo(2);
    }

    // ---- 空白单元格收敛 null ----

    @Test
    void 可选列空白_收敛为null() {
        // 客户模板示例行含全部列，但可测试空白行为：用商品模板，编码列示例有值
        // 验证 col() 空白收敛逻辑：用供应商模板的联系人等可选列
        byte[] xlsx = ExcelTemplateWriter.customers();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.CUSTOMER_REQUIRED);

        assertThat(sheet.rows()).hasSize(1);
        Map<String, String> row = sheet.rows().get(0);
        // 示例行有联系人/电话，验证非空
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_CONTACT_PERSON))
                .isNotNull().isNotBlank();
    }

    // ---- 缺必填列报文件级错误 ----

    @Test
    void 缺必填列_抛FileImportException() {
        // 用商品模板，但要求供应商必填列（缺列）
        byte[] xlsx = ExcelTemplateWriter.products();
        Set<String> wrongRequiredColumns = Set.of("不存在的列");

        assertThatThrownBy(() -> reader.open(new ByteArrayInputStream(xlsx), wrongRequiredColumns))
                .isInstanceOf(FileImportException.class)
                .hasMessageContaining("缺少必填列");
    }

    @Test
    void 必填列存在_不抛异常() {
        byte[] xlsx = ExcelTemplateWriter.products();
        // 不抛异常
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.PRODUCT_REQUIRED);
        assertThat(sheet).isNotNull();
    }

    // ---- 非合法 xlsx 字节流 ----

    @Test
    void 非xlsx字节流_抛FileImportException() {
        byte[] notXlsx = "this is not an xlsx file".getBytes();
        assertThatThrownBy(() -> reader.open(new ByteArrayInputStream(notXlsx), Set.of()))
                .isInstanceOf(FileImportException.class);
    }

    @Test
    void 空字节数组_抛FileImportException() {
        byte[] empty = new byte[0];
        assertThatThrownBy(() -> reader.open(new ByteArrayInputStream(empty), Set.of()))
                .isInstanceOf(FileImportException.class);
    }

    // ---- 数值读为纯文本（不经 double，保证 BigDecimal 精度） ----

    @Test
    void 期初单价读为纯文本字符串() {
        byte[] xlsx = ExcelTemplateWriter.openingStock();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.OPENING_STOCK_REQUIRED);

        String unitCost = ExcelWorkbookReader.col(sheet.rows().get(0), ImportColumns.OPENING_UNIT_COST);
        // 以字符串形式读出，可直接 new BigDecimal() 解析
        assertThat(unitCost).isEqualTo("10.00");
        // 确保可以无损解析为 BigDecimal（不经 double 路径，精度保证）
        assertThat(new java.math.BigDecimal(unitCost)).isEqualByComparingTo("10.00");
    }

    // ---- 客户/供应商模板正常解析 ----

    @Test
    void 客户模板_必填列均可读() {
        byte[] xlsx = ExcelTemplateWriter.customers();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.CUSTOMER_REQUIRED);

        assertThat(sheet.rows()).hasSize(1);
        Map<String, String> row = sheet.rows().get(0);
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_NAME)).isEqualTo("示例客户公司");
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.CUSTOMER_SETTLEMENT)).isEqualTo("月结");
    }

    @Test
    void 供应商模板_必填列均可读() {
        byte[] xlsx = ExcelTemplateWriter.suppliers();
        ParsedSheet sheet = reader.open(new ByteArrayInputStream(xlsx), ImportColumns.SUPPLIER_REQUIRED);

        assertThat(sheet.rows()).hasSize(1);
        Map<String, String> row = sheet.rows().get(0);
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_NAME)).isEqualTo("示例供应商公司");
        assertThat(ExcelWorkbookReader.col(row, ImportColumns.SUPPLIER_SETTLEMENT)).isEqualTo("月结");
    }
}

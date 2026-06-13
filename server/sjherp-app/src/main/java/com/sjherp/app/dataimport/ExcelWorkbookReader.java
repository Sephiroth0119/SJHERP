package com.sjherp.app.dataimport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * POI 读取助手（包私有，M2-T09）：
 * 把上传的 .xlsx 第一个 sheet 读为 {@code List<Map<列名, String>>}，
 * 按表头行建列名→列号映射，单元格一律取字符串、数值/日期格式化为纯文本，空白收敛 null。
 *
 * <p>设计要点（BigDecimal 精度原则）：数值单元格经 {@link DataFormatter} 取格式化字符串，
 * 不经 double 读取，避免 IEEE 754 精度丢失——与 CLAUDE.md「金额/数量一律 BigDecimal」对齐。
 * 调用方再以 {@code new BigDecimal(text)} 解析，保持精度。
 *
 * <p>行号约定：POI row index 从 0 开始；回传的 {@code rowNum} 为用户可见 1-based 行号
 * （表头=第 1 行，数据从第 2 行起）。
 */
final class ExcelWorkbookReader {

    private static final int HEADER_ROW_INDEX = 0; // POI 0-based
    private static final int DATA_START_ROW_INDEX = 1;

    private final DataFormatter formatter = new DataFormatter();

    /**
     * 解析结果：包含列名→列号映射与数据行列表。
     * {@link #open(InputStream, Set)} 验证列头后返回，调用方遍历 rows 逐行处理。
     */
    record ParsedSheet(List<Map<String, String>> rows) {
    }

    /**
     * 打开 .xlsx 字节流，校验表头，返回数据行列表（逐行 Map<列名, 字符串值>）。
     *
     * @param input            .xlsx 字节流（调用方负责关闭上游来源，本方法内部关闭 Workbook）
     * @param requiredColumns  必须存在的列名集合（缺列抛 {@link FileImportException}）
     * @return ParsedSheet     含数据行列表（不含表头行；空文件返回空列表）
     * @throws FileImportException 文件不是合法 xlsx、缺必填列时
     */
    ParsedSheet open(InputStream input, Set<String> requiredColumns) {
        try (XSSFWorkbook wb = new XSSFWorkbook(input)) {
            XSSFSheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                throw new FileImportException("Excel 文件不含任何 sheet");
            }

            // 读表头行（POI row 0）
            Row headerRow = sheet.getRow(HEADER_ROW_INDEX);
            if (headerRow == null) {
                throw new FileImportException("Excel 文件表头行为空");
            }
            Map<String, Integer> columnIndex = buildColumnIndex(headerRow);

            // 校验必填列
            for (String required : requiredColumns) {
                if (!columnIndex.containsKey(required)) {
                    throw new FileImportException("缺少必填列「" + required + "」，请使用最新模板");
                }
            }

            // 读数据行
            List<Map<String, String>> rows = new ArrayList<>();
            int lastRowNum = sheet.getLastRowNum(); // POI 0-based
            for (int i = DATA_START_ROW_INDEX; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, columnIndex)) {
                    continue; // 跳过完全空白行
                }
                Map<String, String> rowMap = new LinkedHashMap<>();
                rowMap.put("__rowNum__", String.valueOf(i + 1)); // 1-based 行号
                for (Map.Entry<String, Integer> entry : columnIndex.entrySet()) {
                    Cell cell = row.getCell(entry.getValue());
                    rowMap.put(entry.getKey(), cellText(cell));
                }
                rows.add(rowMap);
            }
            return new ParsedSheet(rows);

        } catch (FileImportException e) {
            throw e;
        } catch (IOException e) {
            throw new FileImportException("无法解析 Excel 文件（不是合法的 .xlsx 格式）：" + e.getMessage());
        } catch (Exception e) {
            throw new FileImportException("Excel 文件解析失败：" + e.getMessage());
        }
    }

    /** 读取行号（1-based，存于 rowMap 的 {@code __rowNum__} key） */
    static int rowNum(Map<String, String> row) {
        String val = row.get("__rowNum__");
        return val == null ? -1 : Integer.parseInt(val);
    }

    /** 从 rowMap 取指定列值（null 表示空） */
    static String col(Map<String, String> row, String column) {
        String val = row.get(column);
        return val == null || val.isBlank() ? null : val.strip();
    }

    // ---- 内部工具 ----

    /** 表头行 → 列名→列号映射（列名取 strip，忽略空白列头） */
    private Map<String, Integer> buildColumnIndex(Row headerRow) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int c = 0; c <= headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            String name = cellText(cell);
            if (name != null && !name.isBlank()) {
                index.put(name.strip(), c);
            }
        }
        return index;
    }

    /** 数值/日期格式化取字符串，空白收敛 null（严禁经 double 读数值，保证 BigDecimal 精度） */
    private String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.BLANK) {
            return null;
        }
        // 数值型（含日期）：DataFormatter 按单元格格式字符串化，不走 getNumericCellValue() → double
        String text = formatter.formatCellValue(cell).strip();
        return text.isEmpty() ? null : text;
    }

    /** 判断某行在已知列集合中是否全部空白（跳过完全空白行，不作为失败行） */
    private boolean isBlankRow(Row row, Map<String, Integer> columnIndex) {
        for (int colIdx : columnIndex.values()) {
            Cell cell = row.getCell(colIdx);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String text = formatter.formatCellValue(cell).strip();
                if (!text.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 文件级错误（无法解析/缺 sheet/列头不符）；
     * 与行级 {@link ImportDtos.RowFailure} 区分，由 {@link ImportExceptionHandler} 映射为 400。
     */
    static class FileImportException extends RuntimeException {
        FileImportException(String message) {
            super(message);
        }
    }
}

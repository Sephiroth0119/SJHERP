/**
 * 前端生成 Excel 2003 SpreadsheetML（.xls）。
 * 单元格一律 ss:Type=String，金额/数量保持后端字符串，不做数值转换。
 */

export interface SpreadsheetSheet {
  name: string;
  headers: readonly string[];
  rows: readonly (readonly string[])[];
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function sanitizeSheetName(name: string): string {
  const cleaned = name.replace(/[\\/*?:\[\]]/g, ' ').trim() || 'Sheet1';
  return cleaned.slice(0, 31);
}

function cellXml(value: string): string {
  return `<Cell><Data ss:Type="String">${escapeXml(value)}</Data></Cell>`;
}

function rowXml(cells: readonly string[]): string {
  return `<Row>${cells.map(cellXml).join('')}</Row>`;
}

export function buildSpreadsheetMl(sheets: readonly SpreadsheetSheet[]): string {
  const worksheets = sheets.map((sheet) => {
    const name = escapeXml(sanitizeSheetName(sheet.name));
    const header = rowXml(sheet.headers);
    const body = sheet.rows.map(rowXml).join('');
    return `<Worksheet ss:Name="${name}"><Table>${header}${body}</Table></Worksheet>`;
  }).join('');
  return `<?xml version="1.0" encoding="UTF-8"?>`
    + `<?mso-application progid="Excel.Sheet"?>`
    + `<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"`
    + ` xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">`
    + worksheets
    + `</Workbook>`;
}

export function spreadsheetFilename(title: string, stamp: string): string {
  const safeTitle = title.replace(/[\\/:*?"<>|]/g, '-').trim() || '报表';
  const safeStamp = stamp.replace(/[\\/:*?"<>|]/g, '-').trim();
  return safeStamp ? `${safeTitle}-${safeStamp}.xls` : `${safeTitle}.xls`;
}

export function downloadSpreadsheet(filename: string, sheets: readonly SpreadsheetSheet[]): void {
  const xml = buildSpreadsheetMl(sheets);
  const blob = new Blob([`\uFEFF${xml}`], { type: 'application/vnd.ms-excel;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename.endsWith('.xls') ? filename : `${filename}.xls`;
  anchor.click();
  URL.revokeObjectURL(url);
}

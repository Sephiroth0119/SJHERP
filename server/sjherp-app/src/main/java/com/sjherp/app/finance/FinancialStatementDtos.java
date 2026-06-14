package com.sjherp.app.finance;

import java.util.List;

/**
 * 财务报表 API 的响应 DTO（M4-T06，资产负债表 + 利润表）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额在 JSON 中一律以<b>字符串</b>承载
 * （{@code BigDecimal#toPlainString}），绝不用 JSON 数字——口径同账龄/选项返回协议。
 * 由 {@link FinancialStatementService} 构造（service 已完成 toPlainString），DTO 仅作传输结构。
 */
public final class FinancialStatementDtos {

    private FinancialStatementDtos() {
    }

    // =====================================================================
    // 资产负债表（时点，Assets = Liabilities + Equity）
    // =====================================================================

    /** 资产负债表行：报表项名称 + 金额（字符串，自然展示方向净额）。 */
    public record BalanceSheetLine(String name, String amount) {
    }

    /**
     * 资产负债表：账期 P 末时点；资产/负债/权益三组报表行 + 各组总计 + 平衡标志。
     * balanced = 资产总计.compareTo(负债合计 + 权益合计) == 0（设计真源 §2.1 平衡不变式）。
     */
    public record BalanceSheet(String period,
                               List<BalanceSheetLine> assetLines, String totalAssets,
                               List<BalanceSheetLine> liabilityLines, String totalLiabilities,
                               List<BalanceSheetLine> equityLines, String totalEquity,
                               boolean balanced) {
    }

    // =====================================================================
    // 利润表（期间 + 本年累计；Revenue − Expense = Net Profit）
    // =====================================================================

    /** 利润表行：报表项名称 + 本期金额 + 本年累计金额（均字符串，自然方向净额）。 */
    public record IncomeStatementLine(String name, String currentPeriod, String yearToDate) {
    }

    /**
     * 利润表：账期 P；逐行（营业收入…净利润）含本期/本年累计两列 + 净利润两口径冗余暴露。
     * netProfitCurrent/netProfitYtd 与 lines 中"净利润"行同值（便于交叉校验、调用方直取）。
     */
    public record IncomeStatement(String period, List<IncomeStatementLine> lines,
                                  String netProfitCurrent, String netProfitYtd) {
    }
}

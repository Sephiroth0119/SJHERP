package com.sjherp.app.finance;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.app.finance.AgingReportDao.AgingGrandTotal;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.finance.AgingReportDao.AgingRow;

/**
 * 应收应付账龄分析 API 的响应 DTO（M4-T03）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额在 JSON 中一律以<b>字符串</b>承载（{@code BigDecimal#toPlainString}），
 * 绝不用 JSON 数字——口径同报表/选项返回协议。桶字段命名 notDue/overdue1To30/overdue31To60/
 * overdue61To90/overdue90Plus/totalOutstanding。
 */
public final class AgingDtos {

    private AgingDtos() {
    }

    /** 账龄报表响应：截止日 + 分页明细 + 全过滤集总计。 */
    public record AgingReportResponse(String asOf, List<AgingItem> items, long total, int page, int size,
                                      GrandTotal grandTotal) {

        static AgingReportResponse from(AgingReport report) {
            return new AgingReportResponse(
                    report.asOf() == null ? null : report.asOf().toString(),
                    report.page().items().stream().map(AgingItem::from).toList(),
                    report.page().total(), report.page().page(), report.page().size(),
                    GrandTotal.from(report.grandTotal()));
        }
    }

    /** 账龄行（按对手方：客户或供应商；档案缺失时 code/name 为 null）。 */
    public record AgingItem(long counterpartyId, String counterpartyCode, String counterpartyName,
                            String notDue, String overdue1To30, String overdue31To60,
                            String overdue61To90, String overdue90Plus, String totalOutstanding) {

        static AgingItem from(AgingRow r) {
            return new AgingItem(r.counterpartyId(), r.counterpartyCode(), r.counterpartyName(),
                    plain(r.notDue()), plain(r.overdue1To30()), plain(r.overdue31To60()),
                    plain(r.overdue61To90()), plain(r.overdue90Plus()), plain(r.totalOutstanding()));
        }
    }

    /** 全过滤集总计（各桶 grandTotal + 总未核销）。 */
    public record GrandTotal(String notDue, String overdue1To30, String overdue31To60,
                             String overdue61To90, String overdue90Plus, String totalOutstanding) {

        static GrandTotal from(AgingGrandTotal g) {
            return new GrandTotal(plain(g.notDue()), plain(g.overdue1To30()), plain(g.overdue31To60()),
                    plain(g.overdue61To90()), plain(g.overdue90Plus()), plain(g.totalOutstanding()));
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}

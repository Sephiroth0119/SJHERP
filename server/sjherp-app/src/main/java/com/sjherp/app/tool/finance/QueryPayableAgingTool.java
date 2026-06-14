package com.sjherp.app.tool.finance;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.tool.ArchiveToolSupport;

/**
 * 应付账龄查询工具（M4-T08，NORMAL，只读）：按截止日 + 可选供应商过滤，返回未结清应付的账龄分桶
 * （未到期/逾期 1-30/31-60/61-90/90+ 天）+ 合计行。
 *
 * <p>只读经 {@link AgingReportDao#payableAging}（{@code @Transactional(readOnly = true)}）。
 * 权限点 finance:settlement（与 AgingReportController 端点同口径）。
 * 金额一律 {@link java.math.BigDecimal#toPlainString}，日期 ISO 字符串。
 */
public class QueryPayableAgingTool implements Tool {

    public static final String NAME = "query_payable_aging";

    private final AgingReportDao agingReportDao;

    public QueryPayableAgingTool(AgingReportDao agingReportDao) {
        this.agingReportDao = Objects.requireNonNull(agingReportDao, "agingReportDao 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询应付账龄：按截止日（asOf）对未结清应付按供应商汇总，返回 5 个逾期桶（未到期/1-30天/"
                + "31-60天/61-90天/90天以上）的未核销余额，以及全过滤集总计行（grandTotal）。"
                + "用户问\"应付账龄\"\"还有哪些款没付\"\"供应商欠款\"时调用。"
                + "asOf 缺省今天；supplierId 可选，填写供应商数据库 ID 可过滤单个供应商。"
                + "需要 finance:settlement 权限。金额返回字符串格式（非 JSON 数字）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "asOf":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$",\
                "description":"截止日 YYYY-MM-DD（缺省今天）"},\
                "supplierId":{"type":"integer","description":"供应商 ID（可选，不填查全部）"},\
                "page":{"type":"integer","description":"页码（从 1 开始，缺省 1）"},\
                "size":{"type":"integer","description":"每页条数（缺省 20，最大 200）"}},\
                "required":[],"additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return "finance:settlement";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        // asOf 缺省今天
        LocalDate asOf;
        String asOfStr = ArchiveToolSupport.str(arguments.get("asOf"));
        if (asOfStr == null || asOfStr.isBlank()) {
            asOf = LocalDate.now();
        } else {
            try {
                asOf = LocalDate.parse(asOfStr);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("asOf 日期格式错误，须为 YYYY-MM-DD，如 2026-06-30");
            }
        }

        Long supplierId = null;
        Object sidRaw = arguments.get("supplierId");
        if (sidRaw != null) {
            if (sidRaw instanceof Number n) {
                supplierId = n.longValue();
            } else {
                return ToolResult.fail("supplierId 须为整数（供应商数据库 ID）");
            }
        }

        int page = QueryReceivableAgingTool.intArg(arguments.get("page"), 1);
        int size = QueryReceivableAgingTool.intArg(arguments.get("size"), 20);
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > AgingReportDao.MAX_SIZE) {
            size = 20;
        }

        AgingReport report = agingReportDao.payableAging(asOf, supplierId, page, size);
        return ToolResult.ok(QueryReceivableAgingTool.toData(report));
    }
}

package com.sjherp.app.tool.finance;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.finance.AgingReportDao.AgingRow;
import com.sjherp.app.tool.ArchiveToolSupport;

/**
 * 应收账龄查询工具（M4-T08，NORMAL，只读）：按截止日 + 可选客户过滤，返回未结清应收的账龄分桶
 * （未到期/逾期 1-30/31-60/61-90/90+ 天）+ 合计行。
 *
 * <p>只读经 {@link AgingReportDao#receivableAging}（{@code @Transactional(readOnly = true)}）。
 * 权限点 finance:settlement（与 AgingReportController 端点同口径）。
 * 金额一律 {@link java.math.BigDecimal#toPlainString}，日期 ISO 字符串。
 */
public class QueryReceivableAgingTool implements Tool {

    public static final String NAME = "query_receivable_aging";

    private final AgingReportDao agingReportDao;

    public QueryReceivableAgingTool(AgingReportDao agingReportDao) {
        this.agingReportDao = Objects.requireNonNull(agingReportDao, "agingReportDao 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询应收账龄：按截止日（asOf）对未结清应收按客户汇总，返回 5 个逾期桶（未到期/1-30天/"
                + "31-60天/61-90天/90天以上）的未核销余额，以及全过滤集总计行（grandTotal）。"
                + "用户问\"应收账龄\"\"哪些客户的款逾期了\"\"催款列表\"时调用。"
                + "asOf 缺省今天；customerId 可选，填写客户数据库 ID 可过滤单个客户。"
                + "需要 finance:settlement 权限。金额返回字符串格式（非 JSON 数字）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "asOf":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$",\
                "description":"截止日 YYYY-MM-DD（缺省今天）"},\
                "customerId":{"type":"integer","description":"客户 ID（可选，不填查全部）"},\
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

        Long customerId = null;
        Object cidRaw = arguments.get("customerId");
        if (cidRaw != null) {
            if (cidRaw instanceof Number n) {
                customerId = n.longValue();
            } else {
                return ToolResult.fail("customerId 须为整数（客户数据库 ID）");
            }
        }

        int page = intArg(arguments.get("page"), 1);
        int size = intArg(arguments.get("size"), 20);
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20; // 缺省/非法 → 默认页大小
        } else if (size > AgingReportDao.MAX_SIZE) {
            size = AgingReportDao.MAX_SIZE; // 超限截断至上限（非重置默认），与「最大 200」文档一致（评审 P2）
        }

        AgingReport report = agingReportDao.receivableAging(asOf, customerId, page, size);
        return ToolResult.ok(toData(report));
    }

    static Map<String, Object> toData(AgingReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("asOf", report.asOf().toString());

        // 分页元信息
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("total", report.page().total());
        pagination.put("page", report.page().page());
        pagination.put("size", report.page().size());
        data.put("pagination", pagination);

        // 明细行
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgingRow row : report.page().items()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("counterpartyId", row.counterpartyId());
            r.put("counterpartyCode", row.counterpartyCode());
            r.put("counterpartyName", row.counterpartyName());
            r.put("notDue", row.notDue().toPlainString());
            r.put("overdue1To30", row.overdue1To30().toPlainString());
            r.put("overdue31To60", row.overdue31To60().toPlainString());
            r.put("overdue61To90", row.overdue61To90().toPlainString());
            r.put("overdue90Plus", row.overdue90Plus().toPlainString());
            r.put("totalOutstanding", row.totalOutstanding().toPlainString());
            rows.add(r);
        }
        data.put("rows", rows);

        // 总计行
        Map<String, Object> gt = new LinkedHashMap<>();
        gt.put("notDue", report.grandTotal().notDue().toPlainString());
        gt.put("overdue1To30", report.grandTotal().overdue1To30().toPlainString());
        gt.put("overdue31To60", report.grandTotal().overdue31To60().toPlainString());
        gt.put("overdue61To90", report.grandTotal().overdue61To90().toPlainString());
        gt.put("overdue90Plus", report.grandTotal().overdue90Plus().toPlainString());
        gt.put("totalOutstanding", report.grandTotal().totalOutstanding().toPlainString());
        data.put("grandTotal", gt);

        return data;
    }

    /** 从参数中读整数，缺省返回 defaultValue。 */
    static int intArg(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}

package com.sjherp.app.consistency;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据一致性校验 API（M3-T13 检查 Agent，ADMIN/BOSS 限定）：
 * <ul>
 *   <li>GET /api/consistency/check → 200 完整勾稽报告（七条规则的全部 break + 按严重度计数）；</li>
 *   <li>非 ADMIN/BOSS → 403（与审计日志查询同级——它能看到全量账本差异）。</li>
 * </ul>
 * 只读：本 API 只跑校验、出报告，绝不改账（纠错走业务单据红字冲销）。
 */
@RestController
@RequestMapping("/api/consistency")
public class ConsistencyController {

    private final ConsistencyCheckService consistencyCheckService;

    public ConsistencyController(ConsistencyCheckService consistencyCheckService) {
        this.consistencyCheckService = Objects.requireNonNull(consistencyCheckService,
                "consistencyCheckService 不能为空");
    }

    /** 跑一遍全部勾稽校验，返回完整报告（仅 ADMIN/BOSS——含全量账本差异）。 */
    @PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
    @GetMapping("/check")
    public ConsistencyReportResponse check() {
        return ConsistencyReportResponse.from(consistencyCheckService.check());
    }

    // ---------------------------------------------------------------
    // 响应 DTO（金额/数量一律字符串承载——精度原则，规避 JSON 数字误差）
    // ---------------------------------------------------------------

    /** 报告响应：校验时刻 + 计数汇总 + 全量差异明细。 */
    public record ConsistencyReportResponse(Instant checkedAt, boolean clean, long total,
                                            long errorCount, long warnCount, long infoCount,
                                            List<BreakItem> breaks) {

        static ConsistencyReportResponse from(ConsistencyReport report) {
            return new ConsistencyReportResponse(
                    report.checkedAt(), report.clean(), report.breaks().size(),
                    report.errorCount(), report.warnCount(), report.infoCount(),
                    report.breaks().stream().map(BreakItem::from).toList());
        }
    }

    /** 单条差异。 */
    public record BreakItem(String checkType, String checkTypeName, String key,
                            String expected, String actual, String severity, String message) {

        static BreakItem from(ConsistencyBreak b) {
            return new BreakItem(b.checkType().code(), b.checkType().displayName(), b.key(),
                    b.expected(), b.actual(), b.severity().name(), b.message());
        }
    }
}

package com.sjherp.app.consistency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;

/**
 * 数据一致性校验 API（M6-T05 检查 Agent，ADMIN/BOSS 限定）：
 * <ul>
 *   <li>GET /api/consistency/check → 即时纯预览，不保存报告；</li>
 *   <li>POST /api/consistency/runs → 显式运行并保存报告；</li>
 *   <li>GET /api/consistency/reports/** → 历史报告摘要与明细。</li>
 *   <li>非 ADMIN/BOSS → 403（与审计日志查询同级——它能看到全量账本差异）。</li>
 * </ul>
 * 校验绝不改业务账；显式运行只追加运行报告和必要通知（纠错走业务单据红字冲销）。
 */
@RestController
@RequestMapping("/api/consistency")
public class ConsistencyController {

    private final ConsistencyCheckService consistencyCheckService;
    private final ConsistencyCheckRunner consistencyCheckRunner;
    private final ConsistencyReportService consistencyReportService;

    public ConsistencyController(ConsistencyCheckService consistencyCheckService,
                                 ConsistencyCheckRunner consistencyCheckRunner,
                                 ConsistencyReportService consistencyReportService) {
        this.consistencyCheckService = Objects.requireNonNull(consistencyCheckService,
                "consistencyCheckService 不能为空");
        this.consistencyCheckRunner = Objects.requireNonNull(consistencyCheckRunner,
                "consistencyCheckRunner 不能为空");
        this.consistencyReportService = Objects.requireNonNull(consistencyReportService,
                "consistencyReportService 不能为空");
    }

    /** 跑一遍全部勾稽校验并仅返回内存报告，不持久化（仅 ADMIN/BOSS）。 */
    @PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
    @GetMapping("/check")
    public ConsistencyReportResponse check() {
        return ConsistencyReportResponse.from(consistencyCheckService.check());
    }

    /** 显式运行并持久化一次管理端检查；与纯预览 GET /check 严格分离。 */
    @PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
    @PostMapping("/runs")
    public RunResponse run() {
        try {
            return RunResponse.from(consistencyCheckRunner.runManual(CurrentUser.operator()));
        } catch (RuntimeException executionFailure) {
            throw new ConsistencyRunExecutionException();
        }
    }

    /** 分页查询历史运行摘要，不返回差异正文。 */
    @PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
    @GetMapping("/reports")
    public RunPageResponse reports(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return RunPageResponse.from(consistencyReportService.search(page, size));
    }

    /** 查询单次运行及其完整差异明细。 */
    @PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
    @GetMapping("/reports/{runNo}")
    public RunDetailResponse report(@PathVariable String runNo) {
        return RunDetailResponse.from(consistencyReportService.get(runNo));
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

    /** 显式运行及报告列表共用的脱敏摘要。 */
    public record RunResponse(String runNo, String triggerType, String requestedBy,
                              Instant startedAt, Instant completedAt, String status,
                              boolean clean, long totalCount, long errorCount, long warnCount,
                              long infoCount, String analysisStatus, String analysisSummary,
                              String failureType) {

        static RunResponse from(ConsistencyCheckRun run) {
            return new RunResponse(run.runNo(), run.triggerType().name(), run.requestedBy(),
                    run.startedAt(), run.completedAt(), run.status().name(), run.clean(),
                    run.totalCount(), run.errorCount(), run.warnCount(), run.infoCount(),
                    run.analysisStatus().name(), run.analysisSummary(), run.failureType());
        }
    }

    public record RunPageResponse(List<RunResponse> items, long total, int page, int size) {

        static RunPageResponse from(PageResult<ConsistencyCheckRun> result) {
            return new RunPageResponse(result.items().stream().map(RunResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    public record RunDetailResponse(String runNo, String triggerType, String requestedBy,
                                    Instant startedAt, Instant completedAt, String status,
                                    boolean clean, long totalCount, long errorCount, long warnCount,
                                    long infoCount, String analysisStatus, String analysisSummary,
                                    String failureType, List<FindingItem> findings) {

        static RunDetailResponse from(ConsistencyCheckRun run) {
            return new RunDetailResponse(run.runNo(), run.triggerType().name(), run.requestedBy(),
                    run.startedAt(), run.completedAt(), run.status().name(), run.clean(),
                    run.totalCount(), run.errorCount(), run.warnCount(), run.infoCount(),
                    run.analysisStatus().name(), run.analysisSummary(), run.failureType(),
                    run.findings().stream().map(FindingItem::from).toList());
        }
    }

    public record FindingItem(int sequenceNo, String ruleCode, String checkType, String objectKey,
                              String expected, String actual, String severity, String message) {

        static FindingItem from(ConsistencyFinding finding) {
            return new FindingItem(finding.sequenceNo(), finding.ruleCode(), finding.checkType(),
                    finding.objectKey(), plain(finding.expectedValue()), plain(finding.actualValue()),
                    finding.severity().name(), finding.message());
        }

        private static String plain(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }
    }

    @ExceptionHandler(ConsistencyReportNotFoundException.class)
    ResponseEntity<Map<String, String>> handleNotFound(ConsistencyReportNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ConsistencyRunExecutionException.class)
    ResponseEntity<Map<String, String>> handleExecutionFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ConsistencyRunExecutionException.SAFE_MESSAGE));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "分页参数不合法"));
    }
}

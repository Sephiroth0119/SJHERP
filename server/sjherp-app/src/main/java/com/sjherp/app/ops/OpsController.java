package com.sjherp.app.ops;

import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.ops.HttpMetricsSummaryService.HttpMetricsSummary;
import com.sjherp.app.ops.LlmDailySummaryService.LlmDailySummary;

/**
 * 运维只读查询（M8-T04）：HTTP 进程内汇总 + LLM 日报。仅 ADMIN/BOSS。
 *
 * <p>Prometheus 抓取走 {@code /actuator/prometheus}，不经过本控制器。
 */
@RestController
@RequestMapping("/api/ops")
@PreAuthorize("hasAnyRole('ADMIN','BOSS')")
public class OpsController {

    private final LlmDailySummaryService llmDailySummaryService;
    private final HttpMetricsSummaryService httpMetricsSummaryService;

    public OpsController(LlmDailySummaryService llmDailySummaryService,
                         HttpMetricsSummaryService httpMetricsSummaryService) {
        this.llmDailySummaryService = Objects.requireNonNull(llmDailySummaryService,
                "llmDailySummaryService 不能为空");
        this.httpMetricsSummaryService = Objects.requireNonNull(httpMetricsSummaryService,
                "httpMetricsSummaryService 不能为空");
    }

    @GetMapping("/llm-daily")
    public LlmDailySummary llmDaily(@RequestParam(required = false) String date) {
        return llmDailySummaryService.summarize(date);
    }

    @GetMapping("/http-summary")
    public HttpMetricsSummary httpSummary() {
        return httpMetricsSummaryService.snapshot();
    }
}

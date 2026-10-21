package com.sjherp.app.ops;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.sjherp.infra.persistence.invocation.AgentInvocationRepository;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository.LlmUsageWindow;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository.ModelBreakdown;

/**
 * LLM 用量日报：聚合 {@code agent_invocation} 真源，金额只在配置了单价时估算。
 */
@Service
public class LlmDailySummaryService {

    private final AgentInvocationRepository repository;
    private final OpsProperties properties;
    private final Clock clock;

    public LlmDailySummaryService(AgentInvocationRepository repository, OpsProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    LlmDailySummaryService(AgentInvocationRepository repository, OpsProperties properties, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    public LlmDailySummary summarize(String utcDate) {
        LocalDate date = parseUtcDate(utcDate);
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        LlmUsageWindow window = repository.summarizeLlm(from, to);
        Optional<BigDecimal> cost = LlmCostEstimator.estimate(
                window.promptTokens(),
                window.completionTokens(),
                properties.llmPromptPricePerMillion(),
                properties.llmCompletionPricePerMillion());
        List<LlmDailySummary.ModelRow> models = window.byModel().stream()
                .map(LlmDailySummaryService::toRow)
                .toList();
        return new LlmDailySummary(
                date.toString(),
                from.toString(),
                to.toString(),
                window.llmCalls(),
                window.llmFailures(),
                window.promptTokens(),
                window.completionTokens(),
                window.durationMs(),
                cost.map(BigDecimal::toPlainString).orElse(null),
                cost.isPresent() ? "CNY" : null,
                cost.isPresent()
                        ? "按 deploy/.env 单价估算，非正式账单"
                        : "未配置 SJHERP_LLM_PRICE_*_PER_MILLION，只汇总 token，不估算金额",
                models);
    }

    public LocalDate todayUtc() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public LocalDate yesterdayUtc() {
        return todayUtc().minusDays(1);
    }

    private LocalDate parseUtcDate(String utcDate) {
        if (utcDate == null || utcDate.isBlank()) {
            return todayUtc();
        }
        try {
            return LocalDate.parse(utcDate.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date 必须是 UTC 的 yyyy-MM-dd");
        }
    }

    private static LlmDailySummary.ModelRow toRow(ModelBreakdown row) {
        return new LlmDailySummary.ModelRow(
                row.model(),
                row.calls(),
                row.failures(),
                row.promptTokens(),
                row.completionTokens(),
                row.durationMs());
    }

    public record LlmDailySummary(
            String date,
            String from,
            String to,
            long llmCalls,
            long llmFailures,
            long promptTokens,
            long completionTokens,
            long durationMs,
            String estimatedCost,
            String currency,
            String costNote,
            List<ModelRow> byModel) {
        public LlmDailySummary {
            byModel = byModel == null ? List.of() : List.copyOf(byModel);
        }

        public record ModelRow(String model, long calls, long failures,
                               long promptTokens, long completionTokens, long durationMs) {
        }
    }
}

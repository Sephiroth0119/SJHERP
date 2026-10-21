package com.sjherp.app.ops;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 从 Micrometer {@code http.server.requests} 汇总进程内 HTTP 次数/4xx/5xx/最大耗时。
 * 进程重启后计数归零，适合本机刮取，不是长期账单。
 */
@Service
public class HttpMetricsSummaryService {

    private final MeterRegistry meterRegistry;

    public HttpMetricsSummaryService(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry 不能为空");
    }

    public HttpMetricsSummary snapshot() {
        long requests = 0;
        long errors4xx = 0;
        long errors5xx = 0;
        double maxMs = 0d;
        double totalMs = 0d;
        for (Meter meter : meterRegistry.find("http.server.requests").meters()) {
            if (!(meter instanceof Timer timer)) {
                continue;
            }
            long count = timer.count();
            requests += count;
            totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
            maxMs = Math.max(maxMs, timer.max(TimeUnit.MILLISECONDS));
            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("4")) {
                errors4xx += count;
            } else if (status != null && status.startsWith("5")) {
                errors5xx += count;
            }
        }
        return new HttpMetricsSummary(
                requests,
                errors4xx,
                errors5xx,
                formatMs(maxMs),
                formatMs(totalMs),
                "进程内累计（重启归零）。Prometheus 文本见 GET /actuator/prometheus");
    }

    private static String formatMs(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public record HttpMetricsSummary(
            long requests,
            long errors4xx,
            long errors5xx,
            String maxLatencyMs,
            String totalTimeMs,
            String note) {
    }
}

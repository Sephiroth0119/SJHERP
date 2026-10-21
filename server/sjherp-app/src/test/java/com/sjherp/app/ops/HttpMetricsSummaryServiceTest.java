package com.sjherp.app.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HttpMetricsSummaryServiceTest {

    @Test
    void 按状态码累计请求与5xx() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.timer("http.server.requests", "status", "200", "uri", "/api/health")
                .record(Duration.ofMillis(12));
        registry.timer("http.server.requests", "status", "200", "uri", "/api/health")
                .record(Duration.ofMillis(8));
        registry.timer("http.server.requests", "status", "500", "uri", "/api/chat")
                .record(Duration.ofMillis(40));
        registry.timer("http.server.requests", "status", "404", "uri", "/api/missing")
                .record(Duration.ofMillis(3));

        HttpMetricsSummaryService.HttpMetricsSummary snapshot =
                new HttpMetricsSummaryService(registry).snapshot();
        assertThat(snapshot.requests()).isEqualTo(4);
        assertThat(snapshot.errors5xx()).isEqualTo(1);
        assertThat(snapshot.errors4xx()).isEqualTo(1);
        assertThat(snapshot.maxLatencyMs()).isNotBlank();
        assertThat(snapshot.note()).contains("Prometheus");
    }

    @Test
    void 无请求时全零() {
        HttpMetricsSummaryService.HttpMetricsSummary snapshot =
                new HttpMetricsSummaryService(new SimpleMeterRegistry()).snapshot();
        assertThat(snapshot.requests()).isZero();
        assertThat(snapshot.errors5xx()).isZero();
        assertThat(snapshot.maxLatencyMs()).isEqualTo("0.00");
    }
}

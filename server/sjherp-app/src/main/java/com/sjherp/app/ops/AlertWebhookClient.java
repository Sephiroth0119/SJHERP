package com.sjherp.app.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 可选告警 Webhook（M8-T04）。未配置 URL 时静默跳过；失败只打日志，不抛给业务。
 *
 * <p>载荷为通用 JSON：{@code {"text":"...","status":"CRIT|WARN|INFO"}}，Slack Incoming
 * Webhook 可直接吃 {@code text}；钉钉/企微需在文档里用适配器转换。
 */
@Component
public class AlertWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookClient.class);

    private final OpsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public AlertWebhookClient(OpsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    AlertWebhookClient(OpsProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient 不能为空");
    }

    public void send(String status, String text) {
        String url = properties.alertWebhook();
        if (url == null || url.isBlank()) {
            return;
        }
        String bodyText = text == null ? "" : text;
        String statusCode = status == null || status.isBlank() ? "INFO" : status;
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("text", bodyText);
            payload.put("status", statusCode);
            payload.put("source", "sjherp");
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("告警 webhook 返回 HTTP {}（不抛给业务）", response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("告警 webhook 被中断（不抛给业务）");
        } catch (Exception e) {
            log.warn("告警 webhook 发送失败（{}，不抛给业务）", e.getClass().getSimpleName());
        }
    }
}

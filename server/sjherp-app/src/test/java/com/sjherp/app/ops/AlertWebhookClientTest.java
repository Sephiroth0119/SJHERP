package com.sjherp.app.ops;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AlertWebhookClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private HttpResponse response;

    @Test
    void 未配置URL时不发送() throws Exception {
        AlertWebhookClient client = new AlertWebhookClient(
                new OpsProperties("  ", true, "0 5 0 * * *", false, "", ""),
                new ObjectMapper(),
                httpClient);
        assertThatCode(() -> client.send("CRIT", "磁盘满了")).doesNotThrowAnyException();
        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void 发送失败不抛给调用方() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("down"));
        AlertWebhookClient client = new AlertWebhookClient(
                new OpsProperties("http://127.0.0.1:9/hook", true, "0 5 0 * * *", false, "", ""),
                new ObjectMapper(),
                httpClient);
        assertThatCode(() -> client.send("CRIT", "健康检查失败")).doesNotThrowAnyException();
        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void HTTP非2xx也不抛() throws Exception {
        when(response.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(response);
        AlertWebhookClient client = new AlertWebhookClient(
                new OpsProperties("http://example.test/hook", true, "0 5 0 * * *", false, "", ""),
                new ObjectMapper(),
                httpClient);
        assertThatCode(() -> client.send("WARN", "备份过期")).doesNotThrowAnyException();
    }
}

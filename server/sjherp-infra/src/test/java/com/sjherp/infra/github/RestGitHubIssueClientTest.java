package com.sjherp.infra.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GitHubIssueClient.IssueRequest;
import com.sjherp.domain.gap.GitHubIssueGatewayException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RestGitHubIssueClientTest {
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void createAndSearchUseGitHubContract() throws Exception {
        AtomicReference<String> createPath = new AtomicReference<>();
        AtomicReference<String> searchQuery = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response;
            if ("POST".equals(exchange.getRequestMethod())) {
                createPath.set(exchange.getRequestURI().getPath());
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                response = "{\"number\":7,\"html_url\":\"http://issue/7\",\"labels\":[{\"name\":\"sjherp-gap\"}]}";
            } else {
                searchQuery.set(exchange.getRequestURI().getQuery());
                response = "{\"total_count\":1,\"items\":[{\"number\":7,\"html_url\":\"http://issue/7\",\"body\":\"SJHERP-GAP-TRACE:key\",\"labels\":[{\"name\":\"sjherp-gap\"}]}]}";
            }
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        var client = client();
        assertThat(client.create(new IssueRequest("title", List.of("label"), "SJHERP-GAP-TRACE:key")).number()).isEqualTo(7);
        assertThat(client.findByTraceMarker("SJHERP-GAP-TRACE:key")).isPresent();
        assertThat(createPath.get()).isEqualTo("/repos/acme/demo/issues");
        assertThat(searchQuery.get()).contains("in:body").contains("type:issue");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        assertThat(requestBody.get()).contains("title").contains("label").contains("SJHERP-GAP-TRACE:key");
    }

    @Test
    void rejectsNonSuccessAndMissingFields() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(502, 0); exchange.close(); });
        server.start();
        assertThatThrownBy(() -> client().create(new IssueRequest("title", List.of(), "body"))).isInstanceOf(GitHubIssueGatewayException.class);
    }

    @Test
    void searchFailsClosedWhenGitHubMarksResultsIncomplete() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String body = "{\"incomplete_results\":true,\"items\":[]}";
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> client().findByTraceMarker("SJHERP-GAP-TRACE:key"))
                .isInstanceOf(GitHubIssueGatewayException.class);
    }

    private RestGitHubIssueClient client() {
        return new RestGitHubIssueClient("http://localhost:" + server.getAddress().getPort(), "acme/demo", "secret", Duration.ofSeconds(2), new ObjectMapper());
    }
}

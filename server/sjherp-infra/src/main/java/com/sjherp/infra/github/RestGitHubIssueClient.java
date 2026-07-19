package com.sjherp.infra.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GapIssueDisabledException;
import com.sjherp.domain.gap.GitHubIssueClient;
import com.sjherp.domain.gap.GitHubIssueGatewayException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RestGitHubIssueClient implements GitHubIssueClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final String base;
    private final String repo;
    private final String token;
    private final Duration timeout;

    public RestGitHubIssueClient(String base, String repo, String token, Duration timeout, ObjectMapper json) {
        if (base == null || base.isBlank() || repo == null || repo.isBlank() || token == null || token.isBlank()) {
            throw new GapIssueDisabledException("GitHub Issue configuration is incomplete");
        }
        this.base = base.replaceAll("/$", "");
        this.repo = repo;
        this.token = token;
        this.timeout = timeout;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public IssueResponse create(IssueRequest request) {
        try {
            String payload = json.writeValueAsString(java.util.Map.of(
                    "title", request.title(), "body", request.body(), "labels", request.labels()));
            HttpResponse<String> response = http.send(
                    request("/repos/" + repo + "/issues").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new GitHubIssueGatewayException("GitHub create returned HTTP " + response.statusCode());
            return response(json.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubIssueGatewayException("GitHub create interrupted", e);
        } catch (GitHubIssueGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubIssueGatewayException("GitHub create failed", e);
        }
    }

    @Override
    public Optional<IssueResponse> findByTraceMarker(String marker) {
        try {
            String query = "repo:" + repo + "+in:body+type:issue+" + URLEncoder.encode(marker, StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(request("/search/issues?q=" + query).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new GitHubIssueGatewayException("GitHub search returned HTTP " + response.statusCode());
            JsonNode root = json.readTree(response.body());
            if (!root.isObject() || !root.path("items").isArray()) throw new GitHubIssueGatewayException("GitHub search response missing items");
            if (root.path("incomplete_results").asBoolean(false)) throw new GitHubIssueGatewayException("GitHub search results are incomplete");
            for (JsonNode item : root.path("items")) {
                if (item.path("body").asText().contains(marker)) return Optional.of(response(item));
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubIssueGatewayException("GitHub search interrupted", e);
        } catch (GitHubIssueGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubIssueGatewayException("GitHub search failed", e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("User-Agent", "SJHERP-gap-issue")
                .header("X-GitHub-Api-Version", "2022-11-28").header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json");
    }

    private IssueResponse response(JsonNode node) {
        if (!node.hasNonNull("number") || !node.hasNonNull("html_url") || !node.path("labels").isArray()) {
            throw new GitHubIssueGatewayException("GitHub Issue response missing required fields");
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode label : node.path("labels")) labels.add(label.path("name").asText());
        return new IssueResponse(node.get("number").asLong(), node.get("html_url").asText(), labels);
    }
}

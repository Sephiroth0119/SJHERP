package com.sjherp.domain.gap;

import java.util.List;

public interface GitHubIssueClient {
    IssueResponse create(IssueRequest request);
    java.util.Optional<IssueResponse> findByTraceMarker(String marker);
    record IssueRequest(String title, List<String> labels, String body) {}
    record IssueResponse(long number, String url, List<String> labels) {}
}

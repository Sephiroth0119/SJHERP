package com.sjherp.domain.gap;
public final class GitHubIssueGatewayException extends RuntimeException {
    public GitHubIssueGatewayException(Throwable cause) { super("GitHub Issue gateway failed", cause); }
    public GitHubIssueGatewayException(String message) { super(message); }
    public GitHubIssueGatewayException(String message, Throwable cause) { super(message, cause); }
}

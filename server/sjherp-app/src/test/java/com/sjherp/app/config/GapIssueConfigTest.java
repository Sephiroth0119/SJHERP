package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GitHubIssueClient;
import com.sjherp.domain.gap.GitHubIssueClient.IssueRequest;
import com.sjherp.domain.gap.GapIssueDisabledException;
import com.sjherp.infra.github.RestGitHubIssueClient;
import org.junit.jupiter.api.Test;

class GapIssueConfigTest {
    private final GapIssueConfig config = new GapIssueConfig();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void missingCredentialsUseFailClosedClient() {
        GitHubIssueClient client = config.gitHubIssueClient("https://api.github.com", "", "", 2, json);
        assertThatThrownBy(() -> client.create(new IssueRequest("title", java.util.List.of(), "body")))
                .isInstanceOf(GapIssueDisabledException.class);
    }

    @Test
    void configuredCredentialsUseRestAdapter() {
        assertThat(config.gitHubIssueClient("https://github.example", "acme/demo", "token", 2, json))
                .isInstanceOf(RestGitHubIssueClient.class);
    }
}

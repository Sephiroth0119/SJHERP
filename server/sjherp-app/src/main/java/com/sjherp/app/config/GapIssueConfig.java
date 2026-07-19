package com.sjherp.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueClusterWriter;
import com.sjherp.domain.gap.GapIssueDeliveryFinalizer;
import com.sjherp.domain.gap.GapIssueDisabledException;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GitHubIssueClient;
import com.sjherp.infra.github.RestGitHubIssueClient;
import com.sjherp.infra.persistence.gap.JdbcGapIssueCandidateRepository;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class GapIssueConfig {

    @Bean
    GapIssueCandidateRepository gapIssueCandidateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcGapIssueCandidateRepository(jdbc, mapper);
    }

    @Bean
    GitHubIssueClient gitHubIssueClient(
            @Value("${sjherp.github.issue.api-base:https://api.github.com}") String apiBase,
            @Value("${sjherp.github.issue.repo:}") String repository,
            @Value("${sjherp.github.issue.token:}") String token,
            @Value("${sjherp.github.issue.timeout-seconds:10}") long timeoutSeconds,
            ObjectMapper mapper) {
        if (repository.isBlank() || token.isBlank()) {
            return new DisabledGitHubIssueClient();
        }
        return new RestGitHubIssueClient(apiBase, repository, token, Duration.ofSeconds(timeoutSeconds), mapper);
    }

    @Bean
    GapIssueService gapIssueService(
            GapRecordRepository gaps,
            GapIssueCandidateRepository candidates,
            GitHubIssueClient github,
            @Value("${sjherp.github.issue.enabled:false}") boolean enabled,
            GapIssueDeliveryFinalizer finalizer,
            GapIssueClusterWriter writer) {
        return new GapIssueService(gaps, candidates, github, enabled, finalizer, writer);
    }

    private static final class DisabledGitHubIssueClient implements GitHubIssueClient {
        private static final String MESSAGE = "GitHub Issue configuration is incomplete";

        @Override
        public IssueResponse create(IssueRequest request) {
            throw new GapIssueDisabledException(MESSAGE);
        }

        @Override
        public Optional<IssueResponse> findByTraceMarker(String marker) {
            throw new GapIssueDisabledException(MESSAGE);
        }
    }
}

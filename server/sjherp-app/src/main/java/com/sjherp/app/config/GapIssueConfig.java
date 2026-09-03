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
import com.sjherp.domain.gap.DeveloperAgentTaskRepository;
import com.sjherp.infra.persistence.gap.JdbcDeveloperAgentTaskRepository;
import com.sjherp.infra.persistence.gap.JdbcClosureFeedbackRepository;
import com.sjherp.domain.gap.DeveloperAgentRunner;
import com.sjherp.app.gap.FakeDeveloperAgentRunner;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import com.sjherp.app.gap.ClosureFeedbackService;
import com.sjherp.app.memory.MemoryWriteChannel;
import com.sjherp.domain.notification.SystemNotificationRepository;
import com.sjherp.agent.session.AgentSessionRepository;
import java.nio.file.Path;

@Configuration
public class GapIssueConfig {

    @Bean
    com.sjherp.domain.gap.ClosureFeedbackRepository closureFeedbackRepository(JdbcTemplate jdbc) {
        return new JdbcClosureFeedbackRepository(jdbc);
    }

    @Bean
    DeveloperAgentTaskRepository developerAgentTaskRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcDeveloperAgentTaskRepository(jdbc, mapper);
    }

    @Bean
    ClosureFeedbackService closureFeedbackService(DeveloperAgentTaskRepository tasks,
            GapIssueCandidateRepository candidates, GapRecordRepository gaps,
            com.sjherp.domain.gap.ClosureFeedbackRepository closures,
            MemoryWriteChannel memory, SystemNotificationRepository notifications,
            AgentSessionRepository sessions) {
        return new ClosureFeedbackService(tasks, candidates, gaps, closures, memory, notifications, sessions);
    }

    @Bean
    DeveloperAgentRunner developerAgentRunner(@Value("${sjherp.developer-agent.demo:false}") boolean demo,
            @Value("${sjherp.developer-agent.base-url:}") String baseUrl,
            @Value("${sjherp.developer-agent.token:}") String token,
            @Value("${sjherp.developer-agent.timeout-seconds:30}") long timeoutSeconds,
            ObjectMapper mapper) {
        if (demo) return new FakeDeveloperAgentRunner();
        if (!baseUrl.isBlank() && !token.isBlank()) return new com.sjherp.app.gap.RestDeveloperAgentRunner(baseUrl, token, Duration.ofSeconds(timeoutSeconds), mapper);
        return new com.sjherp.app.gap.DisabledDeveloperAgentRunner();
    }

    @Bean
    com.sjherp.app.gap.WorkspacePolicy developerWorkspacePolicy(@Value("${sjherp.developer-agent.repository-root:${user.dir}}") String root) {
        return new com.sjherp.app.gap.WorkspacePolicy(Path.of(root));
    }

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

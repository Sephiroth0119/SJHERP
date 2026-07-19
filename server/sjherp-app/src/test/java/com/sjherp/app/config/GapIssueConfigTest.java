package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.app.gap.GapIssueScheduledPublisher;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueClusterWriter;
import com.sjherp.domain.gap.GapIssueDeliveryFinalizer;
import com.sjherp.domain.gap.GapIssueDisabledException;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GitHubIssueClient;
import com.sjherp.domain.gap.GitHubIssueClient.IssueRequest;
import com.sjherp.infra.github.RestGitHubIssueClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

class GapIssueConfigTest {

    @Test
    void missingCredentialsUseDisabledClientInSpringContext() {
        gapIssueRunner().run(context -> {
            assertThat(context).hasNotFailed();
            GitHubIssueClient client = context.getBean(GitHubIssueClient.class);

            assertThatThrownBy(() -> client.create(new IssueRequest("title", java.util.List.of(), "body")))
                    .isInstanceOf(GapIssueDisabledException.class);
        });
    }

    @Test
    void completeCredentialsUseRestAdapterInSpringContext() {
        gapIssueRunner()
                .withPropertyValues(
                        "sjherp.github.issue.repo=acme/demo",
                        "sjherp.github.issue.token=test-token",
                        "sjherp.github.issue.api-base=https://github.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GitHubIssueClient.class)).isInstanceOf(RestGitHubIssueClient.class);
                });
    }

    @Test
    void schedulerExistsOnlyWhenBothExternalWriteAndAutoRunAreEnabled() {
        schedulerRunner(false, false).run(context -> assertThat(context).doesNotHaveBean(GapIssueScheduledPublisher.class));
        schedulerRunner(false, true).run(context -> assertThat(context).doesNotHaveBean(GapIssueScheduledPublisher.class));
        schedulerRunner(true, false).run(context -> assertThat(context).doesNotHaveBean(GapIssueScheduledPublisher.class));
        schedulerRunner(true, true).run(context -> assertThat(context).hasSingleBean(GapIssueScheduledPublisher.class));
    }

    private ApplicationContextRunner gapIssueRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(GapIssueConfig.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(GapRecordRepository.class, () -> mock(GapRecordRepository.class))
                .withBean(GapIssueDeliveryFinalizer.class, () -> mock(GapIssueDeliveryFinalizer.class))
                .withBean(GapIssueClusterWriter.class, () -> mock(GapIssueClusterWriter.class));
    }

    private ApplicationContextRunner schedulerRunner(boolean enabled, boolean autoRun) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SchedulerConfiguration.class)
                .withPropertyValues(
                        "sjherp.github.issue.enabled=" + enabled,
                        "sjherp.github.issue.auto-run=" + autoRun);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(GapIssueScheduledPublisher.class)
    static class SchedulerConfiguration {
        @Bean
        GapIssueService gapIssueService() {
            return mock(GapIssueService.class);
        }
    }
}

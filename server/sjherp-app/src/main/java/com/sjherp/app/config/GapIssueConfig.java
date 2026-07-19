package com.sjherp.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;
import com.sjherp.infra.persistence.gap.JdbcGapIssueCandidateRepository;

@Configuration
public class GapIssueConfig {
 @Bean GapIssueCandidateRepository gapIssueCandidateRepository(JdbcTemplate j,ObjectMapper o){return new JdbcGapIssueCandidateRepository(j,o);}
 @Bean GitHubIssueClient gitHubIssueClient(@Value("${sjherp.github.issue.repo:}") String repo,@Value("${sjherp.github.issue.token:}") String token){return new GitHubIssueClient(){public IssueResponse create(IssueRequest request){throw new IllegalStateException("GitHub 客户端未配置：请注入安全凭证和仓库");} public java.util.Optional<IssueResponse> findByTraceMarker(String marker){return java.util.Optional.empty();}};}
 @Bean GapIssueService gapIssueService(GapRecordRepository g,GapIssueCandidateRepository c,GitHubIssueClient h,@Value("${sjherp.github.issue.enabled:false}") boolean enabled){return new GapIssueService(g,c,h,enabled);}
}

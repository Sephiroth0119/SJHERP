package com.sjherp.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;
import com.sjherp.infra.persistence.gap.JdbcGapIssueCandidateRepository;
import com.sjherp.infra.github.RestGitHubIssueClient;
import java.time.Duration;

@Configuration
public class GapIssueConfig {
 @Bean GapIssueCandidateRepository gapIssueCandidateRepository(JdbcTemplate j,ObjectMapper o){return new JdbcGapIssueCandidateRepository(j,o);}
 @Bean GitHubIssueClient gitHubIssueClient(@Value("${sjherp.github.issue.api-base:https://api.github.com}") String base,@Value("${sjherp.github.issue.repo:}") String repo,@Value("${sjherp.github.issue.token:}") String token,@Value("${sjherp.github.issue.timeout-seconds:10}") long seconds,ObjectMapper mapper){
     if(repo.isBlank()||token.isBlank()) return new GitHubIssueClient(){public IssueResponse create(IssueRequest r){throw new IllegalStateException("GitHub 配置缺失，外部写入已安全关闭");} public java.util.Optional<IssueResponse> findByTraceMarker(String m){throw new IllegalStateException("GitHub 配置缺失，外部写入已安全关闭");}};
     return new RestGitHubIssueClient(base,repo,token,Duration.ofSeconds(seconds),mapper);
 }
 @Bean GapIssueService gapIssueService(GapRecordRepository g,GapIssueCandidateRepository c,GitHubIssueClient h,@Value("${sjherp.github.issue.enabled:false}") boolean enabled){return new GapIssueService(g,c,h,enabled);}
}

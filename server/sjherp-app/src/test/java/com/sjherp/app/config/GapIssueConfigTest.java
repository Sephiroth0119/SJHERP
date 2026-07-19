package com.sjherp.app.config;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.infra.github.RestGitHubIssueClient;
class GapIssueConfigTest {
 @Test void 缺少仓库或token时真实客户端拒绝装配(){assertThatThrownBy(()->new RestGitHubIssueClient("http://localhost","","",Duration.ofSeconds(1),new ObjectMapper())).isInstanceOf(IllegalStateException.class);}
}

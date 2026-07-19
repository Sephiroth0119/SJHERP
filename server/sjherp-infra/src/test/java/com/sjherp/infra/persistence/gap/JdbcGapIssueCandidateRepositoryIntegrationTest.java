package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/** V35 真库门禁：容器不可用时由 integration-db profile 执行。 */
class JdbcGapIssueCandidateRepositoryIntegrationTest extends MySqlContainerTestBase {
    @Test void v35迁移与来源表契约由真库启动验证(){
        assertThat(jdbc).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='gap_issue_candidate'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='gap_issue_source'",Integer.class)).isEqualTo(1);
    }
}

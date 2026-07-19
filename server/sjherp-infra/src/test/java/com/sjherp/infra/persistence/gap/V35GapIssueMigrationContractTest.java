package com.sjherp.infra.persistence.gap;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class V35GapIssueMigrationContractTest {
 @Test void migrationContainsAppendOnlySourceAndLeaseFields() throws Exception {
  String sql=new String(getClass().getClassLoader().getResourceAsStream("db/migration/V35__gap_issue_delivery.sql").readAllBytes(),StandardCharsets.UTF_8);
  assertThat(sql).contains("sending_started_at","reviewed_by","attempt_count","gap_issue_source","FOREIGN KEY (tenant_id, candidate_id)","FOREIGN KEY (tenant_id, gap_no)");
 }
}

package com.sjherp.infra.persistence.gap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class JdbcDeveloperAgentTaskRepository implements DeveloperAgentTaskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcDeveloperAgentTaskRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public DeveloperAgentTask createIfAbsent(DeveloperAgentTask task, String operator) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO developer_agent_task(tenant_id,candidate_id,idempotency_key,status,branch_name,workspace_path,runner_kind,attempt_count,ci_green,human_approved,generated_artifacts,targeted_tests_green,full_tests_green,ci_evidence,runner_output_summary,created_by,created_at,updated_at) VALUES(0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",
                task.candidateId(), task.idempotencyKey(), task.status().name(), task.branchName(), task.workspacePath(), task.runnerKind(), task.attemptCount(), task.ciGreen(), task.humanApproved(), jsonArray(task.generatedArtifacts()), task.targetedTestsGreen(), task.fullTestsGreen(), task.ciEvidence(), null, operator, now, now);
        return findByCandidateId(task.candidateId()).orElseThrow();
    }
    @Override public Optional<DeveloperAgentTask> findById(long id) { return query("id=?", id); }
    @Override public Optional<DeveloperAgentTask> findByCandidateId(long id) { return query("candidate_id=?", id); }
    private Optional<DeveloperAgentTask> query(String where, Object... args) {
        return jdbc.query("SELECT * FROM developer_agent_task WHERE tenant_id=0 AND " + where, (r,n) -> new DeveloperAgentTask(r.getLong("id"),r.getLong("candidate_id"),r.getString("idempotency_key"),DeveloperAgentTaskStatus.valueOf(r.getString("status")),r.getString("branch_name"),r.getString("workspace_path"),r.getString("runner_kind"),r.getString("lease_token"),r.getInt("attempt_count"),parseArtifacts(r.getString("generated_artifacts")),r.getBoolean("targeted_tests_green"),r.getBoolean("full_tests_green"),r.getBoolean("ci_green"),r.getString("ci_evidence"),r.getBoolean("human_approved"),r.getString("failure_type"),r.getString("failure_summary"),r.getString("runner_output_summary")), args).stream().findFirst();
    }
    @Override public Optional<String> claim(long id, Instant now) {
        String token=UUID.randomUUID().toString(); LocalDateTime t=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
        int changed=jdbc.update("UPDATE developer_agent_task SET status='RUNNING',lease_token=?,attempt_count=attempt_count+1,generated_artifacts='[]',targeted_tests_green=false,full_tests_green=false,ci_green=false,ci_evidence=NULL,failure_type=NULL,failure_summary=NULL,runner_output_summary=NULL,updated_at=? WHERE tenant_id=0 AND id=? AND status IN ('QUEUED','FAILED') AND attempt_count<3",token,t,id);
        return changed==1?Optional.of(token):Optional.empty();
    }
    @Override public void transition(long id, DeveloperAgentTaskStatus expected, DeveloperAgentTaskStatus target, String leaseToken, java.util.List<String> artifacts, boolean targeted, boolean full, boolean ciGreen, String ciEvidence, String outputSummary) {
        int changed=jdbc.update("UPDATE developer_agent_task SET status=?,generated_artifacts=?,targeted_tests_green=?,full_tests_green=?,ci_green=?,ci_evidence=?,runner_output_summary=?,lease_token=?,updated_at=? WHERE tenant_id=0 AND id=? AND status=? AND lease_token=?",target.name(),jsonArray(artifacts),targeted,full,ciGreen,ciEvidence,truncate(outputSummary),target==DeveloperAgentTaskStatus.TESTING?leaseToken:null,LocalDateTime.now(ZoneOffset.UTC),id,expected.name(),leaseToken);
        if(changed!=1) throw new IllegalStateException("developer task lease conflict");
    }
    @Override public void markFailed(long id, DeveloperAgentTaskStatus expected, String leaseToken, String failureType, String failureSummary, List<String> artifacts, boolean targeted, boolean full, boolean ci, String ciEvidence, String outputSummary){int c=jdbc.update("UPDATE developer_agent_task SET status='FAILED',generated_artifacts=?,targeted_tests_green=?,full_tests_green=?,ci_green=?,ci_evidence=?,failure_type=?,failure_summary=?,runner_output_summary=?,lease_token=NULL,updated_at=? WHERE tenant_id=0 AND id=? AND status IN ('RUNNING','TESTING') AND status=? AND lease_token=?",jsonArray(artifacts),targeted,full,ci,ciEvidence,failureType,failureSummary,truncate(outputSummary),LocalDateTime.now(ZoneOffset.UTC),id,expected.name(),leaseToken);if(c!=1)throw new IllegalStateException("developer task failure lease conflict");}
    @Override public void approve(long id,String operator){int c=jdbc.update("UPDATE developer_agent_task SET status='APPROVED',human_approved=TRUE,updated_at=? WHERE tenant_id=0 AND id=? AND status='AWAITING_REVIEW' AND JSON_LENGTH(generated_artifacts)>0 AND JSON_SEARCH(generated_artifacts,'one','pending') IS NULL AND targeted_tests_green=TRUE AND full_tests_green=TRUE AND ci_green=TRUE AND ci_evidence IS NOT NULL AND ci_evidence<>''",LocalDateTime.now(ZoneOffset.UTC),id);if(c!=1)throw new IllegalStateException("task is not approvable");}
    @Override public void cancel(long id,String operator){int c=jdbc.update("UPDATE developer_agent_task SET status='CANCELLED',lease_token=NULL,updated_at=? WHERE tenant_id=0 AND id=? AND status NOT IN ('APPROVED','CANCELLED')",LocalDateTime.now(ZoneOffset.UTC),id);if(c!=1)throw new IllegalStateException("task cannot be cancelled");}
    @Override public int reclaimExpired(Instant cutoff){return jdbc.update("UPDATE developer_agent_task SET status='FAILED',lease_token=NULL,failure_type='LEASE_EXPIRED',failure_summary='developer runner lease expired',updated_at=? WHERE tenant_id=0 AND status IN ('RUNNING','TESTING') AND updated_at<?",LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC));}
    private String jsonArray(List<String> values){try{return json.writeValueAsString(values);}catch(Exception e){throw new IllegalStateException("cannot serialize artifacts",e);}}
    private List<String> parseArtifacts(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException("cannot deserialize artifacts",e);}}
    private static String truncate(String value){return value==null?null:(value.length()<=500?value:value.substring(0,500));}
}

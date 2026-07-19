package com.sjherp.infra.persistence.gap;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;

@Transactional
public class JdbcGapIssueCandidateRepository implements GapIssueCandidateRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public JdbcGapIssueCandidateRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public GapIssueCandidate upsert(GapIssueCandidate c){
        String sql="INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,status,created_at,updated_at) VALUES(0,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id";
        jdbc.update(sql,c.idempotencyKey(),c.clusterKey(),c.businessModule().name(),c.severity().name(),c.title(),write(c.scenarioSamples()),c.expectedBehavior(),c.missingCapability(),c.status().name(),LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.now(ZoneOffset.UTC));
        return findByKey(c.idempotencyKey()).orElseThrow(); }
    @Override public void addSources(long candidateId, List<String> gapNos) {
        for (String gapNo : gapNos) {
            jdbc.update("INSERT IGNORE INTO gap_issue_source(tenant_id,candidate_id,gap_no,created_at) VALUES(0,?,?,?)",
                    candidateId, gapNo, LocalDateTime.now(ZoneOffset.UTC));
        }
    }
    private Optional<GapIssueCandidate> findByKey(String key){return jdbc.query("SELECT * FROM gap_issue_candidate WHERE idempotency_key=?",(rs,n)->map(rs),key).stream().findFirst();}
    @Override public List<GapIssueCandidate> findAll(){return jdbc.query("SELECT * FROM gap_issue_candidate ORDER BY id",(rs,n)->map(rs));}
    @Override public Optional<GapIssueCandidate> findById(long id){return jdbc.query("SELECT * FROM gap_issue_candidate WHERE id=?",(rs,n)->map(rs),id).stream().findFirst();}
    @Override public Optional<String> claimForSend(long id){String token=java.util.UUID.randomUUID().toString();int changed=jdbc.update("UPDATE gap_issue_candidate SET status='SENDING',attempt_count=attempt_count+1,sending_started_at=?,lease_token=?,updated_at=? WHERE tenant_id=0 AND id=? AND status IN ('APPROVED','FAILED') AND issue_number IS NULL",LocalDateTime.now(ZoneOffset.UTC),token,LocalDateTime.now(ZoneOffset.UTC),id);return changed==1?Optional.of(token):Optional.empty();}
    @Override public int reclaimExpiredSending(java.time.Instant cutoff){return jdbc.update("UPDATE gap_issue_candidate SET status='FAILED',failure_type='LEASE_EXPIRED',updated_at=? WHERE status='SENDING' AND sending_started_at<?",LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC));}
    @Override public void markApproved(long id,String op){
        int changed=jdbc.update("UPDATE gap_issue_candidate SET status='APPROVED',reviewed_by=?,reviewed_at=?,updated_at=? WHERE id=? AND status IN ('PENDING','FAILED')",op,LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.now(ZoneOffset.UTC),id);
        if(changed!=1) throw new IllegalStateException("候选不允许审核或已被并发处理");
    }
    @Override public void markSent(long id,String token,long no,String url){
        int changed=jdbc.update("UPDATE gap_issue_candidate SET status='SENT',issue_number=?,issue_url=?,failure_type=NULL,sending_started_at=NULL,lease_token=NULL,updated_at=? WHERE tenant_id=0 AND id=? AND status='SENDING' AND lease_token=?",no,url,LocalDateTime.now(ZoneOffset.UTC),id,token);
        if(changed!=1) throw new IllegalStateException("候选不在发送状态");
    }
    @Override public void markFailed(long id,String token,String type){
        int changed=jdbc.update("UPDATE gap_issue_candidate SET status='FAILED',failure_type=?,sending_started_at=NULL,lease_token=NULL,updated_at=? WHERE tenant_id=0 AND id=? AND status='SENDING' AND lease_token=?",type,LocalDateTime.now(ZoneOffset.UTC),id,token);
        if(changed!=1) throw new IllegalStateException("候选不在发送状态");
    }
    private GapIssueCandidate map(java.sql.ResultSet r)throws java.sql.SQLException{return new GapIssueCandidate(r.getLong("id"),r.getString("idempotency_key"),r.getString("cluster_key"),BusinessModule.valueOf(r.getString("business_module")),GapSeverity.valueOf(r.getString("severity")),r.getString("title"),read(r.getString("scenario_samples")),r.getString("expected_behavior"),r.getString("missing_capability"),sourceNos(r.getLong("id"),null),GapIssueStatus.valueOf(r.getString("status")),(Long)r.getObject("issue_number"),r.getString("issue_url"),r.getString("reviewed_by"),instant(r.getTimestamp("reviewed_at")),r.getString("failure_type"),r.getInt("attempt_count"),instant(r.getTimestamp("created_at")),instant(r.getTimestamp("updated_at")),instant(r.getTimestamp("sending_started_at")));}
    private java.time.Instant instant(java.sql.Timestamp value){return value==null?null:value.toInstant();}
    private List<String> sourceNos(long id,String fallback){
        return jdbc.query("SELECT gap_no FROM gap_issue_source WHERE tenant_id=0 AND candidate_id=? ORDER BY gap_no",(rs,n)->rs.getString(1),id);
    }
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> read(String v){try{return json.readValue(v,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
}

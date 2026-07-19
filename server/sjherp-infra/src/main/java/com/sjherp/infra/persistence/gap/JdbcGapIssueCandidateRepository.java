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
        String sql="INSERT INTO gap_issue_candidate(idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,source_gap_nos,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id";
        jdbc.update(sql,c.idempotencyKey(),c.clusterKey(),c.businessModule().name(),c.severity().name(),c.title(),write(c.scenarioSamples()),c.expectedBehavior(),c.missingCapability(),write(c.sourceGapNos()),c.status().name(),LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.now(ZoneOffset.UTC));
        return findByKey(c.idempotencyKey()).orElseThrow(); }
    private Optional<GapIssueCandidate> findByKey(String key){return jdbc.query("SELECT * FROM gap_issue_candidate WHERE idempotency_key=?",(rs,n)->map(rs),key).stream().findFirst();}
    @Override public List<GapIssueCandidate> findAll(){return jdbc.query("SELECT * FROM gap_issue_candidate ORDER BY id",(rs,n)->map(rs));}
    @Override public Optional<GapIssueCandidate> findById(long id){return jdbc.query("SELECT * FROM gap_issue_candidate WHERE id=?",(rs,n)->map(rs),id).stream().findFirst();}
    @Override public boolean claimForSend(long id){return jdbc.update("UPDATE gap_issue_candidate SET status='SENDING',attempt_count=attempt_count+1,updated_at=? WHERE id=? AND status IN ('APPROVED','FAILED') AND issue_number IS NULL",LocalDateTime.now(ZoneOffset.UTC),id)==1;}
    @Override public void markApproved(long id,String op){jdbc.update("UPDATE gap_issue_candidate SET status='APPROVED',reviewed_by=?,reviewed_at=?,updated_at=? WHERE id=?",op,LocalDateTime.now(ZoneOffset.UTC),LocalDateTime.now(ZoneOffset.UTC),id);}
    @Override public void markSent(long id,long no,String url){jdbc.update("UPDATE gap_issue_candidate SET status='SENT',issue_number=?,issue_url=?,failure_type=NULL,updated_at=? WHERE id=?",no,url,LocalDateTime.now(ZoneOffset.UTC),id);}
    @Override public void markFailed(long id,String type){jdbc.update("UPDATE gap_issue_candidate SET status='FAILED',failure_type=?,updated_at=? WHERE id=?",type,LocalDateTime.now(ZoneOffset.UTC),id);}
    private GapIssueCandidate map(java.sql.ResultSet r)throws java.sql.SQLException{return new GapIssueCandidate(r.getLong("id"),r.getString("idempotency_key"),r.getString("cluster_key"),BusinessModule.valueOf(r.getString("business_module")),GapSeverity.valueOf(r.getString("severity")),r.getString("title"),read(r.getString("scenario_samples")),r.getString("expected_behavior"),r.getString("missing_capability"),read(r.getString("source_gap_nos")),GapIssueStatus.valueOf(r.getString("status")),(Long)r.getObject("issue_number"),r.getString("issue_url"));}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> read(String v){try{return json.readValue(v,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
}

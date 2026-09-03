package com.sjherp.infra.persistence.gap;
import com.sjherp.domain.gap.ClosureFeedbackRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
public class JdbcClosureFeedbackRepository implements ClosureFeedbackRepository {
 private final JdbcTemplate jdbc; public JdbcClosureFeedbackRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public boolean claim(long taskId,long candidateId,String ref,String summary,String operator){try { jdbc.update("INSERT INTO closure_feedback(tenant_id,task_id,candidate_id,evidence_reference,evidence_summary,created_by,created_at) VALUES(0,?,?,?,?,?,?)",taskId,candidateId,ref,summary,operator,LocalDateTime.now(ZoneOffset.UTC)); return true; } catch (DuplicateKeyException duplicate) { return false; }}
}

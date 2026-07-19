CREATE TABLE closure_feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    task_id BIGINT UNSIGNED NOT NULL,
    candidate_id BIGINT UNSIGNED NOT NULL,
    evidence_reference VARCHAR(500) NOT NULL,
    evidence_summary VARCHAR(2000) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_closure_feedback_task (tenant_id, task_id),
    CONSTRAINT fk_closure_feedback_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES developer_agent_task(tenant_id, id),
    CONSTRAINT fk_closure_feedback_candidate FOREIGN KEY (tenant_id, candidate_id)
        REFERENCES gap_issue_candidate(tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

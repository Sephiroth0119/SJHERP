CREATE TABLE developer_agent_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    candidate_id BIGINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    branch_name VARCHAR(128) NOT NULL,
    workspace_path VARCHAR(500) NOT NULL,
    runner_kind VARCHAR(16) NOT NULL,
    lease_token VARCHAR(64) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    ci_green BOOLEAN NOT NULL DEFAULT FALSE,
    human_approved BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_developer_task_tenant_idem (tenant_id, idempotency_key),
    UNIQUE KEY uk_developer_task_tenant_candidate (tenant_id, candidate_id),
    CONSTRAINT fk_developer_task_candidate FOREIGN KEY (tenant_id, candidate_id)
        REFERENCES gap_issue_candidate(tenant_id, id),
    KEY idx_developer_task_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

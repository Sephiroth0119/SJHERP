CREATE TABLE gap_issue_candidate (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128) NOT NULL,
    cluster_key VARCHAR(128) NOT NULL,
    business_module VARCHAR(16) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    title VARCHAR(200) NOT NULL,
    scenario_samples JSON NOT NULL,
    expected_behavior VARCHAR(2000) NOT NULL,
    missing_capability VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reviewed_by VARCHAR(64) NULL,
    reviewed_at DATETIME(6) NULL,
    issue_number BIGINT NULL,
    issue_url VARCHAR(500) NULL,
    failure_type VARCHAR(64) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    sending_started_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_gap_issue_tenant_id (tenant_id, id), UNIQUE KEY uk_gap_issue_tenant_idem (tenant_id, idempotency_key),
    KEY idx_gap_issue_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gap_issue_source (
    tenant_id BIGINT NOT NULL DEFAULT 0,
    candidate_id BIGINT UNSIGNED NOT NULL,
    gap_no VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, gap_no),
    KEY idx_gap_issue_source_candidate (tenant_id, candidate_id),
    CONSTRAINT fk_gap_issue_source_candidate FOREIGN KEY (tenant_id, candidate_id)
        REFERENCES gap_issue_candidate(tenant_id, id),
    CONSTRAINT fk_gap_issue_source_gap FOREIGN KEY (tenant_id, gap_no)
        REFERENCES gap_record(tenant_id, gap_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

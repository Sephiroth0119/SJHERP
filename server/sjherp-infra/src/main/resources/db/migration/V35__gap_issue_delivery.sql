CREATE TABLE gap_issue_candidate (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    cluster_key VARCHAR(128) NOT NULL,
    business_module VARCHAR(16) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    title VARCHAR(200) NOT NULL,
    scenario_samples JSON NOT NULL,
    expected_behavior VARCHAR(2000) NOT NULL,
    missing_capability VARCHAR(1000) NOT NULL,
    source_gap_nos JSON NOT NULL,
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
    PRIMARY KEY (id), UNIQUE KEY uk_gap_issue_idem (idempotency_key),
    KEY idx_gap_issue_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gap_issue_source (
    candidate_id BIGINT UNSIGNED NOT NULL,
    gap_no VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (candidate_id, gap_no),
    CONSTRAINT fk_gap_issue_source_candidate FOREIGN KEY (candidate_id)
        REFERENCES gap_issue_candidate(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

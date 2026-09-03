CREATE TABLE consistency_check_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    run_no VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    clean BOOLEAN NOT NULL,
    total_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    warn_count BIGINT NOT NULL,
    info_count BIGINT NOT NULL,
    analysis_status VARCHAR(16) NOT NULL,
    analysis_summary VARCHAR(1000) NULL,
    failure_type VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consistency_check_run_no (tenant_id, run_no),
    UNIQUE KEY uk_consistency_check_run_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE consistency_check_break (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    check_type VARCHAR(64) NOT NULL,
    object_key VARCHAR(256) NULL,
    expected_value DECIMAL(24,6) NULL,
    actual_value DECIMAL(24,6) NULL,
    severity VARCHAR(16) NOT NULL,
    message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consistency_check_break_sequence (tenant_id, run_id, sequence_no),
    CONSTRAINT fk_consistency_check_break_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES consistency_check_run (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE sys_user
    ADD UNIQUE KEY uk_sys_user_tenant_id (tenant_id, id);

CREATE TABLE system_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    category VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(128) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_notification_source
        (tenant_id, recipient_user_id, source_type, source_ref),
    KEY idx_system_notification_inbox (tenant_id, recipient_user_id, read_at, id),
    CONSTRAINT fk_system_notification_recipient FOREIGN KEY (tenant_id, recipient_user_id)
        REFERENCES sys_user (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- M6-T06：审计完整性定时勾稽按租户、目标、动作查找状态链。
ALTER TABLE audit_log
    ADD KEY idx_audit_log_tenant_target_action (tenant_id, target_type, target_code, action);

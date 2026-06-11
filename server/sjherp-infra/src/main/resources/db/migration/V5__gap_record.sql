-- V5：流程缺口记录表（M1-T04，自进化闭环第一环）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 注意：V4 由并行任务（客户/供应商/仓库档案）占用，本迁移固定使用 V5。

CREATE TABLE gap_record (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '缺口记录主键',
    tenant_id          BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    gap_no             VARCHAR(32)     NOT NULL COMMENT '缺口编号（GAP-年月-序号，如 GAP-202606-0001，复用 doc_sequence 编号机制）',
    session_id         VARCHAR(64)     NULL COMMENT '来源会话 id（M6-T10 缺口解决后回写通知原会话的依据）；开发侧手工补录可空',
    title              VARCHAR(200)    NOT NULL COMMENT '缺口一句话标题',
    scenario           VARCHAR(2000)   NOT NULL COMMENT '用户场景（原文或 Agent 复述）',
    expected_behavior  VARCHAR(2000)   NOT NULL COMMENT '用户期望系统做到什么',
    missing_capability VARCHAR(1000)   NOT NULL COMMENT 'Agent 判断当前系统缺失的能力',
    business_module    VARCHAR(16)     NOT NULL COMMENT '所属业务模块：PURCHASE / SALES / INVENTORY / PRODUCTION / FINANCE / GENERAL',
    severity           VARCHAR(8)      NOT NULL COMMENT '严重度：LOW / MEDIUM / HIGH',
    status             VARCHAR(16)     NOT NULL COMMENT '状态：NEW / TRIAGED / IN_DEVELOPMENT / RESOLVED / REJECTED（简单流转，非单据状态机）',
    reporter           VARCHAR(64)     NOT NULL COMMENT '提出人（userId 占位，M2-T05 登录落地后为真实用户）',
    created_by         VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at         DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by         VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at         DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gap_record_tenant_no (tenant_id, gap_no),
    -- 开发侧 triage 列表：按状态/模块筛选 + 最新在前
    KEY idx_gap_record_status (status),
    KEY idx_gap_record_module (business_module),
    KEY idx_gap_record_session (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '流程缺口记录（用户提出系统做不到的需求时由 Agent 结构化落库，不可物理删除）';

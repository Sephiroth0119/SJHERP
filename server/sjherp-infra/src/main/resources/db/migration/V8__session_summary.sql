-- V8：agent_session 增加历史摘要列（M1-T05 会话上下文治理，还技术债 D-2）
-- 长会话发给 LLM 的历史估算 token 超过预算（sjherp.agent.history-token-budget）时，
-- 最旧的若干轮压缩为摘要存入 history_summary，summarized_until_seq 记录摘要覆盖到的
-- 消息 seq（agent_message.seq）。完整历史仍在 agent_message 表，会话回放 API 不受影响，
-- 裁剪只影响发给 LLM 的上下文。

ALTER TABLE agent_session
    ADD COLUMN history_summary TEXT NULL
        COMMENT '历史对话摘要（LLM 生成的要点清单，保留单据号/客户名/金额等业务关键信息）；NULL 表示尚未做过摘要'
        AFTER pending_tool_call,
    ADD COLUMN summarized_until_seq INT NOT NULL DEFAULT 0
        COMMENT '摘要已覆盖到的消息 seq（agent_message.seq）；0 表示未覆盖任何消息'
        AFTER history_summary;

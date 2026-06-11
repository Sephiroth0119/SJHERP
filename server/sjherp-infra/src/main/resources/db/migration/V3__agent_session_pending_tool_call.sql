-- V3：agent_session 增加待确认高风险工具调用列（M1-T03 Human-in-the-loop）
-- Agent 执行循环拦截高风险工具后，把恢复现场（PendingToolCall 序列化 JSON）存入本列；
-- 用户点击确认/取消选项后恢复执行并清空。NULL 表示当前没有待确认调用。
-- 落库保证任意时刻杀进程，确认流程仍可恢复（ADR-001 延伸）。

ALTER TABLE agent_session
    ADD COLUMN pending_tool_call JSON NULL
        COMMENT '待人工确认的高风险工具调用现场（PendingToolCall JSON，infra PendingToolCallJsonCodec 编解码）；NULL 表示无待确认调用'
        AFTER status;

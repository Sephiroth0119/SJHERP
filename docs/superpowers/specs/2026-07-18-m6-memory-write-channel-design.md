# M6-T02 记忆写入通道设计

T02 只负责把缺口解决方案、业务术语/口径和操作偏好转成可审计的结构化记忆候选，并在明确批准后复用 T01 `MemoryService` 写入 `memory_entry`。不在本阶段做召回、提示注入或管理前端。

候选契约包含 `memoryType`、标题、按键值排序的 `facts`、来源类型、可回查的 `sourceRef`、可选 `sessionId` 和 `requiresHumanApproval`。落库 `content` 为确定性 JSON 对象，来源仍写入 T01 的 `source_type/source_ref`；金额和数量由调用方以十进制字符串传递，禁止浮点序列化。

来源边界：GapRecord 使用 `GAP_RECORD + gapNo`，Agent 会话使用 `AGENT_SESSION + sourceRef + sessionId`，用户主动确认使用 `USER_INPUT`，业务文档使用 `BUSINESS_DOCUMENT`。候选生成不写库；只有 `approveAndWrite` 能写入，并统一复用 T01 的版本、幂等索引、事务后索引和 `memory.write_from_candidate` 审计。需要人工确认的候选没有批准人一律拒绝。版本替换、逻辑失效和物理不可删除规则完全沿用 T01。

相同类型、标题、来源和规范化 facts 生成稳定 `write:<uuid>` memoryKey；`MemoryService.createIdempotent` 命中活动 key 时返回原记录，不重复发布索引事件。T02 不直接调用 Qdrant。`write_memory` 声明 HIGH，复用现有 Agent pending tool call 持久化确认现场；聊天召回仍由 T03 接入。

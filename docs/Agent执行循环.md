# Agent 执行循环（AgentLoop）

> 状态：v1.1（2026-06，M1-T02 + M1-T03 + M1-T06 交付）
> 代码：`server/sjherp-agent/src/main/java/com/sjherp/agent/loop/`（框架，零运行时依赖）
> 关联：[CLAUDE.md](../CLAUDE.md)「自研 Agent 框架设计要点」、[选项返回协议.md](./选项返回协议.md)「框架级工具确认选项」

## 1. 职责与边界

`AgentLoop` 是自研 Agent 框架的执行核心：

```
系统提示 + 会话历史 + 工具列表
        │
        ▼
   调 LLM（带 tools）──── 无 toolCalls ──► 终轮收尾 ──► 最终文本 + 工具调用记录
        │
     有 toolCalls
        │
   逐个执行工具（校验→权限→执行）
        │
   结果以 TOOL 消息回灌 ──► 回到调 LLM
```

- 循环只负责**消息流编排**：终轮文本是什么（聊天链路下为选项返回协议 JSON）由上层解析；
- 零依赖约束：LLM 调用（`LlmClient`）、参数 JSON 编解码（`ToolArgumentsCodec`）、参数校验（`ToolArgumentValidator`）、权限校验（`ToolPermissionChecker`）全部经接口注入，sjherp-agent 模块保持纯 Java；
- 入口：`run(AgentLoopRequest)`（从头执行）与 `resume(request, pending, confirmed)`（高风险确认后恢复，见 §4）。

输入 `AgentLoopRequest`：systemPrompt / history / tools / context（审计：谁、哪个会话、什么指令）/ maxIterations / timeout / finalJsonMode。`tools` 为空时行为退化为单轮对话（与未接入工具前一致）。

输出 `AgentLoopResult`（二选一）：

| 形态 | 字段 | 含义 |
|---|---|---|
| 完成 | `finalText` | 模型产出的最终文本 |
| 待确认 | `pendingToolCall` | 高风险工具被拦截，循环中断（见 §4） |

两种形态都带 `toolCallRecords`（每次调用的名称 / 参数 / 结果 / 耗时 / 是否成功），供日志与后续 M1-T06 落 agent_invocation 表。

## 2. 防护机制（框架级，不靠提示词自觉）

| 防护 | 行为 |
|---|---|
| 最大迭代次数 | 默认 8（`AgentLoopRequest.DEFAULT_MAX_ITERATIONS`）。用尽后强制做一次**不带工具**的终轮调用收尾，保证循环必然收敛产出文本 |
| 整体超时预算 | `timeout` 覆盖循环内全部 LLM 调用与工具执行；超出抛 `AgentLoopTimeoutException`，由 app 层兜底致歉文案 |
| 未知工具 | 模型调了未注册工具 → 错误 JSON 以 TOOL 消息回灌，不中断循环 |
| 参数非法 / 校验失败 | arguments 不是合法 JSON、或不满足工具 JSON Schema（required/type/enum）→ 错误回灌，工具不执行 |
| 工具执行异常 | 任何 RuntimeException → `{"success":false,"error":"工具执行异常: ..."}` 回灌，模型自行调整（换参数 / 换工具 / 向用户说明） |
| 权限不足 | `Tool.requiredPermission()` 非空且 `ToolPermissionChecker` 拒绝 → 错误回灌（本期为 `allowAll` 占位，M2-T06 接真实权限） |
| 高风险拦截 | 见 §4，唯一会**中断循环**的防护 |

工具结果回灌格式（TOOL 消息 content）：成功 `{"success":true,"data":{...}}`，失败 `{"success":false,"error":"..."}`（success 恒为首字段）。

## 3. 终轮 JSON 模式（FinalJsonMode）—— DeepSeek 实测结论

聊天链路要求最终文本是协议 JSON（response_format=json_object），但 json_object 与 tools 的兼容性因厂商而异，故做成循环参数：

| 模式 | 行为 | 适用 |
|---|---|---|
| `NONE` | 不要求 JSON | 非聊天链路 |
| `JSON_WITH_TOOLS` | 每次调用同时带 tools + json_object（少一次调用） | 两者兼容的厂商 |
| `JSON_SEPARATE_FINAL_CALL` | 工具轮不带 json_object；模型不再调工具后，终轮**单独再调一次** json_object 且不带 tools（多一次调用，丢弃工具轮的自由文本） | **DeepSeek（默认）** |

**DeepSeek 实测（2026-06，deepseek-chat）**：

1. `response_format=json_object` + `tools` 同时携带**不报 HTTP 错误**，但模型**稳定不发起工具调用**（4/4 复现：直接输出文本 JSON；去掉 json_object 后 1/1 正常返回 tool_calls）→ 必须用 `JSON_SEPARATE_FINAL_CALL`；
2. json_object 要求 prompt 中含 "json" 字样，否则 400（`Prompt must contain the word 'json'...`）——聊天系统提示词已满足。

配置（application.yml）：

```yaml
sjherp:
  agent:
    max-iterations: 8
    loop-timeout-seconds: 300
    final-json-mode: separate-final-call   # 或 with-tools
```

## 4. 高风险拦截与 Human-in-the-loop（M1-T03）

工具声明 `riskLevel() = HIGH`（资金、过账、期间关账等）后，由框架强制拦截：

```
模型发起 HIGH 工具调用（未带确认标记）
  │
  ├─ 同轮中排在它前面的普通调用照常执行
  ▼
循环中断，返回 PendingToolCall（恢复现场）：
  assistantContent + 该轮全部 toolCalls + 已执行结果 + 待确认调用 id + 人类可读摘要
  │
  ▼
app 层（LlmAgent）：
  现场 JSON（infra PendingToolCallJsonCodec）→ 写入 agent_session.pending_tool_call（V3 迁移，杀进程可恢复）
  返回确认卡片：requiresConfirmation=true，options = [__tool_confirm__(risk=high), __tool_cancel__]
  │
  ├─ 用户点「确认执行」→ AgentLoop.resume(confirmed=true)：
  │     重建现场（assistant 工具调用消息 + 已执行结果回灌）→ 带确认标记执行该调用
  │     → 该轮剩余调用继续（门禁仍生效，可能再次拦截）→ 结果回灌 → 循环继续至最终文本
  │
  └─ 用户点「取消」/ 改发任何其他输入 → resume(confirmed=false)：
        待确认调用与剩余调用一律不执行，以「用户已取消该高风险操作」回灌，
        模型向用户致意并继续对话
  两个分支都清空 pending_tool_call
```

要点：

- 拦截发生在**执行前**：HIGH 工具在用户确认前绝不运行，确认标记按 tool_call id 精确匹配（同轮多个 HIGH 调用逐个确认）；
- 确认选项的固定 id 约定（`__tool_confirm__` / `__tool_cancel__`）见 [选项返回协议.md](./选项返回协议.md)「框架级工具确认选项」，常量在 `ToolConfirmation`；
- 现场持久化在会话上（`AgentSession.pendingToolCallJson` ↔ `agent_session.pending_tool_call` JSON 列），重启 / 热部署后确认流程照常恢复（ADR-001 延伸）；
- 工具风险级别（`ToolRiskLevel`，工具属性、框架拦截依据）与协议选项级 `Option.risk`（前端渲染样式标记）是两个概念，刻意分开。

## 5. Tool 安全壳（M1-T03）

`Tool` 接口新增声明（CLAUDE.md「工具即领域服务」的框架落点）：

| 方法 | 默认 | 说明 |
|---|---|---|
| `riskLevel()` | `NORMAL` | `HIGH` → 框架强制人工确认（§4） |
| `requiredPermission()` | `null` | 权限点（如 `purchase:create_order`）。本期只声明接口，循环已接 `ToolPermissionChecker` 调用链路，真实权限模型 M2-T06 接入 |
| `parameterSchema()` | — | JSON Schema 字符串，既提交给 LLM 也作为执行前校验依据 |

参数校验双实现：

- `RequiredFieldsToolArgumentValidator`（sjherp-agent，零依赖朴素实现）：只做顶层 required 字段存在性检查；
- `JsonSchemaToolArgumentValidator`（sjherp-infra，Jackson 手写，不引校验库）：顶层 required / type（string、number、integer、boolean、object、array）/ enum / additionalProperties=false，**运行时默认**。

## 6. app 层接线

- `LlmAgent`（sjherp-app）改为基于 AgentLoop：组装系统提示（有无工具两套能力边界文案）+ 历史 + `ToolRegistry` 全部工具 + 审计上下文；结果转协议回复；待确认转确认卡片；
- `ChatService` 无需感知确认流程：固定选项 id 经正常的 optionId 回传机制还原，由 `LlmAgent` 识别语义；
- 装配（`ChatAgentConfig`）：DeepSeekLlmClient + JacksonToolArgumentsCodec + JsonSchemaToolArgumentValidator + `ToolPermissionChecker.allowAll()`；`ToolRegistry` Bean 默认为空（`ToolConfig`）；
- 业务工具（常驻注册）：基础档案查询/创建 9 个（M2-T08，`DomainToolConfig`）+ `record_process_gap`（M1-T04，`GapInfraConfig`），清单与参数详见 [领域工具清单.md](./领域工具清单.md)；
- 演示工具（仅 dev / local profile 注册，`ToolConfig.DemoToolConfig`）：
  - `echo`（NORMAL）：原样回显，验证普通工具往返；
  - `demo_post_document`（HIGH，权限点 demo:post_document）：模拟单据过账，**不写任何真实数据**，验证完整确认流程。

## 7. 调用观测（M1-T06）

审计原则对 Agent 的延伸：每次 LLM 调用与每次工具调用各落一行 `agent_invocation` 表（V7 迁移），只插入不更新，X-6 成本看板数据源。

```
AgentLoop ──回调──► AgentInvocationListener（sjherp-agent 接口，零依赖）
                         │
                         ▼
        PersistingAgentInvocationListener（infra，同步落库）
                         │
                         ▼
        JdbcAgentInvocationRepository ──► agent_invocation 表
                         ▲
        GET /api/agent/invocations?sessionId=xxx（app，只读查询）
```

回调点与口径：

| 回调 | 时机 | 内容 |
|---|---|---|
| `onLlmCall` | 每次 LLM 调用完成（成功或抛错） | 会话 id、循环内调用序号（1 起，**含终轮单独 JSON 调用与强制收尾调用**）、实际应答模型名、耗时 ms、prompt/completion tokens（`LlmResponse.usage`，DeepSeekLlmClient 解析 OpenAI 兼容 `usage` 字段填充；厂商未返回时为 null）、是否含 toolCalls、错误信息（失败时记录后原样抛出） |
| `onToolCall` | 每次工具调用处理完成 | 会话 id、工具名（未知工具原样上报）、参数 JSON、success、结果摘要（500 字符截断）、耗时 ms（未实际执行为 0）、风险等级（未知工具为 null）、是否经高风险确认（resume 确认链路为 true）。口径与 `ToolCallRecord` 一致：未知工具 / 校验失败 / 执行异常 / 用户取消都上报；**被拦截待确认的调用不上报**（尚未执行，确认或取消后才有结果） |

设计要点：

- listener 为 null 时零开销；AgentLoop 对回调 try-catch 吞掉一切异常，落库实现侧也只记 ERROR 日志——观测失败绝不中断对话（双保险）；
- 当前为**同步落库**（循环线程上单行 INSERT，相对 LLM 调用耗时可忽略）；高并发异步化留 TODO（PersistingAgentInvocationListener）；
- 终轮单独 JSON 调用（`JSON_SEPARATE_FINAL_CALL`）在 AgentLoop 内部发起，无需 app 层手动补记；
- 表结构：tenant_id（ADR-002 预留恒 0）、session_id、type ENUM('LLM','TOOL')、model/tool_name、duration_ms、prompt/completion_tokens、success、detail JSON（LLM：round/hasToolCalls/error；TOOL：arguments/resultSummary/riskLevel/confirmed）、created_at，`(session_id, created_at)` 索引。

查询 API（开发/运营侧）：`GET /api/agent/invocations?sessionId=xxx&page=1&size=20` → 分页列表（created_at 倒序）+ 会话累计 token 汇总（totalPromptTokens / totalCompletionTokens，针对整个会话不随分页变化）；参数缺失/非法 → 400 `{"error"}`。

## 8. 测试

- `AgentLoopTest`（sjherp-agent，假 LlmClient + 假 Tool，16 例）：正常往返 / 业务失败回灌 / 未知工具 / 异常回灌 / 参数非法 / 校验失败 / 权限不足 / 最大迭代强制收尾 / 超时 / 两种 JSON 模式 / 高风险拦截 / 确认恢复 / 取消恢复 / 混合批次部分执行；
- `AgentLoopInvocationListenerTest`（sjherp-agent，假 listener，11 例）：LLM 调用序号与 usage 透传 / 终轮单独调用与强制收尾计入 / LLM 失败上报后原样抛出 / 工具成功失败口径 / 摘要截断 / 拦截不上报、确认与取消上报 / 回调异常不影响主流程；
- `ChatServiceToolConfirmationTest`(sjherp-app)：对话触发 → 确认卡片 + 现场落库 → 确认执行 / 取消 / 待确认期间改发文本视为取消；
- infra：`JacksonToolArgumentsCodecTest` / `JsonSchemaToolArgumentValidatorTest` / `PendingToolCallJsonCodecTest` / `DeepSeekLlmClientTest`（含 usage 与 model 解析）/ `PersistingAgentInvocationListenerTest`（假仓储，字段映射 / detail JSON / 错误截断 / 落库失败兜底）。

## 9. 已知边界与后续

- 工具轮的中间消息（assistant tool_calls 与 TOOL 结果）不落会话历史，只有最终协议回复落库——下一轮对话依赖最终文本携带必要信息；调用明细经 agent_invocation 表可查（§7）；
- 观测落库为同步写入，高并发异步化留 TODO（§7）；
- 权限校验为 allowAll 占位（M2-T06）；
- 恢复执行过程中 LLM 调用失败时，已执行的工具不回滚（现场已清，避免重复执行高风险操作），用户会收到兜底致歉文案。

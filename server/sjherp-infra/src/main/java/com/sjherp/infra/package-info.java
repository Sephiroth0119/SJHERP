/**
 * 基础设施层（骨架阶段为空实现）。
 *
 * <p>规划职责（CLAUDE.md）：
 * <ul>
 *   <li>持久化：MySQL 8.x 仓储实现（实现 domain 与 agent 暴露的仓储接口）；</li>
 *   <li>LLM 抽象层实现：DeepSeek / 通义 / Claude / GPT 等 LlmClient 厂商适配，可配置切换；</li>
 *   <li>向量库客户端：系统大记忆（候选 Qdrant）。</li>
 * </ul>
 *
 * <p>依赖方向：仅依赖 sjherp-domain（以及后续的 sjherp-agent 接口），
 * 不依赖 sjherp-app。
 */
package com.sjherp.infra;

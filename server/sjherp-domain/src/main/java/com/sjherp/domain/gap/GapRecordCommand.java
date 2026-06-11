package com.sjherp.domain.gap;

/**
 * 创建流程缺口记录的命令对象（必填校验在 {@link GapRecord} 构造时执行）。
 *
 * @param sessionId         来源会话 id（Agent 工具落库时携带；开发侧手工补录可空）
 * @param title             缺口一句话标题（必填）
 * @param scenario          用户场景原文或复述（必填）
 * @param expectedBehavior  用户期望系统做到什么（必填）
 * @param missingCapability Agent 判断当前系统缺失的能力（必填）
 * @param businessModule    所属业务模块（必填）
 * @param severity          严重度（必填）
 * @param reporter          提出人（userId 占位，必填）
 */
public record GapRecordCommand(String sessionId, String title, String scenario,
                               String expectedBehavior, String missingCapability,
                               BusinessModule businessModule, GapSeverity severity,
                               String reporter) {
}

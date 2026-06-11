package com.sjherp.agent.reply;

import java.util.Objects;

/**
 * 选项卡片：Agent 给出的一个可点击决策项（选项返回协议 v0.1）。
 *
 * <p>回传机制：用户点击后前端只回传 {@code { "optionId": id }}，
 * 后端凭会话中最近一条回复的 options 按 id 还原该选项的语义与 action。
 *
 * @param id          选项唯一标识（一条回复内不重复），点击后回传给后端
 * @param label       卡片标题（用户可见，中文）
 * @param description 卡片副文案，可为 null（如供应商报价、交期、风险提示）
 * @param risk        选项级风险标记：HIGH 选项前端用醒目样式渲染，且只允许出现在
 *                    requiresConfirmation=true 的回复中（Human-in-the-loop）
 * @param action      点中该选项后后端要执行的动作；可为 null，表示该选项只是
 *                    语义化回答（继续对话，不触发动作）
 */
public record Option(String id, String label, String description, RiskLevel risk, Action action) {

    public Option {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(label, "label 不能为空");
        risk = risk == null ? RiskLevel.NORMAL : risk;
    }

    /** 普通选项（无动作，仅作为语义化回答） */
    public static Option of(String id, String label) {
        return new Option(id, label, null, RiskLevel.NORMAL, null);
    }

    /** 普通选项 + 动作 */
    public static Option of(String id, String label, String description, Action action) {
        return new Option(id, label, description, RiskLevel.NORMAL, action);
    }

    /** 高风险确认选项（资金、过账、期间关账等），必须配合回复级 requiresConfirmation 使用 */
    public static Option highRisk(String id, String label, String description, Action action) {
        return new Option(id, label, description, RiskLevel.HIGH, action);
    }

    /**
     * 选项风险级别。
     *
     * <p>JSON 映射规则（协议 v0.1）：JSON 值 = 枚举名小写（"normal"/"high"），
     * 反序列化时大小写不敏感。
     */
    public enum RiskLevel {
        /** 普通操作 */
        NORMAL,
        /** 高风险操作：必须由人显式点击确认 */
        HIGH;

        /** 协议 JSON 值（小写） */
        public String json() {
            return name().toLowerCase();
        }

        /** 从协议 JSON 值解析（大小写不敏感） */
        public static RiskLevel fromJson(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}

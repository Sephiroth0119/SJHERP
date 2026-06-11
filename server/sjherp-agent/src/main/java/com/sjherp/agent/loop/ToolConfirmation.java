package com.sjherp.agent.loop;

/**
 * 高风险工具确认流程的固定选项 id 约定（docs/选项返回协议.md「框架级工具确认选项」小节）。
 *
 * <p>高风险拦截发生后，上层把 {@link PendingToolCall} 转成 requiresConfirmation=true
 * 的确认回复，其中「确认执行」「取消」两个选项使用以下固定 id；
 * ChatService 凭固定 id 识别确认 / 取消语义并恢复 / 终止待确认调用。
 * 双下划线前后缀表示框架保留 id，业务选项不得使用。
 */
public final class ToolConfirmation {

    /** 「确认执行」选项固定 id（risk=high） */
    public static final String CONFIRM_OPTION_ID = "__tool_confirm__";

    /** 「取消」选项固定 id */
    public static final String CANCEL_OPTION_ID = "__tool_cancel__";

    private ToolConfirmation() {
    }

    /** 是否为框架保留的确认 / 取消选项 id */
    public static boolean isReservedOptionId(String optionId) {
        return CONFIRM_OPTION_ID.equals(optionId) || CANCEL_OPTION_ID.equals(optionId);
    }
}

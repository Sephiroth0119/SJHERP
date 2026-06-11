package com.sjherp.agent.llm;

/**
 * 工具选择策略（厂商无关的统一表示，对应 OpenAI 兼容 API 的 tool_choice）。
 *
 * <ul>
 *   <li>{@link #auto()}：由模型自行决定是否调用工具；</li>
 *   <li>{@link #none()}：禁止调用工具，只生成文本；</li>
 *   <li>{@link #function(String)}：强制调用指定名称的工具。</li>
 * </ul>
 *
 * @param mode         选择模式
 * @param functionName 模式为 {@link Mode#FUNCTION} 时的目标工具名，其余模式为 null
 */
public record ToolChoice(Mode mode, String functionName) {

    /** 选择模式 */
    public enum Mode {
        /** 模型自行决定 */
        AUTO,
        /** 禁止调用工具 */
        NONE,
        /** 强制调用指定工具 */
        FUNCTION
    }

    public ToolChoice {
        if (mode == null) {
            throw new IllegalArgumentException("ToolChoice.mode 不能为空");
        }
        if (mode == Mode.FUNCTION && (functionName == null || functionName.isBlank())) {
            throw new IllegalArgumentException("ToolChoice 指定工具模式必须给出 functionName");
        }
        if (mode != Mode.FUNCTION && functionName != null) {
            throw new IllegalArgumentException("ToolChoice 非指定工具模式不应携带 functionName");
        }
    }

    public static ToolChoice auto() {
        return new ToolChoice(Mode.AUTO, null);
    }

    public static ToolChoice none() {
        return new ToolChoice(Mode.NONE, null);
    }

    public static ToolChoice function(String functionName) {
        return new ToolChoice(Mode.FUNCTION, functionName);
    }
}

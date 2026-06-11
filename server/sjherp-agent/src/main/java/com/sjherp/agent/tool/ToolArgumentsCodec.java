package com.sjherp.agent.tool;

import java.util.Map;

/**
 * 工具调用参数 / 结果的 JSON 编解码接口。
 *
 * <p>sjherp-agent 模块保持零运行时依赖、不引 JSON 库，因此只定义接口：
 * <ul>
 *   <li>{@link #parse}：模型给出的 arguments JSON 字符串 → 键值对（喂给 {@link Tool#execute}）；</li>
 *   <li>{@link #serialize}：工具执行结果 → JSON 文本（以 TOOL 消息回灌给模型）。</li>
 * </ul>
 * 基于 Jackson 的实现放在 sjherp-infra（JacksonToolArgumentsCodec），由 app 层装配注入。
 */
public interface ToolArgumentsCodec {

    /**
     * 解析模型给出的调用参数 JSON。
     *
     * @param argumentsJson 原始 JSON 字符串；null / 空白视为无参数，返回空 Map
     * @return 键值对（值类型由实现决定：字符串 / 数字 / 布尔 / 嵌套 Map / List）
     * @throws IllegalArgumentException JSON 不合法时抛出（执行循环会把错误回灌给模型）
     */
    Map<String, Object> parse(String argumentsJson);

    /**
     * 把键值对序列化为 JSON 文本（用于工具结果回灌与错误消息构造）。
     */
    String serialize(Map<String, Object> data);
}

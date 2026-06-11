package com.sjherp.agent.loop;

/**
 * 终轮 JSON 输出约束模式（M1-T02）。
 *
 * <p>聊天链路要求循环的最终文本是选项返回协议 JSON（由上层解析，循环只管消息流）。
 * OpenAI 兼容 API 的 response_format=json_object 与 tools 是否能同时携带因厂商而异
 * （DeepSeek 实测为准），故把策略做成循环参数：
 */
public enum FinalJsonMode {

    /** 不要求 JSON（循环输出自由文本） */
    NONE,

    /** 每次调用同时携带 tools 与 response_format=json_object（厂商兼容时的最优路径，少一次调用） */
    JSON_WITH_TOOLS,

    /**
     * 工具轮不带 response_format；模型不再发起工具调用时，终轮单独再调一次
     * json_object 且不带 tools（兼容 tools 与 json_object 互斥的厂商，多一次调用）。
     */
    JSON_SEPARATE_FINAL_CALL
}

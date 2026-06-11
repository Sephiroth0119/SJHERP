package com.sjherp.app.tool;

import java.util.Map;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;

/**
 * 演示工具：原样回显消息（NORMAL 风险，循环内直接执行）。
 *
 * <p>仅在 dev / local profile 注册（{@code DemoToolConfig}），用于验证
 * M1-T02 执行循环的完整工具往返链路（模型发起调用 → 框架执行 → 结果回灌），
 * 不触碰任何业务数据。
 */
public class EchoTool implements Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "回显工具（演示用）：原样返回传入的 message 文本。当用户明确要求测试工具链路或回显内容时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{"message":{"type":"string","description":"要回显的文本"}},\
                "required":["message"]}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Object message = arguments.get("message");
        return ToolResult.ok(Map.of("echo", String.valueOf(message)));
    }
}

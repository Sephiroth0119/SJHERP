package com.sjherp.infra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * JacksonToolArgumentsCodec 单元测试（M1-T02 参数编解码）。
 */
class JacksonToolArgumentsCodecTest {

    private final JacksonToolArgumentsCodec codec = new JacksonToolArgumentsCodec();

    @Test
    void parsesFlatAndNestedArguments() {
        Map<String, Object> parsed = codec.parse(
                "{\"name\":\"不锈钢板\",\"qty\":\"500\",\"nested\":{\"a\":1},\"list\":[1,2]}");
        assertThat(parsed)
                .containsEntry("name", "不锈钢板")
                .containsEntry("qty", "500"); // 数量按协议是字符串，原样保留
        assertThat(parsed.get("nested")).isInstanceOf(Map.class);
        assertThat(parsed.get("list")).isInstanceOf(List.class);
    }

    @Test
    void nullOrBlankMeansNoArguments() {
        assertThat(codec.parse(null)).isEmpty();
        assertThat(codec.parse("  ")).isEmpty();
        assertThat(codec.parse("{}")).isEmpty();
    }

    @Test
    void invalidJsonThrowsIllegalArgument() {
        assertThatThrownBy(() -> codec.parse("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
        // 非对象（数组）也视为非法参数
        assertThatThrownBy(() -> codec.parse("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serializePreservesInsertionOrderForSuccessPrefix() {
        // AgentLoop 依赖 {"success":true 前缀判定成功——success 必须是首字段
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", Map.of("k", "v"));
        assertThat(codec.serialize(payload)).startsWith("{\"success\":true");
    }

    @Test
    void serializeNullAsEmptyObject() {
        assertThat(codec.serialize(null)).isEqualTo("{}");
    }

    @Test
    void roundTrip() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", "金额必须为字符串");
        String json = codec.serialize(payload);
        assertThat(codec.parse(json))
                .containsEntry("success", false)
                .containsEntry("error", "金额必须为字符串");
    }
}

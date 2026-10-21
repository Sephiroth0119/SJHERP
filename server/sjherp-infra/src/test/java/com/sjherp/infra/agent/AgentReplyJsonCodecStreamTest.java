package com.sjherp.infra.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.AgentStreamEvent;
import com.sjherp.agent.reply.Option;

class AgentReplyJsonCodecStreamTest {

    private final AgentReplyJsonCodec codec = new AgentReplyJsonCodec();

    @Test
    void 完整v01回复仍可解析() {
        AgentReply reply = codec.fromJson(
                "{\"version\":\"0.1\",\"text\":\"历史回复\",\"options\":[{\"id\":\"opt-1\",\"label\":\"查询\"}]}");
        assertThat(reply.version()).isEqualTo("0.1");
        assertThat(reply.text()).isEqualTo("历史回复");
        assertThat(reply.options()).hasSize(1);
    }

    @Test
    void 流式分片往返并可还原完整回复() {
        AgentReply reply = AgentReply.confirmation("请确认过账", List.of(
                Option.highRisk("__tool_confirm__", "确认执行", "高风险", null),
                Option.of("__tool_cancel__", "取消")));

        String status = codec.toStreamJson(AgentStreamEvent.status("thinking"));
        String delta1 = codec.toStreamJson(AgentStreamEvent.delta("请确认"));
        String delta2 = codec.toStreamJson(AgentStreamEvent.delta("过账"));
        String complete = codec.toStreamJson(AgentStreamEvent.complete(reply));

        AgentStreamEvent statusEvent = codec.fromStreamJson(status);
        AgentStreamEvent d1 = codec.fromStreamJson(delta1);
        AgentStreamEvent d2 = codec.fromStreamJson(delta2);
        AgentStreamEvent done = codec.fromStreamJson(complete);

        assertThat(statusEvent.type()).isEqualTo(AgentStreamEvent.Type.STATUS);
        assertThat(d1.text() + d2.text()).isEqualTo(reply.text());
        assertThat(done.type()).isEqualTo(AgentStreamEvent.Type.COMPLETE);
        assertThat(done.reply().requiresConfirmation()).isTrue();
        assertThat(done.reply().options()).extracting(Option::id)
                .containsExactly("__tool_confirm__", "__tool_cancel__");
        assertThat(complete).doesNotContain("0.1\"");
        assertThat(codec.toJson(reply)).contains("\"version\":\"0.2\"");
    }

    @Test
    void 错误事件携带后端原文() {
        AgentStreamEvent event = codec.fromStreamJson(
                "{\"version\":\"0.2\",\"type\":\"error\",\"error\":\"选项不存在或已过期: opt-x\"}");
        assertThat(event.type()).isEqualTo(AgentStreamEvent.Type.ERROR);
        assertThat(event.error()).isEqualTo("选项不存在或已过期: opt-x");
    }
}

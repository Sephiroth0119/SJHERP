package com.sjherp.infra.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.DocumentRef;

class AgentReplyJsonCodecDocumentTest {

    private final AgentReplyJsonCodec codec = new AgentReplyJsonCodec();

    @Test
    void 空documents不输出以保持旧客户端兼容() {
        String json = codec.toJson(AgentReply.text("你好"));
        assertThat(json).doesNotContain("documents");
        assertThat(json).contains("\"version\":\"0.2\"");
        assertThat(json).contains("\"text\":\"你好\"");
    }

    @Test
    void 单据引用往返且旧JSON缺字段可反序列化() {
        AgentReply reply = AgentReply.text("已创建采购订单草稿")
                .withDocuments(List.of(DocumentRef.of("PURCHASE_ORDER", "PO-202607-0001", "采购订单")));
        AgentReply restored = codec.fromJson(codec.toJson(reply));
        assertThat(restored.documents()).containsExactly(
                DocumentRef.of("PURCHASE_ORDER", "PO-202607-0001", "采购订单"));

        AgentReply legacy = codec.fromJson("{\"version\":\"0.1\",\"text\":\"旧回复\",\"requiresConfirmation\":false}");
        assertThat(legacy.documents()).isEmpty();
        assertThat(legacy.text()).isEqualTo("旧回复");
    }
}

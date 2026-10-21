package com.sjherp.agent.reply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentReplyTextExtractorTest {

    @Test
    void 完整JSON一次喂入抽出text() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        String delta = extractor.feed("{\"version\":\"0.2\",\"text\":\"你好世界\",\"options\":[]}");
        assertEquals("你好世界", delta);
        assertEquals("你好世界", extractor.extractedText());
    }

    @Test
    void 分片喂入按到达顺序拼出正文() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        StringBuilder newly = new StringBuilder();
        for (String chunk : new String[] {
                "{\"ver", "sion\":\"0.2\",", "\"text\":\"物", "料 **M-0042**", " 需补货\"",
                ",\"requiresConfirmation\":true,\"options\":[{}]}"
        }) {
            newly.append(extractor.feed(chunk));
        }
        assertEquals("物料 **M-0042** 需补货", newly.toString());
        assertEquals("物料 **M-0042** 需补货", extractor.extractedText());
    }

    @Test
    void 转义引号与换行按JSON规则还原() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        extractor.feed("{\"text\":\"他说\\\"好\\\"\\n下一行\"}");
        assertEquals("他说\"好\"\n下一行", extractor.extractedText());
    }

    @Test
    void unicode转义还原汉字() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        extractor.feed("{\"text\":\"\\u4f60\\u597d\"}");
        assertEquals("你好", extractor.extractedText());
    }

    @Test
    void markdown代码块包裹仍能抽出() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        extractor.feed("```json\n{\"version\":\"0.2\",\"text\":\"开场白\"}\n```");
        assertEquals("开场白", extractor.extractedText());
    }

    @Test
    void 非JSON不吐增量() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        assertEquals("", extractor.feed("库存还有 3 件，需要补货吗？"));
        assertEquals("", extractor.extractedText());
    }

    @Test
    void options在text之前仍能抽出() {
        AgentReplyTextExtractor extractor = new AgentReplyTextExtractor();
        extractor.feed("{\"options\":[{\"id\":\"a\",\"label\":\"x\"}],\"text\":\"后置正文\"}");
        assertEquals("后置正文", extractor.extractedText());
    }
}

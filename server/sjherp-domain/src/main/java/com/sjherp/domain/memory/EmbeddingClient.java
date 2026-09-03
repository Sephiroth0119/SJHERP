package com.sjherp.domain.memory;

/** 文本嵌入客户端端口，具体本地模型实现在基础设施层。 */
public interface EmbeddingClient {

    EmbeddingVector embed(String text, EmbeddingPurpose purpose);
}

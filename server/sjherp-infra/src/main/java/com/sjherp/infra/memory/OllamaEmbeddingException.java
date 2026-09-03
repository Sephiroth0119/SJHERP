package com.sjherp.infra.memory;

/** 本地 Ollama 嵌入请求失败。 */
public final class OllamaEmbeddingException extends RuntimeException {

    public OllamaEmbeddingException(String message) {
        super(message);
    }

    public OllamaEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}

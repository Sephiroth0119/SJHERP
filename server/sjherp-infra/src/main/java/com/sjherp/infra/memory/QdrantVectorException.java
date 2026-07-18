package com.sjherp.infra.memory;

/** 本地 Qdrant 向量索引请求失败。 */
public final class QdrantVectorException extends RuntimeException {

    public QdrantVectorException(String message) {
        super(message);
    }

    public QdrantVectorException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.sjherp.app.memory;

/** 为单次 Agent 请求提供可选的只读企业记忆上下文。 */
@FunctionalInterface
public interface MemoryContextProvider {

    String contextFor(String queryText);

    static MemoryContextProvider none() {
        return queryText -> "";
    }
}

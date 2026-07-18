package com.sjherp.app.memory;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 组合语义召回与提示格式化，并在基础设施异常时安全降级。 */
public class SemanticMemoryContextProvider implements MemoryContextProvider {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryContextProvider.class);

    private final MemoryRecallService recallService;
    private final MemoryPromptFormatter formatter;

    public SemanticMemoryContextProvider(MemoryRecallService recallService,
            MemoryPromptFormatter formatter) {
        this.recallService = Objects.requireNonNull(recallService, "记忆召回服务不能为空");
        this.formatter = Objects.requireNonNull(formatter, "记忆提示格式化器不能为空");
    }

    @Override
    public String contextFor(String queryText) {
        try {
            return formatter.format(recallService.recall(queryText));
        } catch (RuntimeException exception) {
            log.warn("企业记忆召回降级: errorType={}", exception.getClass().getSimpleName());
            return "";
        }
    }
}

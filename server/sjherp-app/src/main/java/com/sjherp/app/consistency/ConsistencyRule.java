package com.sjherp.app.consistency;

import java.util.List;

import com.sjherp.domain.consistency.ConsistencyCheckRun;

public interface ConsistencyRule {

    enum Kind { SQL_ASSERTION, LLM_ANALYSIS }

    record Context(long tenantId, String runNo, ConsistencyCheckRun.TriggerType triggerType,
                   String requestedBy) {}

    record Result(List<ConsistencyBreak> breaks, String analysisSummary) {
        public Result {
            breaks = breaks == null ? List.of() : List.copyOf(breaks);
        }

        public static Result deterministic(List<ConsistencyBreak> breaks) {
            return new Result(breaks, null);
        }

        public static Result analysis(String summary) {
            return new Result(List.of(), summary);
        }
    }

    String code();

    int order();

    Kind kind();

    Result evaluate(Context context);
}

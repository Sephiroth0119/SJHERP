package com.sjherp.app.consistency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.sjherp.app.consistency.ConsistencyRule.Kind;

public final class ConsistencyRuleRegistry {

    private final List<ConsistencyRule> sqlRules;
    private final List<ConsistencyRule> llmRules;

    public ConsistencyRuleRegistry(List<ConsistencyRule> rules) {
        Objects.requireNonNull(rules, "rules 不能为空");
        Set<String> codes = new HashSet<>();
        List<ConsistencyRule> sortedRules = new ArrayList<>(rules.size());
        for (ConsistencyRule rule : rules) {
            Objects.requireNonNull(rule, "rule 不能为空");
            String code = rule.code();
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("rule code 不能为空");
            }
            if (!codes.add(code)) {
                throw new IllegalStateException("duplicate rule code: " + code);
            }
            Objects.requireNonNull(rule.kind(), "rule kind 不能为空: " + code);
            sortedRules.add(rule);
        }
        sortedRules.sort(Comparator.comparingInt(ConsistencyRule::order)
                .thenComparing(ConsistencyRule::code));
        this.sqlRules = immutableRulesOfKind(sortedRules, Kind.SQL_ASSERTION);
        this.llmRules = immutableRulesOfKind(sortedRules, Kind.LLM_ANALYSIS);
    }

    public List<ConsistencyRule> sqlRules() {
        return sqlRules;
    }

    public List<ConsistencyRule> llmRules() {
        return llmRules;
    }

    private static List<ConsistencyRule> immutableRulesOfKind(List<ConsistencyRule> rules, Kind kind) {
        return rules.stream()
                .filter(rule -> rule.kind() == kind)
                .collect(Collectors.toUnmodifiableList());
    }
}

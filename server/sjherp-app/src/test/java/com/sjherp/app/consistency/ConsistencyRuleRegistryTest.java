package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.app.consistency.ConsistencyRule.Kind;

class ConsistencyRuleRegistryTest {

    @Test
    void ordersByOrderThenCodeAndSeparatesKinds() {
        ConsistencyRuleRegistry registry = new ConsistencyRuleRegistry(List.of(
                fake("LLM_B", 20, Kind.LLM_ANALYSIS), fake("SQL_B", 10, Kind.SQL_ASSERTION),
                fake("SQL_A", 10, Kind.SQL_ASSERTION)));

        assertThat(registry.sqlRules()).extracting(ConsistencyRule::code)
                .containsExactly("SQL_A", "SQL_B");
        assertThat(registry.llmRules()).extracting(ConsistencyRule::code)
                .containsExactly("LLM_B");
    }

    @Test
    void rejectsBlankRuleCodes() {
        assertThatThrownBy(() -> new ConsistencyRuleRegistry(List.of(
                fake("  ", 1, Kind.SQL_ASSERTION))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateRuleCodes() {
        assertThatThrownBy(() -> new ConsistencyRuleRegistry(List.of(
                fake("DUP", 1, Kind.SQL_ASSERTION), fake("DUP", 2, Kind.LLM_ANALYSIS))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DUP");
    }

    @Test
    void returnsImmutableRuleLists() {
        ConsistencyRuleRegistry registry = new ConsistencyRuleRegistry(List.of(
                fake("SQL", 1, Kind.SQL_ASSERTION)));

        assertThatThrownBy(() -> registry.sqlRules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.llmRules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ConsistencyRule fake(String code, int order, Kind kind) {
        return new ConsistencyRule() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Kind kind() {
                return kind;
            }

            @Override
            public Result evaluate(Context context) {
                return Result.deterministic(List.of());
            }
        };
    }
}

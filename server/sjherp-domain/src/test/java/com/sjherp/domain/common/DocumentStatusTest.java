package com.sjherp.domain.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单据状态机流转表测试：合法流转全覆盖 + 非法流转全量校验（穷举所有状态对）。
 */
class DocumentStatusTest {

    /** 期望的合法流转表（与领域文档保持一致的单一真源断言） */
    private static final Map<DocumentStatus, Set<DocumentStatus>> EXPECTED = Map.of(
            DocumentStatus.DRAFT, Set.of(DocumentStatus.APPROVED, DocumentStatus.CANCELLED),
            DocumentStatus.APPROVED, Set.of(DocumentStatus.EXECUTING, DocumentStatus.REVERSED),
            DocumentStatus.EXECUTING, Set.of(DocumentStatus.COMPLETED, DocumentStatus.REVERSED),
            DocumentStatus.COMPLETED, Set.of(DocumentStatus.REVERSED),
            DocumentStatus.REVERSED, Set.of(),
            DocumentStatus.CANCELLED, Set.of()
    );

    @Test
    @DisplayName("合法流转表全覆盖：穷举所有状态对，canTransitionTo 与期望表完全一致")
    void transitionTableMatchesExpectedExactly() {
        for (DocumentStatus from : DocumentStatus.values()) {
            for (DocumentStatus to : DocumentStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertEquals(expected, from.canTransitionTo(to),
                        "流转 " + from + " -> " + to + " 应为 " + (expected ? "合法" : "非法"));
            }
        }
    }

    @Test
    @DisplayName("allowedTargets 返回与流转表一致的目标集合")
    void allowedTargetsMatchesTable() {
        for (DocumentStatus from : DocumentStatus.values()) {
            assertEquals(EXPECTED.get(from), from.allowedTargets(), from + " 的允许目标集合");
        }
    }

    @Test
    @DisplayName("REVERSED 与 CANCELLED 是仅有的终态")
    void onlyReversedAndCancelledAreTerminal() {
        Set<DocumentStatus> terminals = EnumSet.noneOf(DocumentStatus.class);
        for (DocumentStatus s : DocumentStatus.values()) {
            if (s.isTerminal()) {
                terminals.add(s);
            }
        }
        assertEquals(EnumSet.of(DocumentStatus.REVERSED, DocumentStatus.CANCELLED), terminals);
    }

    @Test
    @DisplayName("非法流转抽样：不可跳级、不可回退、终态不可流出")
    void illegalTransitionSamples() {
        // 不可跳级
        assertFalse(DocumentStatus.DRAFT.canTransitionTo(DocumentStatus.EXECUTING));
        assertFalse(DocumentStatus.DRAFT.canTransitionTo(DocumentStatus.COMPLETED));
        assertFalse(DocumentStatus.DRAFT.canTransitionTo(DocumentStatus.REVERSED));
        assertFalse(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.COMPLETED));
        // 不可回退
        assertFalse(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.DRAFT));
        assertFalse(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.EXECUTING));
        // 草稿之外不可作废（已审核单据只能走冲销）
        assertFalse(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.CANCELLED));
        assertFalse(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.CANCELLED));
        // 终态不可流出
        assertFalse(DocumentStatus.REVERSED.canTransitionTo(DocumentStatus.DRAFT));
        assertFalse(DocumentStatus.CANCELLED.canTransitionTo(DocumentStatus.APPROVED));
    }

    @Test
    @DisplayName("已完成单据只可冲销（CLAUDE.md 原则 2）")
    void completedCanOnlyBeReversed() {
        assertEquals(Set.of(DocumentStatus.REVERSED), DocumentStatus.COMPLETED.allowedTargets());
        assertTrue(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.REVERSED));
    }
}

package com.sjherp.domain.gap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 缺口状态流转表测试（单一真源在 GapStatus，全路径覆盖）。
 */
class GapStatusTest {

    @Test
    void NEW_允许流转到_TRIAGED_和_REJECTED() {
        assertTrue(GapStatus.NEW.canTransitionTo(GapStatus.TRIAGED));
        assertTrue(GapStatus.NEW.canTransitionTo(GapStatus.REJECTED));
        assertFalse(GapStatus.NEW.canTransitionTo(GapStatus.IN_DEVELOPMENT));
        assertFalse(GapStatus.NEW.canTransitionTo(GapStatus.RESOLVED));
        assertFalse(GapStatus.NEW.canTransitionTo(GapStatus.NEW));
    }

    @Test
    void TRIAGED_允许流转到_IN_DEVELOPMENT_和_REJECTED() {
        assertTrue(GapStatus.TRIAGED.canTransitionTo(GapStatus.IN_DEVELOPMENT));
        assertTrue(GapStatus.TRIAGED.canTransitionTo(GapStatus.REJECTED));
        assertFalse(GapStatus.TRIAGED.canTransitionTo(GapStatus.RESOLVED));
        assertFalse(GapStatus.TRIAGED.canTransitionTo(GapStatus.NEW));
    }

    @Test
    void IN_DEVELOPMENT_允许流转到_RESOLVED_和_REJECTED() {
        assertTrue(GapStatus.IN_DEVELOPMENT.canTransitionTo(GapStatus.RESOLVED));
        assertTrue(GapStatus.IN_DEVELOPMENT.canTransitionTo(GapStatus.REJECTED));
        assertFalse(GapStatus.IN_DEVELOPMENT.canTransitionTo(GapStatus.TRIAGED));
        assertFalse(GapStatus.IN_DEVELOPMENT.canTransitionTo(GapStatus.NEW));
    }

    @Test
    void RESOLVED_和_REJECTED_是终态() {
        for (GapStatus target : GapStatus.values()) {
            assertFalse(GapStatus.RESOLVED.canTransitionTo(target), "RESOLVED 不应允许流转到 " + target);
            assertFalse(GapStatus.REJECTED.canTransitionTo(target), "REJECTED 不应允许流转到 " + target);
        }
    }
}

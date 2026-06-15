package com.sjherp.domain.production;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkOrder 状态机白名单测试（M5-T03）。
 *
 * <p>验证允许的状态流转通过；被 beforeTransition 白名单否决的流转
 * 抛出 {@link IllegalStateTransitionException}。
 *
 * <p>重点覆盖：EXECUTING→REVERSED、COMPLETED→REVERSED 被拒
 * （与基类 BusinessDocument 的默认行为不同，WorkOrder 显式收紧）。
 */
class WorkOrderStateTest {

    private WorkOrder draftWo;

    @BeforeEach
    void setUp() {
        draftWo = WorkOrder.create(
                "WO-STATE-0001", 1L, new BigDecimal("10"), 1L,
                null, null, null, null, null, null, "tester");
    }

    // ================================================================ 允许的状态流转

    @Test
    void DRAFT_下达_变为APPROVED() {
        draftWo.release("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void DRAFT_作废_变为CANCELLED() {
        draftWo.cancel("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void APPROVED_开工_变为EXECUTING() {
        draftWo.release("tester");
        draftWo.start("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.EXECUTING);
    }

    @Test
    void EXECUTING_完工_变为COMPLETED() {
        draftWo.release("tester");
        draftWo.start("tester");
        draftWo.complete("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void APPROVED_冲销_变为REVERSED() {
        draftWo.release("tester");
        draftWo.reverse("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.REVERSED);
    }

    // ================================================================ 被否决的状态流转（核心白名单守卫）

    @Test
    void EXECUTING_冲销_被拒抛IllegalStateTransitionException() {
        // 先下达再开工
        draftWo.release("tester");
        draftWo.start("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.EXECUTING);

        // EXECUTING→REVERSED 不在白名单，应被 beforeTransition 否决
        assertThatThrownBy(() -> draftWo.reverse("tester"))
                .isInstanceOf(IllegalStateTransitionException.class);

        // 状态不应发生变化
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.EXECUTING);
    }

    @Test
    void COMPLETED_冲销_被拒抛IllegalStateTransitionException() {
        // 走完整链路到 COMPLETED
        draftWo.release("tester");
        draftWo.start("tester");
        draftWo.complete("tester");
        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        // COMPLETED→REVERSED 不在白名单，应被否决
        assertThatThrownBy(() -> draftWo.reverse("tester"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void CANCELLED_不可开工_被拒抛IllegalStateTransitionException() {
        draftWo.cancel("tester");

        assertThatThrownBy(() -> draftWo.release("tester"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void DRAFT_不可直接开工_被拒抛IllegalStateTransitionException() {
        // DRAFT→EXECUTING 不在白名单（必须先 release）
        assertThatThrownBy(() -> draftWo.start("tester"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void APPROVED_不可直接完工_被拒抛IllegalStateTransitionException() {
        // APPROVED→COMPLETED 不在白名单（必须先 start）
        draftWo.release("tester");

        assertThatThrownBy(() -> draftWo.complete("tester"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(draftWo.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }
}

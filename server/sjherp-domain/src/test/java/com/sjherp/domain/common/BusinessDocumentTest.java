package com.sjherp.domain.common;

import com.sjherp.domain.common.event.DocumentStatusChangedEvent;
import com.sjherp.domain.common.event.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务单据基类测试：状态机全路径、钩子、冲销语义（原单不可变）、事件发布。
 */
class BusinessDocumentTest {

    private static final String OPERATOR = "user:alice";

    /** 测试用单据子类：带业务金额字段 + 钩子调用记录 */
    static class SampleOrder extends BusinessDocument {
        private final Money amount;
        final List<String> hookCalls = new ArrayList<>();

        SampleOrder(String docNo, String createdBy, Money amount) {
            super(docNo, createdBy);
            this.amount = amount;
        }

        Money getAmount() {
            return amount;
        }

        /** 暴露受保护方法供测试红字单创建语义 */
        void markReversal(String originalDocNo) {
            markAsReversalOf(originalDocNo);
        }

        @Override
        protected void beforeTransition(DocumentStatus from, DocumentStatus to, String operator) {
            hookCalls.add("before:" + from + "->" + to);
        }

        @Override
        protected void afterTransition(DocumentStatus from, DocumentStatus to, String operator) {
            hookCalls.add("after:" + from + "->" + to);
        }
    }

    /** beforeTransition 否决流转的测试子类 */
    static class VetoOrder extends BusinessDocument {
        VetoOrder(String docNo) {
            super(docNo, OPERATOR);
        }

        @Override
        protected void beforeTransition(DocumentStatus from, DocumentStatus to, String operator) {
            throw new IllegalArgumentException("明细为空，禁止审核");
        }
    }

    private SampleOrder newOrder() {
        return new SampleOrder("PO-202606-0001", OPERATOR, Money.of("100.00"));
    }

    // ---------------------------------------------------------------
    // 合法流转全路径
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("合法流转全路径")
    class LegalPaths {

        @Test
        @DisplayName("主干路径：草稿→审核→执行→完成")
        void mainPath() {
            SampleOrder doc = newOrder();
            assertEquals(DocumentStatus.DRAFT, doc.getStatus());
            doc.approve(OPERATOR);
            assertEquals(DocumentStatus.APPROVED, doc.getStatus());
            doc.startExecution(OPERATOR);
            assertEquals(DocumentStatus.EXECUTING, doc.getStatus());
            doc.complete(OPERATOR);
            assertEquals(DocumentStatus.COMPLETED, doc.getStatus());
        }

        @Test
        @DisplayName("已完成→冲销")
        void completedToReversed() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            doc.startExecution(OPERATOR);
            doc.complete(OPERATOR);
            doc.reverse(OPERATOR, "PO-202606-0002");
            assertEquals(DocumentStatus.REVERSED, doc.getStatus());
        }

        @Test
        @DisplayName("已审核→冲销")
        void approvedToReversed() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            doc.reverse(OPERATOR, "PO-202606-0002");
            assertEquals(DocumentStatus.REVERSED, doc.getStatus());
        }

        @Test
        @DisplayName("执行中→冲销")
        void executingToReversed() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            doc.startExecution(OPERATOR);
            doc.reverse(OPERATOR, "PO-202606-0002");
            assertEquals(DocumentStatus.REVERSED, doc.getStatus());
        }

        @Test
        @DisplayName("草稿→作废")
        void draftToCancelled() {
            SampleOrder doc = newOrder();
            doc.cancel(OPERATOR);
            assertEquals(DocumentStatus.CANCELLED, doc.getStatus());
        }

        @Test
        @DisplayName("流转更新审计字段：操作人与时间")
        void transitionUpdatesAuditFields() {
            SampleOrder doc = newOrder();
            Instant before = doc.getUpdatedAt();
            doc.approve("agent:purchase-bot");
            assertEquals("agent:purchase-bot", doc.getUpdatedBy());
            assertFalse(doc.getUpdatedAt().isBefore(before));
            assertEquals(OPERATOR, doc.getCreatedBy(), "创建人不随流转改变");
        }
    }

    // ---------------------------------------------------------------
    // 非法流转
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("非法流转抛领域异常")
    class IllegalPaths {

        @Test
        @DisplayName("草稿不能直接完成；异常携带单据号/当前态/目标态")
        void draftCannotComplete() {
            SampleOrder doc = newOrder();
            IllegalStateTransitionException ex =
                    assertThrows(IllegalStateTransitionException.class, () -> doc.complete(OPERATOR));
            assertEquals("PO-202606-0001", ex.getDocNo());
            assertEquals(DocumentStatus.DRAFT, ex.getCurrentStatus());
            assertEquals(DocumentStatus.COMPLETED, ex.getTargetStatus());
            assertTrue(ex.getMessage().contains("PO-202606-0001"));
            assertEquals(DocumentStatus.DRAFT, doc.getStatus(), "失败后状态不变");
        }

        @Test
        @DisplayName("草稿不能直接冲销")
        void draftCannotReverse() {
            SampleOrder doc = newOrder();
            assertThrows(IllegalStateTransitionException.class,
                    () -> doc.reverse(OPERATOR, "PO-202606-0002"));
            assertNull(doc.getReversedById(), "冲销失败不得留下红字关联");
        }

        @Test
        @DisplayName("已审核不能作废（只能冲销）")
        void approvedCannotCancel() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            assertThrows(IllegalStateTransitionException.class, () -> doc.cancel(OPERATOR));
        }

        @Test
        @DisplayName("终态不可再流转：已冲销/已作废后任何操作被拒")
        void terminalStatesRejectAll() {
            SampleOrder reversed = newOrder();
            reversed.approve(OPERATOR);
            reversed.reverse(OPERATOR, "PO-202606-0002");
            assertThrows(IllegalStateTransitionException.class, () -> reversed.approve(OPERATOR));
            assertThrows(IllegalStateTransitionException.class,
                    () -> reversed.reverse(OPERATOR, "PO-202606-0003"));

            SampleOrder cancelled = newOrder();
            cancelled.cancel(OPERATOR);
            assertThrows(IllegalStateTransitionException.class, () -> cancelled.approve(OPERATOR));
        }

        @Test
        @DisplayName("重复审核被拒")
        void doubleApproveRejected() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            assertThrows(IllegalStateTransitionException.class, () -> doc.approve(OPERATOR));
        }
    }

    // ---------------------------------------------------------------
    // 钩子
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("流转钩子")
    class Hooks {

        @Test
        @DisplayName("合法流转依次触发 before/after 钩子")
        void hooksInvokedInOrder() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            assertEquals(List.of("before:DRAFT->APPROVED", "after:DRAFT->APPROVED"), doc.hookCalls);
        }

        @Test
        @DisplayName("非法流转不触发钩子")
        void hooksNotInvokedOnIllegalTransition() {
            SampleOrder doc = newOrder();
            assertThrows(IllegalStateTransitionException.class, () -> doc.complete(OPERATOR));
            assertTrue(doc.hookCalls.isEmpty());
        }

        @Test
        @DisplayName("beforeTransition 抛异常可否决流转：状态不变、事件不发布")
        void beforeHookCanVeto() {
            VetoOrder doc = new VetoOrder("SO-202606-0001");
            assertThrows(IllegalArgumentException.class, () -> doc.approve(OPERATOR));
            assertEquals(DocumentStatus.DRAFT, doc.getStatus());
            assertTrue(doc.pullPendingEvents().isEmpty(), "被否决的流转不得发布事件");
        }
    }

    // ---------------------------------------------------------------
    // 冲销语义（CLAUDE.md 原则 2）
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("冲销语义：原单不可变 + 红字关联")
    class ReversalSemantics {

        @Test
        @DisplayName("冲销不修改原单业务内容，仅打冲销标记并关联红字单")
        void reverseDoesNotMutateOriginalContent() {
            SampleOrder original = newOrder();
            original.approve(OPERATOR);
            original.startExecution(OPERATOR);
            original.complete(OPERATOR);

            Money amountBefore = original.getAmount();
            String docNoBefore = original.getDocNo();
            Instant createdAtBefore = original.getCreatedAt();

            original.reverse(OPERATOR, "PO-202606-0002");

            // 业务内容与不可变标识丝毫未动
            assertEquals(amountBefore, original.getAmount(), "原单金额不可变");
            assertEquals(docNoBefore, original.getDocNo());
            assertEquals(createdAtBefore, original.getCreatedAt());
            // 仅状态标记 + 红字关联
            assertEquals(DocumentStatus.REVERSED, original.getStatus());
            assertEquals("PO-202606-0002", original.getReversedById());
            assertNull(original.getReversalOfId(), "原单不是红字单");
        }

        @Test
        @DisplayName("红字冲销单通过 markAsReversalOf 关联原单")
        void reversalDocumentLinksToOriginal() {
            SampleOrder reversal =
                    new SampleOrder("PO-202606-0002", OPERATOR, Money.of("100.00").negate());
            reversal.markReversal("PO-202606-0001");
            assertEquals("PO-202606-0001", reversal.getReversalOfId());
            assertTrue(reversal.isReversalDocument());
            assertTrue(reversal.getAmount().isNegative(), "红字单金额为负");
        }

        @Test
        @DisplayName("红字标记只能在草稿态打且只能打一次")
        void markAsReversalOfGuards() {
            SampleOrder doc = newOrder();
            doc.markReversal("PO-202606-0000");
            assertThrows(IllegalStateException.class, () -> doc.markReversal("PO-202606-0009"));

            SampleOrder approved = newOrder();
            approved.approve(OPERATOR);
            assertThrows(IllegalStateException.class, () -> approved.markReversal("PO-202606-0000"));
        }

        @Test
        @DisplayName("冲销必须提供红字单号")
        void reverseRequiresReversalDocNo() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            assertThrows(NullPointerException.class, () -> doc.reverse(OPERATOR, null));
            assertEquals(DocumentStatus.APPROVED, doc.getStatus(), "失败后状态不变");
        }

        @Test
        @DisplayName("领域层不存在物理删除方法（CLAUDE.md 原则 2）")
        void noDeleteMethodExists() {
            for (Method m : BusinessDocument.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("delete") || name.startsWith("remove")
                                || name.startsWith("purge") || name.startsWith("drop"),
                        "BusinessDocument 不得提供物理删除方法: " + m.getName());
            }
        }
    }

    // ---------------------------------------------------------------
    // 领域事件
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("领域事件发布")
    class Events {

        @Test
        @DisplayName("注册发布器后，每次流转即时发布 DocumentStatusChangedEvent")
        void publishesEventOnEachTransition() {
            List<DomainEvent> received = new ArrayList<>();
            SampleOrder doc = newOrder();
            doc.registerEventPublisher(received::add);

            doc.approve(OPERATOR);
            doc.startExecution(OPERATOR);
            doc.complete(OPERATOR);
            doc.reverse(OPERATOR, "PO-202606-0002");

            assertEquals(4, received.size());
            DocumentStatusChangedEvent first =
                    assertInstanceOf(DocumentStatusChangedEvent.class, received.get(0));
            assertEquals("PO-202606-0001", first.getDocNo());
            assertEquals(DocumentStatus.DRAFT, first.getFromStatus());
            assertEquals(DocumentStatus.APPROVED, first.getToStatus());
            assertEquals(OPERATOR, first.getOperator());
            assertNotNull(first.getEventId());
            assertNotNull(first.getOccurredAt());
            assertEquals("PO-202606-0001", first.getAggregateId());

            DocumentStatusChangedEvent last =
                    assertInstanceOf(DocumentStatusChangedEvent.class, received.get(3));
            assertEquals(DocumentStatus.COMPLETED, last.getFromStatus());
            assertEquals(DocumentStatus.REVERSED, last.getToStatus());
        }

        @Test
        @DisplayName("未注册发布器时事件缓存，pullPendingEvents 取走后清空")
        void buffersEventsWhenNoPublisher() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            doc.startExecution(OPERATOR);

            List<DomainEvent> events = doc.pullPendingEvents();
            assertEquals(2, events.size());
            assertInstanceOf(DocumentStatusChangedEvent.class, events.get(0));
            assertTrue(doc.pullPendingEvents().isEmpty(), "取走后缓存应清空");
        }

        @Test
        @DisplayName("每个事件 id 全局唯一")
        void eventIdsAreUnique() {
            SampleOrder doc = newOrder();
            doc.approve(OPERATOR);
            doc.startExecution(OPERATOR);
            List<DomainEvent> events = doc.pullPendingEvents();
            assertEquals(2, events.size());
            assertFalse(events.get(0).getEventId().equals(events.get(1).getEventId()));
        }
    }
}

package com.sjherp.domain.common.numbering;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单据编号规则与生成器测试：格式、序号递增、跨月重置、并发安全。
 */
class DocumentNumberingTest {

    private static final YearMonth JUNE_2026 = YearMonth.of(2026, 6);

    // ---------------------------------------------------------------
    // 编号规则值对象
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("DocumentNumberRule 规则值对象")
    class RuleTests {

        @Test
        @DisplayName("标准格式：前缀-年月-四位序号，如 PO-202606-0001")
        void standardFormat() {
            DocumentNumberRule rule = DocumentNumberRule.of("PO");
            assertEquals("PO-202606-0001", rule.format(JUNE_2026, 1));
            assertEquals("PO-202606-0042", rule.format(JUNE_2026, 42));
            assertEquals("PO-202606-9999", rule.format(JUNE_2026, 9999));
        }

        @Test
        @DisplayName("月份补零：1 月格式化为 01")
        void monthZeroPadded() {
            DocumentNumberRule rule = DocumentNumberRule.of("SO");
            assertEquals("SO-202601-0001", rule.format(YearMonth.of(2026, 1), 1));
        }

        @Test
        @DisplayName("序号超出位数不截断，保证不重号")
        void sequenceOverflowNotTruncated() {
            DocumentNumberRule rule = DocumentNumberRule.of("PO");
            assertEquals("PO-202606-10000", rule.format(JUNE_2026, 10000));
        }

        @Test
        @DisplayName("可自定义序号位数")
        void customSequenceWidth() {
            DocumentNumberRule rule = DocumentNumberRule.of("VCH", 6);
            assertEquals("VCH-202606-000001", rule.format(JUNE_2026, 1));
        }

        @Test
        @DisplayName("序号作用域键 = 前缀-年月（按月独立计数依据）")
        void sequenceScopeKey() {
            DocumentNumberRule rule = DocumentNumberRule.of("PO");
            assertEquals("PO-202606", rule.sequenceScopeKey(JUNE_2026));
            assertNotEquals(rule.sequenceScopeKey(JUNE_2026),
                    rule.sequenceScopeKey(YearMonth.of(2026, 7)));
        }

        @Test
        @DisplayName("非法前缀被拒：小写/空/含数字/过长")
        void invalidPrefixRejected() {
            assertThrows(IllegalArgumentException.class, () -> DocumentNumberRule.of("po"));
            assertThrows(IllegalArgumentException.class, () -> DocumentNumberRule.of(""));
            assertThrows(IllegalArgumentException.class, () -> DocumentNumberRule.of("PO1"));
            assertThrows(IllegalArgumentException.class, () -> DocumentNumberRule.of("ABCDEFGHIJK"));
            assertThrows(NullPointerException.class, () -> DocumentNumberRule.of(null));
        }

        @Test
        @DisplayName("非法序号位数与非法序号被拒")
        void invalidWidthAndSequenceRejected() {
            assertThrows(IllegalArgumentException.class, () -> DocumentNumberRule.of("PO", 0));
            assertThrows(IllegalArgumentException.class, () -> DocumentNumberRule.of("PO", 10));
            DocumentNumberRule rule = DocumentNumberRule.of("PO");
            assertThrows(IllegalArgumentException.class, () -> rule.format(JUNE_2026, 0));
            assertThrows(IllegalArgumentException.class, () -> rule.format(JUNE_2026, -1));
        }

        @Test
        @DisplayName("值对象相等性：同前缀同位数相等")
        void valueEquality() {
            assertEquals(DocumentNumberRule.of("PO"), DocumentNumberRule.of("PO", 4));
            assertEquals(DocumentNumberRule.of("PO").hashCode(),
                    DocumentNumberRule.of("PO", 4).hashCode());
            assertNotEquals(DocumentNumberRule.of("PO"), DocumentNumberRule.of("SO"));
            assertNotEquals(DocumentNumberRule.of("PO", 4), DocumentNumberRule.of("PO", 5));
        }

        @Test
        @DisplayName("getter 与 toString")
        void accessors() {
            DocumentNumberRule rule = DocumentNumberRule.of("PO", 5);
            assertEquals("PO", rule.getPrefix());
            assertEquals(5, rule.getSequenceWidth());
            assertTrue(rule.toString().contains("PO"));
        }
    }

    // ---------------------------------------------------------------
    // 生成器
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("DefaultDocumentNumberGenerator 生成器")
    class GeneratorTests {

        @Test
        @DisplayName("同月连续生成序号递增")
        void sequenceIncrementsWithinMonth() {
            DocumentNumberGenerator gen =
                    new DefaultDocumentNumberGenerator(new InMemorySequenceProvider());
            DocumentNumberRule rule = DocumentNumberRule.of("PO");
            assertEquals("PO-202606-0001", gen.generate(rule, JUNE_2026));
            assertEquals("PO-202606-0002", gen.generate(rule, JUNE_2026));
            assertEquals("PO-202606-0003", gen.generate(rule, JUNE_2026));
        }

        @Test
        @DisplayName("跨月序号从 1 重新开始")
        void sequenceResetsAcrossMonths() {
            DocumentNumberGenerator gen =
                    new DefaultDocumentNumberGenerator(new InMemorySequenceProvider());
            DocumentNumberRule rule = DocumentNumberRule.of("PO");
            assertEquals("PO-202606-0001", gen.generate(rule, JUNE_2026));
            assertEquals("PO-202606-0002", gen.generate(rule, JUNE_2026));
            assertEquals("PO-202607-0001", gen.generate(rule, YearMonth.of(2026, 7)));
        }

        @Test
        @DisplayName("不同前缀序号互不影响")
        void prefixesHaveIndependentSequences() {
            DocumentNumberGenerator gen =
                    new DefaultDocumentNumberGenerator(new InMemorySequenceProvider());
            assertEquals("PO-202606-0001", gen.generate(DocumentNumberRule.of("PO"), JUNE_2026));
            assertEquals("SO-202606-0001", gen.generate(DocumentNumberRule.of("SO"), JUNE_2026));
            assertEquals("PO-202606-0002", gen.generate(DocumentNumberRule.of("PO"), JUNE_2026));
        }

        @Test
        @DisplayName("无年月参数时按注入 Clock 取当前年月")
        void usesInjectedClockForCurrentMonth() {
            Clock fixed = Clock.fixed(Instant.parse("2026-06-11T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
            DocumentNumberGenerator gen =
                    new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), fixed);
            assertEquals("PO-202606-0001", gen.generate(DocumentNumberRule.of("PO")));
        }

        @Test
        @DisplayName("空参数被拒")
        void nullArgumentsRejected() {
            DocumentNumberGenerator gen =
                    new DefaultDocumentNumberGenerator(new InMemorySequenceProvider());
            assertThrows(NullPointerException.class, () -> gen.generate(null, JUNE_2026));
            assertThrows(NullPointerException.class,
                    () -> gen.generate(DocumentNumberRule.of("PO"), null));
            assertThrows(NullPointerException.class, () -> new DefaultDocumentNumberGenerator(null));
        }
    }

    // ---------------------------------------------------------------
    // 内存序号供给
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("InMemorySequenceProvider 内存序号供给（测试用）")
    class ProviderTests {

        @Test
        @DisplayName("各作用域从 1 开始独立递增")
        void independentScopes() {
            InMemorySequenceProvider provider = new InMemorySequenceProvider();
            assertEquals(1, provider.next("PO-202606"));
            assertEquals(2, provider.next("PO-202606"));
            assertEquals(1, provider.next("SO-202606"));
            assertThrows(NullPointerException.class, () -> provider.next(null));
        }

        @Test
        @DisplayName("并发取号不重号不丢号")
        void concurrentNextProducesUniqueSequence() throws InterruptedException {
            InMemorySequenceProvider provider = new InMemorySequenceProvider();
            int threads = 8;
            int perThread = 200;
            Set<Long> seen = java.util.Collections.synchronizedSet(new HashSet<>());
            AtomicLong max = new AtomicLong();
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            for (int i = 0; i < perThread; i++) {
                                long v = provider.next("PO-202606");
                                seen.add(v);
                                max.accumulateAndGet(v, Math::max);
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(30, TimeUnit.SECONDS), "并发取号超时");
            } finally {
                pool.shutdownNow();
            }
            assertEquals(threads * perThread, seen.size(), "序号必须全部唯一");
            assertEquals(threads * perThread, max.get(), "序号必须连续不丢号");
        }
    }
}

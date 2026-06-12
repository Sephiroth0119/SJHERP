package com.sjherp.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Token 启发式估算测试（M1-T05）：中文按字计、英文按 4 字符 1 token 向上取整。
 */
class TokenEstimatorTest {

    @Test
    void nullAndEmptyReturnZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void asciiCountsQuarterTokenPerCharRoundedUp() {
        assertEquals(1, TokenEstimator.estimate("a"));      // 1/4 向上取整 = 1
        assertEquals(1, TokenEstimator.estimate("abcd"));   // 4/4 = 1
        assertEquals(2, TokenEstimator.estimate("abcde"));  // 5/4 向上取整 = 2
        assertEquals(2, TokenEstimator.estimate("abcdefgh"));
        assertEquals(25, TokenEstimator.estimate("x".repeat(100)));
    }

    @Test
    void cjkCountsOneTokenPerChar() {
        assertEquals(2, TokenEstimator.estimate("你好"));
        assertEquals(50, TokenEstimator.estimate("汉".repeat(50)));
    }

    @Test
    void fullWidthPunctuationCountsAsCjk() {
        // 中文逗号 + 全角感叹号各 1 token
        assertEquals(2, TokenEstimator.estimate("，！"));
    }

    @Test
    void mixedTextSumsBothParts() {
        // 4 个汉字 = 4，"abcd1234" 8 个 ASCII = 2
        assertEquals(6, TokenEstimator.estimate("采购订单abcd1234"));
        // 2 个汉字 = 2，1 个空格 = 1（向上取整）
        assertEquals(3, TokenEstimator.estimate("金额 "));
    }
}

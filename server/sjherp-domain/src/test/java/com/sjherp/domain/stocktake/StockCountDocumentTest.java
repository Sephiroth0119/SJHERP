package com.sjherp.domain.stocktake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 盘点单聚合根单测（M3-T03）：行号唯一、账面/录入单价精度、持久层重建状态、审计目标摘要。
 */
class StockCountDocumentTest {

    private static StockCountLine line(int no, long productId, String snapshot) {
        return StockCountLine.create(no, productId, new BigDecimal(snapshot), null);
    }

    @Test
    void 建单行号重复拒绝() {
        assertThrows(IllegalArgumentException.class, () -> StockCountDocument.create("SC-1", 1L, null,
                List.of(line(1, 100L, "10"), line(1, 200L, "20")), "tester"));
    }

    @Test
    void 行号必须从1起且为正() {
        assertThrows(IllegalArgumentException.class,
                () -> StockCountLine.create(0, 100L, BigDecimal.TEN, null));
    }

    @Test
    void 录入单价为负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> StockCountLine.create(1, 100L, BigDecimal.ZERO, new BigDecimal("-1")));
    }

    @Test
    void 持久层重建保留状态_不重放流转() {
        StockCountLine restoredLine = StockCountLine.restore(9L, 1, 100L,
                new BigDecimal("100.000000"), new BigDecimal("103.000000"), null);
        StockCountDocument doc = StockCountDocument.restore("SC-1", 1L, "重建",
                DocumentStatus.COMPLETED, List.of(restoredLine), "tester");

        assertEquals(DocumentStatus.COMPLETED, doc.getStatus());
        assertEquals(0, new BigDecimal("3").compareTo(doc.getLines().get(0).diffQty()));
    }

    @Test
    void 重建为已审核单据不可再录入实盘() {
        StockCountLine restoredLine = StockCountLine.restore(9L, 1, 100L,
                new BigDecimal("100.000000"), new BigDecimal("100.000000"), null);
        StockCountDocument doc = StockCountDocument.restore("SC-1", 1L, null,
                DocumentStatus.APPROVED, List.of(restoredLine), "tester");

        assertThrows(IllegalStateException.class, () -> doc.enterCounted(1, new BigDecimal("99")));
    }

    @Test
    void 审计目标编码为单据号_摘要含状态与行数() {
        StockCountDocument doc = StockCountDocument.create("SC-202606-0001", 7L, "月末盘点",
                List.of(line(1, 100L, "10"), line(2, 200L, "20")), "tester");

        assertEquals("SC-202606-0001", doc.auditTargetCode());
        assertNull(doc.auditTargetId());
        String summary = doc.auditSummary();
        assertTrue(summary.contains("仓库=7"), summary);
        assertTrue(summary.contains("状态=DRAFT"), summary);
        assertTrue(summary.contains("行数=2"), summary);
        assertTrue(summary.contains("已录入=0"), summary);
    }

    @Test
    void 草稿可作废() {
        StockCountDocument doc = StockCountDocument.create("SC-1", 1L, null,
                List.of(line(1, 100L, "10")), "tester");
        doc.cancel("tester");
        assertEquals(DocumentStatus.CANCELLED, doc.getStatus());
    }
}

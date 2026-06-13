package com.sjherp.domain.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;

/**
 * 调拨单聚合单测（M3-T04）：建单约束（同仓拒绝、空行、行号重复、数量校验）、
 * 状态机语义方法、持久层重建工厂。
 */
class TransferDocumentTest {

    private static final long WH_FROM = 1L;
    private static final long WH_TO = 2L;
    private static final String CREATOR = "creator";

    @Test
    void 建单为草稿_持有两仓与行() {
        TransferDocument doc = TransferDocument.create("TR-1", WH_FROM, WH_TO, "门店补货",
                List.of(TransferLine.create(1, 100L, new BigDecimal("10"))), CREATOR);
        assertEquals(DocumentStatus.DRAFT, doc.getStatus());
        assertEquals(WH_FROM, doc.getFromWarehouseId());
        assertEquals(WH_TO, doc.getToWarehouseId());
        assertEquals(1, doc.getLines().size());
    }

    @Test
    void 同仓调拨拒绝() {
        assertThrows(IllegalArgumentException.class, () -> TransferDocument.create("TR-1", WH_FROM,
                WH_FROM, null, List.of(TransferLine.create(1, 100L, new BigDecimal("1"))), CREATOR));
    }

    @Test
    void 空行拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferDocument.create("TR-1", WH_FROM, WH_TO, null, List.of(), CREATOR));
    }

    @Test
    void 行号重复拒绝() {
        assertThrows(IllegalArgumentException.class, () -> TransferDocument.create("TR-1", WH_FROM,
                WH_TO, null,
                List.of(TransferLine.create(1, 100L, new BigDecimal("1")),
                        TransferLine.create(1, 200L, new BigDecimal("2"))), CREATOR));
    }

    @Test
    void 行数量必须为正() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferLine.create(1, 100L, new BigDecimal("0")));
        assertThrows(IllegalArgumentException.class,
                () -> TransferLine.create(1, 100L, new BigDecimal("-1")));
    }

    @Test
    void 行号必须从1起() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferLine.create(0, 100L, new BigDecimal("1")));
    }

    @Test
    void 状态机语义方法_审核执行完成() {
        TransferDocument doc = sample();
        doc.approve("op");
        assertEquals(DocumentStatus.APPROVED, doc.getStatus());
        doc.startExecution("op");
        assertEquals(DocumentStatus.EXECUTING, doc.getStatus());
        doc.complete("op");
        assertEquals(DocumentStatus.COMPLETED, doc.getStatus());
    }

    @Test
    void 草稿可作废() {
        TransferDocument doc = sample();
        doc.cancel("op");
        assertEquals(DocumentStatus.CANCELLED, doc.getStatus());
    }

    @Test
    void 草稿直接执行非法流转() {
        TransferDocument doc = sample();
        assertThrows(IllegalStateTransitionException.class, () -> doc.startExecution("op"));
    }

    @Test
    void 持久层重建保留状态() {
        TransferDocument restored = TransferDocument.restore("TR-9", WH_FROM, WH_TO, "x",
                DocumentStatus.COMPLETED,
                List.of(TransferLine.restore(5L, 1, 100L, new BigDecimal("10"))), CREATOR);
        assertEquals(DocumentStatus.COMPLETED, restored.getStatus());
        assertEquals(5L, restored.getLines().get(0).getId());
    }

    @Test
    void 审计摘要含两仓与行数() {
        TransferDocument doc = sample();
        assertTrue(doc.auditSummary().contains("调出仓=" + WH_FROM));
        assertTrue(doc.auditSummary().contains("调入仓=" + WH_TO));
        assertEquals("TR-1", doc.auditTargetCode());
    }

    private static TransferDocument sample() {
        return TransferDocument.create("TR-1", WH_FROM, WH_TO, "门店补货",
                List.of(TransferLine.create(1, 100L, new BigDecimal("10"))), CREATOR);
    }
}

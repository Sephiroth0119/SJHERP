package com.sjherp.infra.persistence.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 采购发票候选入库单的真实 MySQL 8.4 分页验收。
 *
 * <p>可开票过滤必须在 SQL 的 count/LIMIT/OFFSET 之前完成，已开完入库单不得制造空页或错误 total。
 */
class JdbcPurchaseReceiptRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcPurchaseReceiptRepository repository = new JdbcPurchaseReceiptRepository(jdbc);

    @Test
    void 可开票入库单在分页前过滤_已开完不计总数也不占页位() {
        String suffix = uniqueSuffix();
        long warehouseId = Math.max(1L, System.nanoTime() & Long.MAX_VALUE);

        insertReceipt("PR-OPEN-OLDER-" + suffix, warehouseId, "COMPLETED", "10", "4");
        insertReceipt("PR-DRAFT-" + suffix, warehouseId, "DRAFT", "10", "0");
        insertReceipt("PR-OPEN-LATER-" + suffix, warehouseId, "COMPLETED", "8", "0");
        insertReceipt("PR-REVERSED-" + suffix, warehouseId, "REVERSED", "6", "0");
        insertReceipt("PR-FULL-LATEST-" + suffix, warehouseId, "COMPLETED", "5", "5");

        PageResult<PurchaseReceipt> first = repository.search(
                new PurchaseReceiptQuery(warehouseId, null, DocumentStatus.COMPLETED, true, 1, 1));
        PageResult<PurchaseReceipt> second = repository.search(
                new PurchaseReceiptQuery(warehouseId, null, DocumentStatus.COMPLETED, true, 2, 1));

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(PurchaseReceipt::getDocNo)
                .containsExactly("PR-OPEN-LATER-" + suffix);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(PurchaseReceipt::getDocNo)
                .containsExactly("PR-OPEN-OLDER-" + suffix);
    }

    private static void insertReceipt(String docNo, long warehouseId, String status,
                                      String quantity, String invoicedQty) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO purchase_receipt "
                        + "(tenant_id, doc_no, purchase_order_no, warehouse_id, receipt_date, status, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (0, ?, ?, ?, ?, ?, 'test', ?, 'test', ?)",
                docNo, "PO-" + docNo, warehouseId, LocalDate.now(), status, now, now);
        Long receiptId = jdbc.queryForObject(
                "SELECT id FROM purchase_receipt WHERE tenant_id = 0 AND doc_no = ?",
                Long.class, docNo);
        BigDecimal qty = new BigDecimal(quantity);
        jdbc.update(
                "INSERT INTO purchase_receipt_line "
                        + "(tenant_id, purchase_receipt_id, line_no, po_line_no, product_id, quantity, "
                        + "unit_cost, amount, invoiced_qty) "
                        + "VALUES (0, ?, 1, 1, 1, ?, 2, ?, ?)",
                receiptId, qty, qty.multiply(new BigDecimal("2")), new BigDecimal(invoicedQty));
    }
}

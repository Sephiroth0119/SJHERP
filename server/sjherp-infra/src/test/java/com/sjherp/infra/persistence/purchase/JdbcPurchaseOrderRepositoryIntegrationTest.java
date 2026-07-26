package com.sjherp.infra.persistence.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 采购入库候选订单的真实 MySQL 8.4 分页验收。
 *
 * <p>可收过滤必须在 SQL 的 count/LIMIT/OFFSET 之前完成，已收完订单不得制造空页或错误 total。
 */
class JdbcPurchaseOrderRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcPurchaseOrderRepository repository = new JdbcPurchaseOrderRepository(jdbc);

    @Test
    void 可收订单在分页前过滤_已收完不计总数也不占页位() {
        String suffix = uniqueSuffix();
        long supplierId = Math.max(1L, System.nanoTime() & Long.MAX_VALUE);

        insertOrder("PO-OPEN-OLDER-" + suffix, supplierId, "APPROVED", "10", "4");
        insertOrder("PO-DRAFT-" + suffix, supplierId, "DRAFT", "10", "0");
        insertOrder("PO-OPEN-LATER-" + suffix, supplierId, "APPROVED", "8", "0");
        insertOrder("PO-COMPLETED-" + suffix, supplierId, "COMPLETED", "6", "0");
        insertOrder("PO-FULL-LATEST-" + suffix, supplierId, "APPROVED", "5", "5");

        PageResult<PurchaseOrder> first = repository.search(
                new PurchaseOrderQuery(supplierId, DocumentStatus.APPROVED, true, 1, 1));
        PageResult<PurchaseOrder> second = repository.search(
                new PurchaseOrderQuery(supplierId, DocumentStatus.APPROVED, true, 2, 1));

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(PurchaseOrder::getDocNo)
                .containsExactly("PO-OPEN-LATER-" + suffix);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(PurchaseOrder::getDocNo)
                .containsExactly("PO-OPEN-OLDER-" + suffix);
    }

    private static void insertOrder(String docNo, long supplierId, String status,
                                    String quantity, String receivedQty) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO purchase_order "
                        + "(tenant_id, doc_no, supplier_id, order_date, status, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (0, ?, ?, ?, ?, 'test', ?, 'test', ?)",
                docNo, supplierId, LocalDate.now(), status, now, now);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM purchase_order WHERE tenant_id = 0 AND doc_no = ?",
                Long.class, docNo);
        BigDecimal qty = new BigDecimal(quantity);
        jdbc.update(
                "INSERT INTO purchase_order_line "
                        + "(tenant_id, purchase_order_id, line_no, product_id, quantity, "
                        + "unit_price, amount, received_qty) "
                        + "VALUES (0, ?, 1, 1, ?, 2, ?, ?)",
                orderId, qty, qty.multiply(new BigDecimal("2")), new BigDecimal(receivedQty));
    }
}

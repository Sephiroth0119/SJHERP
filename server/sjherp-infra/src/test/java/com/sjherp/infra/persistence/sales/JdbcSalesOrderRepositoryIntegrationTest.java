package com.sjherp.infra.persistence.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 销售出库候选订单的真实 MySQL 8.4 分页验收。
 *
 * <p>可发货过滤必须在 SQL 的 count/LIMIT/OFFSET 之前完成；已发完、草稿和已完成订单
 * 不得制造空页或错误 total，EXECUTING 部分发货订单仍须可选。
 */
class JdbcSalesOrderRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcSalesOrderRepository repository = new JdbcSalesOrderRepository(jdbc);

    @Test
    void 可发货订单在分页前过滤_已发完不计总数也不占页位_执行中部分发货仍可选() {
        String suffix = uniqueSuffix();
        long customerId = Math.max(1L, System.nanoTime() & Long.MAX_VALUE);

        insertOrder("SO-APPROVED-OLDER-" + suffix, customerId, "APPROVED", "10", "4");
        insertOrder("SO-DRAFT-" + suffix, customerId, "DRAFT", "10", "0");
        insertOrder("SO-EXECUTING-LATER-" + suffix, customerId, "EXECUTING", "8", "3");
        insertOrder("SO-COMPLETED-" + suffix, customerId, "COMPLETED", "6", "0");
        insertOrder("SO-FULL-LATEST-" + suffix, customerId, "APPROVED", "5", "5");

        PageResult<SalesOrder> first = repository.search(
                new SalesOrderQuery(customerId, null, true, 1, 1));
        PageResult<SalesOrder> second = repository.search(
                new SalesOrderQuery(customerId, null, true, 2, 1));

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(SalesOrder::getDocNo)
                .containsExactly("SO-EXECUTING-LATER-" + suffix);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(SalesOrder::getDocNo)
                .containsExactly("SO-APPROVED-OLDER-" + suffix);
    }

    private static void insertOrder(String docNo, long customerId, String status,
                                    String quantity, String deliveredQty) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO sales_order "
                        + "(tenant_id, doc_no, customer_id, order_date, status, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (0, ?, ?, ?, ?, 'test', ?, 'test', ?)",
                docNo, customerId, LocalDate.now(), status, now, now);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM sales_order WHERE tenant_id = 0 AND doc_no = ?",
                Long.class, docNo);
        BigDecimal qty = new BigDecimal(quantity);
        jdbc.update(
                "INSERT INTO sales_order_line "
                        + "(tenant_id, sales_order_id, line_no, product_id, quantity, "
                        + "unit_price, amount, delivered_qty) "
                        + "VALUES (0, ?, 1, 1, ?, 2, ?, ?)",
                orderId, qty, qty.multiply(new BigDecimal("2")), new BigDecimal(deliveredQty));
    }
}

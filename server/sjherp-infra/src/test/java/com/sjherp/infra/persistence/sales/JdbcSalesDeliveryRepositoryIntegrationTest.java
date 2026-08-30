package com.sjherp.infra.persistence.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/** 销售发票候选出库单的真实 MySQL 8.4 分页验收。 */
class JdbcSalesDeliveryRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcSalesDeliveryRepository repository = new JdbcSalesDeliveryRepository(jdbc);

    @Test
    void 可开票出库单在分页前过滤_已开完不计总数也不占页位() {
        String suffix = uniqueSuffix();
        long warehouseId = Math.max(1L, System.nanoTime() & Long.MAX_VALUE);

        insertDelivery("SD-OPEN-OLDER-" + suffix, warehouseId, "COMPLETED", "10", "4");
        insertDelivery("SD-DRAFT-" + suffix, warehouseId, "DRAFT", "10", "0");
        insertDelivery("SD-OPEN-LATER-" + suffix, warehouseId, "COMPLETED", "8", "0");
        insertDelivery("SD-REVERSED-" + suffix, warehouseId, "REVERSED", "6", "0");
        String fullDocNo = "SD-FULL-LATEST-" + suffix;
        insertDelivery(fullDocNo, warehouseId, "COMPLETED", "5", "5");
        insertForeignTenantOpenLine(fullDocNo);

        PageResult<SalesDelivery> first = repository.search(
                new SalesDeliveryQuery(null, warehouseId, DocumentStatus.COMPLETED, true, 1, 1));
        PageResult<SalesDelivery> second = repository.search(
                new SalesDeliveryQuery(null, warehouseId, DocumentStatus.COMPLETED, true, 2, 1));

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(SalesDelivery::getDocNo)
                .containsExactly("SD-OPEN-LATER-" + suffix);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(SalesDelivery::getDocNo)
                .containsExactly("SD-OPEN-OLDER-" + suffix);
    }

    @Test
    void 同一出库单的并发开票回写被行锁串行化_后到事务读取最新剩余量() throws Exception {
        String docNo = "SD-LOCK-" + uniqueSuffix();
        insertDelivery(docNo, Math.max(1L, System.nanoTime() & Long.MAX_VALUE),
                "COMPLETED", "10", "0");
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptingLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<SalesDelivery> first = executor.submit(() -> inTransaction(() -> {
                SalesDelivery delivery = lockingFind(docNo);
                firstLocked.countDown();
                delivery.invoiceLine(1, new BigDecimal("6"));
                repository.save(delivery);
                await(releaseFirst);
                return delivery;
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> second = executor.submit(() -> {
                try {
                    inTransaction(() -> {
                        secondAttemptingLock.countDown();
                        SalesDelivery delivery = lockingFind(docNo);
                        delivery.invoiceLine(1, new BigDecimal("6"));
                        repository.save(delivery);
                        return delivery;
                    });
                    return null;
                } catch (Throwable cause) {
                    return cause;
                }
            });
            assertThat(secondAttemptingLock.await(5, TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("累计已开票量");
            assertThat(repository.findByDocNo(docNo).orElseThrow().getLines().get(0).getInvoicedQty())
                    .isEqualByComparingTo("6");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SalesDelivery lockingFind(String docNo) {
        return repository.findByDocNoForUpdate(docNo).orElseThrow();
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> callback) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        return transaction.execute(status -> {
            try {
                return callback.call();
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待释放开票回写事务超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待开票回写事务时被中断", exception);
        }
    }

    private static void insertForeignTenantOpenLine(String docNo) {
        Long deliveryId = jdbc.queryForObject(
                "SELECT id FROM sales_delivery WHERE tenant_id = 0 AND doc_no = ?",
                Long.class, docNo);
        jdbc.update(
                "INSERT INTO sales_delivery_line "
                        + "(tenant_id, sales_delivery_id, line_no, so_line_no, product_id, quantity, "
                        + "cogs_amount, invoiced_qty) VALUES (1, ?, 2, 2, 2, 9, 12, 0)",
                deliveryId);
    }

    private static void insertDelivery(String docNo, long warehouseId, String status,
                                       String quantity, String invoicedQty) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO sales_delivery "
                        + "(tenant_id, doc_no, sales_order_no, warehouse_id, status, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (0, ?, ?, ?, ?, 'test', ?, 'test', ?)",
                docNo, "SO-" + docNo, warehouseId, status, now, now);
        Long deliveryId = jdbc.queryForObject(
                "SELECT id FROM sales_delivery WHERE tenant_id = 0 AND doc_no = ?",
                Long.class, docNo);
        BigDecimal qty = new BigDecimal(quantity);
        jdbc.update(
                "INSERT INTO sales_delivery_line "
                        + "(tenant_id, sales_delivery_id, line_no, so_line_no, product_id, quantity, "
                        + "cogs_amount, invoiced_qty) "
                        + "VALUES (0, ?, 1, 1, 1, ?, 12, ?)",
                deliveryId, qty, new BigDecimal(invoicedQty));
    }
}

package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * inventory 领域测试用内存仓储替身（仅测试使用，不进生产）。
 *
 * <p>余额仓储额外记录 lockForUpdate 调用顺序（锁顺序升序断言用，拆解 §1.4）；
 * 流水仓储支持「第 N 次 save 抛异常」注入（批量原子性断言用）。
 */
final class InMemoryInventoryFixtures {

    private InMemoryInventoryFixtures() {
    }

    static String key(long warehouseId, long productId) {
        return warehouseId + ":" + productId;
    }

    /** 余额内存仓储：lockForUpdate 不存在即建零行（模拟 infra 的零行 upsert） */
    static final class InMemoryBalanceRepository implements InventoryBalanceRepository {

        final Map<String, InventoryBalance> store = new LinkedHashMap<>();

        /** lockForUpdate 调用顺序记录，元素形如 "warehouseId:productId" */
        final List<String> lockCalls = new ArrayList<>();

        private final AtomicLong idGen = new AtomicLong();

        @Override
        public InventoryBalance lockForUpdate(long warehouseId, long productId, String operator) {
            lockCalls.add(key(warehouseId, productId));
            return store.computeIfAbsent(key(warehouseId, productId), k -> {
                InventoryBalance balance = InventoryBalance.openZero(warehouseId, productId, operator);
                balance.assignId(idGen.incrementAndGet());
                return balance;
            });
        }

        @Override
        public void save(InventoryBalance balance) {
            if (balance.getId() == null) {
                balance.assignId(idGen.incrementAndGet());
            }
            store.put(key(balance.getWarehouseId(), balance.getProductId()), balance);
        }

        @Override
        public Optional<InventoryBalance> find(long warehouseId, long productId) {
            return Optional.ofNullable(store.get(key(warehouseId, productId)));
        }

        BigDecimal quantityOf(long warehouseId, long productId) {
            InventoryBalance balance = store.get(key(warehouseId, productId));
            return balance == null ? BigDecimal.ZERO : balance.getQuantity();
        }

        BigDecimal amountOf(long warehouseId, long productId) {
            InventoryBalance balance = store.get(key(warehouseId, productId));
            return balance == null ? BigDecimal.ZERO : balance.getCostAmount();
        }
    }

    /** 流水内存仓储：只追加；可注入第 N 次 save 失败（1 起算） */
    static final class InMemoryTransactionRepository implements InventoryTransactionRepository {

        final List<InventoryTransaction> store = new ArrayList<>();

        /** 第 N 次 save 抛异常（1 起算），-1 表示不注入失败 */
        int failOnSaveAt = -1;

        private int saveCount;
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(InventoryTransaction transaction) {
            saveCount++;
            if (saveCount == failOnSaveAt) {
                throw new IllegalStateException("模拟流水落库失败（第 " + saveCount + " 次 save）");
            }
            if (transaction.getId() == null) {
                transaction.assignId(idGen.incrementAndGet());
            }
            store.add(transaction);
        }

        @Override
        public Optional<InventoryTransaction> findByIdempotencyKey(String idempotencyKey) {
            return store.stream()
                    .filter(t -> t.getIdempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<InventoryTransaction> findLatestWithUnitCost(long warehouseId, long productId) {
            for (int i = store.size() - 1; i >= 0; i--) {
                InventoryTransaction t = store.get(i);
                if (t.getWarehouseId() == warehouseId && t.getProductId() == productId
                        && t.getUnitCost() != null) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }
}

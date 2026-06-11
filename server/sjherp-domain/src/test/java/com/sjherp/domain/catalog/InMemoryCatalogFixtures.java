package com.sjherp.domain.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.sjherp.domain.common.PageResult;

/**
 * catalog 领域测试用内存仓储替身（仅测试使用，不进生产）。
 */
final class InMemoryCatalogFixtures {

    private InMemoryCatalogFixtures() {
    }

    static final class InMemoryProductRepository implements ProductRepository {
        final Map<Long, Product> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Product product) {
            if (product.getId() == null) {
                product.assignId(idGen.incrementAndGet());
            }
            store.put(product.getId(), product);
        }

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsByCode(String code) {
            return store.values().stream().anyMatch(p -> p.getCode().equals(code));
        }

        @Override
        public PageResult<Product> search(ProductQuery query) {
            List<Product> matched = store.values().stream()
                    .filter(p -> query.status() == null || p.getStatus() == query.status())
                    .filter(p -> matchesKeyword(p, query.keyword()))
                    .sorted(Comparator.comparing(Product::getId).reversed())
                    .toList();
            int from = Math.min((query.page() - 1) * query.size(), matched.size());
            int to = Math.min(from + query.size(), matched.size());
            return new PageResult<>(new ArrayList<>(matched.subList(from, to)),
                    matched.size(), query.page(), query.size());
        }

        private static boolean matchesKeyword(Product p, String keyword) {
            if (keyword == null) {
                return true;
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            return p.getCode().toLowerCase(Locale.ROOT).contains(kw)
                    || p.getName().toLowerCase(Locale.ROOT).contains(kw)
                    || (p.getBarcode() != null && p.getBarcode().toLowerCase(Locale.ROOT).contains(kw));
        }

        @Override
        public boolean existsByCategoryId(long categoryId) {
            return store.values().stream()
                    .anyMatch(p -> categoryId == (p.getCategoryId() == null ? -1 : p.getCategoryId()));
        }

        @Override
        public boolean existsByUnitId(long unitId) {
            return store.values().stream().anyMatch(p -> p.getBaseUnitId() == unitId
                    || p.getUnitConversions().stream().anyMatch(c -> c.unitId() == unitId));
        }
    }

    static final class InMemoryCategoryRepository implements CategoryRepository {
        final Map<Long, Category> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Category category) {
            if (category.getId() == null) {
                category.assignId(idGen.incrementAndGet());
            }
            store.put(category.getId(), category);
        }

        @Override
        public Optional<Category> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Category> findByName(String name) {
            return store.values().stream().filter(c -> c.getName().equals(name)).findFirst();
        }

        @Override
        public List<Category> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public boolean existsByParentId(long parentId) {
            return store.values().stream()
                    .anyMatch(c -> c.getParentId() != null && c.getParentId() == parentId);
        }

        @Override
        public void deleteById(long id) {
            store.remove(id);
        }
    }

    static final class InMemoryUnitRepository implements UnitRepository {
        final Map<Long, Unit> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Unit unit) {
            if (unit.getId() == null) {
                unit.assignId(idGen.incrementAndGet());
            }
            store.put(unit.getId(), unit);
        }

        @Override
        public Optional<Unit> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Unit> findByName(String name) {
            return store.values().stream().filter(u -> u.getName().equals(name)).findFirst();
        }

        @Override
        public List<Unit> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public void deleteById(long id) {
            store.remove(id);
        }
    }
}

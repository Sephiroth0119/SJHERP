package com.sjherp.domain.partner;

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
 * partner 领域测试用内存仓储替身（仅测试使用，不进生产）。
 */
final class InMemoryPartnerFixtures {

    private InMemoryPartnerFixtures() {
    }

    static final class InMemoryCustomerRepository implements CustomerRepository {
        final Map<Long, Customer> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Customer customer) {
            if (customer.getId() == null) {
                customer.assignId(idGen.incrementAndGet());
            }
            store.put(customer.getId(), customer);
        }

        @Override
        public Optional<Customer> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsByCode(String code) {
            return store.values().stream().anyMatch(c -> c.getCode().equals(code));
        }

        @Override
        public PageResult<Customer> search(CustomerQuery query) {
            List<Customer> matched = store.values().stream()
                    .filter(c -> query.status() == null || c.getStatus() == query.status())
                    .filter(c -> matchesKeyword(c, query.keyword()))
                    .sorted(Comparator.comparing(Customer::getId).reversed())
                    .toList();
            int from = Math.min((query.page() - 1) * query.size(), matched.size());
            int to = Math.min(from + query.size(), matched.size());
            return new PageResult<>(new ArrayList<>(matched.subList(from, to)),
                    matched.size(), query.page(), query.size());
        }

        private static boolean matchesKeyword(Customer c, String keyword) {
            if (keyword == null) {
                return true;
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            return c.getCode().toLowerCase(Locale.ROOT).contains(kw)
                    || c.getName().toLowerCase(Locale.ROOT).contains(kw)
                    || (c.getContactPerson() != null && c.getContactPerson().toLowerCase(Locale.ROOT).contains(kw))
                    || (c.getContactPhone() != null && c.getContactPhone().toLowerCase(Locale.ROOT).contains(kw));
        }
    }

    static final class InMemorySupplierRepository implements SupplierRepository {
        final Map<Long, Supplier> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Supplier supplier) {
            if (supplier.getId() == null) {
                supplier.assignId(idGen.incrementAndGet());
            }
            store.put(supplier.getId(), supplier);
        }

        @Override
        public Optional<Supplier> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsByCode(String code) {
            return store.values().stream().anyMatch(s -> s.getCode().equals(code));
        }

        @Override
        public PageResult<Supplier> search(SupplierQuery query) {
            List<Supplier> matched = store.values().stream()
                    .filter(s -> query.status() == null || s.getStatus() == query.status())
                    .filter(s -> matchesKeyword(s, query.keyword()))
                    .sorted(Comparator.comparing(Supplier::getId).reversed())
                    .toList();
            int from = Math.min((query.page() - 1) * query.size(), matched.size());
            int to = Math.min(from + query.size(), matched.size());
            return new PageResult<>(new ArrayList<>(matched.subList(from, to)),
                    matched.size(), query.page(), query.size());
        }

        private static boolean matchesKeyword(Supplier s, String keyword) {
            if (keyword == null) {
                return true;
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            return s.getCode().toLowerCase(Locale.ROOT).contains(kw)
                    || s.getName().toLowerCase(Locale.ROOT).contains(kw)
                    || (s.getContactPerson() != null && s.getContactPerson().toLowerCase(Locale.ROOT).contains(kw))
                    || (s.getContactPhone() != null && s.getContactPhone().toLowerCase(Locale.ROOT).contains(kw));
        }
    }
}

package com.sjherp.infra.persistence.partner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerQuery;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * partner 两仓储（customer / supplier）真实 MySQL 最小往返测试（X-2）：
 * insert → findById → search 一条路径；信用额度 DECIMAL(18,2) 按 BigDecimal 数值核对。
 */
class PartnerRepositoriesIntegrationTest extends MySqlContainerTestBase {

    private final JdbcCustomerRepository customerRepository = new JdbcCustomerRepository(jdbc);
    private final JdbcSupplierRepository supplierRepository = new JdbcSupplierRepository(jdbc);

    @Test
    void 客户_保存后读回并可按编码搜索() {
        String code = "CUS" + uniqueSuffix();
        Customer customer = new Customer(code, "测试客户", "张三", "13800000000",
                "测试地址", "91330100TESTTAX00", SettlementMethod.MONTHLY,
                new BigDecimal("5000.00"), "tester");

        customerRepository.save(customer);

        assertThat(customer.getId()).isNotNull();
        Optional<Customer> found = customerRepository.findById(customer.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(code);
        assertThat(found.get().getName()).isEqualTo("测试客户");
        assertThat(found.get().getSettlementMethod()).isEqualTo(SettlementMethod.MONTHLY);
        // 金额列 DECIMAL(18,2)：按数值比较，杜绝浮点
        assertThat(found.get().getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));

        assertThat(customerRepository.existsByCode(code)).isTrue();
        PageResult<Customer> page = customerRepository.search(new CustomerQuery(code, null, 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).getCode()).isEqualTo(code);
    }

    @Test
    void 供应商_保存后读回并可按编码搜索() {
        String code = "SUP" + uniqueSuffix();
        Supplier supplier = new Supplier(code, "测试供应商", "李四", "13900000000",
                "供应商地址", null, SettlementMethod.CASH, "tester");

        supplierRepository.save(supplier);

        assertThat(supplier.getId()).isNotNull();
        Optional<Supplier> found = supplierRepository.findById(supplier.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(code);
        assertThat(found.get().getName()).isEqualTo("测试供应商");
        assertThat(found.get().getSettlementMethod()).isEqualTo(SettlementMethod.CASH);

        assertThat(supplierRepository.existsByCode(code)).isTrue();
        PageResult<Supplier> page = supplierRepository.search(new SupplierQuery(code, null, 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).getCode()).isEqualTo(code);
    }
}

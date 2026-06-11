package com.sjherp.domain.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;
import com.sjherp.domain.partner.InMemoryPartnerFixtures.InMemoryCustomerRepository;

/**
 * 客户档案领域服务测试：自动编号、编码唯一、必填校验、信用额度、启停规则。
 */
class CustomerServiceTest {

    private static final String OPERATOR = "tester";

    /** 固定时钟：2026-06，自动编号应为 CUS-202606-XXXX */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T08:00:00Z"), ZoneOffset.UTC);

    private InMemoryCustomerRepository customerRepository;
    private CustomerService service;

    @BeforeEach
    void setUp() {
        customerRepository = new InMemoryCustomerRepository();
        service = new CustomerService(customerRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK));
    }

    private CustomerCommand command(String code, String name) {
        return new CustomerCommand(code, name, "张三", "13800000001", "上海市浦东新区",
                "91310000MA1FL0000X", SettlementMethod.MONTHLY, new BigDecimal("500000.00"));
    }

    @Test
    void 编码为空时自动编号_CUS前缀年月序号() {
        Customer first = service.create(command(null, "华东金属"), OPERATOR);
        Customer second = service.create(command("", "南方贸易"), OPERATOR);
        assertEquals("CUS-202606-0001", first.getCode());
        assertEquals("CUS-202606-0002", second.getCode());
        assertNotNull(first.getId());
        assertEquals(ArchiveStatus.ENABLED, first.getStatus());
        // 默认币种固定 CNY（Q-4 决策，字段预留）
        assertEquals("CNY", first.getCurrency());
    }

    @Test
    void 手填编码可用_重复被拒绝() {
        service.create(command("CUST-001", "华东金属"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(command("CUST-001", "山寨华东金属"), OPERATOR));
        assertTrue(e.getMessage().contains("已存在"));
    }

    @Test
    void 更新可改编码_与他人重复被拒绝_与自己相同放行() {
        Customer first = service.create(command("CUST-001", "华东金属"), OPERATOR);
        service.create(command("CUST-002", "南方贸易"), OPERATOR);

        // 改成他人编码 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.update(first.getId(), command("CUST-002", "华东金属"), OPERATOR));
        // 编码不变只改名 → 放行
        Customer updated = service.update(first.getId(), command("CUST-001", "华东金属集团"), OPERATOR);
        assertEquals("华东金属集团", updated.getName());
    }

    @Test
    void 名称为空被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(command(null, null), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(command(null, "  "), OPERATOR));
    }

    @Test
    void 结算方式为空被拒绝() {
        CustomerCommand cmd = new CustomerCommand(null, "华东金属", null, null, null, null, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd, OPERATOR));
        assertTrue(e.getMessage().contains("结算方式"));
    }

    @Test
    void 信用额度可空_负数被拒绝() {
        CustomerCommand noLimit = new CustomerCommand(null, "华东金属", null, null, null, null,
                SettlementMethod.CASH, null);
        Customer customer = service.create(noLimit, OPERATOR);
        assertNull(customer.getCreditLimit());

        CustomerCommand negative = new CustomerCommand(null, "南方贸易", null, null, null, null,
                SettlementMethod.CASH, new BigDecimal("-1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(negative, OPERATOR));
        assertTrue(e.getMessage().contains("信用额度"));
    }

    @Test
    void 信用额度BigDecimal精确保存() {
        CustomerCommand cmd = new CustomerCommand(null, "华东金属", null, null, null, null,
                SettlementMethod.MONTHLY, new BigDecimal("123456.78"));
        Customer customer = service.create(cmd, OPERATOR);
        assertEquals(0, new BigDecimal("123456.78").compareTo(customer.getCreditLimit()));
    }

    @Test
    void 启停规则_停用再启用_重复操作被拒绝() {
        Customer customer = service.create(command(null, "华东金属"), OPERATOR);
        long id = customer.getId();

        Customer disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());
        assertEquals("boss", disabled.getUpdatedBy());
        // 重复停用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.disable(id, "boss"));

        Customer enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
        // 重复启用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.enable(id, OPERATOR));
    }

    @Test
    void 查询不存在的客户抛404异常() {
        assertThrows(PartnerNotFoundException.class, () -> service.get(999L));
    }

    @Test
    void 分页关键字查询_匹配编码名称联系人电话() {
        service.create(command("CUST-001", "华东金属"), OPERATOR);
        service.create(new CustomerCommand("CUST-002", "南方贸易", "李四", "13900000002",
                null, null, SettlementMethod.CASH, null), OPERATOR);

        PageResult<Customer> byName = service.search(new CustomerQuery("金属", null, 1, 20));
        assertEquals(1, byName.total());
        PageResult<Customer> byCode = service.search(new CustomerQuery("CUST-002", null, 1, 20));
        assertEquals(1, byCode.total());
        PageResult<Customer> byContact = service.search(new CustomerQuery("李四", null, 1, 20));
        assertEquals(1, byContact.total());
        PageResult<Customer> byPhone = service.search(new CustomerQuery("13800000001", null, 1, 20));
        assertEquals(1, byPhone.total());
        PageResult<Customer> all = service.search(new CustomerQuery(null, null, 1, 20));
        assertEquals(2, all.total());
    }

    @Test
    void 分页关键字查询_可按状态过滤() {
        Customer first = service.create(command("CUST-001", "华东金属"), OPERATOR);
        service.create(command("CUST-002", "南方贸易"), OPERATOR);
        service.disable(first.getId(), OPERATOR);

        PageResult<Customer> enabled = service.search(new CustomerQuery(null, ArchiveStatus.ENABLED, 1, 20));
        assertEquals(1, enabled.total());
        assertEquals("南方贸易", enabled.items().get(0).getName());
        PageResult<Customer> disabled = service.search(new CustomerQuery(null, ArchiveStatus.DISABLED, 1, 20));
        assertEquals(1, disabled.total());
    }

    @Test
    void 审计字段完整() {
        Customer customer = service.create(command(null, "华东金属"), OPERATOR);
        assertEquals(OPERATOR, customer.getCreatedBy());
        assertNotNull(customer.getCreatedAt());
        assertEquals(OPERATOR, customer.getUpdatedBy());
        assertNotNull(customer.getUpdatedAt());
    }
}

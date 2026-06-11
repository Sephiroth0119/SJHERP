package com.sjherp.domain.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;
import com.sjherp.domain.partner.InMemoryPartnerFixtures.InMemorySupplierRepository;

/**
 * 供应商档案领域服务测试：自动编号、编码唯一、必填校验、启停规则。
 */
class SupplierServiceTest {

    private static final String OPERATOR = "tester";

    /** 固定时钟：2026-06，自动编号应为 SUP-202606-XXXX */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T08:00:00Z"), ZoneOffset.UTC);

    private InMemorySupplierRepository supplierRepository;
    private SupplierService service;

    @BeforeEach
    void setUp() {
        supplierRepository = new InMemorySupplierRepository();
        service = new SupplierService(supplierRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK));
    }

    private SupplierCommand command(String code, String name) {
        return new SupplierCommand(code, name, "王五", "13700000003", "广州市天河区",
                "91440000MA5EX0000Y", SettlementMethod.MONTHLY);
    }

    @Test
    void 编码为空时自动编号_SUP前缀年月序号() {
        Supplier first = service.create(command(null, "不锈钢材料厂"), OPERATOR);
        Supplier second = service.create(command("", "五金配件厂"), OPERATOR);
        assertEquals("SUP-202606-0001", first.getCode());
        assertEquals("SUP-202606-0002", second.getCode());
        assertNotNull(first.getId());
        assertEquals(ArchiveStatus.ENABLED, first.getStatus());
    }

    @Test
    void 手填编码可用_重复被拒绝() {
        service.create(command("SUPP-001", "不锈钢材料厂"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(command("SUPP-001", "山寨材料厂"), OPERATOR));
        assertTrue(e.getMessage().contains("已存在"));
    }

    @Test
    void 更新可改编码_与他人重复被拒绝_与自己相同放行() {
        Supplier first = service.create(command("SUPP-001", "不锈钢材料厂"), OPERATOR);
        service.create(command("SUPP-002", "五金配件厂"), OPERATOR);

        // 改成他人编码 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.update(first.getId(), command("SUPP-002", "不锈钢材料厂"), OPERATOR));
        // 编码不变只改名 → 放行
        Supplier updated = service.update(first.getId(), command("SUPP-001", "不锈钢材料集团"), OPERATOR);
        assertEquals("不锈钢材料集团", updated.getName());
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
        SupplierCommand cmd = new SupplierCommand(null, "不锈钢材料厂", null, null, null, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd, OPERATOR));
        assertTrue(e.getMessage().contains("结算方式"));
    }

    @Test
    void 启停规则_停用再启用_重复操作被拒绝() {
        Supplier supplier = service.create(command(null, "不锈钢材料厂"), OPERATOR);
        long id = supplier.getId();

        Supplier disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());
        assertEquals("boss", disabled.getUpdatedBy());
        // 重复停用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.disable(id, "boss"));

        Supplier enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
        // 重复启用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.enable(id, OPERATOR));
    }

    @Test
    void 查询不存在的供应商抛404异常() {
        assertThrows(PartnerNotFoundException.class, () -> service.get(999L));
    }

    @Test
    void 分页关键字查询_匹配编码名称联系人电话() {
        service.create(command("SUPP-001", "不锈钢材料厂"), OPERATOR);
        service.create(new SupplierCommand("SUPP-002", "五金配件厂", "赵六", "13600000004",
                null, null, SettlementMethod.PREPAID), OPERATOR);

        PageResult<Supplier> byName = service.search(new SupplierQuery("不锈钢", null, 1, 20));
        assertEquals(1, byName.total());
        PageResult<Supplier> byCode = service.search(new SupplierQuery("SUPP-002", null, 1, 20));
        assertEquals(1, byCode.total());
        PageResult<Supplier> byContact = service.search(new SupplierQuery("赵六", null, 1, 20));
        assertEquals(1, byContact.total());
        PageResult<Supplier> all = service.search(new SupplierQuery(null, null, 1, 20));
        assertEquals(2, all.total());
    }

    @Test
    void 分页关键字查询_可按状态过滤() {
        Supplier first = service.create(command("SUPP-001", "不锈钢材料厂"), OPERATOR);
        service.create(command("SUPP-002", "五金配件厂"), OPERATOR);
        service.disable(first.getId(), OPERATOR);

        PageResult<Supplier> enabled = service.search(new SupplierQuery(null, ArchiveStatus.ENABLED, 1, 20));
        assertEquals(1, enabled.total());
        assertEquals("五金配件厂", enabled.items().get(0).getName());
        PageResult<Supplier> disabled = service.search(new SupplierQuery(null, ArchiveStatus.DISABLED, 1, 20));
        assertEquals(1, disabled.total());
    }

    @Test
    void 审计字段完整() {
        Supplier supplier = service.create(command(null, "不锈钢材料厂"), OPERATOR);
        assertEquals(OPERATOR, supplier.getCreatedBy());
        assertNotNull(supplier.getCreatedAt());
        assertEquals(OPERATOR, supplier.getUpdatedBy());
        assertNotNull(supplier.getUpdatedAt());
    }
}

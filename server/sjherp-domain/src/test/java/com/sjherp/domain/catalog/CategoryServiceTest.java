package com.sjherp.domain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.catalog.InMemoryCatalogFixtures.InMemoryCategoryRepository;
import com.sjherp.domain.catalog.InMemoryCatalogFixtures.InMemoryProductRepository;

/**
 * 类目树形层级校验与删除保护测试。
 */
class CategoryServiceTest {

    private static final String OPERATOR = "tester";

    private InMemoryCategoryRepository categoryRepository;
    private InMemoryProductRepository productRepository;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = new InMemoryCategoryRepository();
        productRepository = new InMemoryProductRepository();
        service = new CategoryService(categoryRepository, productRepository);
    }

    @Test
    void 三层以内可正常创建_层级逐层加一() {
        Category l1 = service.create("饮料", null, OPERATOR);
        Category l2 = service.create("碳酸饮料", l1.getId(), OPERATOR);
        Category l3 = service.create("可乐", l2.getId(), OPERATOR);
        assertEquals(1, l1.getLevel());
        assertEquals(2, l2.getLevel());
        assertEquals(3, l3.getLevel());
    }

    @Test
    void 第四层被拒绝() {
        Category l1 = service.create("饮料", null, OPERATOR);
        Category l2 = service.create("碳酸饮料", l1.getId(), OPERATOR);
        Category l3 = service.create("可乐", l2.getId(), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("罐装可乐", l3.getId(), OPERATOR));
        assertTrue(e.getMessage().contains("3 层"));
    }

    @Test
    void 父类目不存在被拒绝() {
        assertThrows(CatalogNotFoundException.class,
                () -> service.create("孤儿类目", 999L, OPERATOR));
    }

    @Test
    void 名称全局唯一_重名被拒绝_重命名放过自己() {
        Category c = service.create("饮料", null, OPERATOR);
        assertThrows(IllegalArgumentException.class, () -> service.create("饮料", null, OPERATOR));
        // 重命名为自己当前名称：允许（excludeId 放过自己）
        service.rename(c.getId(), "饮料", OPERATOR);
        assertEquals("饮料", service.get(c.getId()).getName());
    }

    @Test
    void 有子类目不可删除() {
        Category parent = service.create("饮料", null, OPERATOR);
        service.create("碳酸饮料", parent.getId(), OPERATOR);
        assertThrows(IllegalArgumentException.class, () -> service.delete(parent.getId(), OPERATOR));
    }

    @Test
    void 被商品引用不可删除() {
        Category category = service.create("饮料", null, OPERATOR);
        Product product = new Product("SKU-1", "可乐", null, category.getId(), 1L,
                null, null, List.of(new UnitConversion(2L, new BigDecimal("12"))), OPERATOR);
        productRepository.save(product);
        assertThrows(IllegalArgumentException.class, () -> service.delete(category.getId(), OPERATOR));
    }

    @Test
    void 无引用可删除() {
        Category category = service.create("饮料", null, OPERATOR);
        service.delete(category.getId(), OPERATOR);
        assertThrows(CatalogNotFoundException.class, () -> service.get(category.getId()));
    }

    @Test
    void 名称为空被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> service.create("  ", null, OPERATOR));
    }
}

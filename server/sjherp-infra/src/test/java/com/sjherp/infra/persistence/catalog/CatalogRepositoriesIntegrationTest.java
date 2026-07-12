package com.sjherp.infra.persistence.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.catalog.Category;
import com.sjherp.domain.catalog.CategoryService;
import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * catalog 三仓储（unit / category / product）真实 MySQL 最小往返测试（X-2）：
 * insert → findById →（product 另验 search 一条路径），不求穷尽查询分支。
 */
class CatalogRepositoriesIntegrationTest extends MySqlContainerTestBase {

    private final JdbcUnitRepository unitRepository = new JdbcUnitRepository(jdbc);
    private final JdbcCategoryRepository categoryRepository = new JdbcCategoryRepository(jdbc);
    private final JdbcProductRepository productRepository = new JdbcProductRepository(jdbc);

    @Test
    void 单位_保存后按id与名称读回() {
        String name = "瓶" + uniqueSuffix();
        Unit unit = new Unit(name, 0, "tester");

        unitRepository.save(unit);

        assertThat(unit.getId()).isNotNull();
        Optional<Unit> found = unitRepository.findById(unit.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo(name);
        assertThat(found.get().getPrecision()).isZero();
        assertThat(found.get().getCreatedBy()).isEqualTo("tester");
        assertThat(unitRepository.findByName(name)).isPresent();
    }

    @Test
    void 类目_经领域服务创建后读回() {
        // Category 新建构造器为包私有，按领域约定经 CategoryService 创建（层级由服务计算）
        CategoryService service = new CategoryService(categoryRepository, productRepository);
        String name = "类目" + uniqueSuffix();

        Category category = service.create(name, null, "tester");

        assertThat(category.getId()).isNotNull();
        Optional<Category> found = categoryRepository.findById(category.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo(name);
        assertThat(found.get().getLevel()).isEqualTo(1);
        assertThat(found.get().getParentId()).isNull();
        assertThat(categoryRepository.existsByParentId(category.getId())).isFalse();
    }

    @Test
    void 商品_含换算表保存后读回并可按编码搜索() {
        Unit baseUnit = new Unit("个" + uniqueSuffix(), 0, "tester");
        Unit boxUnit = new Unit("箱" + uniqueSuffix(), 0, "tester");
        unitRepository.save(baseUnit);
        unitRepository.save(boxUnit);

        String code = "P" + uniqueSuffix();
        Product product = new Product(code, "测试商品", "500ml", null, baseUnit.getId(),
                "6901234567890", "集成测试数据",
                List.of(new UnitConversion(boxUnit.getId(), new BigDecimal("12"))),
                InventoryCategory.RAW_MATERIAL, "tester");
        productRepository.save(product);

        assertThat(product.getId()).isNotNull();
        Optional<Product> found = productRepository.findById(product.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(code);
        assertThat(found.get().getBaseUnitId()).isEqualTo(baseUnit.getId());
        assertThat(found.get().getInventoryCategory()).isEqualTo(InventoryCategory.RAW_MATERIAL);
        assertThat(found.get().getUnitConversions()).hasSize(1);
        assertThat(found.get().getUnitConversions().get(0).unitId()).isEqualTo(boxUnit.getId());
        // DECIMAL 读回 scale 可能不同，按数值比较
        assertThat(found.get().getUnitConversions().get(0).rate())
                .isEqualByComparingTo(new BigDecimal("12"));

        assertThat(productRepository.existsByCode(code)).isTrue();
        PageResult<Product> page = productRepository.search(new ProductQuery(code, null, 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).getCode()).isEqualTo(code);
        assertThat(page.items().get(0).getInventoryCategory()).isEqualTo(InventoryCategory.RAW_MATERIAL);
    }
}

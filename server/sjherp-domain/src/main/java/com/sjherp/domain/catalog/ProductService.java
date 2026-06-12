package com.sjherp.domain.catalog;

import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 商品档案领域服务（所有商品写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>编码唯一：手填编码经仓储查重；不填则按 SKU-年月-序号 自动编号
 *       （复用 M2-T01 的 {@link DocumentNumberGenerator}，序号供给为数据库实现，重启不重号）；</li>
 *   <li>引用完整性：基本单位必须存在，类目（若填）必须存在，换算单位必须存在；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（见 {@link Product#enable}/{@link Product#disable}）。</li>
 * </ul>
 */
public class ProductService {

    /** 商品自动编号规则：SKU-202606-0001 */
    static final DocumentNumberRule SKU_RULE = DocumentNumberRule.of("SKU");

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final DocumentNumberGenerator numberGenerator;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository,
                          UnitRepository unitRepository, DocumentNumberGenerator numberGenerator) {
        this.productRepository = Objects.requireNonNull(productRepository);
        this.categoryRepository = Objects.requireNonNull(categoryRepository);
        this.unitRepository = Objects.requireNonNull(unitRepository);
        this.numberGenerator = Objects.requireNonNull(numberGenerator);
    }

    /** 创建商品：编码为空则自动编号；落库后回填 id */
    @Audited(action = "product.create", targetType = "product")
    public Product create(ProductCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        validateReferences(command);

        String code = (command.code() == null || command.code().isBlank())
                ? numberGenerator.generate(SKU_RULE)
                : command.code().strip();
        if (productRepository.existsByCode(code)) {
            throw new IllegalArgumentException("商品编码已存在: " + code);
        }

        Product product = new Product(code, command.name(), command.spec(), command.categoryId(),
                command.baseUnitId(), command.barcode(), command.remark(),
                command.unitConversions(), operator);
        productRepository.save(product);
        return product;
    }

    /** 更新商品：编码可改但仍须唯一（更新时编码必填，不触发自动编号） */
    @Audited(action = "product.update", targetType = "product")
    public Product update(long id, ProductCommand command, String operator) {
        Objects.requireNonNull(command, "command 不能为空");
        Product product = get(id);
        validateReferences(command);

        if (command.code() == null || command.code().isBlank()) {
            throw new IllegalArgumentException("更新商品时编码不能为空");
        }
        String code = command.code().strip();
        if (!code.equals(product.getCode()) && productRepository.existsByCode(code)) {
            throw new IllegalArgumentException("商品编码已存在: " + code);
        }

        product.update(code, command.name(), command.spec(), command.categoryId(),
                command.baseUnitId(), command.barcode(), command.remark(),
                command.unitConversions(), operator);
        productRepository.save(product);
        return product;
    }

    /** 启用商品 */
    @Audited(action = "product.enable", targetType = "product")
    public Product enable(long id, String operator) {
        Product product = get(id);
        product.enable(operator);
        productRepository.save(product);
        return product;
    }

    /**
     * 停用商品。
     *
     * <p>TODO（M3 引入单据后补齐）：停用前的引用约束检查——存在未完结单据
     * （在途采购/销售订单、非零库存余额等）时应给出阻断或警告策略；本期
     * 仅做状态切换，新单据不得引用停用商品的约束由各单据领域服务校验。
     */
    @Audited(action = "product.disable", targetType = "product")
    public Product disable(long id, String operator) {
        Product product = get(id);
        product.disable(operator);
        productRepository.save(product);
        return product;
    }

    /** 按 id 查询（不存在抛 CatalogNotFoundException → API 404） */
    public Product get(long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> CatalogNotFoundException.product(id));
    }

    /** 分页查询（关键字模糊匹配编码/名称/条码） */
    public PageResult<Product> search(ProductQuery query) {
        return productRepository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    /** 引用完整性：基本单位/类目/换算单位必须存在 */
    private void validateReferences(ProductCommand command) {
        if (command.baseUnitId() == null) {
            throw new IllegalArgumentException("基本单位不能为空");
        }
        unitRepository.findById(command.baseUnitId())
                .orElseThrow(() -> CatalogNotFoundException.unit(command.baseUnitId()));
        if (command.categoryId() != null) {
            categoryRepository.findById(command.categoryId())
                    .orElseThrow(() -> CatalogNotFoundException.category(command.categoryId()));
        }
        List<UnitConversion> conversions = command.unitConversions();
        if (conversions != null) {
            for (UnitConversion conversion : conversions) {
                unitRepository.findById(conversion.unitId())
                        .orElseThrow(() -> CatalogNotFoundException.unit(conversion.unitId()));
            }
        }
    }
}

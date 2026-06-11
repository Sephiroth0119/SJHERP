package com.sjherp.domain.catalog;

import java.util.List;
import java.util.Objects;

/**
 * 商品类目领域服务。
 *
 * <p>业务规则：树形最多 {@value Category#MAX_LEVEL} 层（小企业从简）；
 * 名称全局唯一；删除有引用保护（有子类目或被商品引用则拒绝）。
 */
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = Objects.requireNonNull(categoryRepository);
        this.productRepository = Objects.requireNonNull(productRepository);
    }

    /** 创建类目：层级 = 父层级 + 1，超过上限拒绝 */
    public Category create(String name, Long parentId, String operator) {
        requireNameAvailable(name, null);
        int level = 1;
        if (parentId != null) {
            Category parent = get(parentId);
            level = parent.getLevel() + 1;
            if (level > Category.MAX_LEVEL) {
                throw new IllegalArgumentException(
                        "类目最多 " + Category.MAX_LEVEL + " 层，不能在 [" + parent.getName() + "] 下再建子类目");
            }
        }
        Category category = new Category(name, parentId, level, operator);
        categoryRepository.save(category);
        return category;
    }

    /** 重命名（父类目与层级不可变更，见 {@link Category} 类注释） */
    public Category rename(long id, String name, String operator) {
        Category category = get(id);
        requireNameAvailable(name, id);
        category.rename(name, operator);
        categoryRepository.save(category);
        return category;
    }

    /** 删除：有子类目或被商品引用则拒绝（档案样板中类目是唯一允许物理删除的，因其不被单据直接引用） */
    public void delete(long id) {
        Category category = get(id);
        if (categoryRepository.existsByParentId(id)) {
            throw new IllegalArgumentException("类目[" + category.getName() + "] 存在子类目，不可删除");
        }
        if (productRepository.existsByCategoryId(id)) {
            throw new IllegalArgumentException("类目[" + category.getName() + "] 已被商品引用，不可删除");
        }
        categoryRepository.deleteById(id);
    }

    public Category get(long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> CatalogNotFoundException.category(id));
    }

    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    /** 名称全局唯一（excludeId：更新自身时放过自己） */
    private void requireNameAvailable(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("类目名称不能为空");
        }
        categoryRepository.findByName(name.strip()).ifPresent(existing -> {
            if (excludeId == null || !existing.getId().equals(excludeId)) {
                throw new IllegalArgumentException("类目名称已存在: " + name.strip());
            }
        });
    }
}

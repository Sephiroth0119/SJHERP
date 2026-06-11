package com.sjherp.domain.catalog;

import java.util.List;
import java.util.Objects;

/**
 * 计量单位领域服务。
 *
 * <p>业务规则：名称唯一；删除有引用保护（被商品用作基本单位或换算单位则拒绝）。
 */
public class UnitService {

    private final UnitRepository unitRepository;
    private final ProductRepository productRepository;

    public UnitService(UnitRepository unitRepository, ProductRepository productRepository) {
        this.unitRepository = Objects.requireNonNull(unitRepository);
        this.productRepository = Objects.requireNonNull(productRepository);
    }

    public Unit create(String name, int precision, String operator) {
        requireNameAvailable(name, null);
        Unit unit = new Unit(name, precision, operator);
        unitRepository.save(unit);
        return unit;
    }

    public Unit update(long id, String name, int precision, String operator) {
        Unit unit = get(id);
        requireNameAvailable(name, id);
        unit.update(name, precision, operator);
        unitRepository.save(unit);
        return unit;
    }

    /** 删除：被商品引用（基本单位或换算单位）则拒绝 */
    public void delete(long id) {
        Unit unit = get(id);
        if (productRepository.existsByUnitId(id)) {
            throw new IllegalArgumentException("单位[" + unit.getName() + "] 已被商品引用，不可删除");
        }
        unitRepository.deleteById(id);
    }

    public Unit get(long id) {
        return unitRepository.findById(id)
                .orElseThrow(() -> CatalogNotFoundException.unit(id));
    }

    public List<Unit> findAll() {
        return unitRepository.findAll();
    }

    private void requireNameAvailable(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("单位名称不能为空");
        }
        unitRepository.findByName(name.strip()).ifPresent(existing -> {
            if (excludeId == null || !existing.getId().equals(excludeId)) {
                throw new IllegalArgumentException("单位名称已存在: " + name.strip());
            }
        });
    }
}

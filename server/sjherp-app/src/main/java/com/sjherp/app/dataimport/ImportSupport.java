package com.sjherp.app.dataimport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 包私有解析助手（M2-T09）：仓库/商品/单位按名称或编码解析为档案，
 * 提供 BigDecimal 解析与结算方式解析等工具。
 *
 * <p>复用与工具包 {@code InventoryToolSupport} 同口径的解析逻辑（编码精确优先→名称精确→
 * 单条模糊命中→失败），但工具支持类为包私有不可跨包复用，故本包独立一份
 * （与现有 InventoryToolSupport/PurchaseToolSupport 惯例一致）。
 *
 * <p>解析失败抛 {@link IllegalArgumentException}，调用方（ImportService）捕获后
 * 转换为 {@link ImportDtos.RowFailure} 并收集，最终触发整体回滚。
 */
final class ImportSupport {

    /** 候选列举上限（同工具层 InventoryToolSupport） */
    private static final int MAX_CANDIDATES = 10;

    private ImportSupport() {
    }

    /**
     * 仓库按名称或编码解析（精确命中优先，多命中/零命中均抛 IllegalArgumentException）。
     */
    static Warehouse resolveWarehouse(WarehouseService warehouseService, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("仓库名称或编码不能为空");
        }
        String keyword = text.strip();
        List<Warehouse> matched = warehouseService
                .search(new WarehouseQuery(keyword, null, 1, MAX_CANDIDATES)).items();
        return pick(matched, keyword, "仓库", Warehouse::getCode, Warehouse::getName,
                () -> warehouseService.search(new WarehouseQuery(null, null, 1, MAX_CANDIDATES)).items());
    }

    /**
     * 商品按名称或编码解析（精确命中优先，多命中/零命中均抛 IllegalArgumentException）。
     */
    static Product resolveProduct(ProductService productService, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("商品名称或编码不能为空");
        }
        String keyword = text.strip();
        List<Product> matched = productService
                .search(new ProductQuery(keyword, null, 1, MAX_CANDIDATES)).items();
        return pick(matched, keyword, "商品", Product::getCode, Product::getName,
                () -> productService.search(new ProductQuery(null, null, 1, MAX_CANDIDATES)).items());
    }

    /**
     * 计量单位按名称精确匹配（走 {@code UnitService.findAll()} 内存匹配，单位量小）。
     *
     * @throws IllegalArgumentException 单位名称为空或未找到
     */
    static Unit resolveUnit(UnitService unitService, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("基本单位不能为空");
        }
        String trimmed = name.strip();
        return unitService.findAll().stream()
                .filter(u -> u.getName().equals(trimmed))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "基本单位「" + trimmed + "」在系统中不存在，请先在「计量单位」里建好"));
    }

    /**
     * 结算方式解析（支持枚举值 MONTHLY/CASH/PREPAID 及中文 月结/现结/预付）。
     *
     * @throws IllegalArgumentException 非法结算方式
     */
    static SettlementMethod parseSettlementMethod(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("结算方式不能为空（月结/现结/预付 或 MONTHLY/CASH/PREPAID）");
        }
        String v = value.strip();
        // 先尝试枚举名
        try {
            return SettlementMethod.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // 尝试中文标签
        }
        return switch (v) {
            case "月结" -> SettlementMethod.MONTHLY;
            case "现结" -> SettlementMethod.CASH;
            case "预付" -> SettlementMethod.PREPAID;
            default -> throw new IllegalArgumentException(
                    "结算方式仅支持 月结/现结/预付（或 MONTHLY/CASH/PREPAID）：" + v);
        };
    }

    /**
     * 字符串 → BigDecimal（导入行值严禁经 double 解析，保证精度）。
     *
     * @throws IllegalArgumentException 格式非法
     */
    static BigDecimal decimal(String text, String columnName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(columnName + "不能为空");
        }
        // 移除千分位（Excel 格式化后可能带千分位）
        String cleaned = text.strip().replace(",", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(columnName + "数值格式不合法：" + text);
        }
    }

    // ---- 内部精确匹配逻辑（同 InventoryToolSupport.pick） ----

    @FunctionalInterface
    private interface CandidateSupplier<T> {
        List<T> get();
    }

    private static <T> T pick(List<T> matched, String keyword, String label,
                              Function<T, String> code, Function<T, String> name,
                              CandidateSupplier<T> fallbackCandidates) {
        // 编码精确匹配优先（编码唯一）
        List<T> byCode = matched.stream()
                .filter(item -> code.apply(item).equalsIgnoreCase(keyword)).toList();
        if (byCode.size() == 1) {
            return byCode.get(0);
        }
        // 名称精确匹配
        List<T> byName = matched.stream()
                .filter(item -> name.apply(item).equals(keyword)).toList();
        if (byName.size() == 1) {
            return byName.get(0);
        }
        // 唯一模糊命中
        if (matched.size() == 1) {
            return matched.get(0);
        }
        if (matched.isEmpty()) {
            List<T> all = fallbackCandidates.get();
            if (all.isEmpty()) {
                throw new IllegalArgumentException("系统中还没有任何" + label + "档案，请先创建" + label);
            }
            throw new IllegalArgumentException("未找到名称或编码匹配「" + keyword + "」的" + label
                    + "。系统内的" + label + "候选：" + candidates(all, code, name));
        }
        throw new IllegalArgumentException("「" + keyword + "」匹配到多个" + label + "："
                + candidates(matched, code, name) + "，请使用准确编码");
    }

    private static <T> String candidates(List<T> items, Function<T, String> code,
                                         Function<T, String> name) {
        return items.stream()
                .limit(MAX_CANDIDATES)
                .map(item -> name.apply(item) + "（" + code.apply(item) + "）")
                .collect(Collectors.joining("、"));
    }
}

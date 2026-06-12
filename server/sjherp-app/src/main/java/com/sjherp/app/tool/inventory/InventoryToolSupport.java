package com.sjherp.app.tool.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 库存类 Agent 工具公共助手（M3-T01c）：仓库/商品按<b>名称或编码</b>解析为档案
 * （用户在聊天里说"一号仓的不锈钢板"，模型无从得知 id）。
 *
 * <p>解析规则（宁可让用户多说一句，不可猜错对象）：
 * <ol>
 *   <li>按关键字经领域服务分页查询（最多 10 条）；</li>
 *   <li>编码精确匹配（忽略大小写）优先，其次名称精确匹配；</li>
 *   <li>无精确匹配但仅一条模糊命中 → 用之；</li>
 *   <li>多条命中 → 解析失败并列出候选（编码 + 名称），引导用户指明编码；</li>
 *   <li>零命中 → 解析失败并列出系统内前若干条档案作候选（小企业档案量小）。</li>
 * </ol>
 */
final class InventoryToolSupport {

    /** 候选列举上限（与查询类工具返回上限一致） */
    private static final int MAX_CANDIDATES = 10;

    private InventoryToolSupport() {
    }

    /** 解析结果：value 与 error 二选一（error 非空即失败，文案面向 LLM 回灌） */
    record Resolution<T>(T value, String error) {

        static <T> Resolution<T> ok(T value) {
            return new Resolution<>(value, null);
        }

        static <T> Resolution<T> fail(String error) {
            return new Resolution<>(null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    /** 仓库按名称或编码解析 */
    static Resolution<Warehouse> resolveWarehouse(WarehouseService warehouseService, String text) {
        if (text == null || text.isBlank()) {
            return Resolution.fail("仓库名称或编码不能为空");
        }
        String keyword = text.strip();
        List<Warehouse> matched = warehouseService
                .search(new WarehouseQuery(keyword, null, 1, MAX_CANDIDATES)).items();
        return pick(matched, keyword, "仓库", Warehouse::getCode, Warehouse::getName,
                () -> warehouseService.search(new WarehouseQuery(null, null, 1, MAX_CANDIDATES)).items());
    }

    /** 商品按名称或编码解析 */
    static Resolution<Product> resolveProduct(ProductService productService, String text) {
        if (text == null || text.isBlank()) {
            return Resolution.fail("商品名称或编码不能为空");
        }
        String keyword = text.strip();
        List<Product> matched = productService
                .search(new ProductQuery(keyword, null, 1, MAX_CANDIDATES)).items();
        return pick(matched, keyword, "商品", Product::getCode, Product::getName,
                () -> productService.search(new ProductQuery(null, null, 1, MAX_CANDIDATES)).items());
    }

    private static <T> Resolution<T> pick(List<T> matched, String keyword, String label,
                                          Function<T, String> code, Function<T, String> name,
                                          CandidateSupplier<T> fallbackCandidates) {
        // 编码精确匹配优先（编码唯一），其次名称精确匹配
        List<T> byCode = matched.stream()
                .filter(item -> code.apply(item).equalsIgnoreCase(keyword)).toList();
        if (byCode.size() == 1) {
            return Resolution.ok(byCode.get(0));
        }
        List<T> byName = matched.stream()
                .filter(item -> name.apply(item).equals(keyword)).toList();
        if (byName.size() == 1) {
            return Resolution.ok(byName.get(0));
        }
        if (matched.size() == 1) {
            return Resolution.ok(matched.get(0));
        }
        if (matched.isEmpty()) {
            List<T> all = fallbackCandidates.get();
            if (all.isEmpty()) {
                return Resolution.fail("系统中还没有任何" + label + "档案，请先创建" + label);
            }
            return Resolution.fail("未找到名称或编码匹配「" + keyword + "」的" + label
                    + "。系统内的" + label + "候选：" + candidates(all, code, name)
                    + "。请让用户从中选择或给出准确编码");
        }
        return Resolution.fail("「" + keyword + "」匹配到多个" + label + "："
                + candidates(matched, code, name) + "。请让用户指明具体编码后重试");
    }

    private static <T> String candidates(List<T> items, Function<T, String> code, Function<T, String> name) {
        return items.stream()
                .limit(MAX_CANDIDATES)
                .map(item -> name.apply(item) + "（" + code.apply(item) + "）")
                .collect(Collectors.joining("、"));
    }

    /** 参数值 → BigDecimal（协议约定 decimal 用字符串承载；非法值返回 null 由调用方报错） */
    static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值格式不合法: " + text);
        }
    }

    @FunctionalInterface
    interface CandidateSupplier<T> {
        List<T> get();
    }
}

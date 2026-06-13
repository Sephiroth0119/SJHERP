package com.sjherp.app.tool.sales;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerQuery;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 销售类 Agent 工具公共助手（M3-T08）：客户/商品按<b>名称或编码</b>解析为档案
 * （用户在聊天里说「卖给张三百货 5 箱可乐」，模型无从得知 id）。
 *
 * <p>解析规则与库存工具 {@code InventoryToolSupport} 同口径（宁可让用户多说一句，不可猜错对象）：
 * 编码精确匹配优先、其次名称精确匹配、唯一模糊命中、多命中列候选、零命中列系统候选。
 */
final class SalesToolSupport {

    /** 候选列举上限 */
    private static final int MAX_CANDIDATES = 10;

    private SalesToolSupport() {
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

    /** 客户按名称或编码解析 */
    static Resolution<Customer> resolveCustomer(CustomerService customerService, String text) {
        if (text == null || text.isBlank()) {
            return Resolution.fail("客户名称或编码不能为空");
        }
        String keyword = text.strip();
        List<Customer> matched = customerService
                .search(new CustomerQuery(keyword, null, 1, MAX_CANDIDATES)).items();
        return pick(matched, keyword, "客户", Customer::getCode, Customer::getName,
                () -> customerService.search(new CustomerQuery(null, null, 1, MAX_CANDIDATES)).items());
    }

    /** 仓库按名称或编码解析（create_sales_delivery 出库仓解析用） */
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

    /** 参数值 → BigDecimal（协议约定 decimal 用字符串承载；非法值抛 IllegalArgumentException） */
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

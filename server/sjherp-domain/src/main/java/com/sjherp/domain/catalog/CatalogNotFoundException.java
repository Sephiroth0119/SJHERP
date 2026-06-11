package com.sjherp.domain.catalog;

/**
 * 档案不存在异常（按 id 找不到商品/类目/单位时抛出，API 层映射为 404）。
 */
public class CatalogNotFoundException extends RuntimeException {

    public CatalogNotFoundException(String message) {
        super(message);
    }

    public static CatalogNotFoundException product(long id) {
        return new CatalogNotFoundException("商品不存在: id=" + id);
    }

    public static CatalogNotFoundException category(long id) {
        return new CatalogNotFoundException("类目不存在: id=" + id);
    }

    public static CatalogNotFoundException unit(long id) {
        return new CatalogNotFoundException("计量单位不存在: id=" + id);
    }
}

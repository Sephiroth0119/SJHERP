package com.sjherp.domain.warehouse;

/**
 * 仓库档案不存在异常（按 id 找不到仓库时抛出，API 层映射为 404）。
 */
public class WarehouseNotFoundException extends RuntimeException {

    public WarehouseNotFoundException(String message) {
        super(message);
    }

    public static WarehouseNotFoundException warehouse(long id) {
        return new WarehouseNotFoundException("仓库不存在: id=" + id);
    }
}

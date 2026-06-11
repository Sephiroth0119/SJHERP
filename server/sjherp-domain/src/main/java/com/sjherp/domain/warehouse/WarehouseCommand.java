package com.sjherp.domain.warehouse;

/**
 * 仓库创建/更新命令（领域服务入参，字段级格式校验在 {@link Warehouse} 内完成）。
 *
 * @param code            仓库编码；创建时为空表示自动编号（WH-年月-序号）
 * @param name            仓库名称（必填）
 * @param address         地址，可空
 * @param manager         负责人，可空
 * @param locationEnabled 是否启用库位管理（本期仅字段预留，库位表留 M3）；null 视为 false
 */
public record WarehouseCommand(String code, String name, String address, String manager,
                               Boolean locationEnabled) {

    /** 库位开关空值收敛：未填视为不启用 */
    public boolean locationEnabledOrDefault() {
        return locationEnabled != null && locationEnabled;
    }
}

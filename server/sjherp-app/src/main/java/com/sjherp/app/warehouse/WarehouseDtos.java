package com.sjherp.app.warehouse;

import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * warehouse API 的请求/响应 DTO（Bean Validation 做字段级格式校验，
 * 业务规则校验仍在领域层——校验重复无害，绕过有害）。
 */
public final class WarehouseDtos {

    private WarehouseDtos() {
    }

    /** 仓库创建/更新请求（创建时 code 为空表示自动编号 WH-年月-序号；locationEnabled 未填视为 false） */
    public record WarehouseRequest(
            @Size(max = 50, message = "仓库编码不能超过 50 个字符") String code,
            @NotBlank(message = "仓库名称不能为空") @Size(max = 200, message = "仓库名称不能超过 200 个字符") String name,
            @Size(max = 255, message = "地址不能超过 255 个字符") String address,
            @Size(max = 64, message = "负责人不能超过 64 个字符") String manager,
            Boolean locationEnabled) {

        WarehouseCommand toCommand() {
            return new WarehouseCommand(code, name, address, manager, locationEnabled);
        }
    }

    public record WarehouseResponse(long id, String code, String name, String address, String manager,
                                    boolean locationEnabled, String status,
                                    String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static WarehouseResponse from(Warehouse warehouse) {
            return new WarehouseResponse(
                    warehouse.getId(),
                    warehouse.getCode(),
                    warehouse.getName(),
                    warehouse.getAddress(),
                    warehouse.getManager(),
                    warehouse.isLocationEnabled(),
                    warehouse.getStatus().name(),
                    warehouse.getCreatedBy(),
                    warehouse.getCreatedAt().toString(),
                    warehouse.getUpdatedBy(),
                    warehouse.getUpdatedAt().toString());
        }
    }

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<WarehouseResponse> fromWarehouses(PageResult<Warehouse> result) {
            return new PageResponse<>(
                    result.items().stream().map(WarehouseResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}

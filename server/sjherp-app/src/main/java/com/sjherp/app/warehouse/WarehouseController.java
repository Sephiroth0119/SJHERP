package com.sjherp.app.warehouse;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.warehouse.WarehouseDtos.PageResponse;
import com.sjherp.app.warehouse.WarehouseDtos.WarehouseRequest;
import com.sjherp.app.warehouse.WarehouseDtos.WarehouseResponse;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

import jakarta.validation.Valid;

/**
 * 仓库档案 API：
 * <ul>
 *   <li>POST   /api/warehouse/warehouses → 201 仓库（code 留空自动编号 WH-年月-序号）</li>
 *   <li>PUT    /api/warehouse/warehouses/{id} → 200 仓库</li>
 *   <li>POST   /api/warehouse/warehouses/{id}/enable|disable → 200 仓库（启停）</li>
 *   <li>GET    /api/warehouse/warehouses?keyword=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/warehouse/warehouses/{id} → 200 仓库，不存在 404 {"error"}</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link WarehouseExceptionHandler}）。
 */
@RestController
@RequestMapping("/api/warehouse/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    /** 创建仓库（code 留空自动编号） */
    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        WarehouseResponse body = WarehouseResponse.from(
                warehouseService.create(request.toCommand(), WarehouseApiSupport.OPERATOR));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新仓库（整体更新；更新时编码必填） */
    @PutMapping("/{id}")
    public WarehouseResponse update(@PathVariable long id, @Valid @RequestBody WarehouseRequest request) {
        return WarehouseResponse.from(
                warehouseService.update(id, request.toCommand(), WarehouseApiSupport.OPERATOR));
    }

    /** 启用仓库 */
    @PostMapping("/{id}/enable")
    public WarehouseResponse enable(@PathVariable long id) {
        return WarehouseResponse.from(warehouseService.enable(id, WarehouseApiSupport.OPERATOR));
    }

    /** 停用仓库（停用后新单据不得引用，历史数据不受影响） */
    @PostMapping("/{id}/disable")
    public WarehouseResponse disable(@PathVariable long id) {
        return WarehouseResponse.from(warehouseService.disable(id, WarehouseApiSupport.OPERATOR));
    }

    /** 分页列表（keyword 模糊匹配编码/名称/负责人；status 可选 ENABLED/DISABLED） */
    @GetMapping
    public PageResponse<WarehouseResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromWarehouses(
                warehouseService.search(new WarehouseQuery(keyword, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public WarehouseResponse get(@PathVariable long id) {
        return WarehouseResponse.from(warehouseService.get(id));
    }

    /** 状态过滤参数解析（非法值给出友好 400 信息，不透出枚举内部异常） */
    private static ArchiveStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ArchiveStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 仅支持 ENABLED / DISABLED: " + status);
        }
    }
}

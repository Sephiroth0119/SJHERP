package com.sjherp.app.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.catalog.CatalogDtos.UnitRequest;
import com.sjherp.app.catalog.CatalogDtos.UnitResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.catalog.UnitService;

import jakarta.validation.Valid;

/**
 * 计量单位 API：
 * <ul>
 *   <li>POST   /api/catalog/units → 201 单位</li>
 *   <li>PUT    /api/catalog/units/{id} → 200 单位</li>
 *   <li>DELETE /api/catalog/units/{id} → 204（被商品引用则 400）</li>
 *   <li>GET    /api/catalog/units → 200 全量列表</li>
 *   <li>GET    /api/catalog/units/{id} → 200 单位，不存在 404 {"error"}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/catalog/units")
public class UnitController {

    private final UnitService unitService;

    public UnitController(UnitService unitService) {
        this.unitService = unitService;
    }

    /** 创建单位；写操作须 catalog:write（M2-T06，单位精度影响出入库舍入，仅 ADMIN/BOSS） */
    @PreAuthorize("@perm.has('catalog:write')")
    @PostMapping
    public ResponseEntity<UnitResponse> create(@Valid @RequestBody UnitRequest request) {
        UnitResponse body = UnitResponse.from(
                unitService.create(request.name(), request.precision(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新单位（精度调整只影响后续录入的舍入，不回溯历史数据） */
    @PreAuthorize("@perm.has('catalog:write')")
    @PutMapping("/{id}")
    public UnitResponse update(@PathVariable long id, @Valid @RequestBody UnitRequest request) {
        return UnitResponse.from(
                unitService.update(id, request.name(), request.precision(), CurrentUser.operator()));
    }

    /** 删除单位（被商品引用则拒绝） */
    @PreAuthorize("@perm.has('catalog:write')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        unitService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 全量列表 */
    @GetMapping
    public List<UnitResponse> findAll() {
        return unitService.findAll().stream().map(UnitResponse::from).toList();
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public UnitResponse get(@PathVariable long id) {
        return UnitResponse.from(unitService.get(id));
    }
}

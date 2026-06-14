package com.sjherp.app.production;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.production.BomDtos.BomExplosionResponse;
import com.sjherp.app.production.BomDtos.BomRequest;
import com.sjherp.app.production.BomDtos.BomResponse;
import com.sjherp.app.production.BomDtos.PageResponse;
import com.sjherp.app.config.TransactionalBomService;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.production.BillOfMaterialsQuery;

import jakarta.validation.Valid;

/**
 * BOM 档案 API（M5-T01）：
 * <ul>
 *   <li>POST   /api/production/boms → 201 BOM（新建，自动停用同产品旧启用版本）</li>
 *   <li>PUT    /api/production/boms/{id} → 200 BOM（整体更新行列表）</li>
 *   <li>POST   /api/production/boms/{id}/enable|disable → 200 BOM（启停）</li>
 *   <li>GET    /api/production/boms?productId=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/production/boms/{id} → 200 BOM，不存在 404 {"error"}</li>
 *   <li>GET    /api/production/boms/{productId}/explode?quantity=10 → 200 展开树</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link ProductionExceptionHandler}）。
 *
 * <p>权限（矩阵见 docs/权限矩阵.md）：写操作须 production:bom；查询登录即可。
 */
@RestController
@RequestMapping("/api/production/boms")
public class BomController {

    private final TransactionalBomService billOfMaterialsService;

    public BomController(TransactionalBomService billOfMaterialsService) {
        this.billOfMaterialsService = billOfMaterialsService;
    }

    /** 创建 BOM */
    @PreAuthorize("@perm.has('production:bom')")
    @PostMapping
    public ResponseEntity<BomResponse> create(@Valid @RequestBody BomRequest request) {
        BomResponse body = BomResponse.from(
                billOfMaterialsService.create(request.toCommand(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新 BOM 行列表（整体替换；header version 不可变） */
    @PreAuthorize("@perm.has('production:bom')")
    @PutMapping("/{id}")
    public BomResponse update(@PathVariable long id, @Valid @RequestBody BomRequest request) {
        return BomResponse.from(
                billOfMaterialsService.update(id, request.toCommand(), CurrentUser.operator()));
    }

    /** 启用 BOM（同事务停用同产品其他 ENABLED 版本） */
    @PreAuthorize("@perm.has('production:bom')")
    @PostMapping("/{id}/enable")
    public BomResponse enable(@PathVariable long id) {
        return BomResponse.from(billOfMaterialsService.enable(id, CurrentUser.operator()));
    }

    /** 停用 BOM */
    @PreAuthorize("@perm.has('production:bom')")
    @PostMapping("/{id}/disable")
    public BomResponse disable(@PathVariable long id) {
        return BomResponse.from(billOfMaterialsService.disable(id, CurrentUser.operator()));
    }

    /** 分页列表（productId/status 可选过滤；查询登录即可） */
    @GetMapping
    public PageResponse<BomResponse> search(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromBoms(
                billOfMaterialsService.search(
                        new BillOfMaterialsQuery(productId, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404；查询登录即可） */
    @GetMapping("/{id}")
    public BomResponse get(@PathVariable long id) {
        return BomResponse.from(billOfMaterialsService.get(id));
    }

    /**
     * BOM 展开（只读，MRP 消费入口）。
     *
     * @param productId 父件商品 id
     * @param quantity  展开起始数量（必须 &gt; 0）
     */
    @GetMapping("/{productId}/explode")
    public BomExplosionResponse explode(
            @PathVariable long productId,
            @RequestParam BigDecimal quantity) {
        return BomExplosionResponse.from(billOfMaterialsService.explode(productId, quantity));
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

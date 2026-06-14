package com.sjherp.app.production;

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

import com.sjherp.app.production.BomDtos.PageResponse;
import com.sjherp.app.production.RoutingDtos.RoutingRequest;
import com.sjherp.app.production.RoutingDtos.RoutingResponse;
import com.sjherp.app.config.TransactionalRoutingService;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.production.RoutingQuery;

import jakarta.validation.Valid;

/**
 * 工艺路线档案 API（M5-T01）：
 * <ul>
 *   <li>POST   /api/production/routings → 201 工艺路线（新建，自动停用同产品旧启用版本）</li>
 *   <li>PUT    /api/production/routings/{id} → 200 工艺路线（整体更新工序列表）</li>
 *   <li>POST   /api/production/routings/{id}/enable|disable → 200 工艺路线（启停）</li>
 *   <li>GET    /api/production/routings?productId=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/production/routings/{id} → 200 工艺路线，不存在 404 {"error"}</li>
 * </ul>
 * 错误响应与既有契约一致：404/400 均为 {"error": "..."}（见 {@link ProductionExceptionHandler}）。
 *
 * <p>权限（矩阵见 docs/权限矩阵.md）：写操作须 production:routing；查询登录即可。
 */
@RestController
@RequestMapping("/api/production/routings")
public class RoutingController {

    private final TransactionalRoutingService routingService;

    public RoutingController(TransactionalRoutingService routingService) {
        this.routingService = routingService;
    }

    /** 创建工艺路线 */
    @PreAuthorize("@perm.has('production:routing')")
    @PostMapping
    public ResponseEntity<RoutingResponse> create(@Valid @RequestBody RoutingRequest request) {
        RoutingResponse body = RoutingResponse.from(
                routingService.create(request.toCommand(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新工艺路线工序列表（整体替换） */
    @PreAuthorize("@perm.has('production:routing')")
    @PutMapping("/{id}")
    public RoutingResponse update(@PathVariable long id, @Valid @RequestBody RoutingRequest request) {
        return RoutingResponse.from(
                routingService.update(id, request.toCommand(), CurrentUser.operator()));
    }

    /** 启用工艺路线（同事务停用同产品其他 ENABLED 版本） */
    @PreAuthorize("@perm.has('production:routing')")
    @PostMapping("/{id}/enable")
    public RoutingResponse enable(@PathVariable long id) {
        return RoutingResponse.from(routingService.enable(id, CurrentUser.operator()));
    }

    /** 停用工艺路线 */
    @PreAuthorize("@perm.has('production:routing')")
    @PostMapping("/{id}/disable")
    public RoutingResponse disable(@PathVariable long id) {
        return RoutingResponse.from(routingService.disable(id, CurrentUser.operator()));
    }

    /** 分页列表（productId/status 可选过滤；查询登录即可） */
    @GetMapping
    public PageResponse<RoutingResponse> search(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return RoutingDtos.fromRoutings(
                routingService.search(
                        new RoutingQuery(productId, parseStatus(status), page, size)));
    }

    /** 按 id 查询（不存在 404；查询登录即可） */
    @GetMapping("/{id}")
    public RoutingResponse get(@PathVariable long id) {
        return RoutingResponse.from(routingService.get(id));
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

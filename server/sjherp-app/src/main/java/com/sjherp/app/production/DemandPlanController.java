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

import com.sjherp.app.config.TransactionalDemandPlanService;
import com.sjherp.app.production.DemandPlanDtos.DemandPlanRequest;
import com.sjherp.app.production.DemandPlanDtos.DemandPlanResponse;
import com.sjherp.app.production.DemandPlanDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.production.DemandPlanQuery;

import jakarta.validation.Valid;

/**
 * 需求计划 API（M5-T02）：
 * <ul>
 *   <li>POST   /api/production/demand-plans → 201 DemandPlan（新建）</li>
 *   <li>PUT    /api/production/demand-plans/{docNo} → 200 DemandPlan（更新行列表）</li>
 *   <li>GET    /api/production/demand-plans?status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/production/demand-plans/{docNo} → 200 DemandPlan，不存在 404 {"error"}</li>
 * </ul>
 *
 * <p>权限（矩阵见 docs/权限矩阵.md）：写操作须 production:plan；查询登录即可。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/demand-plans")
public class DemandPlanController {

    private final TransactionalDemandPlanService demandPlanService;

    public DemandPlanController(TransactionalDemandPlanService demandPlanService) {
        this.demandPlanService = demandPlanService;
    }

    /** 创建需求计划 */
    @PreAuthorize("@perm.has('production:plan')")
    @PostMapping
    public ResponseEntity<DemandPlanResponse> create(@Valid @RequestBody DemandPlanRequest request) {
        DemandPlanResponse body = DemandPlanResponse.from(
                demandPlanService.create(request.toCommand(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 更新需求计划（行列表整体替换） */
    @PreAuthorize("@perm.has('production:plan')")
    @PutMapping("/{docNo}")
    public DemandPlanResponse update(
            @PathVariable String docNo,
            @Valid @RequestBody DemandPlanRequest request) {
        return DemandPlanResponse.from(
                demandPlanService.update(docNo, request.toCommand(), CurrentUser.operator()));
    }

    /** 分页搜索（status 可选过滤；查询登录即可） */
    @GetMapping
    public PageResponse<DemandPlanResponse> search(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromPlans(
                demandPlanService.search(new DemandPlanQuery(parseStatus(status), page, size)));
    }

    /** 按文档号查询（不存在 404；查询登录即可） */
    @GetMapping("/{docNo}")
    public DemandPlanResponse get(@PathVariable String docNo) {
        return DemandPlanResponse.from(demandPlanService.get(docNo));
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

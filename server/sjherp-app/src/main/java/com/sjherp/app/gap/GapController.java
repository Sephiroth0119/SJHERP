package com.sjherp.app.gap;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.gap.GapDtos.GapResponse;
import com.sjherp.app.gap.GapDtos.PageResponse;
import com.sjherp.app.gap.GapDtos.StatusTransitionRequest;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecordQuery;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapStatus;

import jakarta.validation.Valid;

/**
 * 流程缺口查询与流转 API（开发侧用，M6-T08 Issue 化的数据源）：
 * <ul>
 *   <li>GET  /api/gaps?status=&module=&page=&size= → 200 分页列表</li>
 *   <li>GET  /api/gaps/{id} → 200 缺口详情，不存在 404 {"error"}</li>
 *   <li>POST /api/gaps/{id}/status → 200 缺口（状态流转；非法流转 400 {"error"}）</li>
 * </ul>
 * 缺口的创建入口是 Agent 工具 record_process_gap（聊天通道），本 API 不提供创建。
 */
@RestController
@RequestMapping("/api/gaps")
public class GapController {

    private final GapRecordService gapRecordService;

    public GapController(GapRecordService gapRecordService) {
        this.gapRecordService = gapRecordService;
    }

    /** 分页列表（status / module 可选过滤） */
    @GetMapping
    public PageResponse<GapResponse> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String module,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromGaps(gapRecordService.search(
                new GapRecordQuery(parseStatus(status), parseModule(module), page, size)));
    }

    /** 按 id 查询（不存在 404） */
    @GetMapping("/{id}")
    public GapResponse get(@PathVariable long id) {
        return GapResponse.from(gapRecordService.get(id));
    }

    /** 状态流转（开发侧：TRIAGED / IN_DEVELOPMENT / RESOLVED / REJECTED；非法流转 400） */
    @PostMapping("/{id}/status")
    public GapResponse transition(@PathVariable long id,
                                  @Valid @RequestBody StatusTransitionRequest request) {
        return GapResponse.from(
                gapRecordService.transitionStatus(id, parseRequiredStatus(request.status()),
                        CurrentUser.operator()));
    }

    // ---------------------------------------------------------------- 参数解析

    private static GapStatus parseStatus(String status) {
        return (status == null || status.isBlank()) ? null : parseRequiredStatus(status);
    }

    private static GapStatus parseRequiredStatus(String status) {
        try {
            return GapStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "status 仅支持 NEW / TRIAGED / IN_DEVELOPMENT / RESOLVED / REJECTED: " + status);
        }
    }

    private static BusinessModule parseModule(String module) {
        if (module == null || module.isBlank()) {
            return null;
        }
        try {
            return BusinessModule.valueOf(module.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "module 仅支持 PURCHASE / SALES / INVENTORY / PRODUCTION / FINANCE / GENERAL: " + module);
        }
    }
}

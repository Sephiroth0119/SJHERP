package com.sjherp.app.production;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.config.TransactionalMrpService;
import com.sjherp.app.production.MrpDtos.MrpRunHttpRequest;
import com.sjherp.app.production.MrpDtos.MrpRunResponse;
import com.sjherp.app.production.MrpDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * MRP 运行 API（M5-T02）：
 * <ul>
 *   <li>POST   /api/production/mrp/runs → 201 MrpRun（触发 MRP 全重算）</li>
 *   <li>GET    /api/production/mrp/runs?page=&size= → 200 历史运行分页列表（头信息）</li>
 *   <li>GET    /api/production/mrp/runs/{docNo} → 200 MrpRun（含建议行），不存在 404 {"error"}</li>
 * </ul>
 *
 * <p>权限（矩阵见 docs/权限矩阵.md）：触发与查询**全程受控**须 production:mrp（类级 @PreAuthorize
 * 覆盖所有端点，照采购订单先例——MRP 建议含库存快照/采购生产量等经营敏感数据，查询同权）。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/mrp/runs")
@PreAuthorize("@perm.has('production:mrp')")
public class MrpController {

    private final TransactionalMrpService mrpService;

    public MrpController(TransactionalMrpService mrpService) {
        this.mrpService = mrpService;
    }

    /** 触发 MRP 运行（全重算，regenerative） */
    @PostMapping
    public ResponseEntity<MrpRunResponse> run(@Valid @RequestBody MrpRunHttpRequest request) {
        MrpRunResponse body = MrpRunResponse.from(
                mrpService.run(request.toRequest(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 历史运行分页列表（只含头信息；受控须 production:mrp） */
    @GetMapping
    public PageResponse<MrpDtos.MrpRunSummaryResponse> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromHistory(mrpService.searchHistory(page, size));
    }

    /** 按文档号查询完整运行结果（含建议行；受控须 production:mrp） */
    @GetMapping("/{docNo}")
    public MrpRunResponse get(@PathVariable String docNo) {
        return MrpRunResponse.from(mrpService.get(docNo));
    }
}

package com.sjherp.app.settlement;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.settlement.SettlementDtos.SettlementListResponse;
import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementType;

/**
 * 核销记录查询 API（M4-T03，<b>只读</b>）：
 * <ul>
 *   <li>GET /api/settlements?type=RECEIVABLE|PAYABLE&amp;targetId= → 某应收/应付的核销记录列表。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：核销轨迹敏感，须 {@code finance:settlement}（ADMIN/BOSS/ACCOUNTANT）。
 * 本批<b>仅</b>暴露只读端点——核销写动作（settle）的 REST/Agent 入口在 M4-T04（收付款单驱动），
 * 复用本权限点（设计真源 §0/§6）。{@code type} 非法 → 400 {"error": "..."}。
 */
@RestController
@RequestMapping("/api/settlements")
@PreAuthorize("@perm.has('finance:settlement')")
public class SettlementController {

    private final SettlementReadAppService settlementReadAppService;

    public SettlementController(SettlementReadAppService settlementReadAppService) {
        this.settlementReadAppService = Objects.requireNonNull(settlementReadAppService,
                "settlementReadAppService 不能为空");
    }

    /** 某应收/应付的核销记录列表（type=RECEIVABLE|PAYABLE，targetId=子账主键）。 */
    @GetMapping
    public SettlementListResponse list(@RequestParam String type, @RequestParam long targetId) {
        SettlementType settlementType = parseType(type);
        List<SettlementRecord> records = settlementType == SettlementType.RECEIVABLE
                ? settlementReadAppService.findReceivableSettlements(targetId)
                : settlementReadAppService.findPayableSettlements(targetId);
        return SettlementListResponse.from(records);
    }

    private static SettlementType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type 不能为空（RECEIVABLE/PAYABLE）");
        }
        try {
            return SettlementType.valueOf(type.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type 非法（RECEIVABLE/PAYABLE）: " + type);
        }
    }

    /** 参数不合法 → 400 {"error": "..."}（口径同其它模块异常契约）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}

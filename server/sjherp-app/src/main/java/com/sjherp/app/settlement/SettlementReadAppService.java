package com.sjherp.app.settlement;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementService;

/**
 * 核销历史查询应用服务（M4-T03，<b>只读</b>）：{@code SettlementController} 的公共入口。
 *
 * <p>仅暴露核销记录的只读查询——核销写动作（settle）的 REST/Agent 入口在 M4-T04（收付款单驱动），
 * 本批不暴露独立 settle 入口（设计真源 §0）。委托领域 {@link SettlementService} 的只读方法。
 */
@Service
public class SettlementReadAppService {

    private final SettlementService settlementService;

    public SettlementReadAppService(SettlementService settlementService) {
        this.settlementService = Objects.requireNonNull(settlementService, "settlementService 不能为空");
    }

    /** 某笔应收的核销历史（按发生先后）。 */
    @Transactional(readOnly = true)
    public List<SettlementRecord> findReceivableSettlements(long receivableId) {
        return settlementService.findReceivableSettlements(receivableId);
    }

    /** 某笔应付的核销历史（按发生先后）。 */
    @Transactional(readOnly = true)
    public List<SettlementRecord> findPayableSettlements(long payableId) {
        return settlementService.findPayableSettlements(payableId);
    }
}

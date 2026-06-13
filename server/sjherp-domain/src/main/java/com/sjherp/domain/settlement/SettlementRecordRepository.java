package com.sjherp.domain.settlement;

import java.util.List;

/**
 * 核销记录仓储接口（端口，实现在 infra 层，M4-T03）。
 *
 * <p>核销记录只追加、不修改、不删除（CLAUDE.md 原则 2/3）：本端口只有插入与查询，
 * 无 update/delete 方法。
 */
public interface SettlementRecordRepository {

    /** 保存一条核销记录（新建时回填自增 id；记录本身永不更新） */
    void save(SettlementRecord record);

    /** 按目标子账查核销历史（类型 + 目标主键，按 id 升序即按发生先后） */
    List<SettlementRecord> findByTarget(SettlementType type, long targetId);

    /** 按收付款单号查（M4-T04 收付款单驱动 / M4-T07 红冲反查用；本批装好不调） */
    List<SettlementRecord> findByPaymentDocNo(String paymentDocNo);
}

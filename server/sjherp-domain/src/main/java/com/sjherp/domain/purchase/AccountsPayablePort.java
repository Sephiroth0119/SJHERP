package com.sjherp.domain.purchase;

import java.util.List;

import com.sjherp.domain.payable.AccountsPayable;

/**
 * 采购发票过账生成应付账款所需的端口（M3-T07）。
 *
 * <p>领域 {@link PurchaseInvoiceService} 通过本端口生成应付账款，而不直接依赖应付仓储实现
 * （领域聚合间通过端口协作，保持各自仓储独立装配）。app 层用应付仓储实现本端口（透传 save，
 * 并提供按来源单据号查重以支持过账幂等）。
 *
 * <p>事务边界由 app 装配的 {@code PurchaseInvoiceService} 外层事务保证：发票状态变更与应付生成
 * 同事务原子提交（拆解 §1.4）。
 */
public interface AccountsPayablePort {

    /** 保存一笔应付（新建时回填自增 id） */
    void save(AccountsPayable payable);

    /** 按来源采购发票号查既有应付（过账幂等防重：同发票已生成应付则不重复生成） */
    List<AccountsPayable> findBySourceDocNo(String sourceDocNo);
}

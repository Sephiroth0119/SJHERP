package com.sjherp.app.purchase;

import java.util.List;
import java.util.Objects;

import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.purchase.AccountsPayablePort;

/**
 * 采购发票生成应付的端口 app 实现（M3-T07）：把领域 {@link AccountsPayablePort} 透传到应付仓储
 * {@link AccountsPayableRepository}。
 *
 * <p>事务由 {@code PurchaseInvoiceAppService} 写方法的外层事务（@Transactional）提供——发票状态
 * 变更与应付生成原子提交（拆解 §1.4）。本适配器只做透传，不加任何逻辑。
 */
public class AccountsPayablePortAdapter implements AccountsPayablePort {

    private final AccountsPayableRepository repository;

    public AccountsPayablePortAdapter(AccountsPayableRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    @Override
    public void save(AccountsPayable payable) {
        repository.save(payable);
    }

    @Override
    public List<AccountsPayable> findBySourceDocNo(String sourceDocNo) {
        return repository.findBySourceDocNo(sourceDocNo);
    }
}

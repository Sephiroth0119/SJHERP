package com.sjherp.app.receivable;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableQuery;
import com.sjherp.domain.receivable.ReceivableService;

/**
 * 应收账款应用服务（M3-T10）：REST {@code ReceivableController} 的入口。
 *
 * <p>本期仅查询（应收由销售发票过账生成，开票动作不经本类）。事务边界由本类提供（只读）。
 */
@Service
public class ReceivableAppService {

    private final ReceivableService receivableService;

    public ReceivableAppService(ReceivableService receivableService) {
        this.receivableService = Objects.requireNonNull(receivableService, "receivableService 不能为空");
    }

    /** 分页查询应收（按客户/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<AccountsReceivable> search(ReceivableQuery query) {
        return receivableService.search(query);
    }

    /** 按 id 查（不存在抛 ReceivableNotFoundException → 404） */
    @Transactional(readOnly = true)
    public AccountsReceivable get(long id) {
        return receivableService.get(id);
    }
}

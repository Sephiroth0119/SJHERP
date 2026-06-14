package com.sjherp.app.config;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpRunRepository;
import com.sjherp.domain.production.MrpRunRequest;
import com.sjherp.domain.production.MrpService;

/**
 * MRP 领域服务的事务包装（M5-T02）——<b>调用方（REST 控制器）一律注入本类</b>，
 * 不要直接注入 {@link MrpService}。
 *
 * <p>MRP 运行是多次仓储写（生成 MrpRun 头 + 建议行批量插入），须在单一事务内原子完成；
 * 历史查询和按号查询直接委托给 {@link MrpRunRepository}，无需经过领域服务。
 * 事务包装约定同 {@link TransactionalBomService}。
 */
public class TransactionalMrpService {

    private final MrpService delegate;
    private final MrpRunRepository mrpRunRepository;

    public TransactionalMrpService(MrpService delegate, MrpRunRepository mrpRunRepository) {
        this.delegate = delegate;
        this.mrpRunRepository = mrpRunRepository;
    }

    /** 触发 MRP 运行（全重算），持久化建议行并返回结果 */
    @Transactional
    public MrpRun run(MrpRunRequest request, String operator) {
        return delegate.run(request, operator);
    }

    /** 按文档号查询运行结果（不存在抛 MrpRunNotFoundException → 404） */
    @Transactional(readOnly = true)
    public MrpRun get(String docNo) {
        return delegate.get(docNo);
    }

    /** 历史运行分页列表（只含头信息，不含建议行明细） */
    @Transactional(readOnly = true)
    public PageResult<MrpRun> searchHistory(int page, int size) {
        return mrpRunRepository.searchHistory(page, size);
    }
}

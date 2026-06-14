package com.sjherp.app.config;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.DemandPlan;
import com.sjherp.domain.production.DemandPlanCommand;
import com.sjherp.domain.production.DemandPlanQuery;
import com.sjherp.domain.production.DemandPlanService;

import java.util.List;

/**
 * 需求计划领域服务的事务包装（M5-T02）——<b>调用方（REST 控制器）一律注入本类</b>，
 * 不要直接注入 {@link DemandPlanService}。
 *
 * <p>领域层零 Spring 依赖；事务包装在 app 层完成，同 {@link TransactionalBomService} 约定。
 * 审计路径不受影响：{@code @Audited} 留在领域方法上，外层回滚零审计记录。
 */
public class TransactionalDemandPlanService {

    private final DemandPlanService delegate;

    public TransactionalDemandPlanService(DemandPlanService delegate) {
        this.delegate = delegate;
    }

    /** 创建需求计划（默认 ENABLED） */
    @Transactional
    public DemandPlan create(DemandPlanCommand command, String operator) {
        return delegate.create(command, operator);
    }

    /** 更新需求计划（行列表整体替换） */
    @Transactional
    public DemandPlan update(String docNo, DemandPlanCommand command, String operator) {
        return delegate.update(docNo, command, operator);
    }

    /** 按文档号查询（不存在抛 DemandPlanNotFoundException → 404） */
    @Transactional(readOnly = true)
    public DemandPlan get(String docNo) {
        return delegate.get(docNo);
    }

    /** 分页搜索 */
    @Transactional(readOnly = true)
    public PageResult<DemandPlan> search(DemandPlanQuery query) {
        return delegate.search(query);
    }

    /** 所有启用需求计划（MRP 消费入口） */
    @Transactional(readOnly = true)
    public List<DemandPlan> findAllEnabled() {
        return delegate.findAllEnabled();
    }
}

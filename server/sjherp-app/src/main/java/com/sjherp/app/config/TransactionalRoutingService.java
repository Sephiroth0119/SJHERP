package com.sjherp.app.config;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.Routing;
import com.sjherp.domain.production.RoutingCommand;
import com.sjherp.domain.production.RoutingQuery;
import com.sjherp.domain.production.RoutingService;

/**
 * 工艺路线领域服务的事务包装（M5-T01，评审 P1 修复）——<b>调用方一律注入本类</b>，
 * 不要直接注入 {@link RoutingService}。理由同 {@link TransactionalBomService}：
 * create/enable 多次仓储写（停旧 ENABLED + 启目标）须单一外层事务原子完成。
 * 本类是 app 层薄委托，方法级 {@code @Transactional} 开事务后原样转发。
 */
public class TransactionalRoutingService {

    private final RoutingService delegate;

    public TransactionalRoutingService(RoutingService delegate) {
        this.delegate = delegate;
    }

    /** 创建工艺路线（默认 ENABLED，同事务先停用同产品其他启用版本再插入） */
    @Transactional
    public Routing create(RoutingCommand command, String operator) {
        return delegate.create(command, operator);
    }

    /** 更新工艺路线工序列表（整体替换） */
    @Transactional
    public Routing update(long id, RoutingCommand command, String operator) {
        return delegate.update(id, command, operator);
    }

    /** 启用工艺路线（同事务先停用同产品其他 ENABLED 版本，再启用目标） */
    @Transactional
    public Routing enable(long id, String operator) {
        return delegate.enable(id, operator);
    }

    /** 停用工艺路线 */
    @Transactional
    public Routing disable(long id, String operator) {
        return delegate.disable(id, operator);
    }

    /** 按 id 查询（不存在抛 404） */
    @Transactional(readOnly = true)
    public Routing get(long id) {
        return delegate.get(id);
    }

    /** 分页搜索 */
    @Transactional(readOnly = true)
    public PageResult<Routing> search(RoutingQuery query) {
        return delegate.search(query);
    }
}

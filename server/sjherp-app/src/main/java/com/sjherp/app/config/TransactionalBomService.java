package com.sjherp.app.config;

import java.math.BigDecimal;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.BillOfMaterials;
import com.sjherp.domain.production.BillOfMaterialsCommand;
import com.sjherp.domain.production.BillOfMaterialsQuery;
import com.sjherp.domain.production.BillOfMaterialsService;
import com.sjherp.domain.production.BomExplosion;

/**
 * BOM 领域服务的事务包装（M5-T01，评审 P1 修复）——<b>调用方（REST 控制器及后续生产单据服务）
 * 一律注入本类</b>，不要直接注入 {@link BillOfMaterialsService}。
 *
 * <p><b>为什么需要包装</b>：领域层零依赖（CLAUDE.md 仓库结构铁律），
 * {@code BillOfMaterialsService} 不能标注 Spring 的 {@code @Transactional}；而 create/enable
 * 是多次仓储写（先停用同产品其他 ENABLED 版本 → 再插入/启用目标），必须在单一外层事务内原子完成，
 * 否则中途失败会留下"该产品 0 个或 2 个 ENABLED 版本"的破损状态。本类是 app 层的薄委托：
 * 方法级 {@code @Transactional} 开事务后原样转发，不加任何业务逻辑。
 *
 * <p>审计路径不受影响（同 {@link TransactionalInventoryService} 说明）：{@code @Audited}
 * 留在领域方法上，调用链 = 本类事务代理（先开事务）→ 审计代理 → 领域方法，
 * 审计经 TransactionAwareAuditWriter 延迟到 afterCommit，外层回滚零审计。
 */
public class TransactionalBomService {

    private final BillOfMaterialsService delegate;

    public TransactionalBomService(BillOfMaterialsService delegate) {
        this.delegate = delegate;
    }

    /** 创建 BOM（默认 ENABLED，同事务先停用同产品其他启用版本再插入） */
    @Transactional
    public BillOfMaterials create(BillOfMaterialsCommand command, String operator) {
        return delegate.create(command, operator);
    }

    /** 更新 BOM 行列表（整体替换） */
    @Transactional
    public BillOfMaterials update(long id, BillOfMaterialsCommand command, String operator) {
        return delegate.update(id, command, operator);
    }

    /** 启用 BOM（同事务先停用同产品其他 ENABLED 版本，再启用目标） */
    @Transactional
    public BillOfMaterials enable(long id, String operator) {
        return delegate.enable(id, operator);
    }

    /** 停用 BOM */
    @Transactional
    public BillOfMaterials disable(long id, String operator) {
        return delegate.disable(id, operator);
    }

    /** 按 id 查询（不存在抛 404） */
    @Transactional(readOnly = true)
    public BillOfMaterials get(long id) {
        return delegate.get(id);
    }

    /** 分页搜索 */
    @Transactional(readOnly = true)
    public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
        return delegate.search(query);
    }

    /** BOM 递归展开（只读，MRP 消费入口）；多次仓储读，只读事务保证一致快照 */
    @Transactional(readOnly = true)
    public BomExplosion explode(long productId, BigDecimal quantity) {
        return delegate.explode(productId, quantity);
    }
}

package com.sjherp.domain.receivable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;

/**
 * 应收账款领域服务（M3-T10，路线图 §5 销售线）。
 *
 * <p>纯 Java 零依赖：依赖应收仓储端口 {@link ReceivableRepository}，由 app 层装配并加事务边界。
 * 应收记录由销售发票过账时经 {@link #open} 产生（同一外层事务内调用，与发票状态原子提交）。
 *
 * <h2>核销（M4-T03 预留）</h2>
 * v1.0 仅提供「开票生成应收（OPEN）」与查询；收款核销（settle）留 TODO，由 M4-T03 落地。
 * 财务记录只可冲销不可物理修改/删除（CLAUDE.md 原则 2）。
 */
public class ReceivableService {

    private final ReceivableRepository repository;

    public ReceivableService(ReceivableRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /**
     * 开票生成应收（销售发票过账时调用）：同来源单据号<b>幂等</b>——已存在则直接返回原记录，
     * 不重复生成（防发票过账重试重复挂账）。
     *
     * @param customerId  客户 id
     * @param amount      应收金额（>=0，= 发票金额）
     * @param sourceDocNo 来源销售发票号
     * @param dueDate     到期日（可空）
     * @param operator    操作人
     */
    @Audited(action = "receivable.open", targetType = "receivable")
    public AccountsReceivable open(long customerId, BigDecimal amount, String sourceDocNo,
                                   LocalDate dueDate, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(sourceDocNo, "来源单据号不能为空");
        List<AccountsReceivable> existing = repository.findBySourceDocNo(sourceDocNo);
        if (!existing.isEmpty()) {
            // 幂等：同发票已挂账，返回首条原记录（发票过账重试安全，不重复挂账）
            return existing.get(0);
        }
        AccountsReceivable receivable = AccountsReceivable.open(customerId, amount, sourceDocNo,
                dueDate, operator);
        repository.save(receivable);
        return receivable;
    }

    /** 按 id 查（不存在抛 {@link ReceivableNotFoundException} → API 404） */
    public AccountsReceivable get(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ReceivableNotFoundException(id));
    }

    /** 分页查询（按客户/状态过滤，可空） */
    public PageResult<AccountsReceivable> search(ReceivableQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}

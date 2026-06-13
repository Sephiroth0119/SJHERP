package com.sjherp.domain.gl;

import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.audit.Audited;

/**
 * 会计期间领域服务（所有账期写操作的唯一入口，CLAUDE.md 原则 1/2）。
 *
 * <p>纯 Java 零依赖：仅依赖账期仓储端口 {@link AccountingPeriodRepository}，由 app 层装配。
 * 业务规则：
 * <ul>
 *   <li>开启：账期键 yyyyMM，已存在则拒绝（不可重复开启同一账期）；</li>
 *   <li>关账：仅 OPEN 可关，记关账人/时间（T01 只改状态，月末结转留 T05）；</li>
 *   <li>重开：高敏操作（权限 finance:period_reopen），CLOSED→OPEN 清关账标记。</li>
 * </ul>
 */
public class AccountingPeriodService {

    private final AccountingPeriodRepository repository;

    public AccountingPeriodService(AccountingPeriodRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /** 开启账期：账期键 yyyyMM，已存在则拒绝。 */
    @Audited(action = "period.open", targetType = "accounting_period")
    public AccountingPeriod open(String period, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(period, "账期不能为空");
        if (repository.findByPeriod(period.strip()).isPresent()) {
            throw new IllegalArgumentException("账期已存在，不可重复开启: " + period.strip());
        }
        AccountingPeriod accountingPeriod = AccountingPeriod.open(period, operator);
        repository.save(accountingPeriod);
        return accountingPeriod;
    }

    /** 关账：OPEN→CLOSED（T01 只改状态，结转留 T05）。 */
    @Audited(action = "period.close", targetType = "accounting_period")
    public AccountingPeriod close(String period, String operator) {
        requireOperator(operator);
        AccountingPeriod accountingPeriod = get(period);
        accountingPeriod.close(operator);
        repository.save(accountingPeriod);
        return accountingPeriod;
    }

    /** 重开账期（高敏，CLAUDE.md 原则 2）：CLOSED→OPEN，清空关账标记。 */
    @Audited(action = "period.reopen", targetType = "accounting_period")
    public AccountingPeriod reopen(String period, String operator) {
        requireOperator(operator);
        AccountingPeriod accountingPeriod = get(period);
        accountingPeriod.reopen(operator);
        repository.save(accountingPeriod);
        return accountingPeriod;
    }

    /** 按账期键查（不存在抛 {@link AccountingPeriodNotFoundException} → API 404） */
    public AccountingPeriod get(String period) {
        return repository.findByPeriod(Objects.requireNonNull(period, "账期不能为空").strip())
                .orElseThrow(() -> new AccountingPeriodNotFoundException(period));
    }

    /** 全部账期（按账期键升序） */
    public List<AccountingPeriod> listAll() {
        return repository.findAll();
    }

    /** 账期是否开启（过账前置校验，账期不存在视为未开启返回 false） */
    public boolean isOpen(String period) {
        return repository.findByPeriod(Objects.requireNonNull(period, "账期不能为空").strip())
                .map(AccountingPeriod::isOpen)
                .orElse(false);
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}

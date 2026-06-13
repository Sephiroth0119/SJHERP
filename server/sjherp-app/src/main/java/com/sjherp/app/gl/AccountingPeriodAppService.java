package com.sjherp.app.gl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.gl.AccountingPeriod;
import com.sjherp.domain.gl.AccountingPeriodService;

/**
 * 会计期间应用服务（M4-T01）：REST {@code GlPeriodController} 的公共入口。
 *
 * <p>职责：委托领域 {@link AccountingPeriodService}（账期键 yyyyMM 校验、不可重复开启、关账/重开规则
 * 在领域层）；写方法标 {@code @Transactional}（与 @Audited 写方法同一外层事务），查询
 * {@code @Transactional(readOnly = true)}。账期列表/详情查询登录即可；重开为高敏操作（控制器层另设
 * finance:period_reopen 权限点把控）。
 */
@Service
public class AccountingPeriodAppService {

    private final AccountingPeriodService accountingPeriodService;

    public AccountingPeriodAppService(AccountingPeriodService accountingPeriodService) {
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService,
                "accountingPeriodService 不能为空");
    }

    /** 开启账期：账期键 yyyyMM，已存在则拒绝（→ 400） */
    @Transactional
    public AccountingPeriod open(String period, String operator) {
        return accountingPeriodService.open(period, operator);
    }

    /** 关账：OPEN→CLOSED（重复关账 → 409；T01 只改状态，结转留 T05） */
    @Transactional
    public AccountingPeriod close(String period, String operator) {
        return accountingPeriodService.close(period, operator);
    }

    /** 重开账期（高敏，CLAUDE.md 原则 2）：CLOSED→OPEN，清空关账标记（重复重开 → 409） */
    @Transactional
    public AccountingPeriod reopen(String period, String operator) {
        return accountingPeriodService.reopen(period, operator);
    }

    /** 按账期键查（不存在抛 AccountingPeriodNotFoundException → 404） */
    @Transactional(readOnly = true)
    public AccountingPeriod get(String period) {
        return accountingPeriodService.get(period);
    }

    /** 全部账期（按账期键升序） */
    @Transactional(readOnly = true)
    public List<AccountingPeriod> listAll() {
        return accountingPeriodService.listAll();
    }
}
